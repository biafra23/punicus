package eth.serotine.ui.dappproposal

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [DappProposalScreen] headless. */
class DappProposalScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val toggled = mutableListOf<String>()
    private var approves = 0
    private var rejects = 0
    private var dismissed = 0

    private fun show(state: DappProposalUiState) = rule.setContent {
        SerotineTheme {
            DappProposalScreen(
                state,
                onToggle = { toggled += it },
                onApprove = { approves++ },
                onReject = { rejects++ },
                onDismissExplanation = { dismissed++ },
                onBack = {},
            )
        }
    }

    private val unavailable = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable")

    private val state = DappProposalUiState(
        loading = false,
        name = "A dApp",
        url = "https://a.test",
        accounts = listOf(
            AccountOption("11155111:0x1", "Main", "0x0000…0001", chosen = false, unavailable = null),
            AccountOption("1:0x1", "Savings", "0x0000…0001", chosen = false, unavailable = "This dApp did not ask for Ethereum."),
        ),
    )

    @Test
    fun theDappsClaimIsMarkedAsSuch() {
        show(state)

        rule.onNodeWithText("Stated by the dApp, not verified: Punicus cannot check who is asking.").assertExists()
    }

    @Test
    fun aGreyedOutAccountAndAGreyedOutApproveStillAnswerATap() {
        show(state)

        rule.onNodeWithTag(DAPP_PROPOSAL_ACCOUNT_TAG + "1:0x1").performScrollTo().assert(unavailable).performClick()
        assertEquals(listOf("1:0x1"), toggled)
        rule.onNodeWithText("Approve").performScrollTo().assert(unavailable).performClick()
        assertEquals(1, approves)
        rule.onNodeWithText("Reject").performClick()
        assertEquals(1, rejects)
    }

    @Test
    fun anExplanationIsShownAndDismissed() {
        show(state.copy(explaining = Explanation("Not offered to this dApp", "This dApp did not ask for Ethereum.")))

        rule.onNodeWithText("Not offered to this dApp").assertExists()
        rule.onNodeWithText("Close").performClick()
        assertEquals(1, dismissed)
    }
}
