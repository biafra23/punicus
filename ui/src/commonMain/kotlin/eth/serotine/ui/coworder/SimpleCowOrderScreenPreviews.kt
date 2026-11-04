package eth.serotine.ui.coworder

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// A CoW order's status in simple mode in Android Studio's preview pane, one preview per state it
// draws differently from Detailed view. Built from display-ready values, never from the ViewModel.
// SimpleCowOrderScreenPreviewsTest renders each one in simpleCowOrderScreenPreviews, at the end. The
// account's address is an EIP-55 example; CoW's settlement contract is the public one; the amounts,
// blocks, times and the order's UID are made up.

private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val SETTLEMENT = "0x9008D19f58AAbD9eD0D60971565AA8510560ab41"
private const val UID = "0xc0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0" +
    "5aaeb6053f3e94c9b9a09f33669435e7ef1beaed6ad9e7f4"

private val filled = CowOrderUiState.Shown(
    network = "Sepolia",
    from = SendFrom("Daily", DAILY),
    sold = "40 USDC",
    soldSymbol = "USDC",
    soldIcon = "usdc",
    bought = "0.016102 ETH",
    boughtSymbol = "ETH",
    boughtIcon = "eth",
    state = OrderState.FILLED,
    end = "Filled in block 8,412,322: it bought 0.016102 ETH, as the settlement's trade in this account's " +
        "verified logs says.",
    simpleEnd = "Filled: it bought 0.016102 ETH, as this account's verified logs say.",
    placement = "CoW took the order when it was sent.",
    validUntil = "Valid until 19 Oct 2026, 14:27: the verified head's time when it was quoted, and 5 minutes.",
    settlement = SpenderItem("CoW's settlement contract", SETTLEMENT),
    uid = UID,
)

private val review = CowOrderUiState.CancelReview(
    network = "Sepolia",
    from = SendFrom("Daily", DAILY),
    uid = UID,
    settlement = SpenderItem("CoW's settlement contract", SETTLEMENT),
    maxFee = "0.000957 ETH",
    feeDetail = "Normal fee: 43,500 gas × at most 22 gwei, from the verified fee history up to block 8,412,306.",
    balance = BalanceChange(
        "ETH balance, which pays the fee",
        "1.25 ETH",
        "at least 1.249043 ETH",
        "verified at block 8,412,307",
    ),
    simulation = "The cancellation, run as a verified call from this account at block 8,412,307, goes through.",
    simpleSimulation = "Checked on chain: the cancellation should go through. Once it is in a block, nothing can " +
        "fill the order.",
    unlock = UnlockStep(),
)

@Composable
private fun SimpleCowOrderPreview(state: CowOrderUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleCowOrderScreen(
            state = state,
            onCancel = {},
            onEdit = {},
            onRefresh = {},
            onPasswordChange = {},
            onSign = {},
            onSignWithBiometrics = {},
            onRetry = {},
            onOpenTransfer = {},
            onSwapAgain = {},
            onBack = {},
        )
    }
}

/** Filled, as proven from the account's verified logs, with no block. */
@Preview
@Composable
private fun SimpleCowOrderFilledPreview() = SimpleCowOrderPreview(filled)

@Preview
@Composable
private fun SimpleCowOrderFilledDarkPreview() = SimpleCowOrderPreview(filled, darkTheme = true)

/** The cancellation's review: its run checked on chain, the blocks and the fee's make-up behind Details. */
@Preview
@Composable
private fun SimpleCowOrderCancelReviewPreview() = SimpleCowOrderPreview(review)

/** Every preview above, which SimpleCowOrderScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleCowOrderScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SimpleCowOrderFilledPreview() },
    { SimpleCowOrderFilledDarkPreview() },
    { SimpleCowOrderCancelReviewPreview() },
)
