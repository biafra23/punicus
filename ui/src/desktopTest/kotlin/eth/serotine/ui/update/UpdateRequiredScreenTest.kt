package eth.serotine.ui.update

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [UpdateRequiredScreen] headless. */
class UpdateRequiredScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val state = UpdateRequiredUiState.Required(
        text = "Peers report that Sepolia upgraded to rules this version of Punicus does not support.",
        details = "Reported by peers in 3 networks, not verified · fork id unknown",
        version = "This is Punicus v0.1.0.",
    )

    @Test
    fun saysWhyWhereTheUpdateIsAndThatTheWalletStays() {
        rule.setContent { SerotineTheme { UpdateRequiredScreen(state, onGetUpdate = {}) } }

        rule.onNodeWithText("Update Punicus").assertIsDisplayed()
        rule.onNodeWithText(state.text).assertIsDisplayed()
        rule.onNodeWithText(state.keepsData).assertIsDisplayed()
        rule.onNodeWithText(RELEASES_URL).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(state.details).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("This is Punicus v0.1.0.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theButtonAsksForTheUpdate() {
        var asked = 0
        rule.setContent { SerotineTheme { UpdateRequiredScreen(state, onGetUpdate = { asked++ }) } }

        rule.onNodeWithTag(UPDATE_BUTTON_TAG).performScrollTo().performClick()

        assertEquals(1, asked)
    }

    @Test
    fun aLinkThatCannotBeOpenedDoesNotCrashTheApp() {
        openLink(RELEASES_URL) { throw IllegalStateException("no browser") }
    }
}
