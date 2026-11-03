package eth.serotine.ui.dapprequest

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.usecase.DappSignRefusal
import eth.serotine.domain.usecase.DappSignWarning
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// A dApp's message request in Android Studio's preview pane, one preview per state it draws
// differently. DappRequestScreenPreviewsTest renders each one in dappRequestScreenPreviews, at the end.

private const val ACCOUNT = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"

private val signIn = DappRequestUiState(
    loading = false,
    title = "Sign-in request",
    name = "Example dApp",
    url = "https://app.example.org",
    accountName = "Main",
    accountAddress = ACCOUNT,
    signIn = listOf(
        SignInField("Site", "app.example.org"),
        SignInField("Account", ACCOUNT),
        SignInField("Statement", "Sign in to Example dApp. This request will not trigger a transaction."),
        SignInField("URI", "https://app.example.org/login"),
        SignInField("Network", "Sepolia (11155111)"),
        SignInField("Nonce", "k3T9qL2mVx"),
        SignInField("Issued", "Nov 3, 2026, 10:00 AM"),
        SignInField("Expires", "Nov 3, 2026, 10:10 AM"),
    ),
    messageSize = "412 bytes",
    waits = DappRequestViewModel.waitsLine(240),
)

private val message = signIn.copy(
    title = "Message to sign",
    signIn = emptyList(),
    messageText = "Welcome to Example dApp!\n\nClick to accept the terms of service.\n\nNonce: 81726354",
    messageSize = "86 bytes",
    warnings = listOf(DappRequestViewModel.warningLine(DappSignWarning.NOT_A_SIGN_IN)),
)

@Composable
private fun DappRequestPreview(state: DappRequestUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        DappRequestScreen(
            state,
            onPasswordChange = {},
            onSign = {},
            onSignWithBiometrics = {},
            onRefuse = {},
            onDismissExplanation = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun DappRequestSignInPreview() = DappRequestPreview(signIn)

@Preview
@Composable
private fun DappRequestSignInDarkPreview() = DappRequestPreview(signIn, darkTheme = true)

@Preview
@Composable
private fun DappRequestBiometricsPreview() =
    DappRequestPreview(signIn.copy(unlock = UnlockStep(password = WalletPassword("secret"), biometrics = true)))

@Preview
@Composable
private fun DappRequestOtherSitePreview() = DappRequestPreview(
    signIn.copy(
        signIn = signIn.signIn.map { if (it.label == "Site") it.copy(value = "app.example.org.evil.test") else it },
        warnings = listOf(DappRequestViewModel.warningLine(DappSignWarning.DOMAIN_NOT_DAPP_URL)),
    ),
)

@Preview
@Composable
private fun DappRequestMessagePreview() = DappRequestPreview(message)

@Preview
@Composable
private fun DappRequestHexPreview() = DappRequestPreview(
    message.copy(messageText = null, messageHex = "4f2a91c3e8b07d5566d0a1f2c3b4e5d6deadbeef", messageSize = "20 bytes"),
)

@Preview
@Composable
private fun DappRequestRefusedPreview() = DappRequestPreview(
    signIn.copy(refusal = DappRequestViewModel.refusalLine(DappSignRefusal.SIGN_IN_EXPIRED)),
)

@Preview
@Composable
private fun DappRequestWrongPasswordPreview() = DappRequestPreview(
    signIn.copy(unlock = UnlockStep(error = "That is not the wallet password. Nothing was signed.")),
)

@Preview
@Composable
private fun DappRequestSigningPreview() = DappRequestPreview(signIn.copy(busy = true))

@Preview
@Composable
private fun DappRequestExplainingPreview() = DappRequestPreview(
    signIn.copy(
        refusal = DappRequestViewModel.refusalLine(DappSignRefusal.SAFE),
        explaining = RequestExplanation("Cannot be signed", DappRequestViewModel.refusalLine(DappSignRefusal.SAFE)),
    ),
)

@Preview
@Composable
private fun DappRequestEndedPreview() = DappRequestPreview(
    DappRequestUiState(loading = false, ended = true, notice = DappRequestViewModel.EXPIRED),
)

@Preview
@Composable
private fun DappRequestLoadingPreview() = DappRequestPreview(DappRequestUiState())

/** Every preview above, which DappRequestScreenPreviewsTest renders: a new preview goes in here too. */
internal val dappRequestScreenPreviews: List<@Composable () -> Unit> = listOf(
    { DappRequestSignInPreview() },
    { DappRequestSignInDarkPreview() },
    { DappRequestBiometricsPreview() },
    { DappRequestOtherSitePreview() },
    { DappRequestMessagePreview() },
    { DappRequestHexPreview() },
    { DappRequestRefusedPreview() },
    { DappRequestWrongPasswordPreview() },
    { DappRequestSigningPreview() },
    { DappRequestExplainingPreview() },
    { DappRequestEndedPreview() },
    { DappRequestLoadingPreview() },
)
