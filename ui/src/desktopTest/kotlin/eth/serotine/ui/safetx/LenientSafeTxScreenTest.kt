package eth.serotine.ui.safetx

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
 * Renders [SafeTxScreen] and [SimpleSafeTxScreen] headless for lenient mode's states
 * (docs/lenient-mode.md, *Safe transactions in lenient mode*): the overview on the Safe as last
 * verified, a signature held until its check, and an approval or execution drafted to be queued.
 */
class LenientSafeTxScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private val actions = SafeTxActions(
        onShare = { events += "share" },
        onConfirm = { events += "confirm" },
        onRefresh = { events += "refresh" },
        onStepBack = { events += "step back" },
        onDiscardHeld = { events += "discard held" },
        onProposeHeldAgain = { events += "propose held again" },
    )

    private fun show(state: SafeTxUiState, simple: Boolean = false) = rule.setContent {
        SerotineTheme {
            if (simple) {
                SimpleSafeTxScreen(state, actions, onBack = { events += "back" }, onCopy = {}, onShareText = null)
            } else {
                SafeTxScreen(
                    state = state,
                    lightClient = catchingUpStripSample,
                    actions = actions,
                    onBack = { events += "back" },
                    onCopy = {},
                    onShareText = null,
                )
            }
        }
    }

    @Test
    fun theOverviewSaysWhatItIsPreparedOnAndSharesNothing() {
        show(lenientOverviewSample)

        rule.onNodeWithTag(SAFE_TX_PREPARED_ON_TAG).assertIsDisplayed()
        rule.onNodeWithText("1 of 2 confirmations here").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Owners as last verified at block 8,412,307, 12 min ago").performScrollTo().assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag(SAFE_TX_SHARE_TAG).fetchSemanticsNodes().size, "nothing to share")
    }

    @Test
    fun aHeldSignatureShowsItsStepsOffersNoShareAndIsDiscarded() {
        show(heldSample)

        rule.onNodeWithTag(SAFE_TX_PREPARED_ON_TAG)
            .assertTextEquals("Signed on data from 12 min ago. It's checked on chain before it's shown to anyone.")
        assertEquals(3, rule.onAllNodesWithTag(SAFE_TX_HELD_STEP_TAG).fetchSemanticsNodes().size)
        rule.onNodeWithText("Waits for Sepolia to catch up. Keep Serotine open to check it sooner.")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Share request").fetchSemanticsNodes().size, "no QR code or text")
        assertEquals(0, rule.onAllNodesWithText("Propose again").fetchSemanticsNodes().size)

        rule.onNodeWithTag(SAFE_TX_DISCARD_HELD_TAG).performScrollTo().performClick()
        assertEquals(listOf("discard held"), events)
    }

    @Test
    fun aHeldProposalWhoseNonceWasUsedOffersToBeProposedAgain() {
        show(
            heldSample.copy(
                discardLabel = "Dismiss",
                again = SafeProposeAgain(11_155_111, "0x" + "5a".repeat(20), null, "0x" + "bb".repeat(20), "0.1"),
            ),
        )

        rule.onNodeWithText("Propose again").performScrollTo().performClick()
        rule.onNodeWithTag(SAFE_TX_DISCARD_HELD_TAG).performScrollTo().assertTextEquals("Dismiss")
        assertEquals(listOf("propose held again"), events)
    }

    @Test
    fun aDraftedApprovalSaysWhatItIsPreparedOnAndIsSignedIntoTheOutboxWithNoRefresh() {
        show(draftApprovalSample.copy(unlock = UnlockStep(WalletPassword("made-up preview password"))))

        rule.onNodeWithTag(SAFE_TX_PREPARED_ON_TAG)
            .assertTextEquals("Prepared on data from 12 min ago. It's checked on chain again before it's sent.")
        rule.onNodeWithText("Not checked yet: the Safe and this transaction run on chain before it's sent.")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Refresh the quote").fetchSemanticsNodes().size, "a draft is old already")
        rule.onNodeWithTag(SAFE_TX_SIGN_TAG).performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("confirm", "step back"), events)
    }

    @Test
    fun inSimpleModeADraftSaysEachFiguresAgeAndKeepsItsBlocksBehindDetails() {
        show(draftApprovalSample, simple = true)

        rule.onNodeWithText("Fees as of 12 min ago").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Nonce 7, counted as of 12 min ago").performScrollTo().assertIsDisplayed()
        assertEquals(
            0,
            rule.onAllNodesWithText(draftApprovalSample.txNonce).fetchSemanticsNodes().size,
            "the block behind Details",
        )
    }
}
