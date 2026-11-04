package eth.serotine.ui.createaccount

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [CreateAccountScreen] headless for its steps. */
class CreateAccountScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val phrase = Mnemonic("test test test test test test test test test test test junk")
    private val events = mutableListOf<String>()

    private fun show(state: CreateAccountUiState) = rule.setContent {
        SerotineTheme {
            CreateAccountScreen(
                state,
                onWrittenDown = { events += "written" },
                onShowAgain = { events += "again" },
                onCheckedWordChange = { index, text -> events += "word $index $text" },
                onCheck = { events += "check" },
                onPasswordChange = {},
                onRepeatChange = {},
                onCreate = { events += "create" },
                onStartOver = { events += "start over" },
                onBack = {},
            )
        }
    }

    @Test
    fun thePhraseIsShownNumberedOnce() {
        show(CreateAccountUiState.Showing(phrase))

        rule.onNodeWithText("1. test").assertIsDisplayed()
        rule.onNodeWithText("12. junk").assertIsDisplayed()
        rule.onNodeWithText("I have written them down").performClick()
        assertEquals(listOf("written"), events)
    }

    @Test
    fun threeWordsAreAskedBackByPosition() {
        show(CreateAccountUiState.Checking(phrase, listOf(2, 7, 12), List(3) { Mnemonic.EMPTY }))

        rule.onNodeWithText("Word 7").assertIsDisplayed()
        rule.onNodeWithText("Check").assertIsNotEnabled()
        rule.onNodeWithTag("$CREATE_CHECK_TAG-2").performTextInput("junk")
        rule.onNodeWithText("Show the phrase").performClick()
        // The state is fixed here, so the field reports the typed word, then its reset.
        assertEquals("word 2 junk", events.first())
        assertEquals("again", events.last())
    }

    @Test
    fun aWrongCheckIsSaid() {
        val typed = listOf("a", "b", "c").map(::Mnemonic)
        show(CreateAccountUiState.Checking(phrase, listOf(2, 7, 12), typed, wrong = true))

        rule.onNodeWithText("At least one word does not match. Check what you wrote down, or look at the phrase again.")
            .assertIsDisplayed()
        rule.onNodeWithText("Check").assertIsEnabled()
    }

    @Test
    fun aFirstKeySetsTheWalletPasswordTwiceAndTheDesktopSaysCaptureIsNotBlocked() {
        show(CreateAccountUiState.Securing(phrase, PasswordStep.New("Protected by the password alone.")))

        rule.onNodeWithText("New wallet password").assertIsDisplayed()
        rule.onNodeWithText("Repeat the password").assertIsDisplayed()
        // The button, not the title of the same words.
        rule.onNode(hasText("Create account") and hasClickAction()).assertIsNotEnabled()
        rule.onNodeWithText(
            "Screenshots and screen recordings of this window cannot be blocked here.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun withNoNetworkTickedCreateSaysWhyAndCreatesNothing() {
        val sepolia = NetworkChoice(11_155_111, "Sepolia", testnet = true, on = true)
        val securing = CreateAccountUiState.Securing(
            phrase,
            PasswordStep.Existing("Kept in hardware."),
            password = WalletPassword("correct horse"),
            networks = listOf(sepolia),
        )
        show(securing)

        rule.onNodeWithText("Add to which networks?").assertExists()
        rule.onNode(hasText("Create account") and hasClickAction()).performClick()
        rule.onNodeWithText(NO_NETWORK_TICKED).assertExists()
        assertEquals(emptyList(), events)
    }

    @Test
    fun aKeyStoredButNotListedPointsToTheImportNotToANewPhrase() {
        show(CreateAccountUiState.NotListed)

        rule.onNodeWithText("import the phrase you wrote down", substring = true).assertIsDisplayed()
        rule.onNodeWithText("New phrase").assertDoesNotExist()
    }

    @Test
    fun aClearedPhraseOffersANewOne() {
        show(CreateAccountUiState.Restart("The phrase was cleared."))

        rule.onNodeWithText("New phrase").performClick()
        assertEquals(listOf("start over"), events)
    }
}
