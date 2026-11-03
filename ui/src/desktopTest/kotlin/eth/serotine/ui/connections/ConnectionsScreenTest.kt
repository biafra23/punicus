package eth.serotine.ui.connections

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [ConnectionsScreen] headless. */
class ConnectionsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<String>()
    private val extended = mutableListOf<String>()
    private val disconnected = mutableListOf<String>()
    private var paired = 0

    private fun show(state: ConnectionsUiState) = rule.setContent {
        SerotineTheme {
            ConnectionsScreen(
                state,
                onBack = {},
                onPair = { paired++ },
                onProposal = { opened += it },
                onExtend = { extended += it },
                onDisconnect = { disconnected += it },
            )
        }
    }

    private val state = ConnectionsUiState(
        loading = false,
        link = "Connected to WalletConnect's relay",
        proposals = listOf(ProposalRow("p/1", "A dApp", "https://a.test", "Asks to connect · Review")),
        sessions = listOf(SessionRow("t1", "B dApp", "https://b.test", "Sees 1 account · Ends in 6 days")),
    )

    @Test
    fun aProposalOpensItsReviewAndASessionIsExtendedOrDisconnected() {
        show(state)

        rule.onNodeWithTag(CONNECTIONS_PROPOSAL_TAG + "p/1").performClick()
        assertEquals(listOf("p/1"), opened)
        rule.onNodeWithText("Extend").performClick()
        assertEquals(listOf("t1"), extended)
        rule.onNodeWithText("Disconnect").performClick()
        assertEquals(listOf("t1"), disconnected)
        rule.onNodeWithText("Connect a dApp").performScrollTo().performClick()
        assertEquals(1, paired)
    }

    @Test
    fun offItSaysWhyAndOffersNothing() {
        show(ConnectionsUiState(loading = false, off = true))

        rule.onNodeWithText(
            "This build has dApp connections switched off: it was built without a WalletConnect project ID.",
        ).assertExists()
        rule.onNodeWithText("Connect a dApp").assertDoesNotExist()
    }
}
