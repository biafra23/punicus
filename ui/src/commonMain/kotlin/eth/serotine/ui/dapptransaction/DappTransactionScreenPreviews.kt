package eth.serotine.ui.dapptransaction

import androidx.compose.runtime.Composable
import eth.serotine.domain.usecase.DappSignRefusal
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.dapprequest.DappRequestViewModel
import eth.serotine.ui.dapprequest.SignInField
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// A dApp's transaction in Android Studio's preview pane, one preview per state it draws differently.
// DappTransactionScreenPreviewsTest renders each one in dappTransactionScreenPreviews, at the end.

private const val ACCOUNT = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val RECIPIENT = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val USDC = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"

private val quote = listOf(
    SignInField("Fee, at most", "0.00168 ETH (testnet)"),
    SignInField("Left in the account, at least", "0.49732 ETH (testnet)"),
    SignInField("Verified run", "Runs without reverting at block 7,412,903"),
    SignInField("Gas limit", "80000"),
    SignInField("Nonce", "12"),
)

private val payment = DappTransactionUiState(
    loading = false,
    name = "Example dApp",
    url = "https://app.example.org",
    accountName = "Main",
    accountAddress = ACCOUNT,
    to = RECIPIENT,
    value = "0.5 ETH (testnet)",
    meaning = "A payment of its value to the recipient.",
    quote = quote,
    waits = DappRequestViewModel.waitsLine(240),
)

private val tokenTransfer = payment.copy(
    to = USDC,
    value = "0 ETH (testnet)",
    meaning = "A transfer of 25 USDC to $RECIPIENT.",
    meaningFields = listOf(
        SignInField("Token", "USDC · $USDC"),
        SignInField("Recipient", RECIPIENT),
        SignInField("Amount", "25 USDC"),
    ),
    dataHex = "a9059cbb000000000000000000000000fb6916095ca1df60bb79ce92ce3ea74c37c5d359" +
        "0000000000000000000000000000000000000000000000000000000001312d00",
    dataSize = "68 bytes",
)

private val notDecoded = payment.copy(
    to = "0x3fC91A3afd70395Cd496C647d5a6CC9D4B2b7FAD",
    value = "0.01 ETH (testnet)",
    meaning = null,
    meaningFields = listOf(SignInField("Function", "0x3593564c")),
    dataHex = "3593564c0000000000000000000000000000000000000000000000000000000000000060" +
        "0000000000000000000000000000000000000000000000000000000000000a0",
    dataSize = "100 bytes",
    warnings = listOf(DappTransactionViewModel.NOT_DECODED),
)

private const val SAFE = "0x7a3B9c1E5d2F4a6B8c0D1e2F3a4B5c6D7e8F9a0B"
private const val OWNER = "0x2B5AD5c4795c026514f8317c7a215E218DcCD6cF"

private val fromSafe = payment.copy(
    accountName = "Team Safe",
    accountAddress = SAFE,
    fromSafe = true,
    quote = listOf(
        SignInField("Safe transaction", "Nonce 4, behind 1 queued; 2 of 3 owners sign it"),
        SignInField("Left in the Safe, at least", "1.25 ETH (testnet)"),
        SignInField("Verified run", "Runs from the Safe without reverting at block 7,412,903"),
        SignInField("Signs here as", OWNER),
        SignInField("Fee", "Paid by the account that executes it, quoted when it is executed"),
    ),
)

