package eth.serotine.ui.safesettings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.components.MODULE_ENABLED_REFUSAL
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SimpleSafeSettingsScreen] headless. */
class SimpleSafeSettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theSettingsAreCheckedOnChainAndTheirBlockIsBehindDetails() {
        val daily = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
        val state = SafeSettingsUiState.Overview(
            network = "Sepolia",
            safe = SendFrom("Treasury", "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"),
            title = "Safe 1.4.1 · nonce 12",
            signers = "1 of 1 owner must sign",
            owners = listOf(SettingsOwner(daily, "Daily", heldHere = true)),
            modules = emptyList(),
            guard = null,
            checkedAt = "Verified at block 8,412,307",
            simpleCheckedAt = "Checked on chain",
            changeReason = null,
            enableModuleReason = MODULE_ENABLED_REFUSAL,
            updateReason = "Not offered here.",
        )
        rule.setContent { SerotineTheme { SimpleSafeSettingsScreen(state, SafeSettingsActions(), onBack = {}) } }

        rule.onNodeWithText("Checked on chain").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("at block", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(SETTINGS_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText("Verified at block 8,412,307").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aChangeTheSafeRevertsOnIsRefusedOnTheFormWithNoBlockAndTheBlockIsBehindDetails() {
        val reverts = "The change reverts, run as a verified call from the Safe at block 8,412,307: the Safe refuses " +
            "it as it is now. Nothing was proposed."
        val state = SafeSettingsUiState.Edit(
            network = "Sepolia",
            safe = SendFrom("Treasury", "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"),
            form = SettingsForm(
                kind = SettingsChangeKind.CHANGE_THRESHOLD,
                title = "Change the threshold",
                explanation = "How many of the Safe's owners must sign each of its transactions.",
                threshold = ThresholdField(1, 3, "1 of 3 owners must sign"),
                error = reverts,
                simpleError = "The change reverts, as checked on chain. Nothing was proposed.",
                canReview = true,
            ),
        )
        rule.setContent { SerotineTheme { SimpleSafeSettingsScreen(state, SafeSettingsActions(), onBack = {}) } }

        rule.onNodeWithText("The change reverts, as checked on chain. Nothing was proposed.")
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText("at block", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(SETTINGS_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText(reverts).performScrollTo().assertIsDisplayed()
    }
}
