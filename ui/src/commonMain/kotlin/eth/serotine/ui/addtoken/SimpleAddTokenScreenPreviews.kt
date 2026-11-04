package eth.serotine.ui.addtoken

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.NetworkChip
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Add token in simple mode in Android Studio's preview pane, one preview per state it draws
// differently from Detailed view. Built from display-ready values, never from the ViewModel.
// SimpleAddTokenScreenPreviewsTest renders each one in simpleAddTokenScreenPreviews, at the end. The
// address is one of EIP-55's own examples, and the token is made up.

private const val CONTRACT = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"

private val claimed = AddTokenUiState(
    networks = listOf(NetworkChip(NetworkLabel(11_155_111, "Sepolia", testnet = true), selected = true)),
    address = CONTRACT,
    card = TokenCard.Claim(
        CONTRACT,
        "EURS",
        "STASIS EURS Token",
        2,
        "Read from the contract at block 8,412,307",
        warning = null,
        simpleCheckedAt = "Read from the contract, checked on chain",
    ),
)

@Composable
private fun SimpleAddTokenPreview(state: AddTokenUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleAddTokenScreen(state = state, onAddressChange = {}, onAdd = {}, onBack = {})
    }
}

/** The contract's claim, read on chain, with no block and no strip. */
@Preview
@Composable
private fun SimpleAddTokenClaimPreview() = SimpleAddTokenPreview(claimed)

@Preview
@Composable
private fun SimpleAddTokenClaimDarkPreview() = SimpleAddTokenPreview(claimed, darkTheme = true)

/** Every preview above, which SimpleAddTokenScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleAddTokenScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SimpleAddTokenClaimPreview() },
    { SimpleAddTokenClaimDarkPreview() },
)
