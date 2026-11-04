package eth.serotine.ui.deriveaccount

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.components.STEPPED_DOWN_TEXT
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// New account from the same phrase in Android Studio's preview pane, one preview per state it
// draws differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. DeriveAccountScreenPreviewsTest renders each one in deriveAccountScreenPreviews, at
// the end. The addresses are EIP-55's own examples; the password is made up.

private val ask = DeriveAccountUiState.Ask("Daily", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "Ethereum")

private val added =
    DeriveAccountUiState.Added("Account 2", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359", "Ethereum", number = 2)

@Composable
private fun DeriveAccountPreview(state: DeriveAccountUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        DeriveAccountScreen(state, onPasswordChange = {}, onDerive = {}, onBack = {})
    }
}

@Preview
@Composable
private fun DeriveAccountAskPreview() = DeriveAccountPreview(ask)

@Preview
@Composable
private fun DeriveAccountAskDarkPreview() = DeriveAccountPreview(ask, darkTheme = true)

@Preview
@Composable
private fun DeriveAccountTypedPreview() = DeriveAccountPreview(ask.copy(password = WalletPassword("made-up words")))

@Preview
@Composable
private fun DeriveAccountDerivingPreview() = DeriveAccountPreview(ask.copy(deriving = true))

@Preview
@Composable
private fun DeriveAccountWrongPasswordPreview() =
    DeriveAccountPreview(ask.copy(error = "That is not the wallet password. Nothing was added."))

@Preview
@Composable
private fun DeriveAccountAddedPreview() = DeriveAccountPreview(added)

@Preview
@Composable
private fun DeriveAccountAddedSteppedDownPreview() = DeriveAccountPreview(added.copy(note = STEPPED_DOWN_TEXT))

@Preview
@Composable
private fun DeriveAccountAddedDarkPreview() = DeriveAccountPreview(added, darkTheme = true)

@Preview
@Composable
private fun DeriveAccountUnavailablePreview() = DeriveAccountPreview(
    DeriveAccountUiState.Unavailable(
        "The key of Savings is not on this device, so no account of its recovery phrase can be added from it. " +
            "Import the phrase to hold its key here first.",
    ),
)

@Preview
@Composable
private fun DeriveAccountLoadingPreview() = DeriveAccountPreview(DeriveAccountUiState.Loading)

/** Every preview above, which DeriveAccountScreenPreviewsTest renders: a new preview goes in here too. */
internal val deriveAccountScreenPreviews: List<@Composable () -> Unit> = listOf(
    { DeriveAccountAskPreview() },
    { DeriveAccountAskDarkPreview() },
    { DeriveAccountTypedPreview() },
    { DeriveAccountDerivingPreview() },
    { DeriveAccountWrongPasswordPreview() },
    { DeriveAccountAddedPreview() },
    { DeriveAccountAddedSteppedDownPreview() },
    { DeriveAccountAddedDarkPreview() },
    { DeriveAccountUnavailablePreview() },
    { DeriveAccountLoadingPreview() },
)
