package eth.serotine.ui.swap

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.SwapForm
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SimpleSwapScreen] headless. */
class SimpleSwapScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private val approval = SwapUiState.ApprovalReview(
        network = "Ethereum",
        from = SendFrom("Main", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"),
        token = AssetChoice("USDC", "USD Coin", "usdc", "1,250 USDC", verifiedAt = "block 23,614,077"),
        amount = "100 USDC",
        allowance = "0 USDC",
        spender = SpenderItem("Uniswap's SwapRouter02", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"),
        resetNote = null,
        maxFee = "0.00013 ETH",
        feeDetail = "Normal fee: 46,000 gas × at most 2.8 gwei, up to block 23,614,076.",
        balance = BalanceChange("ETH balance", "3.2 ETH", "at least 3.19 ETH", "verified at block 23,614,077"),
        simulation = "The approval, run as a verified call at block 23,614,077, goes through.",
        unlock = UnlockStep(),
        step = "Step 1 of 2",
        next = "Step 2, the swap: once a block has this approval, you confirm the swap.",
        headline = "Allow Uniswap's SwapRouter02 to use 100 USDC",
        scope = "Only this amount, for this swap.",
    )

    private fun show(state: SwapUiState) = rule.setContent {
        SerotineTheme {
            SimpleSwapScreen(
                state = state,
                onSellChange = {},
                onBuyChange = {},
                onAmountChange = {},
                onMax = {},
                onToleranceChange = {},
                onFeeChange = {},
                onVenueChange = {},
                onSwapOnUniswap = {},
                onReview = {},
                onEdit = {},
                onRefresh = {},
                onPasswordChange = {},
                onSign = {},
                onSignWithBiometrics = {},
                onRetry = {},
                onOpenTransfer = { events += "open $it" },
                onOpenOrder = {},
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun theApprovalIsStepOneOfTwoAndNamesNoBlockUntilDetails() {
        show(approval)

        rule.onNodeWithText("Step 1 of 2").assertIsDisplayed()
        rule.onNodeWithText("Allow Uniswap's SwapRouter02 to use 100 USDC").assertIsDisplayed()
        rule.onNodeWithText("Confirm step 1 of 2").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("block 23,", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(SWAP_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText(approval.feeDetail).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun whileTheApprovalWaitsItsStatusAndLaterAreOffered() {
        show(
            SwapUiState.ApprovalPending(
                network = "Ethereum",
                hash = "0xabc",
                title = "Step 1 of 2 sent: the approval",
                message = "Once a block has it…",
            ),
        )

        rule.onNodeWithTag(SWAP_AWAITING_TAG).assertIsDisplayed()
        rule.onNodeWithText("Open the approval").performClick()
        rule.onNodeWithTag(SWAP_LATER_TAG).performClick()
        assertEquals(listOf("open 0xabc", "back"), events)
    }

    @Test
    fun anApprovalThatMayHaveFailedIsSaidWithItsStatus() {
        show(approval.copy(earlierFailed = FailedApproval("It may have failed.", "0xdef")))

        rule.onNodeWithText("It may have failed.").assertIsDisplayed()
        rule.onNodeWithText("Open the earlier approval").performClick()
        assertEquals(listOf("open 0xdef"), events)
    }

    @Test
    fun theFormNamesNoBlockButEachBalanceSaysItsAge() {
        val usdc = approval.token.copy(
            contract = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
            age = "updated 2 min ago",
        )
        show(
            SwapUiState.Compose(
                form = SwapForm(
                    network = "Ethereum",
                    from = approval.from,
                    sellOptions = listOf(usdc),
                    sell = usdc,
                    buyOptions = listOf(usdc),
                    buy = null,
                    amount = "100",
                    tolerance = "0.5",
                ),
                fee = FeeLevel.NORMAL,
            ),
        )

        rule.onNodeWithText("1,250 USDC · updated 2 min ago").assertIsDisplayed()
        rule.onNodeWithText("block 23,", substring = true).assertDoesNotExist()
    }

    @Test
    fun aRefusalOnTheFormIsSaidInSimpleModesWordsWithNoBlock() {
        show(
            SwapUiState.Compose(
                form = SwapForm(
                    network = "Ethereum",
                    from = approval.from,
                    sellOptions = listOf(approval.token),
                    sell = approval.token,
                    buyOptions = listOf(approval.token),
                    buy = null,
                    amount = "100",
                    tolerance = "0.5",
                    error = "The swap reverts, or returns less than its minimum, run as a verified call at block " +
                        "23,614,077. Nothing was signed.",
                    simpleError = "The swap reverts, or returns less than its minimum, as checked on chain. Nothing " +
                        "was signed.",
                ),
                fee = FeeLevel.NORMAL,
            ),
        )

        rule.onNodeWithText("as checked on chain", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("at block", substring = true).assertDoesNotExist()
    }
}
