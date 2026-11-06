package eth.serotine.ui.renameaccount

import androidx.compose.runtime.Composable
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The rename dialog in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the dialog tests are, never from the
// ViewModel. RenameAccountDialogPreviewsTest renders each one in renameAccountDialogPreviews,
// at the end.

private val editing = RenameAccountUiState.Editing("Daily")

@Composable
private fun RenameAccountPreview(state: RenameAccountUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        RenameAccountDialog(state = state, onNameChange = {}, onSave = {}, onCancel = {})
    }
}

@Preview
@Composable
private fun RenameAccountEditingPreview() = RenameAccountPreview(editing)

@Preview
@Composable
private fun RenameAccountEditingDarkPreview() = RenameAccountPreview(editing, darkTheme = true)

@Preview
@Composable
private fun RenameAccountBlankPreview() = RenameAccountPreview(RenameAccountUiState.Editing("", error = "Enter a name"))

@Preview
@Composable
private fun RenameAccountSavingPreview() = RenameAccountPreview(editing.copy(saving = true))

@Preview
@Composable
private fun RenameAccountListUnavailablePreview() = RenameAccountPreview(
    RenameAccountUiState.Unavailable("Your account list cannot be read right now. Serotine keeps trying."),
)

@Preview
@Composable
private fun RenameAccountLoadingPreview() = RenameAccountPreview(RenameAccountUiState.Loading)

/** Every preview above, which RenameAccountDialogPreviewsTest renders: a new preview goes in here too. */
internal val renameAccountDialogPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("editing") { RenameAccountEditingPreview() },
    ScreenPreview("editing-dark") { RenameAccountEditingDarkPreview() },
    ScreenPreview("blank") { RenameAccountBlankPreview() },
    ScreenPreview("saving") { RenameAccountSavingPreview() },
    ScreenPreview("list-unavailable") { RenameAccountListUnavailablePreview() },
    ScreenPreview("loading") { RenameAccountLoadingPreview() },
)
