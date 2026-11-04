package eth.serotine.ui.coworder

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.CowTrade
import eth.serotine.domain.model.GPv2Settlement
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderLife
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.CowPlacement
import eth.serotine.domain.repository.FinalizedBlock
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveCowOrder
import eth.serotine.domain.usecase.ObserveCowOrderLife
import eth.serotine.domain.usecase.QuoteCowCancellation
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.SendCowCancellation
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeBalances
import eth.serotine.ui.FakeBroadcaster
import eth.serotine.ui.FakeCowApi
import eth.serotine.ui.FakeCowChain
import eth.serotine.ui.FakeKeys
import eth.serotine.ui.FakeReceipts
import eth.serotine.ui.FakeSigner
import eth.serotine.ui.FakeTransactionInputs
import eth.serotine.ui.MemoryCowOrders
import eth.serotine.ui.MemorySentTransfers
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [CowOrderViewModel] over fake ports, through the real use cases. */
@OptIn(ExperimentalCoroutinesApi::class)
class CowOrderViewModelTest {

    private val daily = Account(
        Network.SEPOLIA,
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )
    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc")
    private val settlement = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41")
    private val validTo = 1_792_419_300L
    private val order = CowOrder(
        usdc.address,
        CowOrder.NATIVE_BUY_TOKEN,
        daily.address,
        UInt256.of(40_000_000),
        UInt256.of("15900000000000000"),
        validTo,
    )
    private val record = CowOrderRecord(
        account = daily.address,
        network = Network.SEPOLIA,
        settlement = settlement,
        order = order,
        uid = order.uid(Network.SEPOLIA.chainId, settlement, daily.address),
        signature = FakeSigner.SIGNATURE,
        sell = TransferAsset.Erc20Token(usdc),
        buy = TransferAsset.Native,
        signedAt = validTo - 300,
        rangeStart = 9_412_000,
        placement = CowPlacement.Accepted(order.uid(Network.SEPOLIA.chainId, settlement, daily.address)),
    )

    private val orders = MemoryCowOrders()
    private val cowApi = FakeCowApi()
    private val cowChain = FakeCowChain()
    private val accounts = FakeAccounts(daily)
    private val balances = FakeBalances()
    private val inputs = FakeTransactionInputs()
    private val sent = MemorySentTransfers()
    private val signer = FakeSigner()
    private val broadcaster = FakeBroadcaster()
    private val status = MutableStateFlow<LightClientStatus>(
        LightClientStatus.Synced(9_412_118, validTo - 200, FinalizedBlock(9_412_050, validTo - 1_000)),
    )
    private val light = object : LightClient {
        override val datesFinalizedBlocks = true
        override fun observeStatus(network: Network): Flow<LightClientStatus> = status
    }
    private var now = validTo - 200

