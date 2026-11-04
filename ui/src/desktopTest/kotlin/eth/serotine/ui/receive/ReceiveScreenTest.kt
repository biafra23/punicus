package eth.serotine.ui.receive

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [ReceiveScreen] headless. */
class ReceiveScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val ready = ReceiveUiState.Ready(
        "Daily",
        "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2",
        NetworkLabel(11_155_111, "Sepolia", testnet = true),
        "Only send Sepolia assets to this address",
    )
    private val events = mutableListOf<String>()

    private fun show(state: ReceiveUiState, share: ((String) -> Unit)?) = rule.setContent {
        SerotineTheme {
            ReceiveScreen(state, onBack = { events += "back" }, onCopy = { events += "copy $it" }, onShare = share)
        }
    }

    @Test
    fun showsTheQrCodeTheFullAddressAndTheNetworkWarning() {
        show(ready, share = null)

        rule.onNodeWithTag(RECEIVE_QR_TAG).assertIsDisplayed()
        rule.onNodeWithContentDescription("QR code of the address").assertIsDisplayed()
        rule.onNodeWithTag(RECEIVE_ADDRESS_TAG).assertTextEquals(ready.address)
        rule.onNodeWithText("Only send Sepolia assets to this address").assertIsDisplayed()
    }

    @Test
    fun copiesTheAddressAndSharesItWhereThePlatformCan() {
        show(ready, share = { events += "share $it" })

        rule.onNodeWithText("Copy").performClick()
        rule.onNodeWithText("Share").performClick()

        assertEquals(listOf("copy ${ready.address}", "share ${ready.address}"), events)
        rule.onNodeWithText("Address copied").assertIsDisplayed()
    }

    @Test
    fun noShareButtonWithoutAShareSheet() {
        show(ready, share = null)

        rule.onNodeWithText("Share").assertDoesNotExist()
    }
}
