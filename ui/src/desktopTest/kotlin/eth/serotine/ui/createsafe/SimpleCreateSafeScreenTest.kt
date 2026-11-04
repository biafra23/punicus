package eth.serotine.ui.createsafe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SimpleCreateSafeScreen] headless. */
class SimpleCreateSafeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val daily = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val feeDetail = "Normal fee: 297,000 gas × at most 22 gwei, up to block 8,412,306."

    private val review = CreateSafeUiState.Review(
        network = "Sepolia",
        name = "Family vault",
        address = "0x2Ca0226086a7C24808217F7F8707827608B17Dc5",
        signers = "1/1",
        version = "Safe 1.4.1",
        prediction = "The factory's verified run at block 8,412,307 returns this address.",
        simplePrediction = "The factory's run, checked on chain, returns this address.",
        owners = listOf(OwnerItem(daily, "Daily", "Key on this device")),
        shape = "1 of 1 owner signs each transaction",
        payers = listOf(AccountChoice("Daily", daily)),
        payer = daily,
        fee = FeeLevel.NORMAL,
        unlock = UnlockStep(),
        quote = DeploymentQuoteItem(
            maxFee = "0.006534 ETH",
            feeDetail = feeDetail,
            balance = BalanceChange(
                "ETH balance of Daily",
                "1.25 ETH",
                "at least 1.24 ETH",
                "verified at block 8,412,307",
            ),
            simulation = "Run as a verified call at block 8,412,307, the factory deploys the Safe.",
            simpleSimulation = "Checked on chain: the factory should deploy the Safe.",
        ),
    )

    @Test
    fun theReviewNamesNoBlockUntilDetailsAreOpened() {
        rule.setContent { SerotineTheme { SimpleCreateSafeScreen(review, CreateSafeActions(), onBack = {}) } }

        rule.onNodeWithText("The factory's run, checked on chain, returns this address.").assertIsDisplayed()
        rule.onNodeWithText("Checked on chain: the factory should deploy the Safe.")
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText("at block", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(CREATE_SAFE_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText(feeDetail).performScrollTo().assertIsDisplayed()
    }
}