@Composable
private fun DappTransactionPreview(state: DappTransactionUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        DappTransactionScreen(
            state,
            onPasswordChange = {},
            onSend = {},
            onSendWithBiometrics = {},
            onRetry = {},
            onRequote = {},
            onRefuse = {},
            onDismissExplanation = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun DappTransactionPaymentPreview() = DappTransactionPreview(payment)

@Preview
@Composable
private fun DappTransactionPaymentDarkPreview() = DappTransactionPreview(payment, darkTheme = true)

@Preview
@Composable
private fun DappTransactionTokenTransferPreview() = DappTransactionPreview(tokenTransfer)

@Preview
@Composable
private fun DappTransactionApprovalPreview() = DappTransactionPreview(
    tokenTransfer.copy(
        meaning = "An approval: lets 0x3fC91A3afd70395Cd496C647d5a6CC9D4B2b7FAD take up to an unlimited amount of " +
            "USDC from this account.",
        meaningFields = listOf(
            SignInField("Token", "USDC · $USDC"),
            SignInField("Spender", "0x3fC91A3afd70395Cd496C647d5a6CC9D4B2b7FAD"),
            SignInField("Amount", "an unlimited amount of USDC"),
        ),
        warnings = listOf(DappTransactionViewModel.APPROVAL_NOTE),
    ),
)

@Preview
@Composable
private fun DappTransactionNotDecodedPreview() = DappTransactionPreview(notDecoded)

@Preview
@Composable
private fun DappTransactionQuotingPreview() = DappTransactionPreview(payment.copy(quote = emptyList(), quoting = true))

@Preview
@Composable
private fun DappTransactionNotQuotedPreview() = DappTransactionPreview(
    payment.copy(
        quote = emptyList(),
        notQuoted = "Not enough ETH in this account for the most the fee can be and the value.",
    ),
)

@Preview
@Composable
private fun DappTransactionRefusedPreview() = DappTransactionPreview(
    payment.copy(
        accountName = "Watched",
        quote = emptyList(),
        refusal = DappTransactionViewModel.refusalLine(DappSignRefusal.NO_KEY),
    ),
)

@Preview
@Composable
private fun DappTransactionSafePreview() = DappTransactionPreview(fromSafe)

@Preview
@Composable
private fun DappTransactionSafeDarkPreview() = DappTransactionPreview(fromSafe, darkTheme = true)

@Preview
@Composable
private fun DappTransactionSafeNoOwnerPreview() = DappTransactionPreview(
    fromSafe.copy(quote = emptyList(), notQuoted = DappTransactionViewModel.NO_OWNER_HERE),
)

@Preview
@Composable
private fun DappTransactionSafeSigningPreview() = DappTransactionPreview(fromSafe.copy(busy = true))

@Preview
@Composable
private fun DappTransactionSafeQueuedPreview() = DappTransactionPreview(
    fromSafe.copy(quote = emptyList(), queued = "0x8f3c1b2a4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8"),
)

@Preview
@Composable
private fun DappTransactionSendingPreview() = DappTransactionPreview(payment.copy(busy = true))

@Preview
@Composable
private fun DappTransactionNotSentPreview() = DappTransactionPreview(
    payment.copy(
        notSent = NotSentItem(
            "Signed, but not sent: no peer took it right now. Try again sends the same signed transaction, " +
                "not a new one.",
            canRetry = true,
        ),
        // No peer answered: it may be on its way, so Refuse is greyed out.
        refuseUnavailable = true,
    ),
)

@Preview
@Composable
private fun DappTransactionExplainingPreview() = DappTransactionPreview(
    payment.copy(
        quote = emptyList(),
        notQuoted = "Not enough ETH in this account for the most the fee can be and the value.",
        explaining = TransactionExplanation(
            "Cannot be sent",
            "Not enough ETH in this account for the most the fee can be and the value.",
        ),
    ),
)

@Preview
@Composable
private fun DappTransactionEndedPreview() = DappTransactionPreview(
    DappTransactionUiState(loading = false, ended = true, notice = DappTransactionViewModel.EXPIRED),
)

@Preview
@Composable
private fun DappTransactionLoadingPreview() = DappTransactionPreview(DappTransactionUiState())

/** Every preview above, which DappTransactionScreenPreviewsTest renders: a new preview goes in here too. */
internal val dappTransactionScreenPreviews: List<@Composable () -> Unit> = listOf(
    { DappTransactionPaymentPreview() },
    { DappTransactionPaymentDarkPreview() },
    { DappTransactionTokenTransferPreview() },
    { DappTransactionApprovalPreview() },
    { DappTransactionNotDecodedPreview() },
    { DappTransactionQuotingPreview() },
    { DappTransactionNotQuotedPreview() },
    { DappTransactionRefusedPreview() },
    { DappTransactionSafePreview() },
    { DappTransactionSafeDarkPreview() },
    { DappTransactionSafeNoOwnerPreview() },
    { DappTransactionSafeSigningPreview() },
    { DappTransactionSafeQueuedPreview() },
    { DappTransactionSendingPreview() },
    { DappTransactionNotSentPreview() },
    { DappTransactionExplainingPreview() },
    { DappTransactionEndedPreview() },
    { DappTransactionLoadingPreview() },
)
