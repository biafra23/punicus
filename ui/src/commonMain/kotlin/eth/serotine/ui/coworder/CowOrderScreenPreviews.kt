package eth.serotine.ui.coworder

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// A CoW order's status in Android Studio's preview pane, one preview per state it draws differently.
// Built from display-ready values, as the screen tests are, never from the ViewModel.
// CowOrderScreenPreviewsTest renders each one in cowOrderScreenPreviews, at the end. The account's
// address is an EIP-55 example; CoW's settlement contract is the public one; the names, amounts,
// blocks, times, the order's UID and the password are made up.

private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val SETTLEMENT = "0x9008D19f58AAbD9eD0D60971565AA8510560ab41"
private const val UID = "0xc0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0" +
    "5aaeb6053f3e94c9b9a09f33669435e7ef1beaed6ad9e7f4"

private val live = CowOrderUiState.Shown(
    network = "Sepolia",
    from = SendFrom("Daily", DAILY),
    sold = "40 USDC",
    soldSymbol = "USDC",
    soldIcon = "usdc",
    bought = "at least 0.015851 ETH",
    boughtSymbol = "ETH",
    boughtIcon = "eth",
    state = OrderState.LIVE,
    end = "Not proven yet: it may fill until 19 Oct 2026, 14:27. Its end is read from this account's verified " +
        "logs, never from CoW.",
    held = "The USDC it sells is held back from other sends and swaps until it fills or can no longer fill.",
    cow = "CoW says, not verified: the order is open in its order book, in the auction.",
    placement = "CoW took the order when it was sent.",
    validUntil = "Valid until 19 Oct 2026, 14:27: the verified head's time when it was quoted, and 5 minutes.",
    settlement = SpenderItem("CoW's settlement contract", SETTLEMENT),
    uid = UID,
    canCancel = true,
)

private val review = CowOrderUiState.CancelReview(
    network = "Sepolia",
    from = SendFrom("Daily", DAILY),
    uid = UID,
    settlement = SpenderItem("CoW's settlement contract", SETTLEMENT),
    maxFee = "0.000957 ETH",
    feeDetail = "Normal fee: 43,500 gas × at most 22 gwei, from the verified fee history up to block 8,412,306. " +
        "Only what the block charges is paid.",
    balance = BalanceChange(
        "ETH balance, which pays the fee",
        "1.25 ETH",
        "at least 1.249043 ETH",
        "verified at block 8,412,307",
    ),
    simulation = "The cancellation, run as a verified call from this account at block 8,412,307, goes through. " +
        "Once a block has it, nothing can fill the order, and its end is proven once that block is finalized.",
    unlock = UnlockStep(),
)

