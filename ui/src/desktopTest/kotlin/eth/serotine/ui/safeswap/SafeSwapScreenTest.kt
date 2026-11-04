package eth.serotine.ui.safeswap

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.components.ASSET_OPTION_TAG
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.RouteStep
import eth.serotine.ui.components.SWAP_AMOUNT_TAG
import eth.serotine.ui.components.SWAP_SELL_TAG
import eth.serotine.ui.components.SWAP_TOLERANCE_TAG
import eth.serotine.ui.components.SafeBalanceChange
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.SwapForm
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SafeSwapScreen] headless for its states. */
class SafeSwapScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val usdcContract = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
    private val owner = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val ether = AssetChoice("ETH", "Ether", "eth", "1.5 ETH")
    private val usdc = AssetChoice("USDC", "USDC", "usdc", "250 USDC", contract = usdcContract)
    private val from = SendFrom("Treasury", "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB")

    private val form = SwapForm(
        network = "Sepolia",
        from = from,
        sellOptions = listOf(ether, usdc),
        sell = usdc,
        buyOptions = listOf(ether),
        buy = ether,
        amount = "100",
        tolerance = "0.5",
        canReview = true,
    )

    private val review = SafeSwapUiState.Review(
        network = "Sepolia",
        from = from,
        sell = usdc,
        sold = "100 USDC",
        buy = ether,
        quoted = "0.0402 ETH",
        quotedAt = "QuoterV2's verified quote at block 100.",
        minimum = "at least 0.039999 ETH",
        tolerance = "0.5 % tolerance",
        route = listOf(RouteStep("USDC → WETH", "0.05 %", "0x3030303030303030303030303030303030303030")),
        routeNote = "One Uniswap v3 pool.",
        priceImpact = "Price impact: not known this time.",
        spender = SpenderItem("Uniswap's SwapRouter02", "0x5050505050505050505050505050505050505050"),
        deadline = "Refused by the router after 14:30.",
        transactions = listOf(
            SafeSwapTransaction("Safe nonce 4", SafeTxActionItem("Allow a contract to take 100 USDC", emptyList())),
            SafeSwapTransaction(
                "Safe nonce 5",
                SafeTxActionItem("Swap 100 USDC for at least 0.039999 ETH", emptyList()),
            ),
        ),
        approvalNote = null,
        balance = SafeBalanceChange("USDC balance of the Safe", "250 USDC", "150 USDC", "verified at block 99"),
        simulation = "The swap cannot run before the router's allowance is approved on chain.",
        owners = listOf(AccountChoice("Alice", owner)),
        owner = owner,
        unlock = UnlockStep(),
    )

    private val events = mutableListOf<String>()

    private fun show(state: SafeSwapUiState) = rule.setContent {
        SerotineTheme {
            SafeSwapScreen(
                state = state,
                lightClient = verifiedStripSample,
                actions = SafeSwapActions(
                    onSellChange = { events += "sell $it" },
                    onReview = { events += "review" },
                    onEdit = { events += "edit" },
                    onSign = { events += "sign" },
                ),
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun theComposeStepReviewsTheSwap() {
        show(SafeSwapUiState.Compose(form))

        rule.onNodeWithTag(SWAP_SELL_TAG).performClick()
        rule.onAllNodesWithTag(ASSET_OPTION_TAG)[0].performClick()
        rule.onNodeWithTag(SAFE_SWAP_REVIEW_TAG).performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf("sell null", "review"), events)
    }

    @Test
    fun aSwapProposedAgainHoldsItsPairAndAmount() {
        show(SafeSwapUiState.Compose(form.copy(fixed = true), replacing = "Proposes the swap queued at nonce 5 again."))

        rule.onNodeWithText("Proposes the swap queued at nonce 5 again.").assertExists()
        rule.onNodeWithTag(SWAP_AMOUNT_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(SWAP_SELL_TAG).assertIsNotEnabled().performClick()
        rule.onAllNodesWithTag(ASSET_OPTION_TAG).assertCountEquals(0)
        assertEquals(emptyList(), events, "the asset sold is fixed")
    }

    @Test
    fun theReviewShowsBothSafeTransactionsAndSignsThem() {
        show(review.copy(unlock = UnlockStep(WalletPassword("made-up test password"))))

        rule.onNodeWithTag(SAFE_SWAP_MINIMUM_TAG).assertTextEquals("at least 0.039999 ETH")
        rule.onAllNodesWithTag(SAFE_SWAP_TRANSACTION_TAG).assertCountEquals(2)
        rule.onNodeWithText("Safe nonce 5").performScrollTo()
        rule.onNodeWithTag(SAFE_SWAP_SIGN_TAG).performScrollTo().assertIsEnabled()
            .assertTextEquals("Sign both and queue").performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf("sign", "edit"), events)
    }

    @Test
    fun aReviewOfThreeTransactionsSignsAllThree() {
        val reset = review.transactions.first().copy(nonce = "Safe nonce 3")
        show(review.copy(transactions = listOf(reset) + review.transactions))

        rule.onAllNodesWithTag(SAFE_SWAP_TRANSACTION_TAG).assertCountEquals(3)
        rule.onNodeWithTag(SAFE_SWAP_SIGN_TAG).performScrollTo().assertTextEquals("Sign all three and queue")
        rule.onNodeWithText("All three are signed", substring = true).assertExists()
    }

    @Test
    fun whileSigningItSaysWhichTransaction() {
        show(review.copy(signing = true, progress = "Transaction 2 of 2"))

        rule.onNodeWithText("Signing transaction 2 of 2…").performScrollTo()
    }

    @Test
    fun theKeyboardsDoneOnTheToleranceReviews() {
        show(SafeSwapUiState.Compose(form.copy(canReview = true)))
        rule.onNodeWithTag(SWAP_TOLERANCE_TAG).performScrollTo().performImeAction()
        assertEquals(listOf("review"), events)
    }
}
