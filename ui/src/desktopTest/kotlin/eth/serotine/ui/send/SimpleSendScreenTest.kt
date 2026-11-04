package eth.serotine.ui.send

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.RecipientItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SimpleSendScreen] headless. */
class SimpleSendScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val review = SendUiState.Review(
        network = "Ethereum",
        from = SendFrom("Main", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"),
        amount = "0.1 ETH",
        asset = AssetChoice("ETH", "Ether", "eth", "3.2 ETH", verifiedAt = "block 23,614,077"),
        recipient = RecipientItem("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", label = "Savings"),
        maxFee = "0.000462 ETH",
        feeDetail = "Normal fee: 21,000 gas × at most 22 gwei, from the verified fee history up to block 23,614,076.",
        balances = listOf(BalanceChange("ETH balance", "3.2 ETH", "at least 3.09 ETH", "verified at block 23,614,077")),
        simulation = "The transfer, run as a verified call from this account at block 23,614,077, goes through.",
        unlock = UnlockStep(),
    )

    private fun show(state: SendUiState) = rule.setContent {
        SerotineTheme {
            SimpleSendScreen(
                state = state,
                onAssetChange = {},
                onToChange = {},
                onPickAccount = {},
                onRetryLookup = {},
                onAmountChange = {},
                onMax = {},
                onFeeChange = {},
                onReview = {},
                onEdit = {},
                onRefresh = {},
                onPasswordChange = {},
                onSign = {},
                onSignWithBiometrics = {},
                onRetry = {},
                onOpenTransfer = {},
                onBack = {},
            )
        }
    }

    @Test
    fun theReviewSaysTheCheckInWordsAndNamesNoBlockUntilDetails() {
        show(review)

        rule.onNodeWithTag(SEND_SIMULATION_TAG).assertTextEquals("Checked on chain: this transfer should go through")
        rule.onNodeWithText("block 23,", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(SEND_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText(review.feeDetail).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theReviewSaysTheNetworkAndTheAmount() {
        show(review)

        rule.onNodeWithTag(SEND_NETWORK_TAG).assertTextEquals("On Ethereum")
        rule.onNodeWithTag(SEND_AMOUNT_REVIEWED_TAG).assertTextEquals("Send 0.1 ETH")
    }

    @Test
    fun theFormNamesNoBlockBesideTheBalanceOrTheNameNorInItsError() {
        val dated = review.asset.copy(age = "updated 2 min ago")
        show(
            SendUiState.Compose(
                form = eth.serotine.ui.components.TransferForm(
                    network = "Ethereum",
                    from = SendFrom("Main", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"),
                    assets = listOf(dated),
                    asset = dated,
                    to = "friend-example.eth",
                    accounts = emptyList(),
                    amount = "1",
                    recipient = RecipientItem(
                        "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
                        name = "friend-example.eth",
                        resolvedAt = "Resolved at finalized block 23,613,990",
                    ),
                    error = "The transfer reverts, run as a verified call at block 23,614,077: the recipient " +
                        "refuses it.",
                    simpleError = "Checked on chain, this transfer fails: the recipient refuses it. Nothing was " +
                        "signed.",
                ),
                fee = eth.serotine.domain.repository.FeeLevel.NORMAL,
            ),
        )

        rule.onNodeWithText("block 23,", substring = true).assertDoesNotExist()
        // Its age in place of its block (docs/wallet-ux.md, *Verified, or not available*).
        rule.onNodeWithText("3.2 ETH · updated 2 min ago").assertIsDisplayed()
        rule.onNodeWithText("Checked on chain, this transfer fails", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}

