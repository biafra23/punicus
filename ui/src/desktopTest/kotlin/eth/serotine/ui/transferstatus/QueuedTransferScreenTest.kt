package eth.serotine.ui.transferstatus

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.components.catchingUpStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * A transfer queued in lenient mode on the stateless [TransferStatusScreen] and
 * [SimpleTransferStatusScreen], headless (docs/wallet-ux.md, screen 9; docs/lenient-mode.md).
 */
class QueuedTransferScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(state: TransferStatusUiState, simple: Boolean = false) = rule.setContent {
        SerotineTheme {
            if (simple) {
                SimpleTransferStatusScreen(
                    state,
                    onDone = { events += "done" },
                    onRetry = { events += "retry" },
                    onDiscard = { events += "discard" },
                    onSignAgain = { events += "again" },
                    onConfirm = { events += "confirm" },
                )
            } else {
                TransferStatusScreen(
                    state,
                    catchingUpStripSample,
                    onDone = { events += "done" },
                    onRetry = { events += "retry" },
                    onDiscard = { events += "discard" },
                    onSignAgain = { events += "again" },
                    onConfirm = { events += "confirm" },
                )
            }
        }
    }

    @Test
    fun aQueuedTransferSaysWhatItWaitsForAndOffersEditAndDiscard() {
        show(queuedSample)

        rule.onNodeWithText("Waits for Sepolia to catch up. Keep Serotine open to send it sooner.").assertIsDisplayed()
        rule.onNodeWithTag(TRANSFER_SIGN_AGAIN_TAG).performScrollTo().performClick()
        rule.onNodeWithTag(TRANSFER_DISCARD_TAG).performScrollTo().performClick()
        assertEquals(listOf("again", "discard"), events)
    }

    @Test
    fun pastItsHourStillSendThisIsAskedAndSendNowAnswersIt() {
        show(queuedPastItsHourSample, simple = true)

        rule.onNodeWithText("Still send this? You signed it 2 h ago.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TRANSFER_SEND_NOW_TAG).performScrollTo().performClick()
        assertEquals(listOf("confirm"), events)
    }

    @Test
    fun aConflictOffersSignAgain() {
        show(queuedConflictSample, simple = true)

        rule.onNodeWithText("Needs your signature again").assertIsDisplayed()
        rule.onNodeWithText("Sign again").performScrollTo().performClick()
        assertEquals(listOf("again"), events)
    }

    @Test
    fun whileItIsBeingSentDiscardAndEditAreGreyedOutAndATapSaysWhy() {
        show(queuedSendingSample)
        // Its step says so already; the tap's dialog says it again.
        rule.onAllNodesWithText("It's being checked and sent.").assertCountEquals(1)

        rule.onNodeWithTag(TRANSFER_DISCARD_TAG).performScrollTo().performClick()
        rule.onAllNodesWithText("It's being checked and sent.").assertCountEquals(2)
        assertEquals(emptyList(), events, "nothing discarded while its check and broadcast run")
    }

    @Test
    fun aDiscardedTransferSaysNothingWasSent() {
        show(discardedSample)

        rule.onNodeWithText("Discarded: its signature was deleted, and nothing was sent.").assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        assertEquals(listOf("done"), events)
    }
}
