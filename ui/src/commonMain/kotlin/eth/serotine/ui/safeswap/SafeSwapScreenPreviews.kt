package eth.serotine.ui.safeswap

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.ActionAddress
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.RouteStep
import eth.serotine.ui.components.SafeBalanceChange
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.SwapForm
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Swap for a Safe in Android Studio's preview pane, one preview per state it draws differently.
// Built from display-ready values, as the screen tests are, never from the ViewModel.
// SafeSwapScreenPreviewsTest renders each one in safeSwapScreenPreviews, at the end. The addresses
// are EIP-55's own examples and Sepolia's pinned router; the names, amounts, blocks, times and the
// password are made up. A preview has no window to keep out of screen capture, so the review's
// password field shows the line saying so, which a device that blocks capture does not.

private const val TREASURY = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val SAVINGS = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val USDC = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
private const val USDT = "0x52908400098527886E0F7030069857D2E4169EE7"
private const val ROUTER = "0x3bFA4769FB09eefC5a80d6E87c3B9C650f7Ae48E"
private const val POOL = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"

private val from = SendFrom("Treasury", TREASURY)
private val ether = AssetChoice("ETH", "Ether", "eth", "2.5 ETH", verifiedAt = "block 8,412,307")
private val usdc = AssetChoice("USDC", "USDC", "usdc", "250 USDC", contract = USDC, verifiedAt = "block 8,412,307")
private val usdt =
    AssetChoice("USDT", "Tether USD", "usdt", "250 USDT", contract = USDT, verifiedAt = "block 8,412,307")

private val form = SwapForm(
    network = "Sepolia",
    from = from,
    sellOptions = listOf(ether, usdc),
    sell = usdc,
    buyOptions = listOf(ether),
    buy = ether,
    amount = "100",
    tolerance = "0.5",
    amountHint = "≈ $100.00 · testnet prices",
    canMax = true,
    info = "Proposed as Safe transactions its owners sign: the router's approval first when it needs one, then " +
        "the swap, its minimum and a deadline 24 hours away fixed now. Whoever executes it pays the network fee.",
    canReview = true,
)

private val approval = SafeSwapTransaction(
    "Safe nonce 12",
    SafeTxActionItem(
        title = "Allow a contract to take 100 USDC",
        addresses = listOf(ActionAddress("Spender", ROUTER), ActionAddress("Token contract", USDC)),
        notes = listOf("Whether the spender is Uniswap's is checked against the chain once the transaction is opened."),
        icon = "usdc",
        symbol = "USDC",
    ),
)

private val swap = SafeSwapTransaction(
    "Safe nonce 13",
    SafeTxActionItem(
        title = "Swap 100 USDC for at least 0.04 ETH",
        addresses = listOf(ActionAddress("Router", ROUTER), ActionAddress("Token sold", USDC)),
        notes = listOf("Through one pool at 0.05 %.", "Paid to the Safe."),
        icon = "usdc",
        symbol = "USDC",
    ),
)

private val review = SafeSwapUiState.Review(
    network = "Sepolia",
    from = from,
    sell = usdc,
    sold = "100 USDC",
    buy = ether,
    quoted = "0.0402 ETH",
    quotedAt = "QuoterV2's verified quote at block 8,412,307: what the route returns for this amount now.",
    minimum = "at least 0.039999 ETH",
    tolerance = "0.5 % tolerance below the quote, fixed in the swap the owners sign: it is not quoted again when " +
        "they do.",
    route = listOf(RouteStep("USDC → WETH", "0.05 %", POOL)),
    routeNote = "One Uniswap v3 pool, as the pinned factory lists it, chosen by what it returns. ETH goes in or out " +
        "as WETH, which the router wraps or unwraps in the same transaction.",
    priceImpact = "Price impact: 0.12 %, the quote against the pools' spot prices, their fees aside.",
    priceHint = "Sepolia's pools and Chainlink's testnet feeds do not track each other: this quote is not held " +
        "against the prices the wallet shows.",
    spender = SpenderItem("Uniswap's SwapRouter02", ROUTER),
    deadline = "Refused by the router after 20 Oct 2026, 14:30: the verified head's time and 24 hours, for its " +
        "owners to sign and one to execute it.",
    transactions = listOf(approval, swap),
    approvalNote = "The router may take 0 USDC of the Safe's USDC: the approval before the swap lets it take exactly " +
        "the amount sold, in its own Safe transaction.",
    balance = SafeBalanceChange("USDC balance of the Safe", "250 USDC", "150 USDC", "verified at block 8,412,307"),
    simulation = "The approval, run as a verified call from the Safe at block 8,412,307, goes through. The swap " +
        "cannot run before the router's allowance is approved on chain: it is run as a verified call when its " +
        "execution is quoted, before anyone sends it.",
    owners = listOf(AccountChoice("Daily", DAILY), AccountChoice("Savings", SAVINGS)),
    owner = DAILY,
    unlock = UnlockStep(),
)

@Composable
private fun SafeSwapPreview(
    state: SafeSwapUiState,
    darkTheme: Boolean = false,
    lightClient: LightClientItem = verifiedStripSample,
) {
    SerotineTheme(darkTheme = darkTheme) {
        SafeSwapScreen(state = state, lightClient = lightClient, actions = SafeSwapActions(), onBack = {})
    }
}

@Preview
@Composable
private fun SafeSwapComposePreview() = SafeSwapPreview(SafeSwapUiState.Compose(form))

@Preview
@Composable
private fun SafeSwapComposeDarkPreview() = SafeSwapPreview(SafeSwapUiState.Compose(form), darkTheme = true)