@Composable
private fun CowOrderPreview(
    state: CowOrderUiState,
    darkTheme: Boolean = false,
    lightClient: LightClientItem = verifiedStripSample,
) {
    SerotineTheme(darkTheme = darkTheme) {
        CowOrderScreen(
            state = state,
            lightClient = lightClient,
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

@Preview
@Composable
private fun CowOrderLivePreview() = CowOrderPreview(live)

@Preview
@Composable
private fun CowOrderLiveDarkPreview() = CowOrderPreview(live, darkTheme = true)

@Preview
@Composable
private fun CowOrderPastValidityPreview() = CowOrderPreview(
    live.copy(
        end = "Its validity passed at 19 Oct 2026, 14:27. Whether it filled is proven once a finalized block is " +
            "past it, a quarter of an hour or so, from this account's verified logs.",
        cow = "CoW says, not verified: the order expired in its order book.",
        canCancel = false,
    ),
)

@Preview
@Composable
private fun CowOrderCancellationOnItsWayPreview() = CowOrderPreview(
    live.copy(
        earlier = EarlierTransaction(
            "0x" + "5e".repeat(32),
            "Its cancellation is on its way and not in a block yet.",
            "Open the cancellation",
        ),
    ),
)

@Preview
@Composable
private fun CowOrderQuotingCancellationPreview() = CowOrderPreview(live.copy(quoting = true))

@Preview
@Composable
private fun CowOrderCancelRefusedPreview() = CowOrderPreview(
    live.copy(error = "Not enough ETH for the most the cancellation's fee can be."),
)

@Preview
@Composable
private fun CowOrderFilledPreview() = CowOrderPreview(
    live.copy(
        bought = "0.016102 ETH",
        state = OrderState.FILLED,
        end = "Filled in block 8,412,322: it bought 0.016102 ETH, as the settlement's trade in this account's " +
            "verified logs says.",
        held = null,
        cow = null,
        canCancel = false,
    ),
)

@Preview
@Composable
private fun CowOrderNotFilledPreview() = CowOrderPreview(
    live.copy(
        state = OrderState.NOT_FILLED,
        end = "Not filled: no trade for it in this account's verified logs up to block 8,412,401, a finalized " +
            "block past its validity.",
        held = null,
        cow = null,
        canCancel = false,
        uniswapAgain = SwapAgain(DAILY.lowercase(), "0x" + "1c".repeat(20), buy = null, amount = "40"),
    ),
)

@Preview
@Composable
private fun CowOrderCancelledPreview() = CowOrderPreview(
    live.copy(
        state = OrderState.CANCELLED,
        end = "Cancelled on chain in block 8,412,330: it never filled, as this account's verified logs prove.",
        held = null,
        cow = null,
        canCancel = false,
        uniswapAgain = SwapAgain(DAILY.lowercase(), "0x" + "1c".repeat(20), buy = null, amount = "40"),
    ),
)

@Preview
@Composable
private fun CowOrderNotProvablePreview() = CowOrderPreview(
    live.copy(
        state = OrderState.NOT_PROVABLE,
        end = "Its end cannot be proven on this device: this account's history is not followed from early enough, " +
            "or no longer. The balance shows what happened.",
        held = null,
        cow = null,
        canCancel = false,
    ),
)

@Preview
@Composable
private fun CowOrderCancelReviewPreview() = CowOrderPreview(
    review.copy(unlock = UnlockStep(WalletPassword("made-up preview password"))),
)

@Preview
@Composable
private fun CowOrderCancelNotSentPreview() = CowOrderPreview(
    review.copy(
        notSent = NotSentItem(
            "Signed, but not sent: no peer took it right now (no peers). Try again sends the same signed " +
                "cancellation, not a new one.",
            canRetry = true,
        ),
    ),
)

@Preview
@Composable
private fun CowOrderCancelSentPreview() = CowOrderPreview(CowOrderUiState.CancelSent("Sepolia", "0x" + "5e".repeat(32)))

@Preview
@Composable
private fun CowOrderMissingPreview() = CowOrderPreview(
    CowOrderUiState.Missing(
        "Sepolia",
        "This order is not recorded on this device: it was signed elsewhere, or its record was dropped a week " +
            "after its end was proven.",
    ),
)

@Preview
@Composable
private fun CowOrderLoadingPreview() = CowOrderPreview(CowOrderUiState.Loading("Sepolia"))

@Preview
@Composable
private fun CowOrderKeyNotHerePreview() = CowOrderPreview(
    live.copy(
        from = SendFrom("This account", DAILY),
        cancelReason = "This account is not in your wallet any more, so its key is not on this device. The order " +
            "ends by itself at its validity.",
    ),
)

/** Every preview above, which CowOrderScreenPreviewsTest renders: a new preview goes in here too. */
internal val cowOrderScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("live") { CowOrderLivePreview() },
    ScreenPreview("live-dark") { CowOrderLiveDarkPreview() },
    ScreenPreview("past-validity") { CowOrderPastValidityPreview() },
    ScreenPreview("cancellation-on-its-way") { CowOrderCancellationOnItsWayPreview() },
    ScreenPreview("quoting-cancellation") { CowOrderQuotingCancellationPreview() },
    ScreenPreview("cancel-refused") { CowOrderCancelRefusedPreview() },
    ScreenPreview("filled") { CowOrderFilledPreview() },
    ScreenPreview("not-filled") { CowOrderNotFilledPreview() },
    ScreenPreview("cancelled") { CowOrderCancelledPreview() },
    ScreenPreview("not-provable") { CowOrderNotProvablePreview() },
    ScreenPreview("cancel-review") { CowOrderCancelReviewPreview() },
    ScreenPreview("cancel-not-sent") { CowOrderCancelNotSentPreview() },
    ScreenPreview("cancel-sent") { CowOrderCancelSentPreview() },
    ScreenPreview("missing") { CowOrderMissingPreview() },
    ScreenPreview("loading") { CowOrderLoadingPreview() },
    ScreenPreview("key-not-here") { CowOrderKeyNotHerePreview() },
)
