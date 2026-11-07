package eth.serotine.ui.safepropose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.catchingUpStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Renders [SafeProposeScreen] and [SimpleSafeProposeScreen] headless for a proposal drafted in lenient
 * mode (docs/lenient-mode.md, *Safe transactions in lenient mode*): what it is prepared on, each
 * figure's block or age, no simulation and no Refresh, signed and held.
 */
class LenientSafeProposeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private val actions = SafeProposeActions(
        onEdit = { events += "edit" },
        onRefresh = { events += "refresh" },
        onSign = { events += "sign" },
    )

    private val typed = draftReviewSample.copy(unlock = UnlockStep(WalletPassword("made-up preview password")))

    private fun show(state: SafeProposeUiState, simple: Boolean = false) = rule.setContent {
        SerotineTheme {
            if (simple) {
                SimpleSafeProposeScreen(state, actions, onBack = { events += "back" })
            } else {
                SafeProposeScreen(state, catchingUpStripSample, actions, onBack = { events += "back" })
            }
        }
    }

    @Test
    fun aDraftSaysWhatItIsPreparedOnAndIsSignedAndHeldWithNoRefresh() {
        show(typed)

        rule.onNodeWithTag(PROPOSE_PREPARED_ON_TAG)
            .assertTextEquals("Prepared on data from 12 min ago. It's checked on chain before it's shown to anyone.")
        rule.onNodeWithText("Safe nonce 12, after the Safe's nonce last verified at block 8,412,307")
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText("→ 2 ETH, if nothing changed").performScrollTo().assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Propose it again").fetchSemanticsNodes().size, "a draft is old already")
        rule.onNodeWithTag(PROPOSE_SIGN_TAG).performScrollTo().assertIsEnabled().assertTextEquals("Sign and hold")
            .performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("sign", "edit"), events)
    }

    @Test
    fun inSimpleModeADraftSaysEachFiguresAgeInPlaceOfItsBlock() {
        show(draftReviewSample, simple = true)

        rule.onNodeWithText("Safe nonce 12, counted as of 12 min ago").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Balance as of 12 min ago").performScrollTo().assertIsDisplayed()
        assertEquals(
            0,
            rule.onAllNodesWithText(draftReviewSample.nonce).fetchSemanticsNodes().size,
            "the block behind Details",
        )
    }
}