@Preview
@Composable
private fun SafeSwapRefusedPreview() = SafeSwapPreview(
    SafeSwapUiState.Compose(
        form.copy(
            error = "A swap of USDC is queued at Safe nonce 12, and counts on the router's allowance this one " +
                "would set: execute or reject it first.",
        ),
    ),
)

@Preview
@Composable
private fun SafeSwapProposeAgainPreview() = SafeSwapPreview(
    SafeSwapUiState.Compose(
        form.copy(sellOptions = listOf(usdc), buyOptions = listOf(ether), canMax = false, info = null, fixed = true),
        replacing = "Proposes the swap queued at Safe nonce 13 again, in its place: the same amount and pair, with " +
            "a fresh minimum and deadline. Only one of the two can ever run, and the owners sign the new one anew.",
    ),
)

@Preview
@Composable
private fun SafeSwapReviewPreview() = SafeSwapPreview(
    review.copy(unlock = UnlockStep(WalletPassword("made-up preview password"))),
)

@Preview
@Composable
private fun SafeSwapReviewDarkPreview() = SafeSwapPreview(review, darkTheme = true)

@Preview
@Composable
private fun SafeSwapReviewSwapOnlyPreview() = SafeSwapPreview(
    review.copy(
        transactions = listOf(swap.copy(nonce = "Safe nonce 12")),
        approvalNote = "The router may take 100 USDC of the Safe's USDC, on chain or once those queued before it " +
            "run: no approval of its own is needed.",
        simulation = "The swap, run as a verified call from the Safe at block 8,412,307, returns 0.0401 ETH: at " +
            "least its minimum.",
    ),
)

private fun usdtApproval(title: String) = approval.action.copy(
    title = title,
    addresses = listOf(ActionAddress("Spender", ROUTER), ActionAddress("Token contract", USDT)),
    icon = "usdt",
    symbol = "USDT",
)

@Preview
@Composable
private fun SafeSwapReviewResetFirstPreview() = SafeSwapPreview(
    review.copy(
        sell = usdt,
        sold = "100 USDT",
        route = listOf(RouteStep("USDT → WETH", "0.05 %", POOL)),
        spender = SpenderItem("Uniswap's SwapRouter02", ROUTER),
        transactions = listOf(
            SafeSwapTransaction(
                "Safe nonce 12",
                usdtApproval("Allow a contract to take 0 USDT"),
            ),
            SafeSwapTransaction(
                "Safe nonce 13",
                usdtApproval("Allow a contract to take 100 USDT"),
            ),
            SafeSwapTransaction(
                "Safe nonce 14",
                swap.action.copy(
                    title = "Swap 100 USDT for at least 0.04 ETH",
                    addresses = listOf(ActionAddress("Router", ROUTER), ActionAddress("Token sold", USDT)),
                    icon = "usdt",
                    symbol = "USDT",
                ),
            ),
        ),
        approvalNote = "The router may take 5 USDT of the Safe's USDT, and USDT refuses to move one allowance to " +
            "another, as the approval of the whole amount showed: the first Safe transaction sets it to zero, the " +
            "next approves exactly the amount sold, each its own.",
        balance = SafeBalanceChange("USDT balance of the Safe", "250 USDT", "150 USDT", "verified at block 8,412,307"),
        simulation = "The allowance set to zero, run as a verified call from the Safe at block 8,412,307, goes " +
            "through; the approval after it cannot run before it has, and is run as a verified call when its " +
            "execution is quoted. The swap cannot run before the router's allowance is approved on chain: it is " +
            "run as a verified call when its execution is quoted, before anyone sends it.",
    ),
)

@Preview
@Composable
private fun SafeSwapReviewReplacesPreview() = SafeSwapPreview(
    review.copy(
        transactions = listOf(swap),
        approvalNote = null,
        replaces = "Takes the place of the swap queued at Safe nonce 13: only one of the two can ever run. The " +
            "signatures collected for that one do not count for this one.",
        simulation = "The swap, run as a verified call from the Safe at block 8,412,307, returns 0.0401 ETH: at " +
            "least its minimum.",
    ),
)

@Preview
@Composable
private fun SafeSwapReviewSigningPreview() = SafeSwapPreview(
    review.copy(signing = true, progress = "Transaction 2 of 2"),
)

@Preview
@Composable
private fun SafeSwapReviewHalfSignedPreview() = SafeSwapPreview(
    review.copy(
        error = "The approval is signed and queued; the swap is not. Refresh, and the swap is proposed after the " +
            "approval: Serotine could not finish this signature. Nothing more was queued: try again.",
    ),
)

@Preview
@Composable
private fun SafeSwapLoadingPreview() = SafeSwapPreview(SafeSwapUiState.Loading("Sepolia"))

@Preview
@Composable
private fun SafeSwapWatchOnlyPreview() = SafeSwapPreview(
    SafeSwapUiState.CannotSwap(
        "Sepolia",
        "No owner of this Safe has its key on this device: it is watch-only here, and nothing can be proposed " +
            "from it.",
    ),
)

/** Every preview above, which SafeSwapScreenPreviewsTest renders: a new preview goes in here too. */
internal val safeSwapScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SafeSwapComposePreview() },
    { SafeSwapComposeDarkPreview() },
    { SafeSwapRefusedPreview() },
    { SafeSwapProposeAgainPreview() },
    { SafeSwapReviewPreview() },
    { SafeSwapReviewDarkPreview() },
    { SafeSwapReviewSwapOnlyPreview() },
    { SafeSwapReviewResetFirstPreview() },
    { SafeSwapReviewReplacesPreview() },
    { SafeSwapReviewSigningPreview() },
    { SafeSwapReviewHalfSignedPreview() },
    { SafeSwapLoadingPreview() },
    { SafeSwapWatchOnlyPreview() },
)
