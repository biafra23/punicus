package eth.serotine.ui.handoff

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.ChainTransaction
import eth.serotine.domain.repository.HandoffAvailability
import eth.serotine.domain.repository.HandoffOpening
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.usecase.HandOffTransfer
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveUsdPrice
import eth.serotine.domain.usecase.OfferHandoff
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.WatchHandoff
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeBalances
import eth.serotine.ui.FakeBlockTransactions
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.FakeNames
import eth.serotine.ui.FakeReceipts
import eth.serotine.ui.FakeTokenBalances
import eth.serotine.ui.FakeTokenCatalog
import eth.serotine.ui.FakeWalletHandoff
import eth.serotine.ui.MemoryCurrencySetting
import eth.serotine.ui.MemoryUserTokens
import eth.serotine.ui.components.NO_WALLET
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** [HandoffViewModel] over fake ports, through the real use cases. */
@OptIn(ExperimentalCoroutinesApi::class)
class HandoffViewModelTest {

    // EIP-55's own examples.
    private val hardware = Account(
        Network.SEPOLIA,
        Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"),
        AccountKind.EOA,
        "Hardware",
        AccountCustody.Watched,
    )
    private val daily = Account(
        Network.SEPOLIA,
        Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )
    private val other = Address.of("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb")
    private val usdc =
        Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc", PriceId("USDC"))

    private val accounts = FakeAccounts(hardware, daily)
    private val balances = FakeBalances()
    private val feeds = FakeFeeds()
    private val wallets = FakeWalletHandoff()
    private val blocks = FakeBlockTransactions(head = 100)
    private val receipts = FakeReceipts()
    private val clock = TestTimeSource()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(from: Account = hardware): HandoffViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = HandoffViewModel(
            AccountId(Network.SEPOLIA, from.address),
            null,
            ObserveAccounts(accounts),
            ObserveAccountValue(
                ObserveNativeBalance(balances),
                ObserveTokenHoldings(
                    ObserveKnownTokens(FakeTokenCatalog(usdc), MemoryUserTokens(), FakeMetadata()),
                    FakeTokenBalances(),
                ),
                ObserveUsdPrice(feeds),
                ObserveDisplayCurrencies(feeds, MemoryCurrencySetting(), accounts),
            ),
            ResolveRecipient(FakeNames()),
            OfferHandoff(wallets),
            HandOffTransfer(wallets, blocks),
            WatchHandoff(blocks, receipts, pollInterval = 4.seconds),
            clock,
            now = { 1_800_000_000L },
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private val HandoffViewModel.compose get() = assertIs<HandoffUiState.Compose>(uiState.value).form

    /** 0.1 ETH from Hardware to Daily, composed and under review. */
    private fun TestScope.reviewed(): HandoffViewModel {
        balances.of(hardware.address).value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        val viewModel = viewModel()
        viewModel.onPickAccount(viewModel.compose.accounts.single().address)
        viewModel.onAmountChange("0.1")
        viewModel.review()
        runCurrent()
        return viewModel
    }

    private fun payment(sender: Address, wei: String, hash: Char = 'a') =
        ChainTransaction("0x" + hash.toString().repeat(64), sender, daily.address, UInt256.of(wei), "0x")

    @Test
    fun theReviewSaysWhatIsHandedOverAndWhichAccountToChooseInTheOtherWallet() = runTest {
        val review = assertIs<HandoffUiState.Review>(reviewed().uiState.value)
        assertEquals("0.1 ETH", review.amount)
        assertEquals("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359", review.recipient.address)
        assertTrue(review.notes.any { "choose “Hardware” (0x5aAe…eAed)" in it }, review.notes.toString())
        assertTrue(review.notes.any { "signs nothing" in it })
        assertTrue(review.notes.any { "test network" in it }, "Sepolia keeps its testnet warning")
        assertTrue(wallets.opened.isEmpty(), "nothing is handed over before Open")
    }

    @Test
    fun openHandsTheLinkOverOnceAndAVerifiedTransferEndsInSent() = runTest {
        val viewModel = reviewed()
        viewModel.open()
        runCurrent()
        assertEquals(
            listOf("ethereum:0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359@11155111?value=100000000000000000"),
            wallets.opened,
        )
        assertIs<HandoffUiState.Waiting>(viewModel.uiState.value)

        blocks.bodies[101] = listOf(payment(hardware.address, "100000000000000000"))
        blocks.head = 101
        receipts.of("0x" + "a".repeat(64)).value = TransactionProgress.Included(101, succeeded = true)
        advanceTimeBy(5.seconds)
        runCurrent()

        val outcome = assertIs<HandoffUiState.Outcome>(viewModel.uiState.value)
        assertEquals(OutcomeKind.SENT, outcome.kind)
        assertEquals("101", outcome.block)
        viewModel.open()
        assertEquals(1, wallets.opened.size, "never handed over a second time")
    }

    @Test
    fun theSameAmountFromAnotherAccountIsNotCountedAsThisOnes() = runTest {
        val viewModel = reviewed()
        viewModel.open()
        runCurrent()
        blocks.bodies[101] = listOf(payment(other, "100000000000000000", hash = 'b'))
        blocks.head = 101
        receipts.of("0x" + "b".repeat(64)).value = TransactionProgress.Included(101, succeeded = true)
        advanceTimeBy(5.seconds)
        runCurrent()
        // Kept while the account's own transfer may still come: said, and the answer once the window has passed.
        val waiting = assertIs<HandoffUiState.Waiting>(viewModel.uiState.value)
        assertTrue("Block 101 has a transfer like it" in waiting.progress, waiting.progress)
        clock += 10.minutes
        advanceTimeBy(5.seconds)
        runCurrent()

        val outcome = assertIs<HandoffUiState.Outcome>(viewModel.uiState.value)
        assertEquals(OutcomeKind.NOT_THE_ONE, outcome.kind)
        assertEquals("Not from this account", outcome.title)
    }

    @Test
    fun nothingWithinTheWindowIsSaidAsNotSeen() = runTest {
        val viewModel = reviewed()
        viewModel.open()
        runCurrent()
        clock += 10.minutes
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(OutcomeKind.NOT_SEEN, assertIs<HandoffUiState.Outcome>(viewModel.uiState.value).kind)
    }

    @Test
    fun noAppTakingTheLinkKeepsTheReviewAndSaysWhy() = runTest {
        val viewModel = reviewed()
        wallets.opening = HandoffOpening.NO_WALLET
        viewModel.open()
        runCurrent()
        val review = assertIs<HandoffUiState.Review>(viewModel.uiState.value)
        assertEquals(NO_WALLET, review.error)
        assertFalse(review.opening)
    }

    @Test
    fun moreThanTheVerifiedBalanceCannotBeReviewed() = runTest {
        balances.of(hardware.address).value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        val viewModel = viewModel()
        viewModel.onPickAccount(viewModel.compose.accounts.single().address)
        viewModel.onAmountChange("2")
        runCurrent()
        assertEquals("This account holds 1.5 ETH, verified: less than that.", viewModel.compose.amountError)
        assertFalse(viewModel.compose.canReview)
    }

    @Test
    fun anAccountWhoseKeyIsHereOrADeviceWithNoWalletCannotHandOver() = runTest {
        assertIs<HandoffUiState.Cannot>(viewModel(from = daily).uiState.value)
        wallets.availability = HandoffAvailability.NO_WALLET
        val state = assertIs<HandoffUiState.Cannot>(viewModel().uiState.value)
        assertEquals(NO_WALLET, state.message)
    }
}
