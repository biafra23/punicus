package eth.serotine.ui.addnetwork

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.NetworkChoice
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Add on another network dialog in Android Studio's preview pane, one preview per state it
// draws differently. Built from display-ready values, as the dialog tests are, never from the
// ViewModel. AddOnNetworksDialogPreviewsTest renders each one in addOnNetworksDialogPreviews, at
// the end. Ethereum and Gnosis Chain come with parts 4 and 5 of docs/networks.md.

/** Docs/networks.md's N6: "Main" on Ethereum, to add on Gnosis Chain, with Sepolia off. */
private val choose = AddOnNetworksUiState.Choose(
    "Main",
    listOf(
        NetworkChoice(100, "Gnosis Chain", testnet = false, on = true, chosen = true),
        NetworkChoice(11_155_111, "Sepolia", testnet = true, on = false),
    ),
)

@Composable
private fun AddOnNetworksPreview(state: AddOnNetworksUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        AddOnNetworksDialog(state, onToggle = {}, onTurnOn = {}, onAdd = {}, onCancel = {})
    }
}

@Preview
@Composable
private fun AddOnNetworksChoosePreview() = AddOnNetworksPreview(choose)

@Preview
@Composable
private fun AddOnNetworksChooseDarkPreview() = AddOnNetworksPreview(choose, darkTheme = true)

@Preview
@Composable
private fun AddOnNetworksNotSavedPreview() = AddOnNetworksPreview(
    choose.copy(error = "The account could not be added: your account list cannot be saved right now."),
)

@Preview
@Composable
private fun AddOnNetworksOnEveryNetworkPreview() =
    AddOnNetworksPreview(AddOnNetworksUiState.Choose("Main", emptyList()))

@Preview
@Composable
private fun AddOnNetworksSafePreview() = AddOnNetworksPreview(
    AddOnNetworksUiState.Unavailable("A Safe or another contract exists only on the network it was deployed on."),
)

/** Every preview above, which AddOnNetworksDialogPreviewsTest renders: a new preview goes in here too. */
internal val addOnNetworksDialogPreviews: List<@Composable () -> Unit> = listOf(
    { AddOnNetworksChoosePreview() },
    { AddOnNetworksChooseDarkPreview() },
    { AddOnNetworksNotSavedPreview() },
    { AddOnNetworksOnEveryNetworkPreview() },
    { AddOnNetworksSafePreview() },
)
