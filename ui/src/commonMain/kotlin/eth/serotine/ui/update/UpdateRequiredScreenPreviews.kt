package eth.serotine.ui.update

import androidx.compose.runtime.Composable
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The update screen in Android Studio's preview pane: it draws one state, in the light and
// the dark theme. Built from display-ready values, as the screen tests are, never from the
// ViewModel. UpdateRequiredScreenPreviewsTest renders each one in updateRequiredScreenPreviews.

private val required = UpdateRequiredUiState.Required(
    text = "Peers report that Sepolia upgraded on Nov 6, 2026, 3:00 PM to rules this version of Serotine " +
        "does not support, and its light client is not verifying the chain. Update the app to keep using " +
        "your wallet.",
    details = "Reported by peers in 5 networks, not verified · fork id 0x6c1d9423",
    version = "This is Serotine v0.1.0.",
)

@Composable
private fun UpdateRequiredPreview(darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        UpdateRequiredScreen(state = required, onGetUpdate = {})
    }
}

@Preview
@Composable
private fun UpdateRequiredLightPreview() = UpdateRequiredPreview()

@Preview
@Composable
private fun UpdateRequiredDarkPreview() = UpdateRequiredPreview(darkTheme = true)

/** Every preview above, which UpdateRequiredScreenPreviewsTest renders: a new preview goes in here too. */
internal val updateRequiredScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("required") { UpdateRequiredLightPreview() },
    ScreenPreview("required-dark") { UpdateRequiredDarkPreview() },
)
