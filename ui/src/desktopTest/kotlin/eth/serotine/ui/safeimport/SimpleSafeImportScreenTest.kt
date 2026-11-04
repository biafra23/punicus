package eth.serotine.ui.safeimport

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SimpleSafeImportScreen] headless. */
class SimpleSafeImportScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aRefusalIsSaidWithNoBlock() {
        val state = SafeImportUiState.Entering(
            network = "Sepolia",
            label = "Treasury",
            address = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
            text = "{}",
            error = "It reverts, run as a verified call at block 9,412,118. Nothing was signed.",
            simpleError = "It reverts, checked on chain. Nothing was signed.",
        )
        rule.setContent {
            SerotineTheme { SimpleSafeImportScreen(state, onTextChange = {}, onImport = {}, onBack = {}) }
        }

        rule.onNodeWithText("It reverts, checked on chain. Nothing was signed.").assertIsDisplayed()
        rule.onNodeWithText("at block", substring = true).assertDoesNotExist()
    }
}
