package eth.serotine.ui.safeswap

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.ActionAddress
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.RouteStep
import eth.serotine.ui.components.SafeBalanceChange
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.SwapForm
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// A Safe's swap in simple mode in Android Studio's preview pane, one preview per state it draws
// differently from Detailed view. Built from display-ready values, never from the ViewModel.
// SimpleSafeSwapScreenPreviewsTest renders each one in simpleSafeSwapScreenPreviews, at the end. The
// addresses are EIP-55's own examples and Sepolia's pinned router; the amounts, blocks and times are
// made up.

private const val TREASURY = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val USDC = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
private const val ROUTER = "0x3bFA4769FB09eefC5a80d6E87c3B9C650f7Ae48E"
private const val POOL = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"

private val from = SendFrom("Treasury", TREASURY)
private val ether =
    AssetChoice("ETH", "Ether", "eth", "2.5 ETH", verifiedAt = "block 8,412,307", age = "updated 2 min ago")
private val usdc = AssetChoice(
    "USDC",
    "USDC",
    "usdc",
    "250 USDC",
    contract = USDC,
    verifiedAt = "block 8,412,307",
    age = "updated 2 min ago",
)

private val form = SwapForm(
    network = "Sepolia",
    from = from,
    sellOptions = listOf(ether, usdc),
    sell = usdc,
    buyOptions = listOf(ether),
    buy = ether,
    amount = "100",
    tolerance = "0.5",
    canMax = true,
    canReview = true,
)

private val review = SafeSwapUiState.Review(
    network = "Sepolia",
    from = from,
    sell = usdc,
    sold = "100 USDC",
    buy = ether,
    quoted = "0.0402 ETH",
    quotedAt = "QuoterV2's verified quote at block 8,412,307: what the route returns for this amount now.",
    simpleQuotedAt = "QuoterV2's quote, checked on chain: what the route returns for this amount now.",
    minimum = "at least 0.039999 ETH",
    tolerance = "0.5 % tolerance below the quote, fixed in the swap the owners sign.",
    route = listOf(RouteStep("USDC → WETH", "0.05 %", POOL)),
    routeNote = "One Uniswap v3 pool, as the pinned factory lists it, chosen by what it returns.",
    priceImpact = "Price impact: 0.12 %.",
    spender = SpenderItem("Uniswap's SwapRouter02", ROUTER),
    deadline = "Refused by the router after 20 Oct 2026, 14:30.",
    transactions = listOf(
        SafeSwapTransaction(
            "Safe nonce 12",
            SafeTxActionItem(
                title = "Swap 100 USDC for at least 0.04 ETH",
                addresses = listOf(ActionAddress("Router", ROUTER)),
                icon = "usdc",
                symbol = "USDC",
            ),
        ),
    ),
    approvalNote = null,
    balance = SafeBalanceChange("USDC balance of the Safe", "250 USDC", "150 USDC", "verified at block 8,412,307"),
    simulation = "The swap, run as a verified call from the Safe at block 8,412,307, returns 0.0402 ETH.",
    simpleSimulation = "Checked on chain: the swap should return 0.0402 ETH, at least its minimum.",
    owners = listOf(AccountChoice("Daily", DAILY)),
    owner = DAILY,
    unlock = UnlockStep(),
)

@Composable
private fun SimpleSafeSwapPreview(state: SafeSwapUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleSafeSwapScreen(state = state, actions = SafeSwapActions(), onBack = {})
    }
}

/** The form: each balance's age, no block, no strip. */
@Preview
@Composable
private fun SimpleSafeSwapComposePreview() = SimpleSafeSwapPreview(SafeSwapUiState.Compose(form))

/** The review: the quote and the run checked on chain, the blocks behind Details. */
@Preview
@Composable
private fun SimpleSafeSwapReviewPreview() = SimpleSafeSwapPreview(review)

@Preview
@Composable
private fun SimpleSafeSwapReviewDarkPreview() = SimpleSafeSwapPreview(review, darkTheme = true)

/** A swap refused: said with no block. */
@Preview
@Composable
private fun SimpleSafeSwapRefusedPreview() = SimpleSafeSwapPreview(
    SafeSwapUiState.Compose(
        form.copy(
            error = "The swap reverts, or returns less than its minimum, run as a verified call from the Safe at " +
                "block 8,412,307. Nothing was proposed.",
            simpleError = "The swap reverts, or returns less than its minimum, as checked on chain. Nothing was " +
                "proposed.",
        ),
    ),
)

/** Every preview above, which SimpleSafeSwapScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleSafeSwapScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SimpleSafeSwapComposePreview() },
    { SimpleSafeSwapRefusedPreview() },
    { SimpleSafeSwapReviewPreview() },
    { SimpleSafeSwapReviewDarkPreview() },
)
