package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.DappTransactionRequest
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSettingsCalls
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.DappAnswer
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappMetadata
import eth.serotine.domain.repository.DappRequest
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappRequests
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A dApp's transaction from a Safe, proposed as a Safe transaction, and its dApp answered once it is executed. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DappSafeTransactionsTest {

    private val world = SafeWorld()
    private val now = 1_793_700_000L
    private val networks = object : NetworkSettings {
        override fun observe(): Flow<Set<Network>> = flowOf(setOf(Network.SEPOLIA))
        override suspend fun setEnabled(network: Network, on: Boolean): Boolean = error("not used")
    }
    private val history = MemoryTransactionHistory()

    private class FakeRequests : DappRequests {
        val waiting = MutableStateFlow<List<DappRequest>>(emptyList())
        val hashes = mutableListOf<Pair<DappRequestId, String>>()
        override fun observe(): Flow<List<DappRequest>> = waiting
        override suspend fun answerSignature(request: DappRequestId, signature: Signature) = error("not used")
        override suspend fun answerNull(request: DappRequestId) = error("not used")
        override suspend fun answerChainSwitch(request: DappRequestId, network: Network) = error("not used")
        override suspend fun refuse(request: DappRequestId, why: DappRequestError) = error("not used")
        override suspend fun answerTransaction(request: DappRequestId, hash: String): DappAnswer {
            hashes += request to hash
            waiting.value = waiting.value.filterNot { it.id == request }
            return DappAnswer.DELIVERED
        }
        var recordSaves = true
        override suspend fun recordSafeTx(request: DappRequestId, safeTx: Digest): Boolean {
            val found = waiting.value.firstOrNull { it.id == request } ?: return false
            if (!recordSaves || (found.safeTx != null && found.safeTx != safeTx)) return false
            waiting.value = waiting.value.map { if (it.id == request) it.copy(safeTx = safeTx) else it }
            return true
        }
    }

    private val requests = FakeRequests()

    private fun request(transaction: DappTransactionRequest) = DappRequest(
        DappRequestId(DappSessionId("t"), 1),
        DappMetadata("Example dApp", "https://app.example.org", ""),
        listOf(AccountId(Network.SEPOLIA, world.safe)),
        "eip155:11155111",
        DappAsk.SendTransaction(transaction),
        now + 300,
    )

    private val payment = DappTransactionRequest(world.safe, world.recipient, UInt256.of("100000000000000000"), ByteArray(0), null)

    private fun propose() = ProposeDappSafeTx(
        requests,
        world.accounts,
        networks,
        ListCatalog(),
        world.observeSafe,
        world.nativeBalances,
        world.inputs,
        world.queue,
        clock = { now },
    )

    private fun sign() = SignDappSafeTx(
        requests,
        SignSafeTx(world.signer, world.observeSafe, world.recovery, world.queue, Network.SEPOLIA),
    )

    private suspend fun signed(proposal: DappSafeProposal): DappSafeSigning {
        val checked = assertIs<SafeTxSignCheck.Ready>(sign().check(proposal))
        return sign().sign(proposal, checked, Unlock.WithBiometrics("x"))
    }

    private fun execution(request: eth.serotine.domain.model.SigningRequest, nonce: Long = 9) = SentTransfer(
        network = Network.SEPOLIA,
        account = world.alice.address,
        nonce = nonce,
        signed = SignedTransfer(byteArrayOf(2, 1, nonce.toByte())),
        action = SentAction.SafeExecution(request),
        maxCost = UInt256.ZERO,
        sentAt = now,
    )

    @Test
    fun aSafesTransactionIsProposedAsASafeTxAtItsNextNonceForAnOwnerHeldHereToSign() = runTest {
        requests.waiting.value = listOf(request(payment))
        val proposed = assertIs<DappSafeProposing.Proposed>(propose()(request(payment).id)).proposal
        val tx = proposed.proposal.request.tx
        assertEquals(world.recipient, tx.to)
        assertEquals(payment.value, tx.value)
        assertEquals(4L, tx.nonce)
        assertEquals(listOf(world.alice.address), proposed.heldOwners)
        assertEquals(SafeCallSimulation.Ran(100), proposed.proposal.simulation)
        // Signed by Alice: tied to the request first, then queued, and the request is that Safe transaction.
        assertIs<DappSafeSigning.Signed>(signed(proposed))
        assertEquals(proposed.proposal.request.hash, requests.waiting.value.single().safeTx)
        val queued = assertIs<DappSafeProposing.AlreadyQueued>(propose()(request(payment).id))
        assertEquals(proposed.proposal.request.hash, queued.request.hash)
    }

    @Test
    fun nothingIsQueuedUnlessTheRequestIsTiedToItsSafeTransaction() = runTest {
        requests.waiting.value = listOf(request(payment))
        val proposed = assertIs<DappSafeProposing.Proposed>(propose()(request(payment).id)).proposal
        requests.recordSaves = false
        assertEquals(DappSafeSigning.NotRecorded, signed(proposed))
        assertTrue(world.queue.requests.value.isEmpty(), "its signature dropped: never queued, never shared")
        assertEquals(null, requests.waiting.value.single().safeTx)
    }

    @Test
    fun aSignatureNotMadeTiesNothing() = runTest {
        requests.waiting.value = listOf(request(payment))
        val proposed = assertIs<DappSafeProposing.Proposed>(propose()(request(payment).id)).proposal
        world.signer.answer = eth.serotine.domain.repository.Signing.WrongPassword
        assertIs<DappSafeSigning.NotSigned>(signed(proposed))
        assertEquals(null, requests.waiting.value.single().safeTx)
        // Another transaction takes nonce 4 meanwhile: the request is simply proposed at 5.
        world.queue.requests.value = listOf(
            eth.serotine.domain.model.SigningRequest.of(
                Network.SEPOLIA,
                world.safe,
                eth.serotine.domain.model.SafeTx.call(world.carol, UInt256.ZERO, ByteArray(0), 4),
            ),
        )
        val again = assertIs<DappSafeProposing.Proposed>(propose()(request(payment).id)).proposal
        assertEquals(5L, again.proposal.request.nonce)
    }

    @Test
    fun aSameCallQueuedByHandIsNotTheRequestsSafeTransaction() = runTest {
        // The user queued the same payment themselves, at nonce 4: the dApp's is proposed behind it, at 5.
        world.queue.requests.value = listOf(
            eth.serotine.domain.model.SigningRequest.of(
                Network.SEPOLIA,
                world.safe,
                eth.serotine.domain.model.SafeTx.call(world.recipient, payment.value, ByteArray(0), 4),
            ),
        )
        requests.waiting.value = listOf(request(payment))
        val proposed = assertIs<DappSafeProposing.Proposed>(propose()(request(payment).id)).proposal
        assertEquals(5L, proposed.proposal.request.nonce)
    }

    @Test
    fun aRecordedSafeTransactionNoLongerQueuedIsNeverReplacedByAnother() = runTest {
        requests.waiting.value = listOf(request(payment))
        val proposed = assertIs<DappSafeProposing.Proposed>(propose()(request(payment).id)).proposal
        assertIs<DappSafeSigning.Signed>(signed(proposed))
        // Rejected here, and another transaction now holds nonce 4: it would be proposed at 5, as another.
        world.queue.requests.value = listOf(
            eth.serotine.domain.model.SigningRequest.of(
                Network.SEPOLIA,
                world.safe,
                eth.serotine.domain.model.SafeTx.call(world.carol, UInt256.ZERO, ByteArray(0), 4),
            ),
        )
        assertEquals(
            DappSafeProposing.NoLongerQueued(proposed.proposal.request.hash),
            propose()(request(payment).id),
        )
        // Not saved to the queue after the record, the same Safe transaction is proposed again to sign.
        world.queue.requests.value = emptyList()
        assertIs<DappSafeProposing.Proposed>(propose()(request(payment).id))
    }

    @Test
    fun theDappIsAnsweredWithTheExecutionsHashNeverTheSafeTxHash() = runTest {
        requests.waiting.value = listOf(request(payment))
        val proposed = assertIs<DappSafeProposing.Proposed>(propose()(request(payment).id)).proposal
        // An older execution of the very same call, not this request's Safe transaction: never its answer.
        val older = execution(
            eth.serotine.domain.model.SigningRequest.of(
                Network.SEPOLIA,
                world.safe,
                eth.serotine.domain.model.SafeTx.call(world.recipient, payment.value, ByteArray(0), 2),
            ),
            nonce = 7,
        )
        world.sent.record(older)
        val answering = backgroundScope.launch { AnswerDappRequestsAtOnce(requests, world.sent, history)() }
        runCurrent()
        assertTrue(requests.hashes.isEmpty())
        assertIs<DappSafeSigning.Signed>(signed(proposed))
        runCurrent()
        assertTrue(requests.hashes.isEmpty(), "signed and queued is not executed")
        val execution = execution(proposed.proposal.request)
        world.sent.record(execution)
        runCurrent()
        assertEquals(listOf(request(payment).id to execution.hash), requests.hashes)
        assertTrue(execution.hash != "0x" + proposed.proposal.request.hash.hex)
        answering.cancel()
    }

    @Test
    fun anExecutionOnAnotherNetworkIsNeverTheAnswer() {
        val safeTx = eth.serotine.domain.model.SigningRequest.of(
            Network.ETHEREUM,
            world.safe,
            eth.serotine.domain.model.SafeTx.call(world.recipient, payment.value, ByteArray(0), 4),
        )
        val elsewhere = execution(safeTx).copy(network = Network.ETHEREUM)
        assertEquals(null, executionFor(safeTx.hash, Network.SEPOLIA, listOf(elsewhere), emptyList()))
        // On its own network, it is.
        assertEquals(elsewhere.hash, executionFor(safeTx.hash, Network.ETHEREUM, listOf(elsewhere), emptyList())?.transfer?.hash)
    }

    @Test
    fun everySafeRuleAppliesAndAnOwnerHeldHereIsNeeded() = runTest {
        // A call of the Safe itself, here setting a fallback handler other than the pinned one: refused before
        // any Safe rule, since D6 decodes no settings change.
        val handler = DappTransactionRequest(
            world.safe,
            world.safe,
            UInt256.ZERO,
            SafeSettingsCalls.setFallbackHandler(Address.of("0x" + "99".repeat(20))),
            null,
        )
        requests.waiting.value = listOf(request(handler))
        assertEquals(DappSafeProposing.Refused(DappSignRefusal.SAFE_SELF_CALL), propose()(request(handler).id))
        // An exact settings change too, its threshold lowered: the Safe's own screens change its settings.
        val owner = DappTransactionRequest(
            world.safe,
            world.safe,
            UInt256.ZERO,
            SafeSettingsCalls.changeThreshold(1),
            null,
        )
        requests.waiting.value = listOf(request(owner))
        assertEquals(DappSafeProposing.Refused(DappSignRefusal.SAFE_SELF_CALL), propose()(request(owner).id))
        // A call that reverts, run from the Safe.
        world.inputs.simulation = Simulation.Reverted(100)
        requests.waiting.value = listOf(request(payment))
        assertEquals(DappSafeProposing.Reverts(100), propose()(request(payment).id))
        world.inputs.simulation = Simulation.Succeeded("", 100)
        // A token transfer whose token answers false moved nothing.
        val transfer = DappTransactionRequest(
            world.safe,
            Address.of("0x" + "77".repeat(20)),
            UInt256.ZERO,
            eth.serotine.domain.model.Erc20.transferData(world.recipient, UInt256.of(1)),
            null,
        )
        world.inputs.simulation = Simulation.Succeeded("0".repeat(64), 100)
        requests.waiting.value = listOf(request(transfer))
        assertEquals(DappSafeProposing.Reverts(100), propose()(request(transfer).id))
        world.inputs.simulation = Simulation.Succeeded("", 100)
        // A gas limit: a Safe transaction's gas is its executor's, so the dApp's is refused, never ignored.
        val gas = DappTransactionRequest(world.safe, world.recipient, payment.value, ByteArray(0), 21_000)
        requests.waiting.value = listOf(request(gas))
        assertEquals(DappSafeProposing.Refused(DappSignRefusal.SAFE_GAS), propose()(request(gas).id))
        // No owner held here: nobody here can propose it.
        requests.waiting.value = listOf(request(payment))
        world.accounts.setCustody(world.alice.id, AccountCustody.Watched)
        assertEquals(DappSafeProposing.NoOwnerHere, propose()(request(payment).id))
        // Never quoted as an account's own transaction.
        val quote = QuoteDappTransaction(
            requests, world.accounts, networks, ListCatalog(), world.nativeBalances, world.inputs, world.sent, history,
            clock = { now },
        )
        assertEquals(DappTransactionQuoting.Gone, quote(request(payment).id))
    }

    @Test
    fun aModuleEnabledIsRefusedAndAValueTheSafeCannotPayIsNotProposed() = runTest {
        // Enabling a module, whatever the module: refused (owner ruling, 2026-10-18, on #54).
        val data = SafeSettingsCalls.ENABLE_MODULE + ByteArray(12) + ByteArray(20) { 0x77 }
        val module = DappTransactionRequest(world.safe, world.safe, UInt256.ZERO, data, null)
        requests.waiting.value = listOf(request(module))
        assertEquals(DappSafeProposing.Refused(DappSignRefusal.SAFE_SELF_CALL), propose()(request(module).id))
        // More than the Safe's verified ether.
        val large = DappTransactionRequest(world.safe, world.recipient, UInt256.of("2000000000000000000"), ByteArray(0), null)
        requests.waiting.value = listOf(request(large))
        assertEquals(DappSafeProposing.InsufficientFunds, propose()(request(large).id))
        assertTrue(world.queue.requests.value.isEmpty(), "nothing is queued by a proposal")
    }

    @Test
    fun anExpiredRequestIsNotProposed() = runTest {
        requests.waiting.value = listOf(request(payment).copy(expiresAtSeconds = now))
        assertEquals(DappSafeProposing.Expired, propose()(request(payment).id))
    }
}
