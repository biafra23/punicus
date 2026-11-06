package eth.serotine.ui.swap

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.RouteStep
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.SwapEstimateItem
import eth.serotine.ui.components.SwapForm
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Swap screen in Android Studio's preview pane, one preview per state it draws differently.
// Built from display-ready values, as the screen tests are, never from the ViewModel.
// SwapScreenPreviewsTest renders each one in swapScreenPreviews, at the end. The addresses are
// EIP-55's own examples standing in for the account, the tokens, the router, the pools and WETH9, and CoW's
// settlement contract, which is public; the names, amounts, blocks, times, the order's UID and the
// password are made up.

private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val USDC = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"
private const val ROUTER = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val POOL = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
private const val SECOND_POOL = "0x52908400098527886E0F7030069857D2E4169EE7"
private const val LINK = "0xde709f2102306220921060314715629080e2fb77"
private const val SETTLEMENT = "0x9008D19f58AAbD9eD0D60971565AA8510560ab41"
private const val WETH = "0x8617E340B3D01FA5F11F306F4090FD50E238070D"

private val from = SendFrom("Daily", DAILY)

private val ether = AssetChoice("ETH", "Ether", "eth", "1.25 ETH", verifiedAt = "block 8,412,307")

private val usdc = AssetChoice("USDC", "USDC", "usdc", "250 USDC", contract = USDC, verifiedAt = "block 8,412,305")

private val link =
    AssetChoice("LINK", "ChainLink Token", null, "0 LINK", contract = LINK, verifiedAt = "block 8,412,305")

private val router = SpenderItem("Uniswap's SwapRouter02", ROUTER)

private val weth =
    AssetChoice("WETH", "Wrapped Ether", "weth", "0.25 WETH", contract = WETH, verifiedAt = "block 8,412,305")

private val wrapReview = SwapUiState.WrapReview(
    network = "Sepolia",
    title = "Review wrap",
    signLabel = "Sign and wrap",
    from = from,
    sell = ether,
    sold = "0.1 ETH",
    buy = weth,
    receive = "0.1 WETH",
    explanation = "Not a swap: WETH9's own deposit takes the ETH and credits as much WETH, one for one. No pool, " +
        "no price, no slippage and no approval: only the network fee.",
    ranAt = "The wrap, run as a verified call from this account at block 8,412,307, goes through.",
    contract = SpenderItem("WETH9, the wrapped ether contract", WETH),
    maxFee = "0.000924 ETH",
    feeDetail = "Normal fee: 42,000 gas × at most 22 gwei, from the verified fee history up to block 8,412,306. " +
        "Only what the block charges is paid.",
    balances = listOf(
        BalanceChange("ETH balance", "1.25 ETH", "at least 1.149076 ETH", "verified at block 8,412,307"),
        BalanceChange("WETH balance", "0.25 WETH", "0.35 WETH", "verified at block 8,412,305"),
    ),
    unlock = UnlockStep(),
)

private val unwrapReview = wrapReview.copy(
    title = "Review unwrap",
    signLabel = "Sign and unwrap",
    sell = weth,
    sold = "0.1 WETH",
    buy = ether,
    receive = "0.1 ETH",
    explanation = "Not a swap: WETH9's own withdraw takes the WETH and pays as much ETH, one for one. No pool, " +
        "no price, no slippage and no approval: only the network fee.",
    ranAt = "The unwrap, run as a verified call from this account at block 8,412,307, goes through.",
    balances = listOf(
        BalanceChange("WETH balance", "0.25 WETH", "0.1 WETH", "verified at block 8,412,305"),
        BalanceChange(
            "ETH balance, which pays the fee",
            "1.25 ETH",
            "at least 1.349076 ETH",
            "verified at block 8,412,307",
        ),
    ),
    pending = "This account's CoW orders that may still fill hold back 0.05 WETH. The balance after counts it.",
)

private val empty = SwapUiState.Compose(
    form = SwapForm(
        network = "Sepolia",
        from = from,
        sellOptions = listOf(ether, usdc),
        sell = ether,
        buyOptions = listOf(usdc, link),
        buy = null,
        amount = "",
        tolerance = "0.5",
        canMax = true,
    ),
    fee = FeeLevel.NORMAL,
    // ETH sold: CoW is greyed out, as the ViewModel draws it.
    venue = VenueItem(
        SwapVenue.UNISWAP,
        cowUnavailable = "CoW Protocol is offered for tokens sold: selling ETH would go through CoW's ETH-flow " +
            "contract, which this wallet does not use yet. Sell WETH on CoW, or swap ETH on Uniswap.",
    ),
)

