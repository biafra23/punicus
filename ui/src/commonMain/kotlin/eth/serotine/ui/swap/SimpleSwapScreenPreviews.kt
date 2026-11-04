package eth.serotine.ui.swap

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.RouteStep
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.SwapForm
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Swap in simple mode (docs/simple-ui.md, W1 to W4) in Android Studio's preview pane, one preview
// per state it draws differently. Built from display-ready values, never from the ViewModel.
// SimpleSwapScreenPreviewsTest renders each one in simpleSwapScreenPreviews, at the end. The
// addresses are EIP-55's own examples, and the amounts are made up.

private val from = SendFrom("Main", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed")
private val usdc = AssetChoice(
    "USDC",
    "USD Coin",
    "usdc",
    "1,250 USDC",
    contract = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
    verifiedAt = "block 23,614,077",
    age = "updated 2 min ago",
)
private val link = AssetChoice(
    "LINK",
    "Chainlink",
    "link",
    "40 LINK",
    contract = "0x514910771AF9Ca656af840dff83E8264EcF986CA",
    verifiedAt = "block 23,614,077",
    age = "updated 2 min ago",
)
private val router = SpenderItem("Uniswap's SwapRouter02", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359")

private val composed = SwapUiState.Compose(
    form = SwapForm(
        network = "Ethereum",
        from = from,
        sellOptions = listOf(usdc, link),
        sell = usdc,
        buyOptions = listOf(link),
        buy = link,
        amount = "100",
        tolerance = "0.5",
        amountHint = "≈ €92.10",
        canMax = true,
        canReview = true,
    ),
    fee = FeeLevel.NORMAL,
    venue = VenueItem(SwapVenue.UNISWAP),
)

private val approval = SwapUiState.ApprovalReview(
    network = "Ethereum",
    from = from,
    token = usdc,
    amount = "100 USDC",
    allowance = "0 USDC",
    spender = router,
    resetNote = null,
    maxFee = "0.00013 ETH",
    feeDetail = "Normal fee: 46,000 gas × at most 2.8 gwei, from the verified fee history up to block 23,614,076.",
    balance = BalanceChange(
        "ETH balance, which pays the fee",
        "3.2041 ETH",
        "at least 3.20397 ETH",
        "verified at block 23,614,077",
        short = "ETH balance",
    ),
    simulation = "The approval, run as a verified call from this account at block 23,614,077, goes through.",
    unlock = UnlockStep(biometrics = true),
    step = "Step 1 of 2",
    next = "Step 2, the swap: once a block has this approval, the price is checked again and you confirm the swap.",
    headline = "Allow Uniswap's SwapRouter02 to use 100 USDC",
    scope = "Only this amount, for this swap.",
)

private val swap = SwapUiState.SwapReview(
    network = "Ethereum",
    from = from,
    sell = usdc,
    sold = "100 USDC",
    buy = link,
    receive = "7.2154 LINK",
    ranAt = "The swap, run as a verified call from this account at block 23,614,090, returns this.",
    minimum = "at least 7.1793 LINK",
    tolerance = "0.5 % tolerance",
    route = listOf(RouteStep("USDC → WETH", "0.05 %", "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB")),
    routeNote = "One Uniswap v3 pool, as the pinned factory lists it.",
    priceImpact = "Price impact: 0.08 %",
    spender = router,
    deadline = "Refused by the router after 27 Oct 2026, 14:42.",
    maxFee = "0.00027 ETH",
    feeDetail = "Normal fee: 148,000 gas × at most 2.8 gwei, from the verified fee history up to block 23,614,089.",
    balances = listOf(
        BalanceChange("USDC balance", "1,250 USDC", "1,150 USDC", "verified at block 23,614,090"),
        BalanceChange("LINK balance", "40 LINK", "at least 47.1793 LINK", "verified at block 23,614,090"),
    ),
    unlock = UnlockStep(biometrics = true),
    step = "Step 2 of 2",
)

private val pending = SwapUiState.ApprovalPending(
    network = "Ethereum",
    hash = "0x" + "a1".repeat(32),
    title = "Step 1 of 2 sent: the approval",
    message = "Once a block has the approval, the swap is quoted again here and you confirm step 2. If you leave, " +
        "it waits in this account's Activity: Continue there quotes it again. The approval only lets USDC be " +
        "taken in a swap you send yourself.",
)

private val cow = SwapUiState.CowReview(
    network = "Ethereum",
    from = from,
    sell = usdc,
    sold = "100 USDC",
    buy = link,
    estimate = "about 7.26 LINK",
    estimateNote = "CoW's own estimate, net of the network fee it takes out of the trade. Not verified.",
    uniswapNet = "7.18 LINK",
    uniswapNote = "Uniswap's verified quote at block 23,614,077 less its expected network fee.",
    comparison = "By CoW's estimate, CoW gives about 0.08 LINK more than Uniswap. Only the minimum is signed.",
    uniswapGivesMore = false,
    minimum = "at least 7.20 LINK",
    minimumNote = "CoW's estimate less the 0.5 % tolerance, never below the Uniswap net less it.",
    validUntil = "Until 27 Oct 2026, 14:37: the verified head's time and 30 minutes.",
    settlement = SpenderItem("CoW's settlement contract", "0x9008D19f58AAbD9eD0D60971565AA8510560ab41"),
    balances = listOf(
        BalanceChange("USDC balance", "1,250 USDC", "1,150 USDC once it fills", "verified at block 23,614,077"),
    ),
    disclosure = "Sent to CoW's servers: this account's address, the pair, the amounts, the signed order and this " +
        "device's IP address. Choose Uniswap to keep it on the chain alone.",
    unlock = UnlockStep(biometrics = true),
)

@Composable
private fun SimpleSwapPreview(state: SwapUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleSwapScreen(
            state = state,
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

/** W1: the form, Uniswap chosen. */
@Preview
@Composable
private fun SimpleSwapComposePreview() = SimpleSwapPreview(composed)

/** W2: step 1 of 2, the approval, for this swap only. */
@Preview
@Composable
private fun SimpleSwapApprovalPreview() = SimpleSwapPreview(approval)

@Preview
@Composable
private fun SimpleSwapApprovalDarkPreview() = SimpleSwapPreview(approval, darkTheme = true)

/** Between the steps: the approval sent, waiting for a block to have it. */
@Preview
@Composable
private fun SimpleSwapPendingPreview() = SimpleSwapPreview(pending)

/** W3: step 2 of 2, the swap quoted again once the approval is in a block. */
@Preview
@Composable
private fun SimpleSwapStepTwoPreview() = SimpleSwapPreview(swap)

/** W4: the order on CoW, its estimate marked as not checked on chain, and what CoW learns. */
@Preview
@Composable
private fun SimpleSwapCowPreview() = SimpleSwapPreview(cow)

/** A token that needs its allowance set to zero first: step 1 of 3. */
@Preview
@Composable
private fun SimpleSwapResetFirstPreview() = SimpleSwapPreview(
    approval.copy(
        amount = "0 USDC",
        allowance = "5 USDC",
        resetNote = "USDC refuses to move one allowance to another, as the approval of the whole amount showed: " +
            "this sets it to zero first, and the whole amount is approved once that is in a block.",
        step = "Step 1 of 3",
        next = "Step 2, the approval of the whole amount: once a block has this one, it is checked again and you " +
            "confirm it, then the swap.",
        headline = "Set what Uniswap's SwapRouter02 may use of USDC to zero",
        scope = "It allows nothing: the whole amount is approved in the next step.",
    ),
)

/** A block has the approval sent before, yet the allowance still falls short. */
@Preview
@Composable
private fun SimpleSwapEarlierFailedPreview() = SimpleSwapPreview(
    approval.copy(
        earlierFailed = FailedApproval(
            "A block has the approval sent before, yet the allowance still falls short: it may have failed. Open " +
                "it to see why before you send another.",
            "0x" + "a1".repeat(32),
        ),
    ),
)

/** A swap that needs no approval: one step, "Confirm swap". */
@Preview
@Composable
private fun SimpleSwapOneStepPreview() = SimpleSwapPreview(swap.copy(step = null))

/** Signed, but not sent: Try again sends the same signed swap. */
@Preview
@Composable
private fun SimpleSwapNotSentPreview() = SimpleSwapPreview(
    swap.copy(
        step = null,
        notSent = NotSentItem(
            "Signed, but not sent: no peer took it right now (no peers). Try again sends the same signed swap, " +
                "not a new one.",
            canRetry = true,
        ),
    ),
)

/** Every preview above, which SimpleSwapScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleSwapScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SimpleSwapComposePreview() },
    { SimpleSwapApprovalPreview() },
    { SimpleSwapApprovalDarkPreview() },
    { SimpleSwapPendingPreview() },
    { SimpleSwapStepTwoPreview() },
    { SimpleSwapCowPreview() },
    { SimpleSwapResetFirstPreview() },
    { SimpleSwapEarlierFailedPreview() },
    { SimpleSwapOneStepPreview() },
    { SimpleSwapNotSentPreview() },
)
