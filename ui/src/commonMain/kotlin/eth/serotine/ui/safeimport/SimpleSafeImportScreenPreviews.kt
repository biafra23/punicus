package eth.serotine.ui.safeimport

import androidx.compose.runtime.Composable
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Import a request in simple mode in Android Studio's preview pane, one preview per state it draws
// differently from Detailed view. Built from display-ready values, never from the ViewModel.
// SimpleSafeImportScreenPreviewsTest renders each one in simpleSafeImportScreenPreviews, at the end.
// The address is one of EIP-55's own examples.

private const val TREASURY = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"

private val empty = SafeImportUiState.Entering("Sepolia", label = "Treasury", address = TREASURY, text = "")

@Composable
private fun SimpleSafeImportPreview(state: SafeImportUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleSafeImportScreen(state, onTextChange = {}, onImport = {}, onBack = {})
    }
}

/** No light-client strip above the field. */
@Preview
@Composable
private fun SimpleSafeImportEmptyPreview() = SimpleSafeImportPreview(empty)

@Preview
@Composable
private fun SimpleSafeImportEmptyDarkPreview() = SimpleSafeImportPreview(empty, darkTheme = true)

/** A request refused: said with no block. */
@Preview
@Composable
private fun SimpleSafeImportRefusedPreview() = SimpleSafeImportPreview(
    empty.copy(
        text = "{\"v\":1,\"chainId\":11155111}",
        error = "It reverts, run as a verified call at block 8,412,307. Nothing was signed.",
        simpleError = "It reverts, checked on chain. Nothing was signed.",
    ),
)

/** Every preview above, which SimpleSafeImportScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleSafeImportScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SimpleSafeImportEmptyPreview() },
    { SimpleSafeImportEmptyDarkPreview() },
    { SimpleSafeImportRefusedPreview() },
)
