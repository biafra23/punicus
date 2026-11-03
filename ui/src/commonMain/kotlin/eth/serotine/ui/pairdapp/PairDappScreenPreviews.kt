package eth.serotine.ui.pairdapp

import androidx.compose.runtime.Composable
import eth.serotine.domain.repository.DappPairing
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Connect a dApp in Android Studio's preview pane, one preview per state it draws differently.
// PairDappScreenPreviewsTest renders each one in pairDappScreenPreviews, at the end.

private const val LINK = "wc:7f6e504bfad60b485450578e05678ed3e8e8c4751d3c6160be17160d63ec90f9@2?relay-protocol=irn&symKey=…"

@Composable
private fun PairDappPreview(state: PairDappUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        PairDappScreen(state, onEdit = {}, onConnect = {}, onBack = {})
    }
}

@Preview
@Composable
private fun PairDappEmptyPreview() = PairDappPreview(PairDappUiState())

@Preview
@Composable
private fun PairDappPastedPreview() = PairDappPreview(PairDappUiState(text = LINK))

@Preview
@Composable
private fun PairDappPastedDarkPreview() = PairDappPreview(PairDappUiState(text = LINK), darkTheme = true)

@Preview
@Composable
private fun PairDappBusyPreview() = PairDappPreview(PairDappUiState(text = LINK, busy = true))

@Preview
@Composable
private fun PairDappExpiredPreview() = PairDappPreview(
    PairDappUiState(text = LINK, message = PairDappViewModel.message(DappPairing.Expired)),
)

@Preview
@Composable
private fun PairDappUnreadablePreview() = PairDappPreview(
    PairDappUiState(text = "https://example.org", message = PairDappViewModel.message(DappPairing.Unreadable)),
)

/** Every preview above, which PairDappScreenPreviewsTest renders: a new preview goes in here too. */
internal val pairDappScreenPreviews: List<@Composable () -> Unit> = listOf(
    { PairDappEmptyPreview() },
    { PairDappPastedPreview() },
    { PairDappPastedDarkPreview() },
    { PairDappBusyPreview() },
    { PairDappExpiredPreview() },
    { PairDappUnreadablePreview() },
)
