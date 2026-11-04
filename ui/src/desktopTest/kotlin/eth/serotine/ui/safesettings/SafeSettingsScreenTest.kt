package eth.serotine.ui.safesettings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.ActionAddress
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.UNAVAILABLE_REASON_TAG
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SafeSettingsScreen] headless for its settings, a form and the review. */
class SafeSettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val treasury = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
    private val daily = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val savings = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
    private val safe = SendFrom("Treasury", treasury)
    private val overview = SafeSettingsUiState.Overview(
        network = "Sepolia",
        safe = safe,
        title = "Safe 1.4.1 · nonce 12",
        signers = "1 of 2 owners must sign",
        owners = listOf(SettingsOwner(daily, "Daily", heldHere = true), SettingsOwner(savings, null, heldHere = false)),
        modules = emptyList(),
        guard = null,
        checkedAt = "Verified at block 9",
        changeReason = null,
        enableModuleReason = "Enabling a module is refused.",
        updateReason = "Not signed here.",
    )
    private val events = mutableListOf<String>()
    private val actions = SafeSettingsActions(
        onAddOwner = { events += "add" },
        onRemoveOwner = { events += "remove $it" },
        onAddressChange = { events += "address $it" },
        onThresholdChange = { events += "threshold $it" },
        onReview = { events += "review" },
        onSign = { events += "sign" },
    )

    private fun show(state: SafeSettingsUiState) = rule.setContent {
        SerotineTheme {
            SafeSettingsScreen(state = state, lightClient = verifiedStripSample, actions = actions, onBack = {})
        }
    }

    @Test
    fun theSettingsListTheOwnersWithTheirChanges() {
        show(overview)

        rule.onAllNodesWithTag(SETTINGS_OWNER_TAG).assertCountEquals(2)
        rule.onNodeWithText("This device").assertIsDisplayed()
        rule.onAllNodesWithText("Remove")[1].performClick()
        rule.onNodeWithTag(SETTINGS_ADD_OWNER_TAG).performScrollTo().performClick()

        assertEquals(listOf("remove $savings", "add"), events)
    }

    @Test
    fun aChangeThatCannotBeProposedIsGreyedOutAndSaysWhy() {
        show(overview.copy(changeReason = "No owner of this Safe has its key on this device."))

        rule.onNodeWithTag(SETTINGS_ADD_OWNER_TAG).performScrollTo().performClick()

        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals("No owner of this Safe has its key on this device.")
        assertEquals(emptyList(), events)
    }

    @Test
    fun enablingAModuleIsShownAndSaysWhyItIsNeverOffered() {
        show(overview)

        rule.onNodeWithText("Enable a module").performScrollTo().performClick()

        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals("Enabling a module is refused.")
    }

    @Test
    fun theFormTakesTheNewOwnerAndTheThreshold() {
        show(
            SafeSettingsUiState.Edit(
                "Sepolia",
                safe,
                SettingsForm(
                    kind = SettingsChangeKind.ADD_OWNER,
                    title = "Add an owner",
                    explanation = "The new owner signs with the others.",
                    address = OwnerField("", null, isError = false, listOf(AccountChoice("Laptop", savings))),
                    threshold = ThresholdField(1, 3, "1 of 3 owners must sign"),
                    problem = "It would leave all 3 owners needed to sign.",
                ),
            ),
        )

        rule.onNodeWithTag(SETTINGS_ADDRESS_TAG).performTextInput("0x12")
        rule.onNodeWithContentDescription("Raise the threshold").performClick()
        rule.onNodeWithTag(SETTINGS_THRESHOLD_TAG).assertTextEquals("1 of 3")
        rule.onNodeWithContentDescription("Lower the threshold").assertIsNotEnabled()
        rule.onNodeWithText("It would leave all 3 owners needed to sign.").assertIsDisplayed()
        rule.onNodeWithTag(SETTINGS_REVIEW_TAG).performScrollTo().assertIsNotEnabled()

        // The field is the state's, which a test does not change: what was typed is the first event.
        assertEquals("address 0x12", events.first())
        assertEquals("threshold 2", events.last())
    }

    @Test
    fun theReviewShowsTheChangeDecodedAndSigns() {
        show(
            SafeSettingsUiState.Review(
                network = "Sepolia",
                safe = safe,
                action = SafeTxActionItem("Remove an owner", listOf(ActionAddress("Owner removed", savings))),
                nonce = "Safe nonce 12",
                simulation = "The change goes through.",
                owners = listOf(AccountChoice("Daily", daily)),
                owner = daily,
                unlock = UnlockStep(WalletPassword("made-up test password")),
            ),
        )

        rule.onNodeWithText("Remove an owner").assertIsDisplayed()
        rule.onNodeWithText("Safe nonce 12").assertIsDisplayed()
        rule.onNodeWithTag(SETTINGS_SIGN_TAG).performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf("sign"), events)
    }
}
