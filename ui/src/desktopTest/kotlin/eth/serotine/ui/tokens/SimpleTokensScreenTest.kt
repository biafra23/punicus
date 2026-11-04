package eth.serotine.ui.tokens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SimpleTokensScreen] headless. */
class SimpleTokensScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun eachTokensCheckNamesNoBlock() {
        val usdc = TokenItem(
            "USDC",
            "USDC",
            "0x52908400098527886E0F7030069857D2E4169EE7",
            "usdc",
            builtIn = true,
            status = "Built in · checked against its contract at block 8,412,307",
            problem = false,
            hidden = false,
            simpleStatus = "Built in · checked against its contract",
        )
        rule.setContent {
            SerotineTheme {
                SimpleTokensScreen(
                    TokensUiState(listOf(TokenSection(NetworkLabel(1, "Ethereum", testnet = false), listOf(usdc)))),
                    onBack = {},
                    onAddToken = {},
                    onCopy = {},
                    onSetHidden = { _, _, _ -> },
                    onRemove = { _, _ -> },
                )
            }
        }

        rule.onNodeWithText("Built in · checked against its contract").assertIsDisplayed()
        rule.onNodeWithText("at block", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(TOKENS_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText("USDC on Ethereum: Built in · checked against its contract at block 8,412,307")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
