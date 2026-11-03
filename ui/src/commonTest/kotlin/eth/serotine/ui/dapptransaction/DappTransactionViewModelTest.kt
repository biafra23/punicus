package eth.serotine.ui.dapptransaction

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DappTransactionRequest
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappMetadata
import eth.serotine.domain.repository.DappRequest
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveDappMessages
import eth.serotine.domain.usecase.ProposeDappSafeTx
import eth.serotine.domain.usecase.QuoteDappTransaction
import eth.serotine.domain.usecase.RefuseDappRequest
import eth.serotine.domain.usecase.SendDappTransaction
import eth.serotine.domain.usecase.SignSafeTx
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeBalances
import eth.serotine.ui.FakeBroadcaster
import eth.serotine.ui.FakeDappRequests
import eth.serotine.ui.FakeKeys
import eth.serotine.ui.FakeReceipts
import eth.serotine.ui.FakeSigner
import eth.serotine.ui.FakeTokenCatalog
import eth.serotine.ui.FakeTransactionInputs
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.MemorySentTransfers
import eth.serotine.ui.SafeWorld
import eth.serotine.ui.components.STALE_QUOTE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Screen D6's ViewModel: a dApp's transaction quoted from verified state, signed and sent. */
@OptIn(ExperimentalCoroutinesApi::class)
class DappTransactionViewModelTest {

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private val now = 1_793_700_000L
    private val held = Account(
        Network.SEPOLIA,
        Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"),
        AccountKind.EOA,
        "Main",
        AccountCustody.InKeyStore,
    )
    private val recipient = Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359")
    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USD Coin", 6, null)
    private val accounts = FakeAccounts(held)
    private val networks = MemoryNetworkSettings(setOf(Network.SEPOLIA))
    private val requests = FakeDappRequests()
    private val balances = FakeBalances().also {
        it.of(held.address).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 99)
    }
    private val inputs = FakeTransactionInputs()
    private val sent = MemorySentTransfers()
    private val history = eth.serotine.ui.MemoryTransactionHistory()
    private val broadcaster = FakeBroadcaster()
    private val signer = FakeSigner()
    private val clock = TestTimeSource()
    private val safes = SafeWorld()

    private fun request(id: Long, transaction: DappTransactionRequest) = DappRequest(
        DappRequestId(DappSessionId("t"), id),
        DappMetadata("Example dApp", "https://app.example.org", ""),
        listOf(held.address),
        "eip155:11155111",
        DappAsk.SendTransaction(transaction),
        now + 300,
    )

    private val payment = DappTransactionRequest(held.address, recipient, UInt256.of("500000000000000000"), ByteArray(0), null)

    private fun TestScope.viewModel(id: Long = 1): DappTransactionViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val catalog = FakeTokenCatalog(usdc)
        val vm = DappTransactionViewModel(
            "t/$id",
            ObserveDappMessages(requests, accounts, networks, catalog, clock = { now }),
            QuoteDappTransaction(requests, accounts, networks, catalog, balances, inputs, sent, history, clock = { now }),
            SendDappTransaction(requests, accounts, networks, signer, broadcaster, sent, history, FakeReceipts(), clock = { now }),
            // A Safe's side, never reached from an account held here.
            ProposeDappSafeTx(requests, accounts, networks, catalog, safes.observeSafe, balances, inputs, safes.queue, clock = { now }),
            SignSafeTx(signer, safes.observeSafe, safes.recovery, safes.queue, Network.SEPOLIA),
            RefuseDappRequest(requests, accounts, networks, clock = { now }),
            DescribeKeyStore(FakeKeys()),
            clock = { now },
            timeSource = clock,
            ticks = kotlinx.coroutines.flow.flowOf(Unit),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun aPaymentIsQuotedSignedSentAndTheDappAnsweredWithItsHash() = runTest {
        requests.waiting.value = listOf(request(1, payment))
        val vm = viewModel()
        val state = vm.uiState.value
        assertEquals("Example dApp", state.name)
        assertEquals("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359", state.to)
        assertEquals("0.5 ETH (testnet)", state.value)
        assertEquals("A payment of its value to the recipient.", state.meaning)
        assertEquals(listOf("Fee, at most", "Left in the account, at least", "Verified run", "Gas limit", "Nonce"), state.quote.map { it.label })
        assertEquals("Runs without reverting at block 100", state.quote[2].value)
        assertFalse(state.sendUnavailable)
        vm.onPasswordChange("correct horse")
        vm.send()
        runCurrent()
        assertEquals(listOf("correct horse"), signer.passwords)
        assertEquals(request(1, payment).id, requests.hashes.single().first)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun aQuoteOverAMinuteOldIsMadeAgainBeforeAnythingIsSigned() = runTest {
        requests.waiting.value = listOf(request(1, payment))
        val vm = viewModel()
        clock += 61.seconds
        vm.onPasswordChange("correct horse")
        vm.send()
        runCurrent()
        assertTrue(signer.asked.isEmpty())
        assertEquals(STALE_QUOTE, vm.uiState.value.unlock.error)
        assertTrue(vm.uiState.value.quote.isNotEmpty())
    }

    @Test
    fun oneThatRevertsIsNotQuotedAndItsSendSaysWhy() = runTest {
        inputs.simulation = Simulation.Reverted(99)
        requests.waiting.value = listOf(request(1, payment))
        val vm = viewModel()
        assertNotNull(vm.uiState.value.notQuoted)
        assertTrue(vm.uiState.value.sendUnavailable)
        vm.send()
        assertEquals("Cannot be sent", vm.uiState.value.explaining?.title)
        assertTrue(signer.asked.isEmpty())
    }

    @Test
    fun aTokenCallIsDecodedWithItsBuiltInTokenAndAnUnknownOneShownRaw() = runTest {
        val transfer = DappTransactionRequest(held.address, usdc.address, UInt256.ZERO, Erc20.transferData(recipient, UInt256.of(25_000_000)), null)
        val other = DappTransactionRequest(held.address, recipient, UInt256.ZERO, byteArrayOf(0x35, 0x93.toByte(), 0x56, 0x4c), null)
        requests.waiting.value = listOf(request(1, transfer), request(2, other))
        val token = viewModel(1).uiState.value
        assertEquals("A transfer of 25 USDC to 0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359.", token.meaning)
        assertNotNull(token.dataHex)
        val raw = viewModel(2).uiState.value
        assertNull(raw.meaning)
        assertEquals("3593564c", raw.dataHex)
        assertEquals(listOf(DappTransactionViewModel.NOT_DECODED), raw.warnings)
    }

    @Test
    fun oneNotSentIsRetriedWithTheSameBytesAndARefusedPasswordSaysSo() = runTest {
        requests.waiting.value = listOf(request(1, payment))
        val vm = viewModel()
        signer.answer = Signing.WrongPassword
        vm.onPasswordChange("wrong")
        vm.send()
        runCurrent()
        assertEquals("That is not the wallet password. Nothing was signed.", vm.uiState.value.unlock.error)
        signer.answer = FakeSigner().answer
        broadcaster.answers += Broadcast.Unavailable("no peer")
        vm.onPasswordChange("correct horse")
        vm.send()
        runCurrent()
        assertNotNull(vm.uiState.value.notSent)
        assertTrue(requests.hashes.isEmpty())
        vm.retry()
        runCurrent()
        assertEquals(2, broadcaster.sent.size)
        assertTrue(broadcaster.sent[0].contentEquals(broadcaster.sent[1]))
        assertEquals(1, requests.hashes.size)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun aRequestSignedEarlierIsNeverSignedAgainAndCannotBeRefusedWhileItMayBeOnItsWay() = runTest {
        requests.waiting.value = listOf(request(1, payment))
        broadcaster.answers += Broadcast.Unavailable("no peer")
        val first = viewModel()
        first.onPasswordChange("correct horse")
        first.send()
        runCurrent()
        assertNotNull(first.uiState.value.notSent)
        // The screen left and opened again: the record, never a new signature.
        val again = viewModel()
        assertNotNull(again.uiState.value.notSent)
        assertTrue(again.uiState.value.refuseUnavailable)
        again.refuse()
        assertEquals("Cannot be refused now", again.uiState.value.explaining?.title)
        assertTrue(requests.refusals.isEmpty())
        assertEquals(1, signer.asked.size)
    }

    @Test
    fun aGasLimitBelowTheEstimateIsRefusedAsInvalid() = runTest {
        requests.waiting.value = listOf(request(1, DappTransactionRequest(held.address, recipient, UInt256.ZERO, ByteArray(0), 5_000)))
        val vm = viewModel()
        assertNotNull(vm.uiState.value.notQuoted)
        vm.refuse()
        runCurrent()
        assertEquals(listOf(request(1, payment).id to DappRequestError.INVALID_PARAMS), requests.refusals)
    }

    @Test
    fun aGasLimitFoundTooLowIsForgottenOnceAQuoteSucceeds() = runTest {
        inputs.estimate = eth.serotine.domain.repository.GasEstimate.Estimated(30_000)
        requests.waiting.value = listOf(request(1, DappTransactionRequest(held.address, recipient, UInt256.ZERO, ByteArray(0), 25_000)))
        val vm = viewModel()
        assertNotNull(vm.uiState.value.notQuoted)
        inputs.estimate = eth.serotine.domain.repository.GasEstimate.Estimated(21_000)
        vm.requote()
        runCurrent()
        assertTrue(vm.uiState.value.quote.isNotEmpty())
        vm.refuse()
        runCurrent()
        assertEquals(listOf(request(1, payment).id to DappRequestError.USER_REJECTED), requests.refusals)
    }

    @Test
    fun aRefusedRequestIsAnsweredAsDeclined() = runTest {
        requests.waiting.value = listOf(request(1, payment))
        val vm = viewModel()
        vm.refuse()
        runCurrent()
        assertEquals(listOf(request(1, payment).id to DappRequestError.USER_REJECTED), requests.refusals)
    }
}
