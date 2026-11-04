package eth.serotine.ui.renameaccount

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import eth.serotine.ui.removeaccount.RemoveAccountDialog
import eth.serotine.ui.removeaccount.RemoveAccountUiState
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless rename and remove dialogs headless. */
class AccountDialogsTest {

    private companion object {
        const val DAILY = "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2"
    }

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    @Test
    fun aRemovalUnderWayCannotBeWalkedAwayFrom() {
        rule.setContent {
            SerotineTheme {
                RemoveAccountDialog(
                    RemoveAccountUiState.Confirm("Daily", DAILY, "Sepolia", keyed = true, removing = true),
                    {},
                    {},
                )
            }
        }

        rule.onNodeWithText("Cancel").assertIsNotEnabled()
        rule.onNodeWithText("Remove").assertIsNotEnabled()
    }

    @Test
    fun renameShowsTheNameAndItsError() {
        rule.setContent {
            SerotineTheme {
                RenameAccountDialog(
                    RenameAccountUiState.Editing("", error = "Enter a name"),
                    onNameChange = { events += "type $it" },
                    onSave = { events += "save" },
                    onCancel = { events += "cancel" },
                )
            }
        }

        rule.onNodeWithText("Enter a name").assertIsDisplayed()
        rule.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("S")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Cancel").performClick()

        // A field whose state never changes (nothing holds it here) may report its reset too.
        assertEquals("type S", events.first())
        assertEquals(listOf("save", "cancel"), events.filterNot { it.startsWith("type") })
    }

    @Test
    fun renameCannotSaveTwice() {
        rule.setContent {
            SerotineTheme { RenameAccountDialog(RenameAccountUiState.Editing("Daily", saving = true), {}, {}, {}) }
        }

        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun removingAnAccountWithAKeySaysTheKeyGoesToo() {
        rule.setContent {
            SerotineTheme {
                RemoveAccountDialog(
                    RemoveAccountUiState.Confirm("Daily", DAILY, "Sepolia", keyed = true),
                    onRemove = { events += "remove" },
                    onCancel = { events += "cancel" },
                )
            }
        }

        rule.onNodeWithText("Remove Daily on Sepolia?").assertIsDisplayed()
        rule.onNodeWithText(DAILY).assertIsDisplayed()
        rule.onNodeWithText("Only its recovery phrase brings this account back", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()

        assertEquals(listOf("remove"), events)
    }

    @Test
    fun removingAWatchedAccountSaysItCanBeWatchedAgain() {
        rule.setContent {
            SerotineTheme {
                RemoveAccountDialog(RemoveAccountUiState.Confirm("Savings", DAILY, "Sepolia", keyed = false), {}, {})
            }
        }

        rule.onNodeWithText("You can watch it again at any time", substring = true).assertIsDisplayed()
    }

    @Test
    fun theKeyboardsDoneSavesTheName() {
        rule.setContent {
            SerotineTheme {
                RenameAccountDialog(
                    RenameAccountUiState.Editing("Savings"),
                    onNameChange = { events += "type $it" },
                    onSave = { events += "save" },
                    onCancel = { events += "cancel" },
                )
            }
        }

        rule.onNodeWithTag(RENAME_FIELD_TAG).performImeAction()
        assertEquals(listOf("save"), events)
    }

    @Test
    fun removingOneNetworksAccountSaysTheKeyStaysForTheOthers() {
        rule.setContent {
            SerotineTheme {
                RemoveAccountDialog(
                    RemoveAccountUiState.Confirm("Main", DAILY, "Gnosis Chain", keyed = true, keyStaysFor = "Ethereum"),
                    {},
                    {},
                )
            }
        }

        rule.onNodeWithText("Remove Main on Gnosis Chain?").assertIsDisplayed()
        rule.onNodeWithText(
            "Its key stays on this device for the same address on Ethereum. You can add it on Gnosis Chain again " +
                "from there.",
        ).assertIsDisplayed()
    }
}
