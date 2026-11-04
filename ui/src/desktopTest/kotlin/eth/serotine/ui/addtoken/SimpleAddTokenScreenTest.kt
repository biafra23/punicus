package eth.serotine.ui.addtoken

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SimpleAddTokenScreen] headless. */
class SimpleAddTokenScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theContractsClaimNamesNoBlock() {
        val contract = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"
        val state = AddTokenUiState(
            address = contract,
            card = TokenCard.Claim(
                contract,
                "EURS",
                "STASIS EURS Token",
                2,
                "Read from the contract at block 8,412,307",
                warning = null,
                simpleCheckedAt = "Read from the contract, checked on chain",
            ),
        )
        rule.setContent {
            SerotineTheme { SimpleAddTokenScreen(state = state, onAddressChange = {}, onAdd = {}, onBack = {}) }
        }

        rule.onNodeWithText("Read from the contract, checked on chain").assertIsDisplayed()
        rule.onNodeWithText("at block", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(ADD_TOKEN_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText("Read from the contract at block 8,412,307").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aClaimReadWithoutABlockHasNoDetails() {
        val contract = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"
        val state = AddTokenUiState(
            address = contract,
            card = TokenCard.Claim(
                contract,
                "EURS",
                "STASIS EURS Token",
                2,
                "Read from the contract",
                warning = null,
                simpleCheckedAt = "Read from the contract",
            ),
        )
        rule.setContent {
            SerotineTheme { SimpleAddTokenScreen(state = state, onAddressChange = {}, onAdd = {}, onBack = {}) }
        }

        rule.onNodeWithText("Read from the contract").assertIsDisplayed()
        rule.onNodeWithTag(ADD_TOKEN_DETAILS_TAG).assertDoesNotExist()
    }
}
