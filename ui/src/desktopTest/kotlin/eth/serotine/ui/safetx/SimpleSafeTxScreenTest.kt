package eth.serotine.ui.safetx

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SimpleSafeTxScreen] headless. */
class SimpleSafeTxScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theOwnersAreCheckedOnChainAndTheirBlocksAreBehindDetails() {
        val savings = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
        val state = SafeTxUiState.Overview(
            network = "Sepolia",
            safe = SafeHeader("Treasury", "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", "2/3"),
            action = SafeTxActionItem(title = "Send 40 USDC", addresses = emptyList()),
            nonce = "Safe nonce 12 · next to run",
            hash = "0x" + "3c".repeat(32),
            owners = OwnersItem.Shown(
                rows = listOf(
                    OwnerStateRow(
                        savings,
                        "Savings",
                        heldHere = false,
                        OwnerState.APPROVED,
                        "Approved on chain, verified at block 8,412,290",
                        simpleDetail = "Approved on chain",
                    ),
                ),
                threshold = "2 of 3 owners must confirm",
                confirmations = "1 of 2 confirmations",
                verifiedAt = "Owners verified at block 8,412,307",
                simpleVerifiedAt = "Owners checked on chain",
            ),
        )
        rule.setContent {
            SerotineTheme { SimpleSafeTxScreen(state, SafeTxActions(), onBack = {}, onCopy = {}, onShareText = null) }
        }

        rule.onNodeWithText("Owners checked on chain").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Approved on chain").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("at block", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(SAFE_TX_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText("Owners verified at block 8,412,307").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Savings: Approved on chain, verified at block 8,412,290")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
