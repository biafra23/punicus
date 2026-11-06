package eth.serotine.ui.pairdapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import eth.serotine.domain.repository.DappPairing
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.components.QrScannerFrame
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Connect a dApp in Android Studio's preview pane, one preview per state it draws differently.
// PairDappScreenPreviewsTest renders each one in pairDappScreenPreviews, at the end.

private const val LINK = "wc:7f6e504bfad60b485450578e05678ed3e8e8c4751d3c6160be17160d63ec90f9" +
    "@2?relay-protocol=irn&symKey=…"

/** [scanner]: drawn as on a platform with a QR code scanner (Android), with Scan. */
@Composable
private fun PairDappPreview(state: PairDappUiState, darkTheme: Boolean = false, scanner: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        PairDappScreen(state, onEdit = {}, onConnect = {}, onBack = {}, onScan = if (scanner) ({}) else null)
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

@Preview
@Composable
private fun PairDappWithScanPreview() = PairDappPreview(PairDappUiState(), scanner = true)

@Preview
@Composable
private fun PairDappWithScanDarkPreview() = PairDappPreview(PairDappUiState(), darkTheme = true, scanner = true)

@Preview
@Composable
private fun PairDappCameraRefusedPreview() = PairDappPreview(
    PairDappUiState(message = PairDappViewModel.CAMERA_REFUSED, cameraUnavailable = true),
    scanner = true,
)

@Preview
@Composable
private fun PairDappScannedNotALinkPreview() = PairDappPreview(
    PairDappUiState(message = PairDappViewModel.NOT_A_LINK),
    scanner = true,
)

@Preview
@Composable
private fun QrScannerFramePreview() = SerotineTheme {
    // The camera's place, as a dark grey field: the platform's scanner fills it with the preview.
    QrScannerFrame(hint = "Point the camera at the dApp's WalletConnect QR code.", onCancel = {}) {
        Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2A)))
    }
}

/** Every preview above, which PairDappScreenPreviewsTest renders: a new preview goes in here too. */
internal val pairDappScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("empty") { PairDappEmptyPreview() },
    ScreenPreview("pasted") { PairDappPastedPreview() },
    ScreenPreview("pasted-dark") { PairDappPastedDarkPreview() },
    ScreenPreview("busy") { PairDappBusyPreview() },
    ScreenPreview("expired") { PairDappExpiredPreview() },
    ScreenPreview("unreadable") { PairDappUnreadablePreview() },
    ScreenPreview("with-scan") { PairDappWithScanPreview() },
    ScreenPreview("with-scan-dark") { PairDappWithScanDarkPreview() },
    ScreenPreview("camera-refused") { PairDappCameraRefusedPreview() },
    ScreenPreview("scanned-not-a-link") { PairDappScannedNotALinkPreview() },
    ScreenPreview("scanner-frame") { QrScannerFramePreview() },
)
