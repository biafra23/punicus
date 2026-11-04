package eth.serotine.ui.activitydetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.activity.ActivityState
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SimpleActivityDetailScreen] headless. */
class SimpleActivityDetailScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun howItEndedIsSaidInWordsAndTheBlockIsBehindDetails() {
        val state = ActivityDetailUiState.Shown(
            title = "Alice",
            headline = "Sent 40 USDC",
            about = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            status = "Finalized in block 9,412,118",
            simpleStatus = "Final",
            state = ActivityState.DONE,
            fields = listOf(
                DetailField("Amount", "40 USDC"),
                DetailField("Block", "9,412,118", DetailKind.BLOCK),
            ),
        )
        rule.setContent {
            SerotineTheme {
                SimpleActivityDetailScreen(state, onBack = {}, onCopy = {}, onStatus = {}, onAddToken = {})
            }
        }

        rule.onNodeWithText("Final").assertIsDisplayed()
        rule.onNodeWithText("9,412,118", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(ACTIVITY_DETAIL_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText("9,412,118").performScrollTo().assertIsDisplayed()
    }
}
