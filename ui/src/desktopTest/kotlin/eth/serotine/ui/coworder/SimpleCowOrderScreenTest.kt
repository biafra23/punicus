package eth.serotine.ui.coworder

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SimpleCowOrderScreen] headless. */
class SimpleCowOrderScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val from = SendFrom("Daily", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed")
    private val settlement = SpenderItem("CoW's settlement contract", "0x9008D19f58AAbD9eD0D60971565AA8510560ab41")

    private fun show(state: CowOrderUiState) = rule.setContent {
        SerotineTheme {
            SimpleCowOrderScreen(
                state = state,
                onCancel = {},
                onEdit = {},
                onRefresh = {},
                onPasswordChange = {},
                onSign = {},
                onSignWithBiometrics = {},
                onRetry = {},
                onOpenTransfer = {},
                onSwapAgain = {},
                onBack = {},
            )
        }
    }

    @Test
    fun aFilledOrderSaysItWasProvenWithoutItsBlock() {
        show(
            CowOrderUiState.Shown(
                network = "Sepolia",
                from = from,
                sold = "40 USDC",
                soldSymbol = "USDC",
                soldIcon = null,
                bought = "0.016102 ETH",
                boughtSymbol = "ETH",
                boughtIcon = null,
                state = OrderState.FILLED,
                end = "Filled in block 8,412,322: it bought 0.016102 ETH.",
                simpleEnd = "Filled: it bought 0.016102 ETH, as this account's verified logs say.",
                placement = "CoW took the order when it was sent.",
                validUntil = "Valid until 14:27.",
                settlement = settlement,
                uid = "0xc0",
            ),
        )

        rule.onNodeWithText("Filled: it bought 0.016102 ETH, as this account's verified logs say.").assertIsDisplayed()
        rule.onNodeWithText("8,412,322", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(COW_ORDER_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText("Filled in block 8,412,322: it bought 0.016102 ETH.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theCancellationsBlocksAreBehindDetails() {
        val feeDetail = "Normal fee: 43,500 gas × at most 22 gwei, up to block 8,412,306."
        show(
            CowOrderUiState.CancelReview(
                network = "Sepolia",
                from = from,
                uid = "0xc0",
                settlement = settlement,
                maxFee = "0.000957 ETH",
                feeDetail = feeDetail,
                balance = BalanceChange("ETH balance", "1.25 ETH", "at least 1.24 ETH", "verified at block 8,412,307"),
                simulation = "The cancellation, run at block 8,412,307, goes through.",
                simpleSimulation = "Checked on chain: the cancellation should go through.",
                unlock = UnlockStep(),
            ),
        )

        rule.onNodeWithText("Checked on chain: the cancellation should go through.")
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText("at block", substring = true).assertDoesNotExist()
        rule.onNodeWithTag(COW_ORDER_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText(feeDetail).performScrollTo().assertIsDisplayed()
    }
}
