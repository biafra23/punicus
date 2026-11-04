package eth.serotine.ui.swap

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowDeployment
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.FeeTier
import eth.serotine.domain.model.GPv2Settlement
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PoolKey
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.QuoterV2
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.model.UniswapV3Factory
import eth.serotine.domain.model.UniswapV3Pool
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.BiometricUnlock
import eth.serotine.domain.repository.CowPlacement
import eth.serotine.domain.repository.CowQuoteReading
import eth.serotine.domain.repository.CowRefusal
import eth.serotine.domain.repository.CowUnavailable
import eth.serotine.domain.repository.CowVenueReading
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FinalizedBlock
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.repository.VenueChoice
import eth.serotine.domain.repository.VenueReading
import eth.serotine.domain.repository.WaitingSwap
import eth.serotine.domain.usecase.ChooseSwapVenue
import eth.serotine.domain.usecase.CowOfferedOnThisDevice
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.DropWaitingSwap
import eth.serotine.domain.usecase.FindUniswapRoute
import eth.serotine.domain.usecase.FindWaitingSwap
import eth.serotine.domain.usecase.KeepWaitingSwap
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveSwapVenue
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveUsdPrice
import eth.serotine.domain.usecase.PlaceCowOrder
import eth.serotine.domain.usecase.QuoteCowSwap
import eth.serotine.domain.usecase.QuoteSwap
import eth.serotine.domain.usecase.QuoteTokenApproval
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.SWAP_DEADLINE_SECONDS
import eth.serotine.domain.usecase.SendSwap
import eth.serotine.domain.usecase.SendTokenApproval
import eth.serotine.domain.usecase.WarmFeeHistory
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeBalances
import eth.serotine.ui.FakeBroadcaster
import eth.serotine.ui.FakeCowApi
import eth.serotine.ui.FakeCowChain
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.FakeReceipts
import eth.serotine.ui.FakeSigner
import eth.serotine.ui.FakeTokenBalances
import eth.serotine.ui.FakeTokenCatalog
import eth.serotine.ui.FakeTransactionInputs
import eth.serotine.ui.MemoryCurrencySetting
import eth.serotine.ui.MemoryCowOrders
import eth.serotine.ui.MemoryHistoryStarts
import eth.serotine.ui.MemorySentTransfers
import eth.serotine.ui.MemorySwapVenue
import eth.serotine.ui.MemoryUserTokens
import eth.serotine.ui.MemoryWaitingSwaps
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.RouteStep
import eth.serotine.ui.components.STALE_QUOTE
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
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

/** [SwapViewModel] over fake ports, through the real use cases. */
@OptIn(ExperimentalCoroutinesApi::class)
class SwapViewModelTest {

    private val daily = Account(
        Network.SEPOLIA,
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )
    private val watched = Account(
        Network.SEPOLIA,
        Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"),
        AccountKind.EOA,
        "Savings",
        AccountCustody.Watched,
    )
    private val safe = Account(
        Network.SEPOLIA,
        Address.of("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb"),
        AccountKind.SAFE,
        "Treasury",
        AccountCustody.Watched,
    )
    private val usdc =
        Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc", PriceId("USDC"))
    private val link = Token(Address.of("0x" + "77".repeat(20)), "LINK", "ChainLink Token", 18, icon = null)
    private val deployment = UniswapV3Deployment(
        factory = Address.of("0x" + "fa".repeat(20)),
        quoterV2 = Address.of("0x" + "0e".repeat(20)),
        swapRouter02 = Address.of("0x" + "50".repeat(20)),
        weth9 = Address.of("0x" + "ff".repeat(20)),
    )
    private val router = deployment.swapRouter02
    private val pool = Address.of("0x" + "30".repeat(20))
    private val fortyUsdc = UInt256.of(40_000_000)
    private val headTime = 1_792_419_000L

