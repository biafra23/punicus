package eth.serotine.ui.dapprequest

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.usecase.DappSignRefusal
import eth.serotine.domain.usecase.DappSignWarning
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.ScreenPreview
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

private const val SPENDER = "0x3fC91A3afd70395Cd496C647d5a6CC9D4B2b7FAD"
private const val USDC = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"

private val approval = signIn.copy(
    title = "Approval request",
    signIn = emptyList(),
    typed = true,
    meaning = "Lets 0x3fC9…7FAD take up to 250 USDC from this account, until Nov 3, 2026, 11:00 AM. " +
        TypedDataText.APPROVAL_NOTE,
    meaningFields = listOf(
        SignInField("Token", "USDC · $USDC"),
        SignInField("Spender", SPENDER),
        SignInField("Amount", "250 USDC"),
        SignInField("Until", "Nov 3, 2026, 11:00 AM"),
    ),
    domainFields = listOf(
        SignInField("Name", "USDC"),
        SignInField("Version", "2"),
        SignInField("Network", "Sepolia (11155111)"),
        SignInField("Contract", USDC),
    ),
    typedRows = listOf(
        TypedRow(0, "owner", ACCOUNT),
        TypedRow(0, "spender", SPENDER),
        TypedRow(0, "value", "250000000"),
        TypedRow(0, "nonce", "0"),
        TypedRow(0, "deadline", "1793703600"),
    ),
)

private val notDecoded = approval.copy(
    title = "Data to sign",
    meaning = null,
    meaningFields = emptyList(),
    domainFields = listOf(
        SignInField("Name", "Example dApp"),
        SignInField("Version", "1"),
        SignInField("Network", "Sepolia (11155111)"),
        SignInField("Contract", "0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"),
    ),
    typedRows = listOf(
        TypedRow(0, "from", null),
        TypedRow(1, "name", "Cow"),
        TypedRow(1, "wallets", "2 items"),
        TypedRow(2, "[0]", "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"),
        TypedRow(2, "[1]", "0xDeaDbeefdEAdbeefdEadbEEFdeadbeEFdEaDbeeF"),
        TypedRow(0, "contents", "Hello, Bob!"),
    ),
    warnings = listOf(DappRequestViewModel.warningLine(DappSignWarning.NOT_DECODED)),
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

@Preview
@Composable
private fun DappRequestApprovalPreview() = DappRequestPreview(approval)

@Preview
@Composable
private fun DappRequestApprovalDarkPreview() = DappRequestPreview(approval, darkTheme = true)

@Preview
@Composable
private fun DappRequestUnlimitedPreview() = DappRequestPreview(
    approval.copy(
        meaning = "Lets 0x3fC9…7FAD take up to an unlimited amount of USDC from this account through Permit2, " +
            "until Dec 3, 2026, 11:00 AM. " + TypedDataText.APPROVAL_NOTE,
        meaningFields = listOf(
            SignInField("Token", "USDC · $USDC"),
            SignInField("Spender", SPENDER),
            SignInField("Amount", "an unlimited amount of USDC"),
            SignInField("Allowance", "until Dec 3, 2026, 11:00 AM"),
            SignInField("Signature good until", "Nov 3, 2026, 11:30 AM"),
        ),
    ),
)

@Preview
@Composable
private fun DappRequestSwapOrderPreview() = DappRequestPreview(
    approval.copy(
        title = "Swap order to sign",
        meaning = "An order on CoW Protocol to sell 1 WETH for at least 2,500 USDC, until Nov 3, 2026, 11:00 AM. " +
            TypedDataText.ORDER_NOTE,
        meaningFields = listOf(
            SignInField("Sell", "1 WETH (WETH · 0xfFf9976782d46CC05630D1f6eBAb18b2324d6B14)"),
            SignInField("Buy", "2500 USDC (USDC · $USDC)"),
            SignInField("Paid to", "This account"),
            SignInField("Valid until", "Nov 3, 2026, 11:00 AM"),
            SignInField("Partly filled", "No: all at once or not"),
        ),
    ),
)

@Preview
@Composable
private fun DappRequestNotDecodedPreview() = DappRequestPreview(notDecoded)

@Preview
@Composable
private fun DappRequestOtherNetworkPreview() = DappRequestPreview(
    notDecoded.copy(
        domainFields = notDecoded.domainFields.map {
            if (it.label == "Network") it.copy(value = "Ethereum (1)") else it
        },
        refusal = DappRequestViewModel.refusalLine(DappSignRefusal.TYPED_OTHER_CHAIN),
    ),
)

// A sign-in on Ethereum, in a connection holding real funds.
private val signInOnEthereum = signIn.copy(
    network = DappRequestViewModel.onLine(eth.serotine.domain.model.Network.ETHEREUM),
    signIn = signIn.signIn.map { if (it.label == "Network") it.copy(value = "Ethereum (1)") else it },
)

@Preview
@Composable
private fun DappRequestRealFundsPreview() = DappRequestPreview(signInOnEthereum)

@Preview
@Composable
private fun DappRequestRealFundsDarkPreview() = DappRequestPreview(signInOnEthereum, darkTheme = true)

/** Every preview above, which DappRequestScreenPreviewsTest renders: a new preview goes in here too. */
internal val dappRequestScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("sign-in") { DappRequestSignInPreview() },
    ScreenPreview("sign-in-dark") { DappRequestSignInDarkPreview() },
    ScreenPreview("biometrics") { DappRequestBiometricsPreview() },
    ScreenPreview("other-site") { DappRequestOtherSitePreview() },
    ScreenPreview("message") { DappRequestMessagePreview() },
    ScreenPreview("hex") { DappRequestHexPreview() },
    ScreenPreview("refused") { DappRequestRefusedPreview() },
    ScreenPreview("wrong-password") { DappRequestWrongPasswordPreview() },
    ScreenPreview("signing") { DappRequestSigningPreview() },
    ScreenPreview("explaining") { DappRequestExplainingPreview() },
    ScreenPreview("ended") { DappRequestEndedPreview() },
    ScreenPreview("loading") { DappRequestLoadingPreview() },
    ScreenPreview("approval") { DappRequestApprovalPreview() },
    ScreenPreview("approval-dark") { DappRequestApprovalDarkPreview() },
    ScreenPreview("unlimited") { DappRequestUnlimitedPreview() },
    ScreenPreview("swap-order") { DappRequestSwapOrderPreview() },
    ScreenPreview("not-decoded") { DappRequestNotDecodedPreview() },
    ScreenPreview("other-network") { DappRequestOtherNetworkPreview() },
    ScreenPreview("real-funds") { DappRequestRealFundsPreview() },
    ScreenPreview("real-funds-dark") { DappRequestRealFundsDarkPreview() },
)
