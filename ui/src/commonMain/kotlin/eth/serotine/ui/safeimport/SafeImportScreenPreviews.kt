package eth.serotine.ui.safeimport

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Import a request in Android Studio's preview pane, one preview per state it draws differently.
// Built from display-ready values, as the screen tests are, never from the ViewModel.
// SafeImportScreenPreviewsTest renders each one in safeImportScreenPreviews, at the end. The
// address is one of EIP-55's own examples, and the request text is made up.

private const val TREASURY = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"

private const val TEXT = "{\"v\":1,\"chainId\":11155111,\"safe\":\"0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb\"," +
    "\"tx\":{\"to\":\"0xd1220a0cf47c7b9be7a2e6ba89f429762e7b9adb\",\"value\":\"500000000000000000\"}}"

private val empty = SafeImportUiState.Entering("Sepolia", label = "Treasury", address = TREASURY, text = "")

@Composable
private fun SafeImportPreview(
    state: SafeImportUiState,
    darkTheme: Boolean = false,
    lightClient: LightClientItem = verifiedStripSample,
) {
    SerotineTheme(darkTheme = darkTheme) {
        SafeImportScreen(state, lightClient, onTextChange = {}, onImport = {}, onBack = {})
    }
}

@Preview
@Composable
private fun SafeImportEmptyPreview() = SafeImportPreview(empty)

@Preview
@Composable
private fun SafeImportEmptyDarkPreview() = SafeImportPreview(empty, darkTheme = true)

@Preview
@Composable
private fun SafeImportPastedPreview() = SafeImportPreview(empty.copy(text = TEXT))

@Preview
@Composable
private fun SafeImportCheckingPreview() = SafeImportPreview(empty.copy(text = TEXT, importing = true))

@Preview
@Composable
private fun SafeImportRefusedPreview() = SafeImportPreview(
    empty.copy(
        text = TEXT,
        error = "Its nonce is used: the Safe is at nonce 12 already, so it can never run.",
    ),
)

@Preview
@Composable
private fun SafeImportImportedPreview() =
    SafeImportPreview(SafeImportUiState.Imported("Sepolia", "0x" + "3c".repeat(32)))

/**
 * Docs/networks.md's N15: a request for the same address on Ethereum, brought to the Safe on Gnosis
 * Chain, refused with both networks named. The networks come with parts 4 and 5.
 */
@Preview
@Composable
private fun SafeImportWrongNetworkPreview() = SafeImportPreview(
    SafeImportUiState.Entering(
        "Gnosis Chain",
        label = "Club Safe",
        address = TREASURY,
        text = TEXT.replace("11155111", "1"),
        error = "This request is for the Safe at this address on Ethereum. This account is the Safe on Gnosis " +
            "Chain: a different Safe, with its own owners and nonce. Watch the Safe on Ethereum to sign it there.",
    ),
)

/** Every preview above, which SafeImportScreenPreviewsTest renders: a new preview goes in here too. */
internal val safeImportScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SafeImportEmptyPreview() },
    { SafeImportEmptyDarkPreview() },
    { SafeImportPastedPreview() },
    { SafeImportCheckingPreview() },
    { SafeImportRefusedPreview() },
    { SafeImportImportedPreview() },
    { SafeImportWrongNetworkPreview() },
)
