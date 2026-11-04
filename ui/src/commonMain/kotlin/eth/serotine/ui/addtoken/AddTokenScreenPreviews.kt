package eth.serotine.ui.addtoken

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NetworkChip
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Add token screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. AddTokenScreenPreviewsTest renders each one in addTokenScreenPreviews, at the
// end. The addresses are EIP-55's own examples, and the tokens are made up.

private const val CONTRACT = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"

private val sepolia = NetworkChip(NetworkLabel(11_155_111, "Sepolia", testnet = true), selected = true)

private val claimed = AddTokenUiState(
    networks = listOf(sepolia),
    address = CONTRACT,
    card = TokenCard.Claim(
        CONTRACT,
        "EURS",
        "STASIS EURS Token",
        2,
        "Read from the contract at block 8,412,307",
        warning = null,
    ),
)

@Composable
private fun AddTokenPreview(
    state: AddTokenUiState,
    darkTheme: Boolean = false,
    lightClient: LightClientItem = verifiedStripSample,
) {
    SerotineTheme(darkTheme = darkTheme) {
        AddTokenScreen(state = state, lightClient = lightClient, onAddressChange = {}, onAdd = {}, onBack = {})
    }
}

@Preview
@Composable
private fun AddTokenEmptyPreview() = AddTokenPreview(AddTokenUiState())

@Preview
@Composable
private fun AddTokenReadingPreview() = AddTokenPreview(AddTokenUiState(address = CONTRACT, card = TokenCard.Checking))

@Preview
@Composable
private fun AddTokenClaimPreview() = AddTokenPreview(claimed)

@Preview
@Composable
private fun AddTokenClaimDarkPreview() = AddTokenPreview(claimed, darkTheme = true)

@Preview
@Composable
private fun AddTokenImpostorPreview() = AddTokenPreview(
    AddTokenUiState(
        address = CONTRACT,
        card = TokenCard.Claim(
            CONTRACT,
            "USDC",
            "USD Coin",
            6,
            "Read from the contract at block 8,412,307",
            warning = "Not the USDC the wallet lists: another contract calls itself that",
        ),
    ),
)

@Preview
@Composable
private fun AddTokenKnownPreview() =
    AddTokenPreview(AddTokenUiState(address = CONTRACT, card = TokenCard.Known("EURC")))

@Preview
@Composable
private fun AddTokenNotATokenPreview() = AddTokenPreview(
    AddTokenUiState(address = CONTRACT, card = TokenCard.Unavailable("This address does not answer as a token.")),
)

@Preview
@Composable
private fun AddTokenInvalidAddressPreview() = AddTokenPreview(
    AddTokenUiState(
        address = "0xD1220a0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
        addressError = "The capitals do not match this address's checksum: check it for a typo.",
    ),
)

@Preview
@Composable
private fun AddTokenAddingPreview() = AddTokenPreview(claimed.copy(adding = true))

@Preview
@Composable
private fun AddTokenNotSavedPreview() = AddTokenPreview(
    claimed.copy(error = "The token could not be saved. Nothing was added."),
)

/**
 * Docs/networks.md's N17: the network first, Gnosis Chain chosen, Sepolia off. Ethereum and Gnosis
 * Chain come with parts 4 and 5.
 */
@Preview
@Composable
private fun AddTokenNetworksPreview() = AddTokenPreview(
    claimed.copy(
        networks = listOf(
            NetworkChip(NetworkLabel(1, "Ethereum", testnet = false), selected = false),
            NetworkChip(NetworkLabel(100, "Gnosis Chain", testnet = false), selected = true),
            sepolia.copy(selected = false, reason = "Sepolia is off in this wallet.", off = true),
        ),
    ),
)

/** Every preview above, which AddTokenScreenPreviewsTest renders: a new preview goes in here too. */
internal val addTokenScreenPreviews: List<@Composable () -> Unit> = listOf(
    { AddTokenEmptyPreview() },
    { AddTokenReadingPreview() },
    { AddTokenClaimPreview() },
    { AddTokenClaimDarkPreview() },
    { AddTokenImpostorPreview() },
    { AddTokenKnownPreview() },
    { AddTokenNotATokenPreview() },
    { AddTokenInvalidAddressPreview() },
    { AddTokenAddingPreview() },
    { AddTokenNotSavedPreview() },
    { AddTokenNetworksPreview() },
)