    init {
        orders.orders.value = listOf(record)
        balances.of(daily.address).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 9_412_118)
        // The settlement counts nothing of it filled at the head.
        cowChain.answers[settlement to GPv2Settlement.filledAmount(record.uid).hex()] =
            Simulation.Succeeded("00".repeat(32), 9_412_118)
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): CowOrderViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = CowOrderViewModel(
            record.uid,
            ObserveCowOrder(orders, Network.SEPOLIA),
            ObserveCowOrderLife(cowApi, Network.SEPOLIA),
            ObserveAccounts(accounts),
            DescribeKeyStore(FakeKeys()),
            QuoteCowCancellation(inputs, balances, sent, cowChain, light, Network.SEPOLIA) { now },
            SendCowCancellation(signer, broadcaster, sent) { now },
            RebroadcastTransfer(broadcaster, sent, FakeReceipts()),
            Network.SEPOLIA,
            clock = { now },
            formatTime = { "at $it" },
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private val CowOrderViewModel.shown get() = assertIs<CowOrderUiState.Shown>(uiState.value)

    @Test
    fun aLiveOrderSaysWhatItSellsUntilWhenAndCowsWordLabelledAsCows() = runTest {
        val shown = viewModel().shown

        assertEquals(OrderState.LIVE, shown.state)
        assertEquals("40 USDC", shown.sold)
        assertEquals("at least 0.0159 ETH", shown.bought)
        assertTrue(shown.end.startsWith("Not proven yet: it may fill until at $validTo."), shown.end)
        assertEquals("CoW says, not verified: the order is open in its order book, in the auction.", shown.cow)
        assertEquals("CoW took the order when it was sent.", shown.placement)
        assertTrue(shown.canCancel)
        assertNull(shown.uniswapAgain)
        assertEquals(listOf(record.uid), cowApi.lives)
    }

    @Test
    fun theOrderIsDrawnAtOnceAndCowsWordFollowsWhenItComes() = runTest {
        val answer = CompletableDeferred<CowOrderLife>()
        cowApi.lifePending = answer
        val viewModel = viewModel()

        assertEquals(OrderState.LIVE, viewModel.shown.state, "not held back by CoW's answer")
        assertNull(viewModel.shown.cow)
        answer.complete(CowOrderLife.Open)
        runCurrent()
        val open = "CoW says, not verified: the order is open in its order book, in the auction."
        assertEquals(open, viewModel.shown.cow)
    }

    @Test
    fun aProvenFillSaysWhatItBoughtAndCowIsNotAsked() = runTest {
        val trade = CowTrade(
            daily.address,
            usdc.address,
            CowOrder.NATIVE_BUY_TOKEN,
            UInt256.of(40_000_000),
            UInt256.of("16100000000000000"),
            UInt256.ZERO,
            record.uid,
        )
        orders.orders.value = listOf(record.copy(end = CowOrderEnd.Filled(trade, 9_412_100, "0x" + "75".repeat(32))))
        val shown = viewModel().shown

        assertEquals(OrderState.FILLED, shown.state)
        assertEquals("0.0161 ETH", shown.bought)
        assertTrue(shown.end.startsWith("Filled in block 9,412,100: it bought 0.0161 ETH"), shown.end)
        assertEquals("Filled: it bought 0.0161 ETH, as this account's verified logs say.", shown.simpleEnd, "no block")
        assertNull(shown.cow)
        assertTrue(cowApi.lives.isEmpty(), "CoW is not asked of an order whose end is proven")
        assertTrue(!shown.canCancel)
    }

    @Test
    fun anOrderProvenNotToHaveFilledOffersTheSameSwapOnUniswap() = runTest {
        orders.orders.value = listOf(record.copy(end = CowOrderEnd.Unfilled(9_412_200, cancelled = false)))
        val shown = viewModel().shown

        assertEquals(OrderState.NOT_FILLED, shown.state)
        assertEquals(SwapAgain(daily.address.hex, usdc.address.hex, buy = null, amount = "40"), shown.uniswapAgain)
    }

    @Test
    fun aCancellationIsReviewedSignedAndSentFromTheOrdersAccount() = runTest {
        val viewModel = viewModel()
        viewModel.cancel()
        runCurrent()

        val review = assertIs<CowOrderUiState.CancelReview>(viewModel.uiState.value)
        assertEquals(record.uid.hex, review.uid)
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        assertIs<CowOrderUiState.CancelSent>(viewModel.uiState.value)
        val action = assertIs<SentAction.CowCancellation>(sent.transfers.value.single().action)
        assertEquals(record.uid, action.uid)
        assertEquals(listOf("made-up test password"), signer.passwords)
    }

    @Test
    fun anOrderWhoseAccountCannotSignHereHasItsCancelGreyedOutSayingWhy() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.shown.canCancel)
        assertNull(viewModel.shown.cancelReason)

        accounts.accounts.value = listOf(daily.copy(custody = AccountCustody.Watched))
        assertTrue(viewModel.shown.canCancel, "shown, greyed out")
        assertEquals(
            "This account is watch-only: its key is not on this device, so the cancellation cannot be signed " +
                "here. The order ends by itself at its validity.",
            viewModel.shown.cancelReason,
        )
        viewModel.cancel()
        assertTrue(!viewModel.shown.quoting, "nothing is quoted")

        accounts.accounts.value = emptyList()
        assertEquals("This account", viewModel.shown.from.label)
        assertTrue(viewModel.shown.cancelReason!!.startsWith("This account is not in your wallet any more"))

        accounts.unreadable.value = true
        assertTrue(viewModel.shown.cancelReason!!.startsWith("Your account list cannot be read right now"))

        accounts.unreadable.value = false
        accounts.accounts.value = listOf(daily)
        assertNull(viewModel.shown.cancelReason)
    }

    @Test
    fun anOrderPastItsValidityHasNothingToCancel() = runTest {
        now = validTo + 1
        val viewModel = viewModel()
        assertTrue(!viewModel.shown.canCancel)
        assertTrue(viewModel.shown.end.startsWith("Its validity passed at at $validTo."), viewModel.shown.end)
    }

    @Test
    fun theScreenSaysWhenTheOrdersValidityPassesWhileItIsShown() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.shown.canCancel)

        now = validTo + 1
        advanceTimeBy(201.seconds)
        runCurrent()

        assertFalse(viewModel.shown.canCancel)
        assertTrue(viewModel.shown.end.startsWith("Its validity passed"), viewModel.shown.end)
    }

    @Test
    fun aCancellationMaybeSentIsOfferedFromTheOrderNeverSignedAgain() = runTest {
        broadcaster.answers += Broadcast.Unavailable("no peer")
        val first = viewModel()
        first.cancel()
        runCurrent()
        first.onPasswordChange("made-up test password")
        first.sign()
        runCurrent()
        val maybe = sent.transfers.value.single()
        assertTrue(maybe.maybeSent)

        // The screen left and opened again: the bytes are in the record, and none is signed anew.
        val again = viewModel()
        again.cancel()
        runCurrent()

        val earlier = EarlierTransaction(maybe.hash, note = null, label = "Open the earlier transaction")
        assertEquals(earlier, again.shown.earlier)
        assertTrue(again.shown.error!!.startsWith("An earlier transaction from this account may still be on its way"))
        assertTrue(again.shown.canCancel, "still there: a tap quotes again, which says whether it still holds")
        assertEquals(1, signer.asked.size)

        // Its end proven meanwhile: what the tap found is of the live order, and goes.
        orders.orders.value = listOf(record.copy(end = CowOrderEnd.Unfilled(9_412_200, cancelled = true)))
        runCurrent()
        assertNull(again.shown.earlier)
        assertNull(again.shown.error)
    }

    @Test
    fun aReviewStaysWithItsPasswordWhenTheRecordCannotSayTheOrderMeanwhile() = runTest {
        val viewModel = viewModel()
        viewModel.cancel()
        runCurrent()
        viewModel.onPasswordChange("made-up test password")

        orders.orders.value = emptyList()
        runCurrent()

        val review = assertIs<CowOrderUiState.CancelReview>(viewModel.uiState.value)
        assertEquals("made-up test password", review.unlock.password.text)
    }

    @Test
    fun anOrderNotRecordedHereSaysSo() = runTest {
        orders.orders.value = emptyList()
        assertIs<CowOrderUiState.Missing>(viewModel().uiState.value)
    }

    @Test
    fun cowsWordIsAskedOnlyWhileTheOrderIsLive() = runTest {
        cowApi.life = CowOrderLife.Expired
        val viewModel = viewModel()
        assertEquals("CoW says, not verified: the order expired in its order book.", viewModel.shown.cow)
        orders.orders.value = listOf(record.copy(end = CowOrderEnd.Unfilled(9_412_200, cancelled = false)))
        runCurrent()
        assertNull(viewModel.shown.cow)
    }

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
