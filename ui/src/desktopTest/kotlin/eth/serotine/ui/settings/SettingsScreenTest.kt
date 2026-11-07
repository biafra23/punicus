package eth.serotine.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.components.LightClientHealth.NOT_RUNNING
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import eth.serotine.ui.theme.SerotineTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SettingsScreen] headless. */
class SettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var tokensOpened = 0
    private var currencyOpened = 0
    private var securityOpened = 0
    private val screenCaptureChanges = mutableListOf<Boolean>()
    private val debugScreenChanges = mutableListOf<Boolean>()
    private val detailedViewChanges = mutableListOf<Boolean>()
    private val networkChanges = mutableListOf<Pair<Long, Boolean>>()
    private var turnOffsConfirmed = 0
    private var turnOffsCancelled = 0

    private val sepolia =
        NetworkRow(11_155_111, "Sepolia", true, "1 account · Verified at block 9,412,118 · 8 s ago", VERIFIED, true)

    private fun show(
        screenCaptureAllowed: Boolean? = null,
        debugScreen: Boolean? = null,
        detailedView: Boolean = false,
        networks: List<NetworkRow> = listOf(sepolia),
        turningOff: TurnOffConfirmation? = null,
        metered: MeteredDownloadsItem? = MeteredDownloadsItem(on = false, text = METERED_DOWNLOADS_OFF),
    ) = rule.setContent {
        SerotineTheme {
            SettingsScreen(
                SettingsUiState(
                    networks,
                    APP_VERSION,
                    tokens = "3 built in · 1 added",
                    currency = "EUR",
                    screenCaptureAllowed = screenCaptureAllowed,
                    debugScreen = debugScreen,
                    detailedView = detailedView,
                    turningOff = turningOff,
                    meteredDownloads = metered,
                ),
                onCurrency = { currencyOpened++ },
                onTokens = { tokensOpened++ },
                onSecurity = { securityOpened++ },
                onScreenCaptureChange = { screenCaptureChanges += it },
                onDebugScreenChange = { debugScreenChanges += it },
                onNetworkChange = { chainId, on -> networkChanges += chainId to on },
                onConfirmTurnOff = { turnOffsConfirmed++ },
                onCancelTurnOff = { turnOffsCancelled++ },
                onDetailedViewChange = { detailedViewChanges += it },
                onMeteredDownloadsChange = { meteredChoices += it },
            )
        }
    }

    private val meteredChoices = mutableListOf<Boolean>()

    @Test
    fun theMeteredDownloadsSwitchIsOffByDefaultAndATapOnItsRowAllowsThem() {
        show()

        rule.onNodeWithTag(SETTINGS_METERED_TAG).assertIsOff()
        rule.onNodeWithText(METERED_DOWNLOADS_OFF).performClick()
        assertEquals(listOf(true), meteredChoices)
    }

    @Test
    fun aTapOnTheAllowedMeteredDownloadsSwitchTurnsItOff() {
        show(metered = MeteredDownloadsItem(on = true, text = METERED_DOWNLOADS_ON))

        rule.onNodeWithTag(SETTINGS_METERED_TAG).assertIsOn()
        rule.onNodeWithText(METERED_DOWNLOADS_ON).performClick()
        assertEquals(listOf(false), meteredChoices)
    }

    @Test
    fun theMeteredDownloadsSwitchTakesNoTapUntilItsChoiceIsRead() {
        show(metered = null)

        rule.onNodeWithTag(SETTINGS_METERED_TAG).assertHasNoClickAction()
        assertEquals(emptyList(), meteredChoices)
    }

    @Test
    fun aGreyedMeteredDownloadsSwitchSaysWhyOnATapAndChangesNothing() {
        show(
            metered = MeteredDownloadsItem(on = false, text = "Not reported here", unavailableReason = "No metering here."),
        )

        rule.onNodeWithText("Not reported here").performClick()
        rule.onNodeWithText("No metering here.").assertExists()
        assertEquals(emptyList(), meteredChoices)
    }

    @Test
    fun theTokensRowSaysWhatTheWalletKnowsAndOpensTheList() {
        show()

        rule.onNodeWithText("3 built in · 1 added").performClick()
        assertEquals(1, tokensOpened)
    }

    @Test
    fun theCurrencyRowSaysTheChoiceAndOpensThePicker() {
        show()

        rule.onNodeWithText("EUR").performClick()
        assertEquals(1, currencyOpened)
    }

    @Test
    fun theSecurityRowOpensThePasswordAndBiometricSettings() {
        show()

        rule.onNodeWithText("Wallet password · Biometric unlock").performClick()
        assertEquals(1, securityOpened)
    }

    @Test
    fun eachNetworkIsARowWithItsLineAndASwitchThatTurnsItOnAndOff() {
        val ethereum = NetworkRow(1, "Ethereum", false, "No account · Off", NOT_RUNNING)
        show(networks = listOf(sepolia, ethereum))

        rule.onNodeWithText("Sepolia · Testnet").assertIsDisplayed()
        rule.onNodeWithText("1 account · Verified at block 9,412,118 · 8 s ago").assertIsDisplayed()
        rule.onNodeWithText("Ethereum").assertIsDisplayed()
        rule.onNodeWithText("No account · Off").assertIsDisplayed()
        rule.onNodeWithText("A network that is off checks nothing; its accounts stay in your list.").assertExists()

        rule.onNodeWithTag(SETTINGS_NETWORK_TAG + 11_155_111).assertIsOn().performClick()
        rule.onNodeWithTag(SETTINGS_NETWORK_TAG + 1).assertIsOff().performClick()
        assertEquals(listOf(11_155_111L to false, 1L to true), networkChanges)
    }

    @Test
    fun aSwitchNotSavedIsSaidUnderItsNetwork() {
        show(networks = listOf(sepolia.copy(notSaved = true)))

        rule.onNodeWithText("The change could not be saved.").assertIsDisplayed()
    }

    @Test
    fun turningOffANetworkWithAccountsAsksFirst() {
        show(
            turningOff = TurnOffConfirmation(
                11_155_111,
                "Turn off Sepolia?",
                "Its account, Main, stays in your list.",
                waiting = "A transaction sent from it is not confirmed yet.",
            ),
        )

        rule.onNodeWithText("Turn off Sepolia?").assertIsDisplayed()
        rule.onNodeWithText("Its account, Main, stays in your list.").assertIsDisplayed()
        rule.onNodeWithText("A transaction sent from it is not confirmed yet.").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, turnOffsCancelled)
        rule.onNodeWithText("Turn off").performClick()
        assertEquals(1, turnOffsConfirmed)
    }

    @Test
    fun theAppAndItsVersionAreOneMergedSemanticsNode() {
        show()

        // Exactly one node carries both strings: proof they merged, which is what a screen
        // reader announces.
        rule.onAllNodes(hasText("Punicus") and hasText("Version $APP_VERSION")).assertCountEquals(1)
    }

    @Test
    fun aReleaseBuildHasNoScreenRecordingRow() {
        show()

        rule.onNodeWithTag(SETTINGS_SCREEN_CAPTURE_TAG).assertDoesNotExist()
        rule.onNodeWithText("Debug build").assertDoesNotExist()
    }

    @Test
    fun theDebugBuildsRowShowsTheSwitchAndTogglesIt() {
        show(screenCaptureAllowed = false)

        rule.onNodeWithText("Allow screen recording").assertExists()
        rule.onNodeWithTag(SETTINGS_SCREEN_CAPTURE_TAG).performScrollTo().assertIsOff().performClick()
        assertEquals(listOf(true), screenCaptureChanges)
    }

    @Test
    fun theDebugBuildsRowSaysWhenCaptureIsAllowed() {
        show(screenCaptureAllowed = true)

        rule.onNodeWithTag(SETTINGS_SCREEN_CAPTURE_TAG).performScrollTo().assertIsOn().performClick()
        assertEquals(listOf(false), screenCaptureChanges)
    }

    @Test
    fun connectedDappsOpensAndInABuildWithoutAProjectIdSaysWhyItIsGreyedOut() {
        var opened = 0
        rule.setContent {
            SettingsScreen(
                SettingsUiState(networks = emptyList(), version = "1.0"),
                onCurrency = {}, onTokens = {}, onSecurity = {}, onScreenCaptureChange = {}, onDebugScreenChange = {},
                onConnections = { opened++ },
                connectionsOff = true,
            )
        }
        rule.onNodeWithText("Connected dApps").performScrollTo().performClick()
        assertEquals(0, opened)
        rule.onNodeWithText(CONNECTIONS_OFF).assertExists()
    }

    @Test
    fun theDebugTabsRowTurnsItOnAndOff() {
        show(debugScreen = false, detailedView = true)

        rule.onNodeWithTag(SETTINGS_DEBUG_SCREEN_TAG).performScrollTo().assertIsOff().performClick()
        assertEquals(listOf(true), debugScreenChanges)
    }

    @Test
    fun theDebugTabsRowSaysWhenItIsOn() {
        show(debugScreen = true, detailedView = true)

        rule.onNodeWithTag(SETTINGS_DEBUG_SCREEN_TAG).performScrollTo().assertIsOn().performClick()
        assertEquals(listOf(false), debugScreenChanges)
    }

    @Test
    fun detailedViewsRowTurnsItOnAndOff() {
        show()

        rule.onNodeWithTag(SETTINGS_DETAILED_VIEW_TAG).performScrollTo().assertIsOff().performClick()
        assertEquals(listOf(true), detailedViewChanges)
    }

    @Test
    fun theDebugTabsRowIsAbsentWhereTheStateOffersNone() {
        show(debugScreen = null)

        rule.onNodeWithTag(SETTINGS_DEBUG_SCREEN_TAG).assertDoesNotExist()
    }

    @Test
    fun signWhileSyncingIsAnAdvancedSwitchThatSaysWhatItDoesAndWhatStaysQueued() {
        val changes = mutableListOf<Boolean>()
        rule.setContent {
            SerotineTheme {
                SettingsScreen(
                    SettingsUiState(
                        listOf(sepolia),
                        APP_VERSION,
                        signWhileSyncing = true,
                        signWhileSyncingNote = "Turning it off leaves what is queued in place.",
                    ),
                    onCurrency = {},
                    onTokens = {},
                    onSecurity = {},
                    onScreenCaptureChange = {},
                    onDebugScreenChange = {},
                    onSignWhileSyncingChange = { changes += it },
                )
            }
        }

        rule.onNodeWithText(SIGN_WHILE_SYNCING).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Turning it off leaves what is queued in place.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SETTINGS_SIGN_WHILE_SYNCING_TAG).performScrollTo().assertIsOn().performClick()
        assertEquals(listOf(false), changes)
    }
}