    private val accounts = FakeAccounts(daily, watched, safe)
    private val balances = FakeBalances()
    private val tokenBalances = FakeTokenBalances()
    private val inputs = FakeTransactionInputs().apply { estimate = GasEstimate.Estimated(150_000) }
    private val uniswap = PoolChain()
    private val light = FakeLightClient()
    private val signer = FakeSigner()
    private val broadcaster = FakeBroadcaster()
    private val sent = MemorySentTransfers()
    private val cowOrders = MemoryCowOrders()
    private val cowChain = FakeCowChain()
    private val cowApi = FakeCowApi()
    private val swapVenue = MemorySwapVenue()
    private val waitingSwaps = MemoryWaitingSwaps()
    private val historyStarts = MemoryHistoryStarts()
    private val clock = TestTimeSource()
    private var wallClock = headTime
    private var biometrics: BiometricUnlock = BiometricUnlock.NotOnThisDevice
    private val keys = object : KeyStore {
        override suspend fun describe() = KeyStoreDescription.Ready(true, KeyProtection.HARDWARE, biometrics)

        override suspend fun newMnemonic(): NewMnemonic = error("not used by these tests")

        override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
            error("not used by these tests")

        override suspend fun enableBiometrics(password: WalletPassword, confirmation: String): BiometricChange =
            error("not used by these tests")

        override suspend fun deriveAccount(from: Address, password: WalletPassword, held: Set<Address>) =
            KeyDerivation.Unavailable

        override suspend fun disableBiometrics(): BiometricChange = error("not used by these tests")

        override suspend fun delete(address: Address): KeyDeletion = error("not used by these tests")
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        from: Address = daily.address,
        sell: Address? = null,
        fill: SwapFill? = null,
        continuing: Boolean = false,
    ): SwapViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val now = { headTime }
        val viewModel = SwapViewModel(
            AccountId(Network.SEPOLIA, from),
            sell,
            ObserveAccounts(accounts),
            ObserveAccountValue(
                ObserveNativeBalance(balances),
                ObserveTokenHoldings(
                    ObserveKnownTokens(FakeTokenCatalog(usdc, link), MemoryUserTokens(), FakeMetadata()),
                    tokenBalances,
                ),
                ObserveUsdPrice(FakeFeeds()),
                ObserveDisplayCurrencies(FakeFeeds(), MemoryCurrencySetting(), accounts),
            ),
            DescribeKeyStore(keys),
            QuoteSwap(
                FindUniswapRoute(uniswap, inputs),
                inputs,
                balances,
                tokenBalances,
                sent,
                cowOrders,
                cowChain,
                light,
                Network.SEPOLIA,
                now,
            ),
            SendSwap(inputs, signer, broadcaster, sent, now),
            QuoteTokenApproval(inputs, balances, sent, Network.SEPOLIA, now),
            SendTokenApproval(signer, broadcaster, sent, now),
            RebroadcastTransfer(broadcaster, sent, FakeReceipts()),
            WarmFeeHistory(inputs),
            ObserveSwapVenue(swapVenue),
            ChooseSwapVenue(swapVenue),
            CowOfferedOnThisDevice(light),
            QuoteCowSwap(
                cowChain,
                cowApi,
                FindUniswapRoute(uniswap, inputs),
                inputs,
                tokenBalances,
                sent,
                cowOrders,
                historyStarts,
                light,
                Network.SEPOLIA,
                now,
            ),
            PlaceCowOrder(FindUniswapRoute(uniswap, inputs), signer, cowOrders, cowApi, light),
            KeepWaitingSwap(waitingSwaps),
            DropWaitingSwap(waitingSwaps),
            FindWaitingSwap(waitingSwaps),
            clock,
            formatTime = { "at $it" },
            now = { wallClock },
            fill = fill,
            continuing = continuing,
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private val SwapViewModel.compose get() = assertIs<SwapUiState.Compose>(uiState.value).form

    init {
        balances.of(daily.address).value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        tokenBalances.of(daily.address, usdc.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("250000000"), 9_412_117)
        tokenBalances.of(daily.address, link.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("0"), 9_412_117)
        light.status.value = LightClientStatus.Synced(headBlock = 9_412_118, headTimestamp = headTime, finalized = null)
        // One USDC–WETH pool at 0.3 %, quoting 0.016 ETH for 40 USDC.
        val key = PoolKey.of(usdc.address, deployment.weth9, FeeTier.MEDIUM)
        uniswap.answers[deployment.factory to UniswapV3Factory.getPool(key).hex()] = word(pool)
        uniswap.answers[pool to UniswapV3Pool.liquidity().hex()] = word(UInt256.of(1_000))
        // 20 040² wei per USDC unit: 40 USDC is 0.016015871808 ETH at the spot, less the 0.3 %.
        uniswap.answers[pool to UniswapV3Pool.slot0().hex()] =
            word(UInt256.of(20_040) * UInt256.of("79228162514264337593543950336")) + "00".repeat(32 * 6)
        val route = SwapRoute.direct(usdc.address, deployment.weth9, FeeTier.MEDIUM)
        uniswap.answers[deployment.quoterV2 to QuoterV2.quote(route, fortyUsdc).hex()] =
            word(UInt256.of("16000000000000000")) + "00".repeat(64) + word(UInt256.of(100_000))
        // The router's run: 0.01595 ETH, above the minimum of 0.01592; then the unwrap's empty result.
        inputs.runs[router] = Simulation.Succeeded(
            listOf(0x20L, 2L, 0x40L, 0x80L, 0x20L).joinToString("") { word(UInt256.of(it)) } +
                word(UInt256.of("15950000000000000")) + word(UInt256.ZERO),
            9_412_118,
        )
        allow(fortyUsdc)
    }

    private fun allow(amount: UInt256) {
        inputs.runs[usdc.address] = Simulation.Succeeded(word(amount), 9_412_118)
    }

    /** Composes 40 USDC for ETH and asks for the review. */
    private fun TestScope.reviewed(): SwapViewModel {
        val viewModel = viewModel(sell = usdc.address)
        viewModel.onBuyChange(null)
        viewModel.onAmountChange("40")
        viewModel.review()
        runCurrent()
        return viewModel
    }

    @Test
    fun composeOffersTheHeldAssetsToSellAndEveryOtherListedOneToBuy() = runTest {
        val viewModel = viewModel(sell = usdc.address)

        val form = viewModel.compose
        assertEquals(SendFrom("Daily", "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2"), form.from)
        assertEquals("USDC", form.sell?.symbol, "the token of the asset menu")
        assertEquals(listOf("ETH", "USDC"), form.sellOptions.map { it.symbol }, "LINK is not held")
        assertEquals(listOf("ETH", "LINK"), form.buyOptions.map { it.symbol }, "all but the one sold")
        assertNull(form.buy)
        assertEquals("0.5", form.tolerance)
        assertFalse(form.canReview, "nothing to buy yet")

        viewModel.onBuyChange(null)
        viewModel.onSellChange(null)
        assertNull(viewModel.compose.buy, "ETH for ETH is no swap: the one bought goes")
        assertEquals(listOf("USDC", "LINK"), viewModel.compose.buyOptions.map { it.symbol })
    }

    @Test
    fun eachBalanceSaysHowLongAgoItWasProvedAndIsDatedAnew() = runTest {
        tokenBalances.of(daily.address, usdc.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("250000000"), 9_412_117, provenAt = wallClock - 120)
        tokenBalances.of(daily.address, link.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("0"), 9_412_117, provenAt = wallClock - 300)
        val viewModel = viewModel(sell = usdc.address)
        assertEquals("updated 2 min ago", viewModel.compose.sell?.age)
        assertEquals("updated 5 min ago", viewModel.compose.buyOptions.first { it.symbol == "LINK" }.age)
        assertNull(viewModel.compose.sellOptions.first { it.symbol == "ETH" }.age, "no time of proof, no age")

        wallClock += 60
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals("updated 3 min ago", viewModel.compose.sell?.age, "the age is dated anew every second")
    }

    @Test
    fun aToleranceOutsideItsRangeIsSaidAndNothingIsQuoted() = runTest {
        val viewModel = viewModel(sell = usdc.address)
        viewModel.onBuyChange(null)
        viewModel.onAmountChange("40")
        assertTrue(viewModel.compose.canReview)

        viewModel.onToleranceChange("7")
        assertEquals("Between 0.1 % and 5 %, with at most two decimals (0.5).", viewModel.compose.toleranceError)
        assertFalse(viewModel.compose.canReview)
    }

    @Test
    fun theReviewShowsTheVerifiedRunItsMinimumRouteAndDeadline() = runTest {
        val review = assertIs<SwapUiState.SwapReview>(reviewed().uiState.value)

        assertEquals("40 USDC", review.sold)
        assertEquals("0.01595 ETH", review.receive)
        assertEquals("at least 0.01592 ETH", review.minimum)
        assertEquals("0.5 % tolerance, below QuoterV2's verified quote of 0.016 ETH", review.tolerance)
        assertEquals(
            listOf(RouteStep("USDC → WETH", "0.3 %", "0x3030303030303030303030303030303030303030")),
            review.route,
        )
        assertTrue(review.routeNote.endsWith("which the router wraps or unwraps in the same transaction."))
        assertEquals(
            SpenderItem("Uniswap's SwapRouter02", "0x5050505050505050505050505050505050505050"),
            review.spender,
        )
        assertEquals(
            "Refused by the router after at ${headTime + SWAP_DEADLINE_SECONDS}: the verified head's time and " +
                "10 minutes.",
            review.deadline,
        )
        assertEquals(listOf("USDC balance", "ETH balance"), review.balances.map { it.what })
        assertEquals("210 USDC", review.balances.first().after)
        assertNull(review.netNote, "one route, chosen as is")
        assertEquals(
            "Price impact: 0.09 %, the quote against the pools' spot prices, their fees aside.",
            review.priceImpact,
        )
        assertEquals(
            "Sepolia's pools and Chainlink's testnet feeds do not track each other: this quote is not held " +
                "against the prices the wallet shows.",
            review.priceHint,
        )
        assertEquals(
            "An earlier approval already lets the router take this much USDC: no approval is needed.",
            review.allowanceNote,
        )
    }

    @Test
    fun aSignedSwapIsSentAndRecordedAsASwap() = runTest {
        val viewModel = reviewed()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        assertIs<SwapUiState.Sent>(viewModel.uiState.value)
        assertEquals(listOf("made-up test password"), signer.passwords)
        val action = assertIs<SentAction.Swap>(sent.transfers.value.single().action)
        assertEquals(router, action.router)
        assertEquals(TransferAsset.Erc20Token(usdc), action.sell)
        assertEquals(fortyUsdc, action.amountIn)
        assertEquals(TransferAsset.Native, action.buy)
        assertEquals(UInt256.of("15920000000000000"), action.minimumOut)
    }

    @Test
    fun anAllowanceShortOfTheAmountIsApprovedFirstAndTheSwapWaitsForItsBlock() = runTest {
        allow(UInt256.of(5_000_000))
        val viewModel = reviewed()

        val approval = assertIs<SwapUiState.ApprovalReview>(viewModel.uiState.value)
        assertEquals("40 USDC", approval.amount)
        assertEquals("5 USDC", approval.allowance)
        assertEquals("0x5050505050505050505050505050505050505050", approval.spender.address)
        assertNull(approval.resetNote)

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        val action = assertIs<SentAction.TokenApproval>(sent.transfers.value.single().action)
        assertEquals(fortyUsdc, action.amount)
        // Step 1 of 2 is sent: the screen waits for its block, then quotes the swap itself.
        val pending = assertIs<SwapUiState.ApprovalPending>(viewModel.uiState.value)
        assertEquals(sent.transfers.value.single().hash, pending.hash)
        assertEquals("Step 1 of 2 sent: the approval", pending.title)

        // Not in a block yet: each quote says so, and the screen keeps waiting.
        advanceTimeBy(20.seconds)
        runCurrent()
        assertIs<SwapUiState.ApprovalPending>(viewModel.uiState.value)

        // In a block, and allowed: the swap is quoted, step 2 of 2, for the user to confirm.
        inputs.mined = 8
        allow(fortyUsdc)
        advanceTimeBy(5.seconds)
        runCurrent()
        val swap = assertIs<SwapUiState.SwapReview>(viewModel.uiState.value)
        assertEquals("Step 2 of 2", swap.step)
        assertEquals(WalletPassword.EMPTY, swap.unlock.password, "nothing typed for the approval signs the swap")
        assertEquals(1, signer.passwords.size, "nothing signed for step 2 before the user confirms it")
    }

    @Test
    fun anEarlierSwapStillPendingOnceTheApprovalIsInABlockIsSaidNotWaitedOutAsTheApproval() = runTest {
        allow(UInt256.of(5_000_000))
        val viewModel = reviewed()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        assertIs<SwapUiState.ApprovalPending>(viewModel.uiState.value)
        val approval = sent.transfers.value.single()

        // The approval is in a block, but a swap of the same token, sent since, is not.
        inputs.mined = approval.nonce + 1
        allow(fortyUsdc)
        val approved = approval.action as SentAction.TokenApproval
        val earlier = approval.copy(
            nonce = approval.nonce + 1,
            signed = SignedTransfer(byteArrayOf(0x02, 0x01)),
            action = SentAction.Swap(
                router = approved.spender,
                sell = TransferAsset.Erc20Token(approved.token),
                amountIn = fortyUsdc,
                buy = TransferAsset.Native,
                minimumOut = UInt256.of(1),
            ),
        )
        sent.transfers.value = listOf(approval, earlier)
        advanceTimeBy(5.seconds)
        runCurrent()

        val compose = assertIs<SwapUiState.Compose>(viewModel.uiState.value)
        assertEquals(earlier.hash, compose.earlier?.hash, "the earlier swap, not the approval, is waited for")
    }

    @Test
    fun aSentApprovalKeepsTheSwapWaitingUntilTheSwapIsSent() = runTest {
        allow(UInt256.of(5_000_000))
        val viewModel = reviewed()
        viewModel.onShown()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        val approval = sent.transfers.value.single().hash

        // Step 1 is sent: the swap waits for step 2, kept as its inputs for the account's Activity.
        assertEquals(
            listOf(
                WaitingSwap(
                    account = AccountId(Network.SEPOLIA, daily.address),
                    sell = usdc.address,
                    buy = null,
                    amount = fortyUsdc,
                    tolerance = SlippageTolerance.DEFAULT,
                    venue = SwapVenue.UNISWAP,
                    approval = approval,
                    step = 2,
                    steps = 2,
                ),
            ),
            waitingSwaps.swaps.value,
        )

        // The swap is sent: nothing waits any more.
        inputs.mined = 8
        allow(fortyUsdc)
        advanceTimeBy(5.seconds)
        runCurrent()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        assertIs<SwapUiState.Sent>(viewModel.uiState.value)
        assertEquals(emptyList(), waitingSwaps.swaps.value)
    }

    @Test
    fun continueFillsTheWaitingSwapInAndQuotesItsStepTwo() = runTest {
        val approval = "0x" + "a1".repeat(32)
        waitingSwaps.swaps.value = listOf(waiting(approval))

        val viewModel = viewModel(sell = usdc.address, continuing = true)
        runCurrent()

        val swap = assertIs<SwapUiState.SwapReview>(viewModel.uiState.value)
        assertEquals("Step 2 of 2", swap.step, "numbered as the step that waited")
        assertEquals(WalletPassword.EMPTY, swap.unlock.password)
        assertEquals(0, signer.passwords.size, "nothing signed before the user confirms it")
    }

    @Test
    fun aContinuedSwapThatBecameAnApprovalOnARefreshIsNumberedAnew() = runTest {
        val approval = "0x" + "a1".repeat(32)
        waitingSwaps.swaps.value = listOf(waiting(approval))
        val viewModel = viewModel(sell = usdc.address, continuing = true)
        runCurrent()
        assertEquals("Step 2 of 2", assertIs<SwapUiState.SwapReview>(viewModel.uiState.value).step)

        // The allowance moved from elsewhere meanwhile: Refresh shows an approval again.
        allow(UInt256.of(5_000_000))
        viewModel.refresh()
        runCurrent()

        val again = assertIs<SwapUiState.ApprovalReview>(viewModel.uiState.value)
        assertEquals("Step 1 of 2", again.step, "numbered as a first quote, never as the swap's step 2")
    }

    @Test
    fun continueWhereTheApprovalLeftTheAllowanceShortSaysItMayHaveFailed() = runTest {
        allow(UInt256.of(5_000_000))
        val approval = "0x" + "a1".repeat(32)
        waitingSwaps.swaps.value = listOf(waiting(approval))

        val viewModel = viewModel(sell = usdc.address, continuing = true)
        runCurrent()

        val again = assertIs<SwapUiState.ApprovalReview>(viewModel.uiState.value)
        assertEquals("Step 1 of 2", again.step)
        assertEquals(approval, again.earlierFailed?.hash)
    }

    @Test
    fun continueWhileTheApprovalIsOnItsWayWaitsForItsBlockThenQuotesStepTwo() = runTest {
        allow(UInt256.of(5_000_000))
        val first = reviewed()
        first.onPasswordChange("made-up test password")
        first.sign()
        runCurrent()
        val approval = sent.transfers.value.single().hash

        // Opened again from the account's Activity before a block has the approval.
        val viewModel = viewModel(sell = usdc.address, continuing = true)
        viewModel.onShown()
        runCurrent()
        val pending = assertIs<SwapUiState.ApprovalPending>(viewModel.uiState.value)
        assertEquals(approval, pending.hash)
        assertEquals("Step 1 of 2 sent: the approval", pending.title)

        inputs.mined = 8
        allow(fortyUsdc)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals("Step 2 of 2", assertIs<SwapUiState.SwapReview>(viewModel.uiState.value).step)
    }

    @Test
    fun continueAfterAnAllowanceSetToZeroQuotesTheWholeAmountAsStepTwoOfThree() = runTest {
        // The allowance is zero now, and the approval of the whole amount goes through (true).
        inputs.runOf = { to, data ->
            when {
                to != usdc.address -> null
                data.size == 68 && data.take(4) == APPROVE -> Simulation.Succeeded(word(UInt256.ONE), 9_412_118)
                else -> Simulation.Succeeded(word(UInt256.ZERO), 9_412_118)
            }
        }
        waitingSwaps.swaps.value = listOf(waiting("0x" + "a1".repeat(32)).copy(step = 2, steps = 3))

        val viewModel = viewModel(sell = usdc.address, continuing = true)
        runCurrent()

        val approval = assertIs<SwapUiState.ApprovalReview>(viewModel.uiState.value)
        assertEquals("Step 2 of 3", approval.step)
        assertNull(approval.earlierFailed, "the allowance set to zero was step 1: this is the next one")
    }

    @Test
    fun anEditedSwapAfterContinueIsNotNumberedAsTheOneThatWaited() = runTest {
        waitingSwaps.swaps.value = listOf(waiting("0x" + "a1".repeat(32)))
        val viewModel = viewModel(sell = usdc.address, continuing = true)
        runCurrent()
        assertEquals("Step 2 of 2", assertIs<SwapUiState.SwapReview>(viewModel.uiState.value).step)

        viewModel.edit()
        viewModel.onBuyChange(link.address.hex)
        viewModel.review()
        runCurrent()
        assertNull((viewModel.uiState.value as? SwapUiState.SwapReview)?.step, "another swap: one step")
    }

    @Test
    fun continueWithNothingKeptIsTheComposeStep() = runTest {
        val viewModel = viewModel(sell = usdc.address, continuing = true)
        runCurrent()
        assertNull(viewModel.compose.buy)
        assertEquals("", viewModel.compose.amount)
    }

    /** 40 USDC for ETH from Daily on Uniswap, waiting for step 2 of 2 after the approval [hash]. */
    private fun waiting(hash: String) = WaitingSwap(
        account = AccountId(Network.SEPOLIA, daily.address),
        sell = usdc.address,
        buy = null,
        amount = fortyUsdc,
        tolerance = SlippageTolerance.DEFAULT,
        venue = SwapVenue.UNISWAP,
        approval = hash,
        step = 2,
        steps = 2,
    )

    @Test
    fun anApprovalNoBlockHasAfterAWhileGoesBackToComposeSayingSo() = runTest {
        allow(UInt256.of(5_000_000))
        val viewModel = reviewed()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        assertIs<SwapUiState.ApprovalPending>(viewModel.uiState.value)

        advanceTimeBy(1.minutes)
        runCurrent()
        assertIs<SwapUiState.ApprovalPending>(viewModel.uiState.value)

        // The wait is bounded by time: a quarter of an hour after the approval was sent, it gives up.
        clock += 15.minutes
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(
            "An approval of this token is on its way and not in a block yet: once a block has it, Review goes on.",
            viewModel.compose.info,
        )
        assertEquals("Open the approval", assertIs<SwapUiState.Compose>(viewModel.uiState.value).earlier?.label)
    }

    @Test
    fun anApprovalABlockHasThatLeftTheAllowanceShortIsSaidBeforeAnother() = runTest {
        allow(UInt256.of(5_000_000))
        val viewModel = reviewed()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        val hash = sent.transfers.value.single().hash

        // In a block, yet the allowance is what it was: the approval may have failed.
        inputs.mined = 8
        inputs.nonce = 8
        advanceTimeBy(5.seconds)
        runCurrent()
        val again = assertIs<SwapUiState.ApprovalReview>(viewModel.uiState.value)
        assertEquals("Step 1 of 2", again.step)
        assertEquals(hash, again.earlierFailed?.hash)
    }

    @Test
    fun theWaitForTheApprovalPausesWhileTheScreenIsHidden() = runTest {
        allow(UInt256.of(5_000_000))
        val viewModel = reviewed()
        viewModel.onShown()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        viewModel.onHidden()
        inputs.mined = 8
        inputs.nonce = 8
        allow(fortyUsdc)
        advanceTimeBy(10.seconds)
        runCurrent()
        assertIs<SwapUiState.ApprovalPending>(viewModel.uiState.value, "nothing is read for a screen no one sees")

        viewModel.onShown()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals("Step 2 of 2", assertIs<SwapUiState.SwapReview>(viewModel.uiState.value).step)
    }

    @Test
    fun aWaitShownAgainPastItsTimeReadsTheChainBeforeGivingUp() = runTest {
        allow(UInt256.of(5_000_000))
        val viewModel = reviewed()
        viewModel.onShown()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        // Hidden for longer than the wait lasts, while a block took the approval.
        viewModel.onHidden()
        clock += 20.minutes
        inputs.mined = 8
        inputs.nonce = 8
        allow(fortyUsdc)

        viewModel.onShown()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals("Step 2 of 2", assertIs<SwapUiState.SwapReview>(viewModel.uiState.value).step)
    }

    @Test
    fun aReadThatFailsForAMomentWhileTheApprovalWaitsIsWaitedOut() = runTest {
        allow(UInt256.of(5_000_000))
        val viewModel = reviewed()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        inputs.mined = 8
        inputs.nonce = 8
        inputs.runs[usdc.address] = Simulation.Unavailable(ReadUnavailable.UNPROVEN)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertIs<SwapUiState.ApprovalPending>(viewModel.uiState.value)

        allow(fortyUsdc)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals("Step 2 of 2", assertIs<SwapUiState.SwapReview>(viewModel.uiState.value).step)
    }

    @Test
    fun readsThatKeepFailingWhileTheApprovalWaitsGoBackToComposeWithItsLink() = runTest {
        allow(UInt256.of(5_000_000))
        val viewModel = reviewed()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        val hash = sent.transfers.value.single().hash

        inputs.mined = 8
        inputs.nonce = 8
        inputs.runs[usdc.address] = Simulation.Unavailable(ReadUnavailable.UNPROVEN)
        advanceTimeBy(13.seconds)
        runCurrent()
        val compose = assertIs<SwapUiState.Compose>(viewModel.uiState.value)
        assertNotNull(compose.form.error)
        assertEquals(hash, compose.earlier?.hash)
    }

    @Test
    fun aTokenThatNeedsItsAllowanceSetToZeroFirstIsSwappedInThreeSteps() = runTest {
        var allowance = UInt256.of(5_000_000)
        // The token refuses to move one allowance to another: an approval of more than zero reverts while one is set.
        inputs.runOf = { to, data ->
            when {
                to != usdc.address -> null
                data.size == 68 && data.take(4) == APPROVE ->
                    if (!allowance.isZero && data.drop(36).any { it != 0.toByte() }) {
                        Simulation.Reverted(9_412_118)
                    } else {
                        Simulation.Succeeded(word(UInt256.ONE), 9_412_118)
                    }
                else -> Simulation.Succeeded(word(allowance), 9_412_118)
            }
        }
        val viewModel = reviewed()
        viewModel.onShown()
        val reset = assertIs<SwapUiState.ApprovalReview>(viewModel.uiState.value)
        assertEquals("Step 1 of 3", reset.step)
        assertEquals("Set what Uniswap's SwapRouter02 may use of USDC to zero", reset.headline)
        assertNotNull(reset.resetNote)
        assertTrue(reset.next.startsWith("Step 2, the approval of the whole amount"), reset.next)

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        assertEquals(
            "Step 1 of 3 sent: the allowance set to zero",
            assertIs<SwapUiState.ApprovalPending>(viewModel.uiState.value).title,
        )

        inputs.mined = 8
        inputs.nonce = 8
        allowance = UInt256.ZERO
        advanceTimeBy(5.seconds)
        runCurrent()
        val approval = assertIs<SwapUiState.ApprovalReview>(viewModel.uiState.value)
        assertEquals("Step 2 of 3", approval.step)
        assertEquals("Allow Uniswap's SwapRouter02 to use 40 USDC", approval.headline)
        assertNull(approval.earlierFailed)
        assertTrue(approval.next.startsWith("Step 3, the swap"), approval.next)

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        assertEquals(
            "Step 2 of 3 sent: the approval",
            assertIs<SwapUiState.ApprovalPending>(viewModel.uiState.value).title,
        )

        inputs.mined = 9
        inputs.nonce = 9
        allowance = fortyUsdc
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals("Step 3 of 3", assertIs<SwapUiState.SwapReview>(viewModel.uiState.value).step)
        assertEquals(2, signer.passwords.size, "nothing signed for the swap before the user confirms it")
    }

    @Test
    fun anApprovalThatBecameTheSwapOnARefreshDropsThePasswordTypedForIt() = runTest {
        // One word of 1 answers both the allowance read and the approval's run (true).
        allow(UInt256.ONE)
        val viewModel = reviewed()
        assertIs<SwapUiState.ApprovalReview>(viewModel.uiState.value)
        viewModel.onPasswordChange("made-up test password")

        // Approved from elsewhere meanwhile: Refresh shows the swap, which was not read yet.
        allow(fortyUsdc)
        viewModel.refresh()
        runCurrent()

        val review = assertIs<SwapUiState.SwapReview>(viewModel.uiState.value)
        assertEquals(WalletPassword.EMPTY, review.unlock.password)
        assertFalse(review.canSign)
    }

    @Test
    fun aSwapThatRevertsIsSaidOnTheComposeStepAndSimpleModeSaysItWithNoBlock() = runTest {
        inputs.runs[router] = Simulation.Reverted(9_412_118)
        val viewModel = reviewed()

        val form = viewModel.compose
        assertTrue("block 9,412,118" in form.error.orEmpty(), form.error)
        assertEquals(
            "The swap reverts, or returns less than its minimum, as checked on chain. Nothing was signed.",
            form.simpleError,
        )
    }

    @Test
    fun aPairNoPoolTakesIsSaidOnTheComposeStep() = runTest {
        val viewModel = viewModel(sell = usdc.address)
        viewModel.onBuyChange(link.address.hex)
        viewModel.onAmountChange("40")
        viewModel.review()
        runCurrent()

        assertEquals(
            "No Uniswap v3 pool takes USDC for LINK, directly or through WETH, for this amount.",
            viewModel.compose.error,
        )
    }

    @Test
    fun nothingIsSwappedFromAWatchOnlyAccountOrASafe() = runTest {
        assertEquals(
            "This account is watch-only: its key is not on this device, so nothing can be swapped from it.",
            assertIs<SwapUiState.CannotSwap>(viewModel(from = watched.address).uiState.value).message,
        )
        assertEquals(
            "A Safe's swap is proposed as Safe transactions its owners sign: Swap on the Safe's own screen " +
                "proposes one.",
            assertIs<SwapUiState.CannotSwap>(viewModel(from = safe.address).uiState.value).message,
        )
    }

    @Test
    fun thePasswordGoesWhenTheScreenIsHiddenAndTheQuoteIsMadeAgainWhenShown() = runTest {
        val viewModel = reviewed()
        viewModel.onShown()
        viewModel.onPasswordChange("made-up test password")
        val runs = uniswap.quotes

        viewModel.onHidden()
        val hidden = assertIs<SwapUiState.SwapReview>(viewModel.uiState.value)
        assertEquals(WalletPassword.EMPTY, hidden.unlock.password)
        viewModel.onShown()
        runCurrent()

        assertTrue(uniswap.quotes > runs, "quoted again")
        assertIs<SwapUiState.SwapReview>(viewModel.uiState.value)
        assertTrue(signer.asked.isEmpty(), "nothing signed")
    }

    @Test
    fun theBiometricPromptSaysWhatIsSignedInFull() = runTest {
        biometrics = BiometricUnlock.On
        val viewModel = reviewed()
        viewModel.signWithBiometrics()
        runCurrent()

        val unlock = assertIs<Unlock.WithBiometrics>(signer.asked.single().second)
        assertEquals("Swap 40 USDC for at least 0.01592 ETH", unlock.reason)
    }

    private val cow = CowDeployment(
        settlement = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41"),
        vaultRelayer = Address.of("0xc92e8bdf79f0507f65a392b0ab4667716bfe0110"),
    )

    /**
     * CoW chosen, and everything a CoW quote checks on verified state in place: the pinned venue and
     * its domain separator, a finalized block, the account followed from before it, and CoW
     * answering [quote].
     */
    private fun cowReady(
        quote: CowQuoteReading = CowQuoteReading.Quoted(UInt256.of("16100000000000000"), UInt256.ONE),
    ) {
        swapVenue.choice.value = VenueChoice.Chosen(SwapVenue.COW)
        cowChain.venue = CowVenueReading.Verified(cow)
        // Sepolia's domain separator for the settlement, as the contract answers it.
        cowChain.answers[cow.settlement to GPv2Settlement.domainSeparator().hex()] =
            Simulation.Succeeded("daee378bd0eb30ddf479272accf91761e697bc00e067a268f95f1d2732ed230b", 9_412_118)
        light.status.value = LightClientStatus.Synced(9_412_118, headTime, FinalizedBlock(9_412_050, headTime - 816))
        historyStarts.starts.value = mapOf(daily.address to HistoryStart.Fixed(9_000_000))
        cowApi.quote = quote
    }

    @Test
    fun aSwapStartsAtTheVenueLastChosenAndAChoiceIsKeptForTheNext() = runTest {
        swapVenue.choice.value = VenueChoice.Chosen(SwapVenue.COW)
        val viewModel = viewModel(sell = usdc.address)
        assertEquals(VenueItem(SwapVenue.COW), assertIs<SwapUiState.Compose>(viewModel.uiState.value).venue)

        viewModel.onVenueChange(SwapVenue.UNISWAP)
        runCurrent()

        assertEquals(SwapVenue.UNISWAP, assertIs<SwapUiState.Compose>(viewModel.uiState.value).venue.chosen)
        assertEquals(VenueChoice.Chosen(SwapVenue.UNISWAP), swapVenue.choice.value)
    }

    @Test
    fun cowIsGreyedOutForEtherSoldAndCannotBeChosenThen() = runTest {
        swapVenue.choice.value = VenueChoice.Chosen(SwapVenue.COW)
        val viewModel = viewModel()

        val venue = assertIs<SwapUiState.Compose>(viewModel.uiState.value).venue
        assertEquals(SwapVenue.UNISWAP, venue.chosen, "ETH sold: on Uniswap")
        assertTrue(venue.cowUnavailable!!.startsWith("CoW Protocol is offered for tokens sold"), venue.cowUnavailable)
        viewModel.onVenueChange(SwapVenue.COW)
        runCurrent()
        assertEquals(SwapVenue.UNISWAP, assertIs<SwapUiState.Compose>(viewModel.uiState.value).venue.chosen)
        assertEquals(VenueChoice.Chosen(SwapVenue.COW), swapVenue.choice.value, "the choice kept for a token sold")
    }

    @Test
    fun aCowOrderIsReviewedBesideTheVerifiedUniswapNetAndPlaced() = runTest {
        cowReady()
        waitingSwaps.swaps.value = listOf(waiting("0x" + "a1".repeat(32)).copy(venue = SwapVenue.COW))
        val viewModel = reviewed()

        val review = assertIs<SwapUiState.CowReview>(viewModel.uiState.value)
        assertEquals("40 USDC", review.sold)
        assertEquals("about 0.0161 ETH", review.estimate)
        assertTrue(review.estimateNote.endsWith("Not verified."), review.estimateNote)
        assertTrue(review.uniswapNote.startsWith("Uniswap's verified quote"), review.uniswapNote)
        assertFalse(review.uniswapGivesMore)
        assertEquals("at least 0.0160195 ETH", review.minimum, "CoW's estimate less the tolerance, above the floor")
        assertEquals(SpenderItem("CoW's settlement contract", Eip55.format(cow.settlement)), review.settlement)
        assertTrue(review.disclosure.startsWith("Sent to CoW's servers"))
        assertEquals(listOf(usdc.address), cowApi.quotes, "CoW asked once, for the review")

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        val placed = assertIs<SwapUiState.OrderPlaced>(viewModel.uiState.value)
        assertEquals("Sent to CoW", placed.title)
        assertEquals(emptyList(), waitingSwaps.swaps.value, "an order of the token placed: nothing of it waits")
        val record = cowOrders.orders.value.single()
        assertEquals(record.uid.hex, placed.uid)
        assertEquals(listOf(record.uid), cowApi.placed)
        assertEquals(listOf("made-up test password"), signer.passwords)
    }

    @Test
    fun cowsEstimateBelowTheUniswapNetIsSaidAndTheOrderSignsTheFloor() = runTest {
        cowReady(CowQuoteReading.Quoted(UInt256.of("12000000000000000"), UInt256.ONE))
        val review = assertIs<SwapUiState.CowReview>(reviewed().uiState.value)

        assertTrue(review.uniswapGivesMore)
        assertTrue(review.comparison.startsWith("By CoW's own estimate, Uniswap gives more."), review.comparison)
        val floor = review.minimum.removePrefix("at least ")
        assertTrue("never below the Uniswap net less it ($floor)" in review.minimumNote, review.minimumNote)
    }

    @Test
    fun cowThatDoesNotAnswerIsSaidAndTheSameSwapIsOfferedOnUniswapNeverTakenByItself() = runTest {
        cowReady(CowQuoteReading.Unavailable(CowUnavailable.TIMEOUT))
        val viewModel = reviewed()

        val compose = assertIs<SwapUiState.Compose>(viewModel.uiState.value)
        assertEquals("CoW did not answer.", compose.form.error)
        assertTrue(compose.uniswapInstead)
        assertEquals(SwapVenue.COW, compose.venue.chosen, "nothing switched by itself")

        viewModel.swapOnUniswap()
        runCurrent()

        assertIs<SwapUiState.SwapReview>(viewModel.uiState.value)
        assertEquals(VenueChoice.Chosen(SwapVenue.UNISWAP), swapVenue.choice.value)
        assertEquals(1, cowApi.quotes.size, "CoW not asked again")
    }

    @Test
    fun anUndecodableAnswerOrARefusalIsSaidInItsOwnWords() = runTest {
        cowReady(CowQuoteReading.Unavailable(CowUnavailable.UNDECODABLE))
        assertEquals(
            "CoW gave an answer this version does not understand: an update of Serotine brings CoW back.",
            assertIs<SwapUiState.Compose>(reviewed().uiState.value).form.error,
        )
        cowApi.quote = CowQuoteReading.Refused(CowRefusal.NO_LIQUIDITY, "NoLiquidity")
        val viewModel = reviewed()
        val compose = assertIs<SwapUiState.Compose>(viewModel.uiState.value)
        assertEquals("CoW found no liquidity for this swap (CoW's word: NoLiquidity).", compose.form.error)
        assertTrue(compose.uniswapInstead)
    }

    @Test
    fun onADeviceThatCannotProveAnOrdersEndCowIsGreyedOut() = runTest {
        swapVenue.choice.value = VenueChoice.Chosen(SwapVenue.COW)
        light.datesFinalizedBlocks = false
        val venue = assertIs<SwapUiState.Compose>(viewModel(sell = usdc.address).uiState.value).venue

        assertEquals(SwapVenue.UNISWAP, venue.chosen)
        val why = venue.cowUnavailable
        assertTrue(why!!.startsWith("CoW Protocol is not offered on this device"), why)
    }

    @Test
    fun aVenueGreyedOutComesBackOnlyAtTheUsersTap() = runTest {
        swapVenue.choice.value = VenueChoice.Chosen(SwapVenue.COW)
        val viewModel = viewModel()
        assertEquals(SwapVenue.UNISWAP, assertIs<SwapUiState.Compose>(viewModel.uiState.value).venue.chosen)

        viewModel.onSellChange(usdc.address.hex)

        assertEquals(SwapVenue.UNISWAP, assertIs<SwapUiState.Compose>(viewModel.uiState.value).venue.chosen)
        viewModel.onVenueChange(SwapVenue.COW)
        assertEquals(SwapVenue.COW, assertIs<SwapUiState.Compose>(viewModel.uiState.value).venue.chosen)
    }

    @Test
    fun cowIsAskedNothingUntilItIsChosenAndTheReviewAskedFor() = runTest {
        cowReady()
        swapVenue.choice.value = VenueChoice.Chosen(SwapVenue.UNISWAP)
        val viewModel = reviewed()
        assertIs<SwapUiState.SwapReview>(viewModel.uiState.value)
        viewModel.edit()
        viewModel.onVenueChange(SwapVenue.COW)
        runCurrent()

        assertTrue(cowApi.quotes.isEmpty(), "chosen, not yet reviewed: CoW knows nothing")
        viewModel.review()
        runCurrent()
        assertEquals(1, cowApi.quotes.size)
    }

    @Test
    fun cowsVaultRelayerIsApprovedFirstWhenItMayNotTakeTheAmount() = runTest {
        cowReady()
        allow(UInt256.of(5_000_000))
        val approval = assertIs<SwapUiState.ApprovalReview>(reviewed().uiState.value)

        assertEquals(SpenderItem("CoW's vault relayer", Eip55.format(cow.vaultRelayer)), approval.spender)
        assertTrue("the order is signed on its own" in approval.simulation, approval.simulation)
        assertTrue(cowApi.quotes.isEmpty(), "CoW is asked once the allowance is there")
    }

    @Test
    fun theBiometricPromptSaysWhatTheOrderSignsInFull() = runTest {
        biometrics = BiometricUnlock.On
        cowReady()
        val viewModel = reviewed()
        viewModel.signWithBiometrics()
        runCurrent()

        val unlock = assertIs<Unlock.WithBiometrics>(signer.asked.single().second)
        assertEquals("Sign a CoW order: 40 USDC for at least 0.0160195 ETH", unlock.reason)
    }

    @Test
    fun aQuoteStaleByTheVerifiedHeadAtTheTapIsQuotedAgainNotSigned() = runTest {
        cowReady()
        val viewModel = reviewed()
        val finalized = FinalizedBlock(9_412_050, headTime - 816)
        light.status.value = LightClientStatus.Synced(9_412_123, headTime + 61, finalized)
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        val review = assertIs<SwapUiState.CowReview>(viewModel.uiState.value)
        assertEquals(STALE_QUOTE, review.unlock.error)
        assertTrue(signer.asked.isEmpty(), "nothing signed")
        assertEquals(2, cowApi.quotes.size, "quoted again")
    }

    @Test
    fun howCowAnsweredTheOrderIsSaid() = runTest {
        cowReady()
        cowApi.placement = CowPlacement.Refused(CowRefusal.FUNDS, "InsufficientBalance")
        val refused = reviewed()
        refused.onPasswordChange("made-up test password")
        refused.sign()
        runCurrent()
        val placed = assertIs<SwapUiState.OrderPlaced>(refused.uiState.value)
        assertEquals("CoW refused the order", placed.title)
        assertTrue("(InsufficientBalance)" in placed.message, placed.message)

        cowOrders.orders.value = emptyList()
        cowApi.placement = CowPlacement.Unclear(CowUnavailable.UNDECODABLE)
        val unread = reviewed()
        unread.onPasswordChange("made-up test password")
        unread.sign()
        runCurrent()
        assertEquals("CoW's answer could not be read", assertIs<SwapUiState.OrderPlaced>(unread.uiState.value).title)
    }

    @Test
    fun whereUniswapGivesMoreTheSameSwapOnUniswapIsOneTapFromTheReview() = runTest {
        cowReady(CowQuoteReading.Quoted(UInt256.of("12000000000000000"), UInt256.ONE))
        val viewModel = reviewed()
        assertTrue(assertIs<SwapUiState.CowReview>(viewModel.uiState.value).uniswapGivesMore)

        viewModel.swapOnUniswap()
        runCurrent()

        assertIs<SwapUiState.SwapReview>(viewModel.uiState.value)
        assertEquals(VenueChoice.Chosen(SwapVenue.UNISWAP), swapVenue.choice.value)
    }

    @Test
    fun aLiveOrderOfTheTokenOffersNoUniswapSwapOfIt() = runTest {
        cowReady()
        val first = reviewed()
        first.onPasswordChange("made-up test password")
        first.sign()
        runCurrent()

        val compose = assertIs<SwapUiState.Compose>(reviewed().uiState.value)
        assertTrue(compose.form.error!!.startsWith("An earlier CoW order selling USDC may still fill"))
        assertFalse(compose.uniswapInstead, "the order and a Uniswap swap could both swap")
        assertEquals(cowOrders.orders.value.single().uid.hex, compose.liveOrder, "its status, one tap away")
    }

    @Test
    fun theSameSwapAgainOpensFilledInOnTheVenueAsked() = runTest {
        swapVenue.choice.value = VenueChoice.Chosen(SwapVenue.COW)
        val viewModel = viewModel(sell = usdc.address, fill = SwapFill(buy = null, "40", SwapVenue.UNISWAP))

        val compose = assertIs<SwapUiState.Compose>(viewModel.uiState.value)
        assertEquals("USDC", compose.form.sell?.symbol)
        assertEquals("ETH", compose.form.buy?.symbol)
        assertEquals("40", compose.form.amount)
        assertEquals(SwapVenue.UNISWAP, compose.venue.chosen, "the venue asked, not the last chosen")
        assertTrue(compose.form.canReview)
        assertEquals(VenueChoice.Chosen(SwapVenue.COW), swapVenue.choice.value, "nothing chosen for the next swap")
    }

    /** Verified calls of the factory, its pool and QuoterV2 a test answers; the factory lists nothing else. */
    private inner class PoolChain : UniswapV3Chain {
        val answers = mutableMapOf<Pair<Address, String>, String>()
        var quotes = 0

        override suspend fun venue(network: Network): VenueReading = VenueReading.Verified(deployment)

        override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation {
            if (to == deployment.quoterV2) quotes++
            answers[to to data.hex()]?.let { return Simulation.Succeeded(it, 9_412_118) }
            if (to == deployment.factory) return Simulation.Succeeded("00".repeat(32), 9_412_118)
            return Simulation.Reverted(9_412_118)
        }
    }

    private class FakeLightClient : LightClient {
        val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)
        override var datesFinalizedBlocks: Boolean = true

        override fun observeStatus(network: Network): Flow<LightClientStatus> = status
    }

    private companion object {
        fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        fun word(value: UInt256): String = value.toBytes32().hex()

        fun word(address: Address): String = "00".repeat(12) + address.hex.drop(2)

        /** The selector of ERC-20's `approve(address,uint256)`. */
        val APPROVE = listOf(0x09, 0x5e, 0xa7, 0xb3).map { it.toByte() }
    }
}
