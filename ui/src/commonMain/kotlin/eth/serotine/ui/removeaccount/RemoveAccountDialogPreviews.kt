package eth.serotine.ui.removeaccount

import androidx.compose.runtime.Composable
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The remove dialog in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the dialog tests are, never from the
// ViewModel. RemoveAccountDialogPreviewsTest renders each one in removeAccountDialogPreviews,
// at the end. The addresses are EIP-55's own examples.

private val daily =
    RemoveAccountUiState.Confirm("Daily", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "Sepolia", keyed = true)

@Composable
private fun RemoveAccountPreview(state: RemoveAccountUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        RemoveAccountDialog(state = state, onRemove = {}, onCancel = {})
    }
}

@Preview
@Composable
private fun RemoveAccountKeyedPreview() = RemoveAccountPreview(daily)

@Preview
@Composable
private fun RemoveAccountKeyedDarkPreview() = RemoveAccountPreview(daily, darkTheme = true)

@Preview
@Composable
private fun RemoveAccountWatchedPreview() = RemoveAccountPreview(
    RemoveAccountUiState.Confirm("Savings", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359", "Sepolia", keyed = false),
)

@Preview
@Composable
private fun RemoveAccountRemovingPreview() = RemoveAccountPreview(daily.copy(removing = true))

@Preview
@Composable
private fun RemoveAccountKeyKeptPreview() = RemoveAccountPreview(
    RemoveAccountUiState.Notice(
        "Daily is removed, but its key could not be deleted from this device, so the wallet password stays " +
            "the one you set. Importing its recovery phrase again brings the account back.",
    ),
)

@Preview
@Composable
private fun RemoveAccountLoadingPreview() = RemoveAccountPreview(RemoveAccountUiState.Loading)

/** Docs/networks.md's N7: one network's account of a key held here; the key stays for the others. */
@Preview
@Composable
private fun RemoveAccountKeyStaysPreview() = RemoveAccountPreview(
    daily.copy(label = "Main", network = "Gnosis Chain", keyStaysFor = "Ethereum"),
)

/** Every preview above, which RemoveAccountDialogPreviewsTest renders: a new preview goes in here too. */
internal val removeAccountDialogPreviews: List<@Composable () -> Unit> = listOf(
    { RemoveAccountKeyedPreview() },
    { RemoveAccountKeyedDarkPreview() },
    { RemoveAccountWatchedPreview() },
    { RemoveAccountRemovingPreview() },
    { RemoveAccountKeyKeptPreview() },
    { RemoveAccountLoadingPreview() },
    { RemoveAccountKeyStaysPreview() },
)
