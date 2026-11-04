package eth.serotine.ui.activitydetail

import androidx.compose.runtime.Composable
import eth.serotine.ui.activity.ActivityState
import eth.serotine.ui.components.AMOUNT_MASK
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Activity detail in simple mode in Android Studio's preview pane, one preview per state it draws
// differently from Detailed view. Built from display-ready values, never from the ViewModel.
// SimpleActivityDetailScreenPreviewsTest renders each one in simpleActivityDetailScreenPreviews, at
// the end. The addresses are EIP-55's own examples and Sepolia's USDC, and the hashes made up.

private const val ALICE = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"
private const val BOB = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val USDC = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"

private val sent = ActivityDetailUiState.Shown(
    title = "Alice",
    headline = "Sent 40 USDC",
    about = BOB,
    status = "Finalized in block 9,412,118",
    simpleStatus = "Final",
    state = ActivityState.DONE,
    fields = listOf(
        DetailField("From", ALICE, DetailKind.ADDRESS, "Alice"),
        DetailField("To", BOB, DetailKind.ADDRESS, "Bob"),
        DetailField("Amount", "40 USDC"),
        DetailField("Token contract", USDC, DetailKind.ADDRESS, "USDC"),
        DetailField("Transaction hash", "0x" + "1".repeat(64), DetailKind.HASH),
        DetailField("Nonce", "7"),
        DetailField("Sent", "Oct 19, 2026, 2:02 PM"),
        DetailField("Block", "9,412,118", DetailKind.BLOCK),
    ),
    opensStatus = "0x" + "1".repeat(64),
)

@Composable
private fun SimpleActivityDetailPreview(state: ActivityDetailUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleActivityDetailScreen(state, onBack = {}, onCopy = {}, onStatus = {}, onAddToken = {})
    }
}

/** A transfer sent from here: how it ended in words, its block behind Details. */
@Preview
@Composable
private fun SimpleActivityDetailSentPreview() = SimpleActivityDetailPreview(sent)

@Preview
@Composable
private fun SimpleActivityDetailSentDarkPreview() = SimpleActivityDetailPreview(sent, darkTheme = true)

/** Amounts hidden: the eye beside Back closed, what it moved masked in its headline and its Amount. */
private val sentHidden = sent.copy(
    headline = "Sent $AMOUNT_MASK USDC",
    fields = sent.fields.map { if (it.label == "Amount") it.copy(value = "$AMOUNT_MASK USDC") else it },
    amountsHidden = true,
)

@Preview
@Composable
private fun SimpleActivityDetailHiddenPreview() = SimpleActivityDetailPreview(sentHidden)

@Preview
@Composable
private fun SimpleActivityDetailHiddenDarkPreview() = SimpleActivityDetailPreview(sentHidden, darkTheme = true)

/** Every preview above, which SimpleActivityDetailScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleActivityDetailScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SimpleActivityDetailSentPreview() },
    { SimpleActivityDetailSentDarkPreview() },
    { SimpleActivityDetailHiddenPreview() },
    { SimpleActivityDetailHiddenDarkPreview() },
)
