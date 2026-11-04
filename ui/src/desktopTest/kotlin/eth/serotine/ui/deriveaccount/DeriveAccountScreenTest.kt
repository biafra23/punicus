package eth.serotine.ui.deriveaccount

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class DeriveAccountScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private val ask = DeriveAccountUiState.Ask("Daily", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "Ethereum")

    private fun show(state: DeriveAccountUiState) = rule.setContent {
        SerotineTheme {
            DeriveAccountScreen(
                state,
                onPasswordChange = { events += "password $it" },
                onDerive = { events += "derive" },
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun itAsksForTheWalletPasswordAndSaysCaptureIsNotBlockedHere() {
        show(ask)

        rule.onNodeWithText("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed").assertIsDisplayed()
        rule.onNodeWithTag(DERIVE_ACCOUNT_ADD_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(DERIVE_ACCOUNT_PASSWORD_TAG).performTextInput("correct horse battery")
        assertEquals(listOf("password correct horse battery"), events)
        rule.onNodeWithText("Screenshots and screen recordings", substring = true).assertIsDisplayed()
    }

    @Test
    fun withAPasswordTypedAddAccountAdds() {
        show(ask.copy(password = WalletPassword("correct horse battery")))

        rule.onNodeWithTag(DERIVE_ACCOUNT_ADD_TAG).assertIsEnabled().performClick()
        assertEquals(listOf("derive"), events)
    }

    @Test
    fun theAccountAddedIsShownAndDoneLeaves() {
        show(DeriveAccountUiState.Added("Account 2", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359", "Ethereum", 2))

        rule.onNodeWithText("Account 2 is added on Ethereum: account 2 of the recovery phrase.").assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        assertEquals(listOf("back"), events)
    }
}
