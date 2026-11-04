package eth.serotine.ui.safepropose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.RecipientItem
import eth.serotine.ui.components.SafeBalanceChange
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SimpleSafeProposeScreen] headless. */
class SimpleSafeProposeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theReviewNamesNoBlockUntilDetailsAreOpened() {
        val daily = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
        val friend = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"
        val state = SafeProposeUiState.Review(
            network = "Sepolia",
            from = SendFrom("Treasury", "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"),
            action = SafeTxActionItem(title = "Send 0.5 ETH", addresses = emptyList(), icon = "eth", symbol = "ETH"),
            amount = "0.5 ETH",
            asset = AssetChoice("ETH", "Ether", "eth", "2.5 ETH"),
            recipient = RecipientItem(
                friend,
                name = "friend.eth",
                resolvedAt = "Resolved at finalized block 8,412,240",
            ),
            nonce = "Safe nonce 12",
            balance = SafeBalanceChange("ETH balance of the Safe", "2.5 ETH", "2 ETH", "verified at block 8,412,307"),
            simulation = "The Safe's call, run at block 8,412,307, goes through.",
            simpleSimulation = "Checked on chain: the Safe's call should go through.",
            owners = listOf(AccountChoice("Daily", daily)),
            owner = daily,
            unlock = UnlockStep(),
        )
        rule.setContent { SerotineTheme { SimpleSafeProposeScreen(state, SafeProposeActions(), onBack = {}) } }

        rule.onNodeWithText("Checked on chain: the Safe's call should go through.")
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText("at block", substring = true).assertDoesNotExist()
        rule.onNodeWithText("at finalized block", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(PROPOSE_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText("ETH balance of the Safe: verified at block 8,412,307")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
