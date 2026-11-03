package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DappTransactionRequest
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
        override suspend fun refuse(request: DappRequestId, why: DappRequestError) = error("not used")
        override suspend fun answerTransaction(request: DappRequestId, hash: String): DappAnswer {
            hashes += request to hash
            waiting.value = waiting.value.filterNot { it.id == request }
            return DappAnswer.DELIVERED
        }
    }

    private val requests = FakeRequests()

    private fun request(transaction: DappTransactionRequest) = DappRequest(
        DappRequestId(DappSessionId("t"), 1),
        DappMetadata("Example dApp", "https://app.example.org", ""),
        listOf(world.safe),
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
        // Signed by Alice, it is queued, and the request is that Safe transaction from then on.
        val sign = SignSafeTx(world.signer, world.observeSafe, world.recovery, world.queue, Network.SEPOLIA)
        assertIs<SafeTxSigning.Signed>(sign(proposed.proposal.request, world.alice.address, Unlock.WithBiometrics("x")))
        val queued = assertIs<DappSafeProposing.AlreadyQueued>(propose()(request(payment).id))
        assertEquals(proposed.proposal.request.hash, queued.request.hash)
    }

    @Test
    fun theDappIsAnsweredWithTheExecutionsHashNeverTheSafeTxHash() = runTest {
        requests.waiting.value = listOf(request(payment))
        val proposed = assertIs<DappSafeProposing.Proposed>(propose()(request(payment).id)).proposal
        val execution = SentTransfer(
            network = Network.SEPOLIA,
            account = world.alice.address,
            nonce = 9,
            signed = SignedTransfer(byteArrayOf(2, 1, 0)),
            action = SentAction.SafeExecution(proposed.proposal.request),
            maxCost = UInt256.ZERO,
            sentAt = now,
        )
        val answering = backgroundScope.launch { AnswerDappRequestsAtOnce(requests, world.sent, history)() }
        runCurrent()
        assertTrue(requests.hashes.isEmpty())
        world.sent.record(execution)
        runCurrent()
        assertEquals(listOf(request(payment).id to execution.hash), requests.hashes)
        assertTrue(execution.hash != "0x" + proposed.proposal.request.hash.hex)
        answering.cancel()
    }

    @Test
    fun everySafeRuleAppliesAndAnOwnerHeldHereIsNeeded() = runTest {
        // A call of the Safe itself that sets a fallback handler other than the pinned one: refused (#63).
        val handler = DappTransactionRequest(
            world.safe,
            world.safe,
            UInt256.ZERO,
            SafeSettingsCalls.setFallbackHandler(Address.of("0x" + "99".repeat(20))),
            null,
        )
        requests.waiting.value = listOf(request(handler))
        assertIs<DappSafeProposing.SafeRefused>(propose()(request(handler).id))
        // A call that reverts, run from the Safe.
        world.inputs.simulation = Simulation.Reverted(100)
        requests.waiting.value = listOf(request(payment))
        assertEquals(DappSafeProposing.Reverts(100), propose()(request(payment).id))
        world.inputs.simulation = Simulation.Succeeded("", 100)
        // No owner held here: nobody here can propose it.
        world.accounts.setCustody(world.alice.id, AccountCustody.Watched)
        assertEquals(DappSafeProposing.NoOwnerHere, propose()(request(payment).id))
        // Never quoted as an account's own transaction.
        val quote = QuoteDappTransaction(
            requests, world.accounts, networks, ListCatalog(), world.nativeBalances, world.inputs, world.sent, history,
            clock = { now },
        )
        assertEquals(DappTransactionQuoting.Gone, quote(request(payment).id))
    }
}
