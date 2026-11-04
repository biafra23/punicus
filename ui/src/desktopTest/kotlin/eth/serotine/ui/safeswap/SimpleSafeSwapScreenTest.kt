package eth.serotine.ui.safeswap

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.SafeBalanceChange
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SimpleSafeSwapScreen] headless. */
class SimpleSafeSwapScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theReviewNamesNoBlockUntilDetailsAreOpened() {
        val daily = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
        val state = SafeSwapUiState.Review(
            network = "Sepolia",
            from = SendFrom("Treasury", "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"),
            sell = AssetChoice("USDC", "USDC", "usdc", "250 USDC"),
            sold = "100 USDC",
            buy = AssetChoice("ETH", "Ether", "eth", "2.5 ETH"),
            quoted = "0.0402 ETH",
            quotedAt = "QuoterV2's verified quote at block 8,412,307.",
            simpleQuotedAt = "QuoterV2's quote, checked on chain.",
            minimum = "at least 0.039999 ETH",
            tolerance = "0.5 % tolerance.",
            route = emptyList(),
            routeNote = "One pool.",
            priceImpact = "Price impact: 0.12 %.",
            spender = SpenderItem("Uniswap's SwapRouter02", "0x3bFA4769FB09eefC5a80d6E87c3B9C650f7Ae48E"),
            deadline = "Refused after 14:30.",
            transactions = emptyList(),
            approvalNote = null,
            balance = SafeBalanceChange(
                "USDC balance of the Safe",
                "250 USDC",
                "150 USDC",
                "verified at block 8,412,307",
            ),
            simulation = "The swap, run at block 8,412,307, returns 0.0402 ETH.",
            simpleSimulation = "Checked on chain: the swap should return 0.0402 ETH, at least its minimum.",
            owners = listOf(AccountChoice("Daily", daily)),
            owner = daily,
            unlock = UnlockStep(),
        )
        rule.setContent { SerotineTheme { SimpleSafeSwapScreen(state, SafeSwapActions(), onBack = {}) } }

        rule.onNodeWithText("0.0402 ETH: QuoterV2's quote, checked on chain.").assertIsDisplayed()
        rule.onNodeWithText("at block", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(SAFE_SWAP_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText("USDC balance of the Safe: verified at block 8,412,307")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
