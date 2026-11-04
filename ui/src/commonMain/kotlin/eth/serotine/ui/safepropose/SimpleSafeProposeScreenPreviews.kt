package eth.serotine.ui.safepropose

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.ActionAddress
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.RecipientItem
import eth.serotine.ui.components.SafeBalanceChange
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.TransferForm
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Send from a Safe in simple mode in Android Studio's preview pane, one preview per state it draws
// differently from Detailed view. Built from display-ready values, never from the ViewModel.
// SimpleSafeProposeScreenPreviewsTest renders each one in simpleSafeProposeScreenPreviews, at the end.
// The addresses are EIP-55's own examples; the names, amounts and blocks are made up.

private const val TREASURY = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val FRIEND = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"

private val from = SendFrom("Treasury", TREASURY)
private val ether =
    AssetChoice("ETH", "Ether", "eth", "2.5 ETH", verifiedAt = "block 8,412,307", age = "updated 2 min ago")

private val form = TransferForm(
    network = "Sepolia",
    from = from,
    assets = listOf(ether),
    asset = ether,
    to = FRIEND,
    accounts = listOf(AccountChoice("Daily", DAILY)),
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
    simulation = "The Safe's call, run as a verified call from the Safe at block 8,412,307, goes through.",
    simpleSimulation = "Checked on chain: the Safe's call should go through. Its owners' signatures, its nonce and " +
        "its guard are checked when its execution is quoted.",
    owners = listOf(AccountChoice("Daily", DAILY)),
    owner = DAILY,
    unlock = UnlockStep(),
)

@Composable
private fun SimpleSafeProposePreview(state: SafeProposeUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleSafeProposeScreen(state = state, actions = SafeProposeActions(), onBack = {})
    }
}

/** The form: each balance's age, no block, no strip. */
@Preview
@Composable
private fun SimpleSafeProposeComposePreview() = SimpleSafeProposePreview(SafeProposeUiState.Compose(form))

/** The review: the Safe's call checked on chain, the blocks behind Details. */
@Preview
@Composable
private fun SimpleSafeProposeReviewPreview() = SimpleSafeProposePreview(review)

@Preview
@Composable
private fun SimpleSafeProposeReviewDarkPreview() = SimpleSafeProposePreview(review, darkTheme = true)

/** A proposal refused: said with no block. */
@Preview
@Composable
private fun SimpleSafeProposeRefusedPreview() = SimpleSafeProposePreview(
    SafeProposeUiState.Compose(
        form.copy(
            error = "The Safe's call reverts, run as a verified call from the Safe at block 8,412,307: the recipient " +
                "or the token refuses it. Nothing was proposed.",
            simpleError = "The Safe's call reverts, checked on chain: the recipient or the token refuses it. Nothing " +
                "was proposed.",
        ),
    ),
)

/** Every preview above, which SimpleSafeProposeScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleSafeProposeScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SimpleSafeProposeComposePreview() },
    { SimpleSafeProposeRefusedPreview() },
    { SimpleSafeProposeReviewPreview() },
    { SimpleSafeProposeReviewDarkPreview() },
)
