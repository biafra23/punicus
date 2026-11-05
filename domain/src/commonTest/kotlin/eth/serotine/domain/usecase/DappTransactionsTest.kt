package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DappCallMeaning
import eth.serotine.domain.model.DappTransactionRequest
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.DappAnswer
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappMetadata
import eth.serotine.domain.repository.DappRequest
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappRequests
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionProgress
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

class DappTransactionsTest {

    private val held = Account(Network.SEPOLIA, Address.of("0x" + "0".repeat(39) + "1"), AccountKind.EOA, "Main", AccountCustody.InKeyStore)
    private val safe = Account(Network.SEPOLIA, Address.of("0x" + "0".repeat(39) + "3"), AccountKind.SAFE, "Safe", AccountCustody.Watched)
    private val recipient = Address.of("0x" + "0".repeat(38) + "cc")
    private val accounts = ListAccountRepository(held, safe)
    private val networks = object : NetworkSettings {
        override fun observe(): Flow<Set<Network>> = flowOf(setOf(Network.SEPOLIA))
        override suspend fun setEnabled(network: Network, on: Boolean): Boolean = error("not used")
    }
    private val now = 1_793_700_000L
    private val inputs = FakeTransactionInputs()
    private val sent = MemorySentTransfers()
    private val balances = FakeNativeBalances().also {
        it.of(held.address).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 99)
    }
    private val broadcaster = FakeBroadcaster()
    private val signer = FakeSigner(FakeRecovery())
    private val receipts = object : ReceiptTracker {
        override fun observe(network: Network, hash: String): Flow<TransactionProgress> = flowOf(TransactionProgress.Pending)
    }

    private class FakeRequests : DappRequests {
        val waiting = MutableStateFlow<List<DappRequest>>(emptyList())
        val hashes = mutableListOf<Pair<DappRequestId, String>>()
        override fun observe(): Flow<List<DappRequest>> = waiting
        override suspend fun answerSignature(request: DappRequestId, signature: Signature) = error("not used")
        override suspend fun answerNull(request: DappRequestId) = error("not used")
        override suspend fun answerChainSwitch(request: DappRequestId, network: Network) = error("not used")
        override suspend fun refuse(request: DappRequestId, why: DappRequestError) = error("not used")
        override suspend fun recordSafeTx(request: DappRequestId, safeTx: eth.serotine.domain.model.Digest) =
            error("not used")
        override suspend fun answerTransaction(request: DappRequestId, hash: String): DappAnswer {
            hashes += request to hash
            waiting.value = waiting.value.filterNot { it.id == request }
            return DappAnswer.DELIVERED
        }
    }

    private val requests = FakeRequests()
    private val history = MemoryTransactionHistory()

    private fun request(id: Long, transaction: DappTransactionRequest) = DappRequest(
        DappRequestId(DappSessionId("t"), id),
        DappMetadata("Example dApp", "https://app.example.org", ""),
        listOf(held.id, safe.id),
        "eip155:11155111",
        DappAsk.SendTransaction(transaction),
        now + 300,
    )

    private fun payment(gas: Long? = null, from: Address = held.address, to: Address = recipient) =
        DappTransactionRequest(from, to, UInt256.of(1_000_000_000_000_000), ByteArray(0), gas)

    private fun quote() =
        QuoteDappTransaction(requests, accounts, networks, ListCatalog(), balances, inputs, sent, history, clock = { now })

    private fun send() =
        SendDappTransaction(requests, accounts, networks, signer, broadcaster, sent, history, receipts, clock = { now })

    @Test
    fun aPaymentIsQuotedAsSendQuotesOneSignedSentAndAnsweredWithItsHash() = runTest {
        requests.waiting.value = listOf(request(1, payment()))
        val quoted = assertIs<DappTransactionQuoting.Quoted>(quote()(request(1, payment()).id)).quote
        assertEquals(DappCallMeaning.Payment, (quoted.review.content as DappSignContent.Transaction).transaction.meaning)
        val transaction = quoted.call.transaction
        assertEquals(11_155_111L, transaction.chainId)
        assertEquals(7L, transaction.nonce)
        assertEquals(80_000L, transaction.gasLimit)
        assertEquals(recipient, transaction.to)
        val sending = assertIs<DappTransactionSending.Sent>(send()(quoted, Unlock.WithBiometrics("x")))
        assertEquals(listOf(quoted.id to sending.hash), requests.hashes)
        assertTrue(sending.hash.startsWith("0x"))
        val record = sent.transfers.value.single()
        assertEquals(SentAction.DappCall(recipient, transaction.value, "Example dApp", quoted.id), record.action)
    }

    @Test
    fun aRequestSignedOnceIsNeverSignedAgainAndOnceSentItsDappIsAnsweredAtOnce() = runTest {
        requests.waiting.value = listOf(request(1, payment()))
        val quoted = assertIs<DappTransactionQuoting.Quoted>(quote()(request(1, payment()).id)).quote
        broadcaster.answer = Broadcast.Unavailable("no peer")
        send()(quoted, Unlock.WithBiometrics("x"))
        val record = sent.transfers.value.single()
        assertTrue(record.maybeSent)
        // The screen left, the request opened again: it is the record, never a new signature.
        val onItsWay = SignedAnswer(record, SignedState.ON_ITS_WAY)
        assertEquals(DappTransactionQuoting.AlreadySigned(onItsWay), quote()(quoted.id))
        assertEquals(DappTransactionSending.AlreadySigned(onItsWay), send()(quoted, Unlock.WithBiometrics("x")))
        // Still, with the review refusing it now (the key gone): it may be on its way, never refused.
        accounts.setCustody(held.id, AccountCustody.Watched)
        assertEquals(DappTransactionQuoting.AlreadySigned(onItsWay), quote()(quoted.id))
        accounts.setCustody(held.id, AccountCustody.InKeyStore)
        assertEquals(1, broadcaster.sent.size)
        // Sent by a retry from its status: the dApp is answered with its hash at once.
        val answering = backgroundScope.launch { AnswerDappRequestsAtOnce(requests, sent, history, networks)() }
        runCurrent()
        assertTrue(requests.hashes.isEmpty())
        sent.transfers.value = listOf(record.copy(maybeSent = false))
        runCurrent()
        assertEquals(listOf(quoted.id to record.hash), requests.hashes)
        answering.cancel()
    }

    @Test
    fun oneMaybeSentAndMinedAnywayAnswersItsDappAndStaysSignedOnceAfterItsRecordIsLetGo() = runTest {
        requests.waiting.value = listOf(request(1, payment()))
        val quoted = assertIs<DappTransactionQuoting.Quoted>(quote()(request(1, payment()).id)).quote
        broadcaster.answer = Broadcast.Unavailable("no peer")
        send()(quoted, Unlock.WithBiometrics("x"))
        val record = sent.transfers.value.single()
        // The history has it, mined anyway; the record lets it go once the mined count passes its nonce.
        history.entries.value = listOf(HistoryEntry(record, SentOutcome.Included(120, succeeded = true)))
        sent.transfers.value = emptyList()
        assertEquals(DappTransactionQuoting.AlreadySigned(SignedAnswer(record, SignedState.MINED)), quote()(quoted.id))
        val answering = backgroundScope.launch { AnswerDappRequestsAtOnce(requests, sent, history, networks)() }
        runCurrent()
        assertEquals(listOf(quoted.id to record.hash), requests.hashes)
        answering.cancel()
    }

    @Test
    fun aRequestThatCannotBeSentAnyMoreIsNotSigned() = runTest {
        requests.waiting.value = listOf(request(1, payment()))
        val quoted = assertIs<DappTransactionQuoting.Quoted>(quote()(request(1, payment()).id)).quote
        accounts.setCustody(held.id, AccountCustody.Watched)
        assertEquals(DappTransactionSending.Refused(DappSignRefusal.NO_KEY), send()(quoted, Unlock.WithBiometrics("x")))
        assertTrue(signer.asked.isEmpty())
    }

    @Test
    fun theDappsGasLimitIsAppliedWhenItCoversTheEstimateAndRefusedBelowIt() = runTest {
        requests.waiting.value = listOf(request(1, payment(gas = 100_000)), request(2, payment(gas = 50_000)))
        val quoted = assertIs<DappTransactionQuoting.Quoted>(quote()(request(1, payment()).id)).quote
        assertEquals(100_000L, quoted.call.transaction.gasLimit)
        assertEquals(quoted.call.transaction.maxFeePerGas * UInt256.of(100_000), quoted.call.maxFee)
        assertEquals(DappTransactionQuoting.GasTooLow(50_000, 80_000), quote()(request(2, payment()).id))
    }

    @Test
    fun whatCannotBeSentIsRefusedOrNotQuotedAndNothingIsSigned() = runTest {
        requests.waiting.value = listOf(
            request(1, payment(from = safe.address)),
            request(2, payment(to = Address.ZERO)),
            request(3, payment()),
        )
        // A Safe's is a Safe transaction (ProposeDappSafeTx), never quoted as an account's own.
        assertEquals(DappTransactionQuoting.Gone, quote()(request(1, payment()).id))
        assertEquals(DappTransactionQuoting.Refused(DappSignRefusal.TX_TO_ZERO_ADDRESS), quote()(request(2, payment()).id))
        inputs.simulation = Simulation.Reverted(99)
        assertIs<DappTransactionQuoting.NotQuoted>(quote()(request(3, payment()).id))
        inputs.simulation = Simulation.Succeeded("", 100)
        inputs.estimate = GasEstimate.Infeasible("insufficient funds for gas * price + value")
        assertEquals(DappTransactionQuoting.NotQuoted(CallRefusal.InsufficientFunds), quote()(request(3, payment()).id))
        assertTrue(broadcaster.sent.isEmpty())
    }

    @Test
    fun oneNotSentIsKeptForARetryThatAnswersTheDappOnceItIsSent() = runTest {
        requests.waiting.value = listOf(request(1, payment()))
        val quoted = assertIs<DappTransactionQuoting.Quoted>(quote()(request(1, payment()).id)).quote
        broadcaster.answer = Broadcast.Unavailable("no peer")
        val notSent = assertIs<DappTransactionSending.NotSent>(send()(quoted, Unlock.WithBiometrics("x")))
        val transfer = assertIs<TransferSending.NotSent>(notSent.sending).transfer
        assertTrue(requests.hashes.isEmpty())
        broadcaster.answer = Broadcast.Sent(transfer.hash)
        val retried = assertIs<DappTransactionSending.Sent>(send().retry(quoted.id, transfer))
        assertEquals(listOf(quoted.id to retried.hash), requests.hashes)
    }

    @Test
    fun aSignerThatSignsNothingSaysSoAndAnExpiredRequestIsNotSigned() = runTest {
        requests.waiting.value = listOf(request(1, payment()))
        val quoted = assertIs<DappTransactionQuoting.Quoted>(quote()(request(1, payment()).id)).quote
        signer.answer = Signing.WrongPassword
        assertEquals(
            DappTransactionSending.NotSent(TransferSending.NotSigned(Signing.WrongPassword)),
            send()(quoted, Unlock.WithBiometrics("x")),
        )
        requests.waiting.value = listOf(request(1, payment()).copy(expiresAtSeconds = now))
        assertEquals(DappTransactionSending.Expired, send()(quoted, Unlock.WithBiometrics("x")))
    }

    @Test
    fun aTokenCallIsDecoded() {
        val token = Address.of("0x" + "0".repeat(38) + "aa")
        val transfer = DappTransactionRequest(held.address, token, UInt256.ZERO, Erc20.transferData(recipient, UInt256.of(5)), null)
        assertEquals(DappCallMeaning.TokenTransfer(token, recipient, UInt256.of(5)), transfer.meaning)
        val approve = DappTransactionRequest(held.address, token, UInt256.ZERO, Erc20.approveData(recipient, UInt256.of(9)), null)
        assertEquals(DappCallMeaning.TokenApproval(token, recipient, UInt256.of(9)), approve.meaning)
        val other = DappTransactionRequest(held.address, token, UInt256.ZERO, byteArrayOf(1, 2, 3, 4, 5), null)
        assertEquals(DappCallMeaning.NotDecoded("01020304"), other.meaning)
    }
}
