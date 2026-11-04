package eth.serotine.ui.addnetwork

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eth.serotine.ui.components.NETWORK_CHOICE_TAG
import eth.serotine.ui.components.NetworkChoice
import eth.serotine.ui.theme.SerotineTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [AddOnNetworksDialog] headless. */
class AddOnNetworksDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(state: AddOnNetworksUiState) = rule.setContent {
        SerotineTheme {
            AddOnNetworksDialog(
                state,
                onToggle = { events += "toggle $it" },
                onTurnOn = { events += "turn on $it" },
                onAdd = { events += "add" },
                onCancel = { events += "cancel" },
            )
        }
    }

    @Test
    fun aNetworkThatIsOnIsTickedAndOneThatIsOffIsTurnedOnFirst() {
        show(
            AddOnNetworksUiState.Choose(
                "Main",
                listOf(
                    NetworkChoice(100, "Gnosis Chain", testnet = false, on = true),
                    NetworkChoice(11_155_111, "Sepolia", testnet = true, on = false),
                ),
            ),
        )

        rule.onNodeWithText("Add").assertIsNotEnabled()
        rule.onNodeWithTag(NETWORK_CHOICE_TAG + 100).performClick()
        rule.onNodeWithTag(NETWORK_CHOICE_TAG + 11_155_111).assertIsNotEnabled()
        rule.onNodeWithText("Turn on").performClick()

        assertEquals(listOf("toggle 100", "turn on 11155111"), events)
    }

    @Test
    fun addIsOfferedOnceANetworkIsTicked() {
        show(
            AddOnNetworksUiState.Choose(
                "Main",
                listOf(NetworkChoice(100, "Gnosis Chain", testnet = false, on = true, chosen = true)),
            ),
        )

        rule.onNodeWithText("Add").assertIsEnabled().performClick()
        assertEquals(listOf("add"), events)
    }

    @Test
    fun anAccountOnEveryNetworkHasNothingToAdd() {
        show(AddOnNetworksUiState.Choose("Main", emptyList()))

        rule.onNodeWithText("It is on every network this wallet offers already.").assertExists()
        rule.onNodeWithText("Add").assertDoesNotExist()
        rule.onNodeWithText("Close").performClick()
        assertEquals(listOf("cancel"), events)
    }
}