/** What 40 USDC would return, as the ViewModel draws the compose step's verified quote. */
private val estimate = SwapEstimateItem(
    label = "You receive",
    receive = "≈ 0.01587 ETH",
    minimum = "At least 0.01579 ETH at 0.5 % tolerance",
    rate = "1 USDC ≈ 0.0003968 ETH",
    inverseRate = "1 ETH ≈ 2519.98 USDC",
    note = "Uniswap's verified quote at block 8,412,307. The review quotes the swap again, with its network fee, " +
        "before anything is signed.",
    simpleNote = "Checked on chain. The review checks it again before you confirm.",
)

private val composed = empty.with {
    it.copy(
        sellOptions = listOf(ether, usdc),
        sell = usdc,
        buyOptions = listOf(ether, link),
        buy = ether,
        amount = "40",
        amountHint = "≈ $40.00 · testnet prices",
        canReview = true,
        estimate = estimate,
    )
}.copy(venue = VenueItem(SwapVenue.UNISWAP))

private val onCow = composed.with {
    it.copy(
        estimate = estimate.copy(
            label = "On Uniswap, you would receive",
            note = "Uniswap's verified quote at block 8,412,307, to compare. CoW is asked for its own estimate " +
                "only when you review the order.",
            simpleNote = "Uniswap's price, checked on chain. CoW's own estimate comes with the review.",
        ),
    )
}.copy(venue = VenueItem(SwapVenue.COW))

/** This form step with [edit] applied to its form and the fee [fee]. */
private fun SwapUiState.Compose.with(fee: FeeLevel = this.fee, edit: (SwapForm) -> SwapForm = { it }) =
    copy(form = edit(form), fee = fee)

private val approval = SwapUiState.ApprovalReview(
    network = "Sepolia",
    from = from,
    token = usdc,
    amount = "40 USDC",
    allowance = "0 USDC",
    spender = router,
    resetNote = null,
    maxFee = "0.000616 ETH",
    feeDetail = "Normal fee: 28,000 gas × at most 22 gwei, from the verified fee history up to block 8,412,306. " +
        "Only what the block charges is paid.",
    balance = BalanceChange(
        "ETH balance, which pays the fee",
        "1.25 ETH",
        "at least 1.249384 ETH",
        "verified at block 8,412,307",
    ),
    simulation = "The approval, run as a verified call from this account at block 8,412,307, goes through. It " +
        "moves nothing: the swap is its own transaction, quoted once a block has this one.",
    unlock = UnlockStep(),
    step = "Step 1 of 2",
    next = "Step 2, the swap: once a block has this approval, the price is checked again and you confirm the swap.",
    headline = "Allow Uniswap's SwapRouter02 to use 40 USDC",
    scope = "Only this amount, for this swap.",
)

private val review = SwapUiState.SwapReview(
    network = "Sepolia",
    from = from,
    sell = usdc,
    sold = "40 USDC",
    buy = ether,
    receive = "0.015873 ETH",
    ranAt = "The swap, run as a verified call from this account at block 8,412,307, returns this.",
    minimum = "at least 0.015794 ETH",
    tolerance = "0.5 % tolerance, below QuoterV2's verified quote of 0.015873 ETH",
    route = listOf(RouteStep("USDC → WETH", "0.05 %", POOL)),
    routeNote = "One Uniswap v3 pool, as the pinned factory lists it. ETH goes in or out as WETH, which the " +
        "router wraps or unwraps in the same transaction.",
    priceImpact = "Price impact: 0.12 %, the quote against the pools' spot prices, their fees aside.",
    priceHint = "Sepolia's pools and Chainlink's testnet feeds do not track each other: this quote is not held " +
        "against the prices the wallet shows.",
    spender = router,
    allowanceNote = "An earlier approval already lets the router take this much USDC: no approval is needed.",
    deadline = "Refused by the router after 19 Oct 2026, 14:32: the verified head's time and 10 minutes.",
    maxFee = "0.003256 ETH",
    feeDetail = "Normal fee: 148,000 gas × at most 22 gwei, from the verified fee history up to block 8,412,306. " +
        "Only what the block charges is paid.",
    balances = listOf(
        BalanceChange("USDC balance", "250 USDC", "210 USDC", "verified at block 8,412,305"),
        BalanceChange("ETH balance", "1.25 ETH", "at least 1.262538 ETH", "verified at block 8,412,307"),
    ),
    unlock = UnlockStep(),
)

