package eth.serotine.ui.transferstatus

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SimpleTransferStatusScreen] headless. */
class SimpleTransferStatusScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val retries = mutableListOf<Unit>()

    private val confirmed = TransferStatusUiState.Shown(
        network = "Ethereum",
        hash = "0x" + "a1".repeat(32),
        sent = null,
        steps = listOf(
            StatusStep("Signed", StepState.DONE, "On this device"),
            StatusStep(
                "Included",
                StepState.DONE,
                "In block 23,614,012, its receipt verified",
                simpleLabel = "Confirmed",
                simpleDetail = "Its receipt checked on chain",
            ),
        ),
    )

    private fun show(state: TransferStatusUiState) = rule.setContent {
        SerotineTheme { SimpleTransferStatusScreen(state, onDone = {}, onRetry = { retries += Unit }) }
    }

    @Test
    fun theStepsSaySimpleWordsAndTheBlockIsBehindDetails() {
        show(confirmed)

        rule.onNodeWithText("Confirmed").assertIsDisplayed()
        rule.onNodeWithText("block 23,614,012", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(TRANSFER_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText("Included: In block 23,614,012, its receipt verified").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aTransferMaybeSentOffersTryAgain() {
        show(confirmed.copy(notSent = NotSentItem("No peer answered when it was broadcast.", canRetry = true)))

        rule.onNodeWithText("Try again", substring = true).performScrollTo().performClick()
        assertEquals(1, retries.size)
    }
}
