package eth.serotine.ui.mnemonicimport

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.ui.components.NOT_ENOUGH_MEMORY_TO_PROTECT
import eth.serotine.ui.components.NetworkChoice
import eth.serotine.ui.components.PasswordStep
import eth.serotine.ui.components.STEPPED_DOWN_TEXT
import eth.serotine.ui.components.protectionText
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The seed-phrase screen in Android Studio's preview pane, one preview per state it draws
// differently; MnemonicImportScreenPreviewsTest renders each one in
// mnemonicImportScreenPreviews, at the end. The words and passwords are made up: the words
// are not a seed phrase, and the passwords say what they are. A preview has no window to
// keep out of screen capture, so the previews of the words step show the line saying so,
// which a device that blocks capture does not.

private const val WORDS = "one two three four five six seven eight nine ten eleven twelve"

// What the screen says under the password on a device with secure hardware, from the one source.
private val HARDWARE = KeyProtection.HARDWARE.protectionText

/**
 * Docs/networks.md's N8 and N9: a release build's defaults, Sepolia off; Ethereum and Gnosis Chain
 * come with parts 4 and 5.
 */
private val networks = listOf(
    NetworkChoice(1, "Ethereum", testnet = false, on = true, line = "Real funds"),
    NetworkChoice(100, "Gnosis Chain", testnet = false, on = true, line = "Real funds, fees in xDAI"),
    NetworkChoice(11_155_111, "Sepolia", testnet = true, on = false, line = "Testnet: test ether only"),
)

/** [networks] with Ethereum ticked. */
private val ethereumTicked = networks.mapIndexed { i, it -> if (i == 0) it.copy(chosen = true) else it }

private val typed = MnemonicImportUiState.Entering(
    Mnemonic(WORDS),
    password = WalletPassword("made-up preview password"),
    repeat = WalletPassword("made-up preview password"),
    step = PasswordStep.New(HARDWARE),
    networks = ethereumTicked,
)

@Composable
private fun MnemonicImportPreview(state: MnemonicImportUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        MnemonicImportScreen(
            state = state,
            onPhraseChange = {},
            onPasswordChange = {},
            onRepeatChange = {},
            onImport = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun MnemonicImportCheckingPreview() = MnemonicImportPreview(MnemonicImportUiState.Entering(Mnemonic.EMPTY))

@Preview
@Composable
private fun MnemonicImportNewPasswordPreview() = MnemonicImportPreview(typed)

@Preview
@Composable
private fun MnemonicImportNewPasswordDarkPreview() = MnemonicImportPreview(typed, darkTheme = true)

@Preview
@Composable
private fun MnemonicImportPasswordHintPreview() = MnemonicImportPreview(
    typed.copy(password = WalletPassword("short"), repeat = WalletPassword.EMPTY),
)

@Preview
@Composable
private fun MnemonicImportWrongPasswordPreview() = MnemonicImportPreview(
    typed.copy(
        repeat = WalletPassword.EMPTY,
        step = PasswordStep.Existing(HARDWARE),
        passwordError = "That is not the wallet password.",
    ),
)

@Preview
@Composable
private fun MnemonicImportInvalidWordsPreview() = MnemonicImportPreview(
    typed.copy(error = "These words are not a valid seed phrase. Check for a typo or two words in the wrong order."),
)

@Preview
@Composable
private fun MnemonicImportNotEnoughMemoryPreview() = MnemonicImportPreview(
    typed.copy(error = NOT_ENOUGH_MEMORY_TO_PROTECT),
)

@Preview
@Composable
private fun MnemonicImportImportingPreview() = MnemonicImportPreview(typed.copy(importing = true))

@Preview
@Composable
private fun MnemonicImportKeyStoreUnavailablePreview() = MnemonicImportPreview(
    MnemonicImportUiState.Entering(Mnemonic(WORDS), step = PasswordStep.Unavailable),
)

@Preview
@Composable
private fun MnemonicImportImportedPreview() = MnemonicImportPreview(MnemonicImportUiState.Imported("Account 1"))

@Preview
@Composable
private fun MnemonicImportImportedSteppedDownPreview() =
    MnemonicImportPreview(MnemonicImportUiState.Imported("Account 1", STEPPED_DOWN_TEXT))

/** N9 before a network is ticked: the button greyed out, a tap saying why. */
@Preview
@Composable
private fun MnemonicImportNoNetworkPreview() = MnemonicImportPreview(typed.copy(networks = networks))

/** Every preview above, which MnemonicImportScreenPreviewsTest renders: a new preview goes in here too. */
internal val mnemonicImportScreenPreviews: List<@Composable () -> Unit> = listOf(
    { MnemonicImportCheckingPreview() },
    { MnemonicImportNewPasswordPreview() },
    { MnemonicImportNewPasswordDarkPreview() },
    { MnemonicImportPasswordHintPreview() },
    { MnemonicImportWrongPasswordPreview() },
    { MnemonicImportInvalidWordsPreview() },
    { MnemonicImportNotEnoughMemoryPreview() },
    { MnemonicImportImportingPreview() },
    { MnemonicImportKeyStoreUnavailablePreview() },
    { MnemonicImportImportedPreview() },
    { MnemonicImportImportedSteppedDownPreview() },
    { MnemonicImportNoNetworkPreview() },
)
