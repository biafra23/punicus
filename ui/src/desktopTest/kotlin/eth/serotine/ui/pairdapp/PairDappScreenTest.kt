package eth.serotine.ui.pairdapp

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [PairDappScreen] headless. */
class PairDappScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val edits = mutableListOf<String>()
    private var connects = 0

    private fun show(state: PairDappUiState) = rule.setContent {
        SerotineTheme { PairDappScreen(state, onEdit = { edits += it }, onConnect = { connects++ }, onBack = {}) }
    }

    @Test
    fun connectWithoutALinkIsGreyedOutAndStillTappable() {
        show(PairDappUiState())

        rule.onNodeWithText("Connect")
            .assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        assertEquals(1, connects)
    }

    @Test
    fun whatIsTypedReachesTheViewModelAndWhatPairingSaidIsShown() {
        show(PairDappUiState(text = "wc:x", message = "That link has expired. Ask the dApp for a new one."))

        rule.onNodeWithText("That link has expired. Ask the dApp for a new one.").assertExists()
        rule.onNodeWithTag(PAIR_DAPP_FIELD_TAG).performTextInput("y")
        assertEquals(1, edits.size)
    }
}
