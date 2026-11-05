package eth.serotine.ui.platform

import androidx.compose.runtime.Composable

/** What a QR code scan came to. */
sealed interface QrScan {
    /** A QR code was read: its text, as the code holds it. Untrusted: the caller checks it. */
    data class Read(val text: String) : QrScan

    /** The camera may not be used: the user refused the permission, now or for good. */
    data object CameraRefused : QrScan

    /** No camera could be started: the device has none at the back, or another app holds it. */
    data object CameraUnavailable : QrScan

    /** The scan was closed without a code. */
    data object Cancelled : QrScan
}

/**
 * Starts a QR code scan with the device's camera, which reports to [onResult] once: a code read,
 * the camera refused or unavailable, or the scan cancelled. Null where the platform has no scanner
 * yet (the desktop, iOS), in which case the screen offers no Scan button. The camera's frames are
 * decoded on the device and never leave it; the scanner asks for the camera permission itself.
 */
@Composable
expect fun rememberQrScanner(onResult: (QrScan) -> Unit): (() -> Unit)?
