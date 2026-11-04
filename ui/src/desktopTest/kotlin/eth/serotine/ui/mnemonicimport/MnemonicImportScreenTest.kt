package eth.serotine.ui.mnemonicimport

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.components.NO_NETWORK_TICKED
import eth.serotine.ui.components.NetworkChoice
import eth.serotine.ui.components.PasswordStep
import eth.serotine.ui.theme.SerotineTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [MnemonicImportScreen] headless for each state. */
class MnemonicImportScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val twelveWords = "one two three four five six seven eight nine ten eleven twelve"

    private val typed = mutableListOf<String>()
    private val passwords = mutableListOf<String>()
    private val repeats = mutableListOf<String>()
    private var imports = 0
    private var backs = 0
    private var dones = 0

    private fun show(state: MnemonicImportUiState) = rule.setContent {
        SerotineTheme {
            MnemonicImportScreen(
                state = state,
                onPhraseChange = { typed += it },
                onPasswordChange = { passwords += it },
                onRepeatChange = { repeats += it },
                onImport = { imports++ },
                onBack = { backs++ },
                onDone = { dones++ },
            )
        }
    }

    @Test
    fun anIncompletePhraseCannotBeSubmitted() {
        show(MnemonicImportUiState.Entering(Mnemonic("one two three")))

        rule.onNodeWithText("3 words").assertIsDisplayed()
        rule.onNodeWithTag(MNEMONIC_IMPORT_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun aFullPhraseCanBeSubmitted() {
        show(
            MnemonicImportUiState.Entering(
                Mnemonic(twelveWords),
                password = WalletPassword("correct horse"),
                step = PasswordStep.Existing(protection = "Kept in hardware."),
                networks = listOf(sepolia.copy(chosen = true)),
            ),
        )

        rule.onNodeWithText("12 words").assertIsDisplayed()
        rule.onNodeWithTag(MNEMONIC_IMPORT_BUTTON_TAG).assertIsEnabled().performClick()
        assertEquals(1, imports)
    }

    @Test
    fun withNoNetworkTickedTheButtonSaysWhyAndImportsNothing() {
        show(
            MnemonicImportUiState.Entering(
                Mnemonic(twelveWords),
                password = WalletPassword("correct horse"),
                step = PasswordStep.Existing(protection = "Kept in hardware."),
                networks = listOf(sepolia),
            ),
        )

        rule.onNodeWithText("Add to which networks?").assertExists()
        rule.onNodeWithTag(MNEMONIC_IMPORT_BUTTON_TAG).performClick()
        rule.onNodeWithText(NO_NETWORK_TICKED).assertExists()
        assertEquals(0, imports)
    }

    private val sepolia = NetworkChoice(11_155_111, "Sepolia", testnet = true, on = true, line = "Testnet: test ether only")

    @Test
    fun typingReportsTheFieldsText() {
        show(MnemonicImportUiState.Entering(Mnemonic.EMPTY))

        rule.onNodeWithText("No words yet").assertIsDisplayed()
        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).performTextInput("abandon")
        assertEquals(listOf("abandon"), typed)
    }

    @Test
    fun aRefusalIsShownInPlaceOfTheWordCount() {
        val error = "Word 3 is not in the BIP-39 English word list."
        show(MnemonicImportUiState.Entering(Mnemonic(twelveWords), error = error))

        rule.onNodeWithText(error).assertIsDisplayed()
        rule.onNodeWithText("12 words").assertDoesNotExist()
    }

    @Test
    fun theDesktopSaysItCannotBlockScreenCapture() {
        show(MnemonicImportUiState.Entering(Mnemonic.EMPTY))

        rule.onNodeWithText("cannot be blocked", substring = true).assertExists()
    }

    @Test
    fun aNewPasswordIsAskedForTwiceAndAnExistingOneOnce() {
        show(MnemonicImportUiState.Entering(Mnemonic(twelveWords), step = PasswordStep.New(protection = "")))
        rule.onNodeWithText("New wallet password").assertExists()
        rule.onNodeWithTag(MNEMONIC_REPEAT_TAG).assertExists()
        rule.onNodeWithTag(MNEMONIC_IMPORT_BUTTON_TAG).assertIsNotEnabled()

        rule.onNodeWithTag(MNEMONIC_PASSWORD_TAG).performTextInput("correct horse")
        rule.onNodeWithTag(MNEMONIC_REPEAT_TAG).performTextInput("correct horse")
        // The screen is stateless here, so a field may also report its unchanged value: what
        // matters is that each field reported what was typed into it.
        assertEquals("correct horse", passwords.first())
        assertEquals("correct horse", repeats.first())
    }

    @Test
    fun noPasswordFieldUntilTheKeyStoreHasAnswered() {
        show(MnemonicImportUiState.Entering(Mnemonic(twelveWords)))

        rule.onNodeWithTag(MNEMONIC_PASSWORD_TAG).assertDoesNotExist()
        rule.onNodeWithTag(MNEMONIC_IMPORT_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun aKeyStoreThatDoesNotWorkHereIsSaidAndNothingCanBeAdded() {
        show(
            MnemonicImportUiState.Entering(
                Mnemonic(twelveWords),
                password = WalletPassword("correct horse"),
                step = PasswordStep.Unavailable,
            ),
        )

        rule.onNodeWithText("The key store does not work on this device", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(MNEMONIC_PASSWORD_TAG).assertDoesNotExist()
        rule.onNodeWithTag(MNEMONIC_IMPORT_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun aWrongPasswordIsShownAtThePasswordNotAtTheWords() {
        show(
            MnemonicImportUiState.Entering(
                Mnemonic(twelveWords),
                password = WalletPassword("correct horse"),
                step = PasswordStep.Existing(protection = ""),
                passwordError = "That is not the wallet password.",
            ),
        )

        rule.onNodeWithText("That is not the wallet password.").assertIsDisplayed()
        rule.onNodeWithText("12 words").assertIsDisplayed() // the words' own line is unchanged
    }

    @Test
    fun theProtectionIsSaid() {
        show(MnemonicImportUiState.Entering(Mnemonic.EMPTY, step = PasswordStep.Existing("Kept in hardware.")))

        rule.onNodeWithText("Kept in hardware.", substring = true).assertExists()
    }

    @Test
    fun theBackArrowLeaves() {
        show(MnemonicImportUiState.Entering(Mnemonic.EMPTY))

        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun aNoteAfterTheImportStaysUntilTheUserIsDone() {
        show(MnemonicImportUiState.Imported("Account 1", "Less memory, more passes."))

        rule.onNodeWithText("Account 1 added").assertExists()
        rule.onNodeWithText("Less memory, more passes.").assertExists()
        rule.onNodeWithText("Done").performClick()
        assertEquals(1, dones)
    }
}
