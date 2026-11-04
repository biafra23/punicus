package eth.serotine.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SimpleSettingsScreen] headless. */
class SimpleSettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val detailedViewChanges = mutableListOf<Boolean>()

    private fun show() = rule.setContent {
        SerotineTheme {
            SimpleSettingsScreen(
                SettingsUiState(
                    listOf(
                        NetworkRow(
                            11_155_111,
                            "Sepolia",
                            true,
                            "1 account · Verified at block 9,412,118 · 8 s ago",
                            VERIFIED,
                            testnet = true,
                            simpleLine = "Test network · live",
                        ),
                    ),
                    APP_VERSION,
                ),
                onCurrency = {},
                onTokens = {},
                onSecurity = {},
                onScreenCaptureChange = {},
                onDebugScreenChange = {},
                onDetailedViewChange = { detailedViewChanges += it },
            )
        }
    }

    @Test
    fun aNetworkSaysHowItStandsWithoutABlockNumber() {
        show()

        rule.onNodeWithText("Test network · live").assertIsDisplayed()
        rule.onNodeWithText("Verified at block", substring = true).assertDoesNotExist()
    }

    @Test
    fun detailedViewCanBeTurnedOn() {
        show()

        rule.onNodeWithTag(SETTINGS_DETAILED_VIEW_TAG).performScrollTo().performClick()
        assertEquals(listOf(true), detailedViewChanges)
    }
}
