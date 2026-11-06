package eth.serotine.ui.components

import androidx.compose.runtime.Composable
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The capture warning in Android Studio's preview pane, one preview per event it warns about.
// ScreenCaptureWarningDialogPreviewsTest renders each one in screenCaptureWarningDialogPreviews,
// at the end.

@Composable
private fun CaptureWarningPreview(event: ScreenCaptureEvent, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        ScreenCaptureWarningDialog(event, onDismiss = {})
    }
}

@Preview
@Composable
private fun CaptureWarningScreenshotPreview() = CaptureWarningPreview(ScreenCaptureEvent.SCREENSHOT)

@Preview
@Composable
private fun CaptureWarningScreenshotDarkPreview() =
    CaptureWarningPreview(ScreenCaptureEvent.SCREENSHOT, darkTheme = true)

@Preview
@Composable
private fun CaptureWarningRecordingPreview() = CaptureWarningPreview(ScreenCaptureEvent.RECORDING)

/** Every preview above, which ScreenCaptureWarningDialogPreviewsTest renders: a new preview goes in here too. */
internal val screenCaptureWarningDialogPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("screenshot") { CaptureWarningScreenshotPreview() },
    ScreenPreview("screenshot-dark") { CaptureWarningScreenshotDarkPreview() },
    ScreenPreview("recording") { CaptureWarningRecordingPreview() },
)
