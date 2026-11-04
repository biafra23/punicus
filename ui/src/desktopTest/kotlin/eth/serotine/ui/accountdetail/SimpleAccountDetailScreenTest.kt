package eth.serotine.ui.accountdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.activity.ActivityUiState
import eth.serotine.ui.activity.WAITING_STEP_TAG
import eth.serotine.ui.activity.WaitingStep
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SimpleAccountDetailScreen] headless. */
class SimpleAccountDetailScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var refreshes = 0
    private val events = mutableListOf<String>()

    private val state = AccountDetailUiState.Ready(
        label = "Main",
        address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        network = NetworkLabel(1, "Ethereum", testnet = false),
        kind = AccountKind.EOA,
        control = Control.FULL_CONTROL,
        total = "€9,612.30",
        totalNote = null,
        assets = listOf(AssetRow("ETH", "Ether", "3.2041 ETH", "eth", true, "block 23,614,077", "€9,612.30")),
        freshness = "Updated 40 s ago · pull down to refresh",
    )

    private fun show(state: AccountDetailUiState) = rule.setContent {
        SerotineTheme {
            SimpleAccountDetailScreen(
                state = state,
                onBack = {},
                onSend = {},
                onSwap = {},
                onReceive = {},
                onCopy = {},
                onRename = {},
                onRemove = {},
                onHideToken = { _, _ -> },
                onMessageShown = {},
                onSort = {},
                onSegment = {},
                onOpenSafeTx = {},
                onImportRequest = {},
                onOpenActivity = {},
                onRefresh = { refreshes++ },
                onContinueSwap = { events += "continue $it" },
                onDismissWaiting = { events += "dismiss $it" },
                onDerive = { events += "derive" },
            )
        }
    }

    @Test
    fun theMenuAddsAnAccountFromTheSamePhrase() {
        show(state)

        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("New account from the same phrase…").performClick()
        assertEquals(listOf("derive"), events)
    }

    @Test
    fun noBlockIsNamedAndTheValuesSayHowFreshTheyAre() {
        show(state)

        rule.onNodeWithTag(ACCOUNT_FRESHNESS_TAG).assertTextEquals("Updated 40 s ago · pull down to refresh")
        rule.onNodeWithText("block", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Ethereum").assertIsDisplayed()
    }

    @Test
    fun aSwapWhoseNextStepWaitsIsContinuedOrDismissedFromItsCard() {
        val usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        show(
            state.copy(
                segment = DetailSegment.ACTIVITY,
                activity = ActivityUiState.Shown(
                    rows = emptyList(),
                    coverage = "Activity since you added it.",
                    waiting = listOf(
                        WaitingStep("Swap 100 USDC → LINK", "Step 1 done. Step 2 is waiting for you.", usdc),
                    ),
                ),
            ),
        )

        rule.onNodeWithTag(WAITING_STEP_TAG).assertIsDisplayed()
        rule.onNodeWithText("Step 1 done. Step 2 is waiting for you.").assertIsDisplayed()
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("continue $usdc", "dismiss $usdc"), events)
    }

    @Test
    fun theRefreshButtonAsksForARefresh() {
        show(state)

        rule.onNodeWithTag(ACCOUNT_REFRESH_TAG).performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun anAmountLastVerifiedSaysHowOldItIs() {
        show(
            state.copy(
                assets = listOf(
                    state.assets.single().copy(
                        verified = false,
                        verifiedAt = null,
                        lastKnown = "Last verified Oct 27, 2026, 9:12 AM · may have changed since",
                        ageNote = "3 h ago · may have changed",
                    ),
                ),
            ),
        )

        rule.onNodeWithText("3 h ago · may have changed").assertIsDisplayed()
    }
}
