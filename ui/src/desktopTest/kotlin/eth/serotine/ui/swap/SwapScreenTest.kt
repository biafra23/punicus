package eth.serotine.ui.swap

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.ASSET_OPTION_TAG
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.RouteStep
import eth.serotine.ui.components.SWAP_AMOUNT_TAG
import eth.serotine.ui.components.SWAP_BUY_TAG
import eth.serotine.ui.components.SWAP_SELL_TAG
import eth.serotine.ui.components.SWAP_TOLERANCE_TAG
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.SwapForm
import eth.serotine.ui.components.UNAVAILABLE_REASON_TAG
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SwapScreen] headless for its states. */
class SwapScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val usdcContract = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
    private val ether = AssetChoice("ETH", "Ether", "eth", "1.5 ETH")
    private val usdc = AssetChoice("USDC", "USDC", "usdc", "250 USDC", contract = usdcContract)
    private val from = SendFrom("Daily", "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2")
    private val router = SpenderItem("Uniswap's SwapRouter02", "0x5050505050505050505050505050505050505050")

    private val compose = SwapUiState.Compose(
        SwapForm(
            network = "Sepolia",
            from = from,
            sellOptions = listOf(ether, usdc),
            sell = usdc,
            buyOptions = listOf(ether),
            buy = null,
            amount = "",
            tolerance = "0.5",
        ),
        FeeLevel.NORMAL,
    )

    private val review = SwapUiState.SwapReview(
        network = "Sepolia",
        from = from,
        sell = usdc,
        sold = "40 USDC",
        buy = ether,
        receive = "0.01595 ETH",
        ranAt = "The swap, run as a verified call from this account at block 9,412,118, returns this.",
        minimum = "at least 0.01592 ETH",
        tolerance = "0.5 % tolerance, below QuoterV2's verified quote of 0.016 ETH",
        route = listOf(RouteStep("USDC → WETH", "0.3 %", "0x3030303030303030303030303030303030303030")),
        routeNote = "One Uniswap v3 pool, as the pinned factory lists it.",
        priceImpact = "Price impact: 0.31 %, the quote against the pools' spot prices, their fees aside.",
        spender = router,
        deadline = "Refused by the router after 14:32: the verified head's time and 10 minutes.",
        maxFee = "0.0033 ETH",
        feeDetail = "Normal fee",
        balances = listOf(BalanceChange("USDC balance", "250 USDC", "210 USDC", "verified at block 9,412,117")),
        unlock = UnlockStep(),
    )

    private val events = mutableListOf<String>()

    private fun show(state: SwapUiState) = show(mutableStateOf(state))

    private fun show(state: State<SwapUiState>) = rule.setContent {
        SerotineTheme {
            SwapScreen(
                state = state.value,
                chrome = SwapChrome.Strip(verifiedStripSample),
                onSellChange = { events += "sell $it" },
                onBuyChange = { events += "buy $it" },
                onAmountChange = { events += "amount $it" },
                onMax = { events += "max" },
                onToleranceChange = { events += "tolerance $it" },
                onFeeChange = { events += "fee $it" },
                onVenueChange = { events += "venue $it" },
                onSwapOnUniswap = { events += "uniswap instead" },
                onReview = { events += "review" },
                onEdit = { events += "edit" },
                onRefresh = { events += "refresh" },
                onPasswordChange = { events += "password" },
                onSign = { events += "sign" },
                onSignWithBiometrics = { events += "biometrics" },
                onRetry = { events += "retry" },
                onOpenTransfer = { events += "open $it" },
                onOpenOrder = { events += "order $it" },
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun theComposeStepChoosesBothAssetsTheAmountAndTheTolerance() {
        show(compose)

        rule.onNodeWithTag(SWAP_SELL_TAG).performClick()
        rule.onAllNodesWithTag(ASSET_OPTION_TAG)[0].performClick()
        rule.onNodeWithTag(SWAP_BUY_TAG).performClick()
        rule.onAllNodesWithTag(ASSET_OPTION_TAG)[0].performClick()
        rule.onNodeWithTag(SWAP_AMOUNT_TAG).performTextInput("40")
        rule.onNodeWithTag(SWAP_TOLERANCE_TAG).performTextInput("1")
        rule.onNodeWithTag(SWAP_REVIEW_TAG).performScrollTo().assertIsNotEnabled()

        // The fields are stateless here: each edit is of the state's text, typed at its start.
        assertEquals(listOf("sell null", "buy null", "amount 40"), events.take(3))
        assertEquals("tolerance 10.5", events.last())
    }

    @Test
    fun eachAssetIsChosenFromAMenuThatShowsTheChosenOneAndClosesOnAChoice() {
        show(compose)

        rule.onNodeWithText("Choose the asset to buy").assertExists()
        rule.onAllNodesWithTag(ASSET_OPTION_TAG).assertCountEquals(0)
        rule.onNodeWithTag(SWAP_SELL_TAG).performClick()
        rule.onAllNodesWithTag(ASSET_OPTION_TAG).assertCountEquals(2)
        rule.onNodeWithContentDescription("Chosen").assertExists()
        rule.onAllNodesWithTag(ASSET_OPTION_TAG)[1].performClick()

        rule.onAllNodesWithTag(ASSET_OPTION_TAG).assertCountEquals(0)
        assertEquals(listOf("sell $usdcContract"), events)
    }

    @Test
    fun noAssetMenuOpensWhileTheReviewIsQuoted() {
        show(compose.copy(form = compose.form.copy(quoting = true)))

        rule.onNodeWithTag(SWAP_SELL_TAG).assertIsNotEnabled().performClick()
        rule.onNodeWithTag(SWAP_BUY_TAG).assertIsNotEnabled().performClick()

        rule.onAllNodesWithTag(ASSET_OPTION_TAG).assertCountEquals(0)
        assertEquals(emptyList(), events)
    }

    @Test
    fun aMenuOpenWhenTheReviewIsQuotedStaysClosedAfterwards() {
        val state = mutableStateOf<SwapUiState>(compose)
        show(state)

        rule.onNodeWithTag(SWAP_SELL_TAG).performClick()
        rule.onAllNodesWithTag(ASSET_OPTION_TAG).assertCountEquals(2)
        state.value = compose.copy(form = compose.form.copy(quoting = true))
        rule.onAllNodesWithTag(ASSET_OPTION_TAG).assertCountEquals(0)
        state.value = compose

        rule.onAllNodesWithTag(ASSET_OPTION_TAG).assertCountEquals(0)
        assertEquals(emptyList(), events)
    }

    @Test
    fun anApprovalOnItsWayIsSaidAndOpens() {
        val hash = "0x" + "5e".repeat(32)
        show(
            compose.copy(
                form = compose.form.copy(
                    buy = ether,
                    amount = "40",
                    info = "Your approval is on its way: once a block has it, Review quotes the swap.",
                    canReview = true,
                ),
                earlier = EarlierItem(hash, "Open the approval", waiting = true),
            ),
        )

        rule.onNodeWithText("Your approval is on its way: once a block has it, Review quotes the swap.")
            .performScrollTo()
        rule.onNodeWithTag(SWAP_EARLIER_TAG).performScrollTo().performClick()
        rule.onNodeWithTag(SWAP_REVIEW_TAG).performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf("open $hash", "review"), events)
    }

    @Test
    fun theReviewShowsTheMinimumAndSignsWithThePassword() {
        show(review.copy(unlock = UnlockStep(WalletPassword("made-up test password"))))

        rule.onNodeWithText("Review swap").assertExists()
        rule.onNodeWithTag(SWAP_MINIMUM_TAG).assertTextEquals("at least 0.01592 ETH")
        rule.onNodeWithTag(SWAP_IMPACT_TAG).performScrollTo()
            .assertTextEquals("Price impact: 0.31 %, the quote against the pools' spot prices, their fees aside.")
        rule.onNodeWithText("USDC → WETH · 0.3 %").performScrollTo()
        rule.onNodeWithTag(SWAP_SIGN_TAG).performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf("sign", "edit"), events)
    }

    @Test
    fun cowGreyedOutSaysWhyOnATapAndIsNotChosen() {
        val why = "CoW Protocol is offered for tokens sold."
        show(compose.copy(venue = VenueItem(SwapVenue.UNISWAP, cowUnavailable = why)))

        rule.onNodeWithTag(SWAP_VENUE_COW_TAG).performClick()

        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals(why)
        assertEquals(emptyList(), events, "not chosen")
    }

    @Test
    fun cowOfferedIsChosenOnATap() {
        show(compose)

        rule.onNodeWithTag(SWAP_VENUE_COW_TAG).performClick()

        assertEquals(listOf("venue COW"), events)
    }

    @Test
    fun cowThatCouldNotQuoteOffersTheSameSwapOnUniswap() {
        show(
            compose.copy(
                form = compose.form.copy(error = "CoW did not answer."),
                venue = VenueItem(SwapVenue.COW),
                uniswapInstead = true,
            ),
        )

        rule.onNodeWithText("CoW did not answer.").performScrollTo()
        rule.onNodeWithTag(SWAP_UNISWAP_INSTEAD_TAG).performScrollTo().performClick()

        assertEquals(listOf("uniswap instead"), events)
    }

    @Test
    fun cowsReviewMarksItsEstimateNotVerifiedAndSignsTheOrder() {
        show(
            SwapUiState.CowReview(
                network = "Sepolia",
                from = from,
                sell = usdc,
                sold = "40 USDC",
                buy = ether,
                estimate = "about 0.0161 ETH",
                estimateNote = "CoW's own estimate. Not verified.",
                uniswapNet = "0.0142 ETH",
                uniswapNote = "Uniswap's verified quote at block 9,412,118.",
                comparison = "By CoW's estimate, CoW gives about 0.0019 ETH more than Uniswap.",
                uniswapGivesMore = false,
                minimum = "at least 0.0160195 ETH",
                minimumNote = "CoW's estimate less the 0.5 % tolerance.",
                validUntil = "Until 14:27.",
                settlement = SpenderItem("CoW's settlement contract", "0x9008D19f58AAbD9eD0D60971565AA8510560ab41"),
                balances = listOf(BalanceChange("USDC balance", "250 USDC", "210 USDC once it fills", "block 1")),
                disclosure = "Sent to CoW's servers.",
                unlock = UnlockStep(WalletPassword("made-up test password")),
            ),
        )

        rule.onNodeWithText("Review order").assertExists()
        rule.onNodeWithText("CoW's estimate, not verified").assertExists()
        rule.onNodeWithTag(SWAP_MINIMUM_TAG).performScrollTo().assertTextEquals("at least 0.0160195 ETH")
        rule.onNodeWithText("Sent to CoW's servers.").performScrollTo()
        rule.onNodeWithText("Sign and send to CoW").assertExists()
        rule.onNodeWithTag(SWAP_SIGN_TAG).performScrollTo().performClick()

        assertEquals(listOf("sign"), events)
    }

    @Test
    fun aPlacedOrderSaysHowCowAnsweredOpensItsStatusAndIsLeftWithDone() {
        val uid = "0x" + "c0".repeat(56)
        show(SwapUiState.OrderPlaced("Sepolia", "Sent to CoW", "CoW took the order.", uid))

        rule.onNodeWithText("CoW took the order.").assertExists()
        rule.onNodeWithTag(SWAP_OPEN_ORDER_TAG).performClick()
        rule.onNodeWithTag(SWAP_DONE_TAG).performClick()

        assertEquals(listOf("order $uid", "back"), events)
    }

    @Test
    fun anEarlierLiveOrderOfTheTokenOpensItsStatus() {
        val uid = "0x" + "c1".repeat(56)
        show(
            compose.copy(
                form = compose.form.copy(error = "An earlier CoW order selling USDC may still fill."),
                venue = VenueItem(SwapVenue.COW),
                liveOrder = uid,
            ),
        )

        rule.onNodeWithTag(SWAP_OPEN_ORDER_TAG).performScrollTo().performClick()

        assertEquals(listOf("order $uid"), events)
    }

    @Test
    fun theApprovalReviewNamesTheRouterAndTheAmount() {
        show(
            SwapUiState.ApprovalReview(
                network = "Sepolia",
                from = from,
                token = usdc,
                amount = "40 USDC",
                allowance = "5 USDC",
                spender = router,
                resetNote = null,
                maxFee = "0.0006 ETH",
                feeDetail = "Normal fee",
                balance = BalanceChange("ETH balance, which pays the fee", "1.5 ETH", "at least 1.4994 ETH", "now"),
                simulation = "The approval, run as a verified call, goes through.",
                unlock = UnlockStep(),
                step = "Step 1 of 2",
                next = "Step 2, the swap.",
                headline = "Allow Uniswap's SwapRouter02 to use 40 USDC",
                scope = "Only this amount, for this swap.",
            ),
        )

        rule.onNodeWithText("Approve").assertExists()
        rule.onNodeWithText("Uniswap's SwapRouter02").assertExists()
        rule.onNodeWithText("It may take 5 USDC now.").performScrollTo()
        rule.onNodeWithTag(SWAP_SIGN_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theKeyboardsNextOnTheAmountGoesToTheTolerance() {
        show(compose)
        rule.onNodeWithTag(SWAP_AMOUNT_TAG).performScrollTo().performImeAction()
        rule.onNodeWithTag(SWAP_TOLERANCE_TAG).assertIsFocused()
        assertEquals(emptyList(), events)
    }

    @Test
    fun theKeyboardsDoneOnTheToleranceReviewsOnlyWhenReviewCan() {
        show(compose)
        rule.onNodeWithTag(SWAP_TOLERANCE_TAG).performScrollTo().performImeAction()
        assertEquals(emptyList(), events)
    }

    @Test
    fun theKeyboardsDoneOnTheToleranceReviews() {
        show(compose.copy(form = compose.form.copy(buy = ether, amount = "100", canReview = true)))
        rule.onNodeWithTag(SWAP_TOLERANCE_TAG).performScrollTo().performImeAction()
        assertEquals(listOf("review"), events)
    }
}
