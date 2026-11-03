package eth.serotine.ui.dapptransaction

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import eth.serotine.ui.dapprequest.SignInField
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [DappTransactionScreen] headless. */
class DappTransactionScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val typed = mutableListOf<String>()
    private var sends = 0
    private var refusals = 0
    private var requotes = 0

    private fun show(state: DappTransactionUiState) = rule.setContent {
        SerotineTheme {
            DappTransactionScreen(
                state,
                onPasswordChange = { typed += it },
                onSend = { sends++ },
                onSendWithBiometrics = {},
                onRetry = {},
                onRequote = { requotes++ },
                onRefuse = { refusals++ },
                onDismissExplanation = {},
                onBack = {},
            )
        }
    }

    private val unavailable = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable")

    private val state = DappTransactionUiState(
        loading = false,
        name = "A dApp",
        url = "https://a.test",
        accountAddress = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        to = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        value = "0.5 ETH (testnet)",
        meaning = "A payment of its value to the recipient.",
        quote = listOf(SignInField("Fee, at most", "0.00168 ETH (testnet)")),
    )

    @Test
    fun theTransactionAndItsVerifiedQuoteAreShownAndSendTakesThePassword() {
        show(state)

        rule.onNodeWithText("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359").assertExists()
        rule.onNodeWithText("0.00168 ETH (testnet)").performScrollTo().assertExists()
        rule.onNodeWithTag(DAPP_TRANSACTION_PASSWORD_TAG).performScrollTo().performTextInput("pw")
        assertEquals(listOf("pw"), typed)
        rule.onNodeWithTag(DAPP_TRANSACTION_REFUSE_TAG).performScrollTo().performClick()
        assertEquals(1, refusals)
    }

    @Test
    fun withoutAQuoteSendIsGreyedOutAndStillTappableAndTryAgainQuotesAnew() {
        show(state.copy(quote = emptyList(), notQuoted = "Not enough ETH."))

        rule.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(1, requotes)
        rule.onNodeWithTag(DAPP_TRANSACTION_SEND_TAG).performScrollTo().assert(unavailable).performClick()
        assertEquals(1, sends)
    }
}
