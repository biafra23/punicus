package eth.serotine.ui.safepropose

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.ActionAddress
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.RecipientItem
import eth.serotine.ui.components.SafeBalanceChange
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.TransferForm
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Send for a Safe in Android Studio's preview pane, one preview per state it draws differently.
// Built from display-ready values, as the screen tests are, never from the ViewModel.
// SafeProposeScreenPreviewsTest renders each one in safeProposeScreenPreviews, at the end. The
// addresses are EIP-55's own examples; the names, amounts, blocks and the password are made up. A
// preview has no window to keep out of screen capture, so the review's password field shows the
// line saying so, which a device that blocks capture does not.

private const val TREASURY = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val SAVINGS = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val FRIEND = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"

private val from = SendFrom("Treasury", TREASURY)
private val ether = AssetChoice("ETH", "Ether", "eth", "2.5 ETH", verifiedAt = "block 8,412,307")

private val form = TransferForm(
    network = "Sepolia",
    from = from,
    assets = listOf(ether),
    asset = ether,
    to = FRIEND,
    accounts = listOf(AccountChoice("Daily", DAILY), AccountChoice("Savings", SAVINGS)),
    amount = "0.5",
    recipient = RecipientItem(FRIEND),
    amountHint = "≈ $1,250.00 · testnet prices",
    canMax = true,
    canReview = true,
)

private val review = SafeProposeUiState.Review(
    network = "Sepolia",
    from = from,
    action = SafeTxActionItem(
        title = "Send 0.5 ETH",
        addresses = listOf(ActionAddress("To", FRIEND)),
        icon = "eth",
        symbol = "ETH",
    ),
    amount = "0.5 ETH",
    asset = ether,
    recipient = RecipientItem(FRIEND),
    nonce = "Safe nonce 12",
    balance = SafeBalanceChange("ETH balance of the Safe", "2.5 ETH", "2 ETH", "verified at block 8,412,307"),
    simulation = "The Safe's call, run as a verified call from the Safe at block 8,412,307, goes through. The Safe's " +
        "own checks — its owners' signatures and its nonce — and its guard run when its execution is quoted.",
    owners = listOf(AccountChoice("Daily", DAILY)),
    owner = DAILY,
    unlock = UnlockStep(),
)

@Composable
private fun SafeProposePreview(
    state: SafeProposeUiState,
    darkTheme: Boolean = false,
    lightClient: LightClientItem = verifiedStripSample,
) {
    SerotineTheme(darkTheme = darkTheme) {
        SafeProposeScreen(state = state, lightClient = lightClient, actions = SafeProposeActions(), onBack = {})
    }
}

@Preview
@Composable
private fun SafeProposeComposePreview() = SafeProposePreview(SafeProposeUiState.Compose(form))

@Preview
@Composable
private fun SafeProposeComposeDarkPreview() = SafeProposePreview(SafeProposeUiState.Compose(form), darkTheme = true)

@Preview
@Composable
private fun SafeProposeRefusedPreview() = SafeProposePreview(
    SafeProposeUiState.Compose(
        form.copy(
            amount = "3",
            error = "The Safe holds less ETH than that, once the transactions queued on this device before it " +
                "have moved what they move.",
        ),
    ),
)

@Preview
@Composable
private fun SafeProposeProposingPreview() = SafeProposePreview(
    SafeProposeUiState.Compose(form.copy(quoting = true, canReview = false)),
)

@Preview
@Composable
private fun SafeProposeReviewPreview() = SafeProposePreview(review)

@Preview
@Composable
private fun SafeProposeReviewDarkPreview() = SafeProposePreview(review, darkTheme = true)

@Preview
@Composable
private fun SafeProposeReviewQueuedNotSimulatedPreview() = SafeProposePreview(
    review.copy(
        owners = listOf(AccountChoice("Daily", DAILY), AccountChoice("Savings", SAVINGS)),
        nonce = "Safe nonce 13",
        queued = "1 transaction queued on this device runs before it.",
        balance = review.balance.copy(after = "1.5 ETH", queued = "0.5 ETH moved by those queued before it"),
        simulation = "Not simulated: the light client on this device does not run a call from the Safe's address. " +
            "Its execution, the whole of it, is run as a verified call before anyone sends it.",
        unlock = UnlockStep(WalletPassword("made-up preview password"), biometrics = true),
    ),
)

@Preview
@Composable
private fun SafeProposeReviewNotSavedPreview() = SafeProposePreview(
    review.copy(
        error = "Signed, but this device's queue cannot be saved right now, so the proposal is not kept: try " +
            "again in a moment.",
    ),
)

@Preview
@Composable
private fun SafeProposeReviewAskingToDropPreview() = SafeProposePreview(
    review.copy(
        error = "Signed, but this device's queue cannot be saved right now, so the proposal is not kept here: " +
            "share it below before you leave this screen, or sign it again in a moment.",
        askingToDrop = true,
    ),
)

@Preview
@Composable
private fun SafeProposeReviewSigningPreview() = SafeProposePreview(review.copy(signing = true))

@Preview
@Composable
private fun SafeProposeReviewCheckingPreview() = SafeProposePreview(review.copy(signing = true, checking = true))

@Preview
@Composable
private fun SafeProposeLoadingPreview() = SafeProposePreview(SafeProposeUiState.Loading("Sepolia"))

@Preview
@Composable
private fun SafeProposeWatchOnlyPreview() = SafeProposePreview(
    SafeProposeUiState.CannotPropose(
        "Sepolia",
        "No owner of this Safe has its key on this device: it is watch-only here, and nothing can be proposed " +
            "from it.",
    ),
)

/** Every preview above, which SafeProposeScreenPreviewsTest renders: a new preview goes in here too. */
internal val safeProposeScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("compose") { SafeProposeComposePreview() },
    ScreenPreview("compose-dark") { SafeProposeComposeDarkPreview() },
    ScreenPreview("refused") { SafeProposeRefusedPreview() },
    ScreenPreview("proposing") { SafeProposeProposingPreview() },
    ScreenPreview("review") { SafeProposeReviewPreview() },
    ScreenPreview("review-dark") { SafeProposeReviewDarkPreview() },
    ScreenPreview("review-queued-not-simulated") { SafeProposeReviewQueuedNotSimulatedPreview() },
    ScreenPreview("review-not-saved") { SafeProposeReviewNotSavedPreview() },
    ScreenPreview("review-asking-to-drop") { SafeProposeReviewAskingToDropPreview() },
    ScreenPreview("review-signing") { SafeProposeReviewSigningPreview() },
    ScreenPreview("review-checking") { SafeProposeReviewCheckingPreview() },
    ScreenPreview("loading") { SafeProposeLoadingPreview() },
    ScreenPreview("watch-only") { SafeProposeWatchOnlyPreview() },
)
