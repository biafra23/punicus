package eth.serotine.ui.watch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.components.LIGHT_CLIENT_STRIP_TAG
import eth.serotine.ui.components.LightClientHealth
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.SYNC_LINE_TAG
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SimpleWatchScreen] headless. */
class SimpleWatchScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val savings = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"

    private fun show(state: WatchUiState) = rule.setContent {
        SerotineTheme {
            SimpleWatchScreen(state = state, onAddressChange = {}, onNameChange = {}, onAdd = {}, onBack = {})
        }
    }

    @Test
    fun theCardAndTheResolvedNameSayCheckedOnChainAndNameNoBlock() {
        show(
            WatchUiState(
                address = "savings-example.eth",
                addressNote = "Resolves to $savings, verified at finalized block 8,412,240",
                simpleAddressNote = "Resolves to $savings, checked on chain",
                detection = Detection.Account(savings, "Checked at block 8,412,307", null, "Checked on chain"),
            ),
        )

        rule.onNodeWithText("Resolves to $savings, checked on chain").assertIsDisplayed()
        rule.onNodeWithText("Checked on chain").assertIsDisplayed()
        rule.onNodeWithText("block", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(WATCH_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText("Checked at block 8,412,307").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aNameOnOneRegistryNamesEachRegistryAndTheCardsNetwork() {
        show(
            WatchUiState(
                address = "savings-example.eth",
                addressNote = "On Ethereum: no address\nOn Sepolia's test registry: resolves to $savings, verified " +
                    "at finalized block 8,412,240",
                simpleAddressNote = "On Ethereum: no address\nOn Sepolia's test registry: resolves to $savings, " +
                    "checked on chain",
                detection = Detection.Account(savings, "Checked at block 8,412,307", null, "Checked on chain"),
                cardNetwork = NetworkLabel(11_155_111, "Sepolia", testnet = true),
            ),
        )

        rule.onNodeWithText("On Sepolia's test registry", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Sepolia · Testnet").assertIsDisplayed()
        rule.onNodeWithTag(WATCH_CARD_NETWORK_TAG, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("block", substring = true).assertDoesNotExist()
    }

    @Test
    fun whileTheNetworkSyncsTheSyncLineSaysSoAndTryAgainIsGreyedOutWithItsReason() {
        val waits = "The light client is still syncing. The name is looked up again by itself every few seconds " +
            "until it has caught up."
        rule.setContent {
            SerotineTheme {
                SimpleWatchScreen(
                    state = WatchUiState(
                        address = "savings-example.eth",
                        addressError = "The light client is still syncing, so the name can't be resolved yet.",
                        canRetryLookup = true,
                        retryLookupWaits = waits,
                    ),
                    onAddressChange = {},
                    onNameChange = {},
                    onAdd = {},
                    onBack = {},
                    onRetryLookup = { error("asking by hand changes nothing while it syncs") },
                    syncLine = LightClientItem(
                        "Verifying sync committees · 2 of 6 periods",
                        2f / 6,
                        true,
                        LightClientHealth.NOT_VERIFIED,
                        simpleText = "Syncing Ethereum · 2 of 6 periods",
                    ),
                )
            }
        }

        rule.onNodeWithTag(SYNC_LINE_TAG).assertIsDisplayed()
        rule.onNodeWithText("Syncing Ethereum · 2 of 6 periods").assertIsDisplayed()
        rule.onNodeWithText("Verifying sync committees", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Try again").performClick()
        rule.onNodeWithText(waits).assertIsDisplayed()
    }

    @Test
    fun aSyncedNetworkDrawsNoSyncLine() {
        rule.setContent {
            SerotineTheme {
                SimpleWatchScreen(
                    state = WatchUiState(),
                    onAddressChange = {},
                    onNameChange = {},
                    onAdd = {},
                    onBack = {},
                    syncLine = LightClientItem("Verified at block 8,412,307", null, false, LightClientHealth.VERIFIED),
                )
            }
        }

        rule.onNodeWithTag(SYNC_LINE_TAG).assertDoesNotExist()
        rule.onNodeWithTag(LIGHT_CLIENT_STRIP_TAG).assertDoesNotExist()
    }
}