private val cowReview = SwapUiState.CowReview(
    network = "Sepolia",
    from = from,
    sell = usdc,
    sold = "40 USDC",
    buy = ether,
    estimate = "about 0.015931 ETH",
    estimateNote = "CoW's own estimate, net of the network fee it takes out of the trade: 0.41 USDC. Not verified.",
    uniswapNet = "0.015688 ETH",
    uniswapNote = "Uniswap's verified quote at block 8,412,307 less its expected network fee, about 0.000185 ETH.",
    comparison = "By CoW's estimate, CoW gives about 0.000243 ETH more than Uniswap. Only the minimum below is signed.",
    uniswapGivesMore = false,
    minimum = "at least 0.015851 ETH",
    minimumNote = "CoW's estimate less the 0.5 % tolerance, never below the Uniswap net less it (0.01561 ETH). A " +
        "fill that buys less is refused by the settlement contract.",
    validUntil = "Until 19 Oct 2026, 14:27: the verified head's time and 5 minutes. It fills by then or not at all.",
    settlement = SpenderItem("CoW's settlement contract", SETTLEMENT),
    balances = listOf(
        BalanceChange("USDC balance", "250 USDC", "210 USDC once it fills", "verified at block 8,412,305"),
    ),
    disclosure = "Sent to CoW's servers (api.cow.fi): this account's address, the pair, the amounts, the signed " +
        "order and this device's IP address. Whether it fills, and what it bought, is read from the chain, never " +
        "from CoW.",
    unlock = UnlockStep(),
)

private val hopReview = review.copy(
    sell = usdc,
    sold = "40 USDC",
    buy = link,
    receive = "2.871904 LINK",
    minimum = "at least 2.857544 LINK",
    tolerance = "0.5 % tolerance, below QuoterV2's verified quote of 2.871904 LINK",
    route = listOf(RouteStep("USDC → WETH", "0.05 %", POOL), RouteStep("WETH → LINK", "0.3 %", SECOND_POOL)),
    routeNote = "Through WETH: two Uniswap v3 pools, as the pinned factory lists them.",
    priceImpact = "Price impact: not known this time: the pools' spot prices could not be read at the quote's " +
        "block, or give no measure for this amount.",
    netNote = "The route was chosen by what it returns, without its network fee, which could not be had right now.",
    balances = listOf(
        BalanceChange("USDC balance", "250 USDC", "210 USDC", "verified at block 8,412,305"),
        BalanceChange("LINK balance", "0 LINK", "at least 2.857544 LINK", "verified at block 8,412,305"),
        BalanceChange(
            "ETH balance, which pays the fee",
            "1.25 ETH",
            "at least 1.243174 ETH",
            "verified at block 8,412,307",
        ),
    ),
    pending = "Earlier transactions from this account, not mined yet, may still take up to 0.00357 ETH: the " +
        "balance after counts it.",
    unlock = UnlockStep(biometrics = true),
)

