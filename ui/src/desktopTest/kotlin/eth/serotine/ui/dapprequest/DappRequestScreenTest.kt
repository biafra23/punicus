package eth.serotine.ui.dapprequest

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [DappRequestScreen] headless. */
class DappRequestScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val typed = mutableListOf<String>()
    private var signs = 0
    private var refusals = 0
    private var dismissed = 0

    private fun show(state: DappRequestUiState) = rule.setContent {
        SerotineTheme {
            DappRequestScreen(
                state,
                onPasswordChange = { typed += it },
                onSign = { signs++ },
                onSignWithBiometrics = {},
                onRefuse = { refusals++ },
                onDismissExplanation = { dismissed++ },
                onBack = {},
            )
        }
    }

    private val unavailable = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable")

    private val state = DappRequestUiState(
        loading = false,
        title = "Sign-in request",
        name = "A dApp",
        url = "https://a.test",
        accountName = "Main",
        accountAddress = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        signIn = listOf(SignInField("Site", "a.test"), SignInField("Nonce", "abcdefgh12")),
        waits = "The dApp waits for an answer for 4 more minutes.",
    )

    @Test
    fun theSignInIsShownFieldByFieldWithTheDappsClaimMarked() {
        show(state)

        rule.onNodeWithText("Stated by the dApp, not verified: Serotine cannot check who is asking.").assertExists()
        rule.onNodeWithText("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed").assertExists()
        rule.onNodeWithText("abcdefgh12").assertExists()
    }

    @Test
    fun signTakesThePasswordFirstAndRefuseAlwaysAnswers() {
        show(state)

        rule.onNodeWithTag(DAPP_REQUEST_SIGN_TAG).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(DAPP_REQUEST_PASSWORD_TAG).performScrollTo().performTextInput("pw")
        assertEquals(listOf("pw"), typed)
        rule.onNodeWithTag(DAPP_REQUEST_REFUSE_TAG).performScrollTo().performClick()
        assertEquals(1, refusals)
    }

    @Test
    fun aMessageThatIsNoTextIsShownAsHexAndSaysSo() {
        show(state.copy(title = "Message to sign", signIn = emptyList(), messageHex = "deadbeef", messageSize = "4 bytes"))

        rule.onNodeWithText("deadbeef").assertExists()
        rule.onNodeWithText("Not text: shown as hex, byte by byte.").assertExists()
    }

    @Test
    fun oneThatCannotBeSignedHasItsSignGreyedOutAndStillTappable() {
        show(state.copy(refusal = "The sign-in has expired."))

        rule.onNodeWithText("This request cannot be signed. The sign-in has expired. Refuse answers the dApp.").assertExists()
        rule.onNodeWithTag(DAPP_REQUEST_PASSWORD_TAG).assertDoesNotExist()
        rule.onNodeWithTag(DAPP_REQUEST_SIGN_TAG).performScrollTo().assert(unavailable).performClick()
        assertEquals(1, signs)
    }

    @Test
    fun anExplanationIsShownAndDismissed() {
        show(state.copy(explaining = RequestExplanation("Cannot be signed", "The sign-in has expired.")))

        rule.onNodeWithText("Cannot be signed").assertExists()
        rule.onNodeWithText("Close").performClick()
        assertEquals(1, dismissed)
    }
}