@Composable
private fun SwapPreview(
    state: SwapUiState,
    darkTheme: Boolean = false,
    lightClient: LightClientItem = verifiedStripSample,
) {
    SerotineTheme(darkTheme = darkTheme) {
        SwapScreen(
            state = state,
            chrome = SwapChrome.Strip(lightClient),
            onSellChange = {},
            onBuyChange = {},
            onAmountChange = {},
            onMax = {},
            onToleranceChange = {},
            onFeeChange = {},
            onVenueChange = {},
            onSwapOnUniswap = {},
            onReview = {},
            onEdit = {},
            onRefresh = {},
            onPasswordChange = {},
            onSign = {},
            onSignWithBiometrics = {},
            onRetry = {},
            onOpenTransfer = {},
            onOpenOrder = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun SwapComposeEmptyPreview() = SwapPreview(empty)

@Preview
@Composable
private fun SwapComposeEmptyDarkPreview() = SwapPreview(empty, darkTheme = true)

@Preview
@Composable
private fun SwapComposedPreview() = SwapPreview(composed)

@Preview
@Composable
private fun SwapComposeEstimateCheckingPreview() = SwapPreview(
    composed.with {
        it.copy(
            estimate = SwapEstimateItem(
                label = "You receive",
                note = "Asking Uniswap's quoter, through the light client.",
                checking = true,
            ),
        )
    },
)

@Preview
@Composable
private fun SwapComposeEstimateUnavailablePreview() = SwapPreview(
    composed.with {
        it.copy(
            estimate = SwapEstimateItem(
                label = "You receive",
                note = "The light client is still syncing: the swap is quoted once it has caught up.",
            ),
        )
    },
)

@Preview
@Composable
private fun SwapComposeHighImpactPreview() = SwapPreview(
    composed.with {
        it.copy(
            estimate = estimate.copy(
                impact = "Price impact 3.42 %: this amount moves the pools' price. The review says more.",
            ),
        )
    },
)

@Preview
@Composable
private fun SwapComposeQuotingPreview() = SwapPreview(composed.with { it.copy(quoting = true, canReview = false) })

@Preview
@Composable
private fun SwapComposeInputErrorsPreview() = SwapPreview(
    composed.with {
        it.copy(
            amount = "0,5",
            amountError = "Use a dot for the decimals: 0.5, not 0,5.",
            amountHint = null,
            tolerance = "7",
            toleranceError = "Between 0.1 % and 5 %, with at most two decimals (0.5).",
            canReview = false,
        )
    },
)

@Preview
@Composable
private fun SwapComposeNoRoutePreview() = SwapPreview(
    composed.with {
        it.copy(
            buy = link,
            error = "No Uniswap v3 pool takes USDC for LINK, directly or through WETH, for this amount.",
            // The estimate says no more than the refused Review: the ViewModel leaves it out.
            estimate = null,
        )
    },
)

@Preview
@Composable
private fun SwapComposeCowOrdersUnreadablePreview() = SwapPreview(
    composed.with {
        it.copy(
            error = "The record of this device's CoW orders cannot be read: one may still fill, so no token is " +
                "swapped until it can be read.",
        )
    },
)

@Preview
@Composable
private fun SwapComposeOnCowPreview() = SwapPreview(onCow)

@Preview
@Composable
private fun SwapComposeCowDidNotAnswerPreview() = SwapPreview(
    onCow.with { it.copy(error = "CoW did not answer.") }.copy(uniswapInstead = true),
)

@Preview
@Composable
private fun SwapComposeEarlierOrderLivePreview() = SwapPreview(
    onCow.with {
        it.copy(
            error = "An earlier CoW order selling USDC may still fill: one order of a token at a time, since both " +
                "would draw on one allowance. It is done once it fills or can no longer fill.",
        )
    }.copy(liveOrder = "0x" + "c1".repeat(32) + "5aaeb6053f3e94c9b9a09f33669435e7ef1beaed" + "6ad9e7f4"),
)

@Preview
@Composable
private fun SwapComposeApprovalSentPreview() = SwapPreview(
    composed.with {
        it.copy(info = "Your approval is on its way: once a block has it, Review quotes the swap.")
    }.copy(earlier = EarlierItem("0x" + "5e".repeat(32), "Open the approval", waiting = true)),
)

@Preview
@Composable
private fun SwapComposeEarlierMaybeSentPreview() = SwapPreview(
    composed.with {
        it.copy(
            error = "An earlier transaction from this account may still be on its way: no peer answered when it " +
                "was broadcast, and no block has it yet. Try it again from its status rather than sign a new " +
                "one, which could pay twice.",
        )
    }.copy(earlier = EarlierItem("0x" + "5e".repeat(32), "Open the earlier transaction", waiting = false)),
)

@Preview
@Composable
private fun SwapComposeTokenNotFoundPreview() = SwapPreview(
    empty.with {
        it.copy(
            sell = null,
            canMax = false,
            note = "The token asked for is not among this account's tokens: choose another.",
        )
    },
)

@Preview
@Composable
private fun SwapApprovalPreview() = SwapPreview(approval)

@Preview
@Composable
private fun SwapApprovalResetFirstPreview() = SwapPreview(
    approval.copy(
        amount = "0 USDC",
        allowance = "12 USDC",
        resetNote = "USDC refuses to move one allowance to another, as the approval of the whole amount showed: " +
            "this sets it to zero first, and the whole amount is approved once that is in a block.",
        unlock = UnlockStep(WalletPassword("made-up preview password")),
        step = "Step 1 of 3",
        next = "Step 2, the approval of the whole amount: once a block has this one, it is checked again and you " +
            "confirm it, then the swap.",
        headline = "Set what Uniswap's SwapRouter02 may use of USDC to zero",
        scope = "It allows nothing: the whole amount is approved in the next step.",
    ),
)

@Preview
@Composable
private fun SwapReviewPreview() = SwapPreview(
    review.copy(unlock = UnlockStep(WalletPassword("made-up preview password"))),
)

@Preview
@Composable
private fun SwapReviewDarkPreview() = SwapPreview(review, darkTheme = true)

@Preview
@Composable
private fun SwapReviewThroughWethWithBiometricsPreview() = SwapPreview(hopReview)

@Preview
@Composable
private fun SwapReviewWrongPasswordPreview() = SwapPreview(
    review.copy(unlock = UnlockStep(error = "That is not the wallet password. Nothing was signed.")),
)

@Preview
@Composable
private fun SwapReviewSigningPreview() = SwapPreview(review.copy(signing = true))

@Preview
@Composable
private fun SwapReviewRefreshingPreview() = SwapPreview(
    review.copy(refreshing = true, unlock = UnlockStep(WalletPassword("made-up preview password"))),
)

@Preview
@Composable
private fun SwapReviewNotSentPreview() = SwapPreview(
    review.copy(
        notSent = NotSentItem(
            "Signed, but not sent: no peer took it right now (no peers). Try again sends the same signed swap, " +
                "not a new one.",
            canRetry = true,
        ),
    ),
)

@Preview
@Composable
private fun SwapCowReviewPreview() = SwapPreview(
    cowReview.copy(unlock = UnlockStep(WalletPassword("made-up preview password"))),
)

@Preview
@Composable
private fun SwapCowReviewDarkPreview() = SwapPreview(cowReview, darkTheme = true)

@Preview
@Composable
private fun SwapCowReviewUniswapGivesMorePreview() = SwapPreview(
    cowReview.copy(
        estimate = "about 0.015402 ETH",
        comparison = "By CoW's own estimate, Uniswap gives more. The order is signed at the floor below, and may " +
            "well not fill. Then nothing is swapped, but the USDC it sells stays held back until a finalized block " +
            "is past its validity, about 18 to 24 minutes from now.",
        uniswapGivesMore = true,
        minimum = "at least 0.01561 ETH",
    ),
)

@Preview
@Composable
private fun SwapOrderPlacedPreview() = SwapPreview(
    SwapUiState.OrderPlaced(
        "Sepolia",
        "Sent to CoW",
        "CoW took the order. It fills before 19 Oct 2026, 14:27 or not at all; what it bought shows once the " +
            "chain proves it. The USDC it sells is held back from other sends and swaps until it fills or can no " +
            "longer fill.",
        "0x" + "c0".repeat(32) + "5aaeb6053f3e94c9b9a09f33669435e7ef1beaed" + "6ad9e7f4",
    ),
)

@Preview
@Composable
private fun SwapLoadingPreview() = SwapPreview(SwapUiState.Loading("Sepolia"))

@Preview
@Composable
private fun SwapWatchOnlyPreview() = SwapPreview(
    SwapUiState.CannotSwap(
        "Sepolia",
        "This account is watch-only: its key is not on this device, so nothing can be swapped from it.",
    ),
)

@Preview
@Composable
private fun SwapSentPreview() = SwapPreview(SwapUiState.Sent("Sepolia", "0x" + "5e".repeat(32)))

/** Between the steps: the approval sent, the swap quoted once a block has it. */
@Preview
@Composable
private fun SwapApprovalPendingPreview() = SwapPreview(
    SwapUiState.ApprovalPending(
        network = "Sepolia",
        hash = "0x" + "a1".repeat(32),
        title = "Step 1 of 2 sent: the approval",
        message = "Once a block has the approval, the swap is quoted again here and you confirm step 2. If you " +
            "leave, it waits in this account's Activity: Continue there quotes it again. The approval only lets " +
            "USDC be taken in a swap you send yourself.",
    ),
)

/** The swap quoted once its approval is in a block: step 2 of 2. */
@Preview
@Composable
private fun SwapReviewStepTwoPreview() = SwapPreview(review.copy(step = "Step 2 of 2"))

/** A block has the approval sent before, yet the allowance still falls short: said before another is sent. */
@Preview
@Composable
private fun SwapApprovalEarlierFailedPreview() = SwapPreview(
    approval.copy(
        earlierFailed = FailedApproval(
            "A block has the approval sent before, yet the allowance still falls short: it may have failed. Open " +
                "it to see why before you send another.",
            "0x" + "a1".repeat(32),
        ),
    ),
)

@Preview
@Composable
private fun SwapWrapReviewPreview() = SwapPreview(
    wrapReview.copy(unlock = UnlockStep(WalletPassword("made-up preview password"))),
)

@Preview
@Composable
private fun SwapWrapReviewDarkPreview() = SwapPreview(wrapReview, darkTheme = true)

@Preview
@Composable
private fun SwapUnwrapReviewPreview() = SwapPreview(unwrapReview)

/** Every preview above, which SwapScreenPreviewsTest renders: a new preview goes in here too. */
internal val swapScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("compose-empty") { SwapComposeEmptyPreview() },
    ScreenPreview("compose-empty-dark") { SwapComposeEmptyDarkPreview() },
    ScreenPreview("composed") { SwapComposedPreview() },
    ScreenPreview("compose-estimate-checking") { SwapComposeEstimateCheckingPreview() },
    ScreenPreview("compose-estimate-unavailable") { SwapComposeEstimateUnavailablePreview() },
    ScreenPreview("compose-high-impact") { SwapComposeHighImpactPreview() },
    ScreenPreview("compose-quoting") { SwapComposeQuotingPreview() },
    ScreenPreview("compose-input-errors") { SwapComposeInputErrorsPreview() },
    ScreenPreview("compose-no-route") { SwapComposeNoRoutePreview() },
    ScreenPreview("compose-cow-orders-unreadable") { SwapComposeCowOrdersUnreadablePreview() },
    ScreenPreview("compose-on-cow") { SwapComposeOnCowPreview() },
    ScreenPreview("compose-cow-did-not-answer") { SwapComposeCowDidNotAnswerPreview() },
    ScreenPreview("compose-earlier-order-live") { SwapComposeEarlierOrderLivePreview() },
    ScreenPreview("compose-approval-sent") { SwapComposeApprovalSentPreview() },
    ScreenPreview("compose-earlier-maybe-sent") { SwapComposeEarlierMaybeSentPreview() },
    ScreenPreview("compose-token-not-found") { SwapComposeTokenNotFoundPreview() },
    ScreenPreview("approval") { SwapApprovalPreview() },
    ScreenPreview("approval-reset-first") { SwapApprovalResetFirstPreview() },
    ScreenPreview("review") { SwapReviewPreview() },
    ScreenPreview("review-dark") { SwapReviewDarkPreview() },
    ScreenPreview("review-through-weth-with-biometrics") { SwapReviewThroughWethWithBiometricsPreview() },
    ScreenPreview("review-wrong-password") { SwapReviewWrongPasswordPreview() },
    ScreenPreview("review-signing") { SwapReviewSigningPreview() },
    ScreenPreview("review-refreshing") { SwapReviewRefreshingPreview() },
    ScreenPreview("review-not-sent") { SwapReviewNotSentPreview() },
    ScreenPreview("cow-review") { SwapCowReviewPreview() },
    ScreenPreview("cow-review-dark") { SwapCowReviewDarkPreview() },
    ScreenPreview("cow-review-uniswap-gives-more") { SwapCowReviewUniswapGivesMorePreview() },
    ScreenPreview("order-placed") { SwapOrderPlacedPreview() },
    ScreenPreview("loading") { SwapLoadingPreview() },
    ScreenPreview("watch-only") { SwapWatchOnlyPreview() },
    ScreenPreview("sent") { SwapSentPreview() },
    ScreenPreview("approval-pending") { SwapApprovalPendingPreview() },
    ScreenPreview("review-step-two") { SwapReviewStepTwoPreview() },
    ScreenPreview("approval-earlier-failed") { SwapApprovalEarlierFailedPreview() },
    ScreenPreview("wrap-review") { SwapWrapReviewPreview() },
    ScreenPreview("wrap-review-dark") { SwapWrapReviewDarkPreview() },
    ScreenPreview("unwrap-review") { SwapUnwrapReviewPreview() },
)
