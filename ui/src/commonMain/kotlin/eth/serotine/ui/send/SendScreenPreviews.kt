package eth.serotine.ui.send

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.catchingUpStripSample
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.RecipientItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.TransferForm
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Send screen in Android Studio's preview pane, one preview per state it draws differently.
// Built from display-ready values, as the screen tests are, never from the ViewModel.
// SendScreenPreviewsTest renders each one in sendScreenPreviews, at the end. The addresses are
// EIP-55's own examples; the names, amounts, blocks and the password are made up. A preview has
// no window to keep out of screen capture, so the review's password field shows the line saying
// so, which a device that blocks capture does not.

private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val SAVINGS = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val FRIEND = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
private const val USDC = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"

private val from = SendFrom("Daily", DAILY)

private val ether = AssetChoice("ETH", "Ether", "eth", "1.25 ETH", verifiedAt = "block 8,412,307")

private val xdai = AssetChoice("XDAI", "xDAI", "xdai", "120 XDAI", verifiedAt = "block 42,118,305")

private val usdc = AssetChoice("USDC", "USDC", "usdc", "250 USDC", contract = USDC, verifiedAt = "block 8,412,305")

private val friend =
    RecipientItem(FRIEND, name = "friend-example.eth", resolvedAt = "Resolved at finalized block 8,412,240")

private val accounts = listOf(AccountChoice("Savings", SAVINGS), AccountChoice("Treasury", FRIEND))

private val empty = SendUiState.Compose(
    form = TransferForm(
        network = "Sepolia",
        from = from,
        assets = listOf(ether, usdc),
        asset = ether,
        to = "",
        accounts = accounts,
        amount = "",
        canMax = true,
    ),
    fee = FeeLevel.NORMAL,
)

private val composed = empty.with(fee = FeeLevel.FAST) {
    it.copy(
        asset = usdc,
        to = "friend-example.eth",
        recipient = friend,
        amount = "40",
        amountHint = "≈ $40.00 · testnet prices",
        canReview = true,
    )
}

/** This form step with [edit] applied to its form and the fee [fee]. */
private fun SendUiState.Compose.with(fee: FeeLevel = this.fee, edit: (TransferForm) -> TransferForm = { it }) =
    copy(form = edit(form), fee = fee)

private val review = SendUiState.Review(
    network = "Sepolia",
    from = from,
    amount = "0.1 ETH",
    asset = ether,
    recipient = RecipientItem(SAVINGS, label = "Savings"),
    maxFee = "0.000462 ETH",
    feeDetail = "Normal fee: 21,000 gas × at most 22 gwei, from the verified fee history up to block 8,412,306. " +
        "Only what the block charges is paid.",
    balances = listOf(
        BalanceChange("ETH balance", "1.25 ETH", "at least 1.149538 ETH", "verified at block 8,412,307"),
    ),
    simulation = "The transfer, run as a verified call from this account at block 8,412,307, goes through.",
    unlock = UnlockStep(),
)

private val tokenReview = review.copy(
    amount = "40 USDC",
    asset = usdc,
    recipient = friend,
    maxFee = "0.001428 ETH",
    feeDetail = "Fast fee: 51,000 gas × at most 28 gwei, from the verified fee history up to block 8,412,306. " +
        "Only what the block charges is paid.",
    balances = listOf(
        BalanceChange("USDC balance", "250 USDC", "210 USDC", "verified at block 8,412,305"),
        BalanceChange(
            "ETH balance, which pays the fee",
            "1.25 ETH",
            "at least 1.246572 ETH",
            "verified at block 8,412,307",
        ),
    ),
    simulation = "The token's transfer, run as a verified call from this account at block 8,412,307, goes through.",
    pending = "Earlier transfers from this account, not mined yet, may still take up to 0.002 ETH: the balance " +
        "after counts it.",
    unlock = UnlockStep(biometrics = true),
)

@Composable
private fun SendPreview(
    state: SendUiState,
    darkTheme: Boolean = false,
    lightClient: LightClientItem = verifiedStripSample,
) {
    SerotineTheme(darkTheme = darkTheme) {
        SendScreen(
            state = state,
            chrome = SendChrome.Strip(lightClient),
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

@Preview
@Composable
private fun SendComposeEmptyPreview() = SendPreview(empty)

@Preview
@Composable
private fun SendComposeEmptyDarkPreview() = SendPreview(empty, darkTheme = true)

@Preview
@Composable
private fun SendComposedTokenToNamePreview() = SendPreview(composed)

@Preview
@Composable
private fun SendComposeResolvingPreview() = SendPreview(
    empty.with {
        it.copy(to = "friend-example.eth", toNote = "Resolving the name with verified calls…", amount = "0.1")
    },
)

// On Gnosis Chain, which resolves no names: the field takes an address, and a name is refused.
@Preview
@Composable
private fun SendComposeGnosisNoNamesPreview() = SendPreview(
    empty.copy(
        form = empty.form.copy(
            network = "Gnosis Chain",
            assets = listOf(xdai),
            asset = xdai,
            to = "friend-example.eth",
            toNote = "Names are not resolved on Gnosis Chain yet: enter the address.",
            toError = true,
            toLabel = "Address",
            toHint = "An address, or one of your accounts below: names are not resolved on Gnosis Chain yet",
        ),
    ),
)

@Preview
@Composable
private fun SendComposeInputErrorsPreview() = SendPreview(
    empty.with {
        it.copy(
            to = "0x5aaEb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            toNote = "The capitals do not match this address's checksum: check it for a typo.",
            toError = true,
            amount = "0,5",
            amountError = "Use a dot for the decimals: 0.5, not 0,5.",
        )
    },
)

@Preview
@Composable
private fun SendComposeNameUnavailablePreview() = SendPreview(
    empty.with {
        it.copy(
            to = "friend-example.eth",
            toNote = "The name could not be resolved with verified calls this time.",
            toError = true,
            canRetryLookup = true,
        )
    },
)

@Preview
@Composable
private fun SendComposeQuotingPreview() = SendPreview(
    empty.with {
        it.copy(
            to = SAVINGS,
            recipient = RecipientItem(SAVINGS, label = "Savings"),
            amount = "0.1",
            amountHint = "≈ $250.00 · testnet prices",
            quoting = true,
        )
    },
)

@Preview
@Composable
private fun SendComposeRefusedPreview() = SendPreview(
    empty.with {
        it.copy(
            to = SAVINGS,
            recipient = RecipientItem(SAVINGS, label = "Savings"),
            amount = "1.25",
            amountHint = "≈ $3,125.00 · testnet prices",
            error = "Not enough ETH for this amount and the most the fee can be: send less, to leave room for the " +
                "fee. Transfers not mined yet count too.",
            canReview = true,
        )
    },
)

@Preview
@Composable
private fun SendComposeEarlierMaybeSentPreview() = SendPreview(
    empty.with {
        it.copy(
            to = SAVINGS,
            recipient = RecipientItem(SAVINGS, label = "Savings"),
            amount = "0.1",
            error = "An earlier transaction from this account may still be on its way: no peer answered when it " +
                "was broadcast, and no block has it yet. Try it again from its status rather than sign a new " +
                "one, which could pay twice.",
            canReview = true,
        )
    }.copy(earlier = "0x" + "5e".repeat(32)),
)

@Preview
@Composable
private fun SendComposeTokenNotFoundPreview() = SendPreview(
    empty.with {
        it.copy(
            asset = null,
            assetNote = "The token asked for is not among this account's tokens: choose another asset.",
        )
    },
)

@Preview
@Composable
private fun SendReviewPreview() = SendPreview(
    review.copy(unlock = UnlockStep(WalletPassword("made-up preview password"))),
)

@Preview
@Composable
private fun SendReviewDarkPreview() = SendPreview(review, darkTheme = true)

@Preview
@Composable
private fun SendReviewTokenWithBiometricsPreview() = SendPreview(tokenReview)

@Preview
@Composable
private fun SendReviewWrongPasswordPreview() = SendPreview(
    review.copy(unlock = UnlockStep(error = "That is not the wallet password. Nothing was signed.")),
)

@Preview
@Composable
private fun SendReviewBiometricsCancelledPreview() = SendPreview(
    tokenReview.copy(
        unlock = UnlockStep(
            biometrics = true,
            error = "The fingerprint or face check was dismissed. Nothing was signed: try again, or use the " +
                "wallet password.",
        ),
    ),
)

@Preview
@Composable
private fun SendReviewSigningPreview() = SendPreview(review.copy(signing = true))

@Preview
@Composable
private fun SendReviewRefreshingPreview() = SendPreview(
    review.copy(refreshing = true, unlock = UnlockStep(WalletPassword("made-up preview password"))),
)

@Preview
@Composable
private fun SendReviewNotSentPreview() = SendPreview(
    review.copy(
        notSent = NotSentItem(
            "Signed, but not sent: no peer took it right now (no peers). Try again sends the same signed " +
                "transfer, not a new one.",
            canRetry = true,
        ),
    ),
)

@Preview
@Composable
private fun SendReviewRejectedPreview() = SendPreview(
    review.copy(
        notSent = NotSentItem(
            "Signed, but not sent: judged on verified state, it can never be mined as signed (nonce too low). " +
                "Sending it again would not change that.",
            canRetry = false,
        ),
    ),
)

@Preview
@Composable
private fun SendLoadingPreview() = SendPreview(SendUiState.Loading("Sepolia"), lightClient = catchingUpStripSample)

@Preview
@Composable
private fun SendWatchOnlyPreview() = SendPreview(
    SendUiState.CannotSend(
        "Sepolia",
        "This account is watch-only: its key is not on this device, so nothing can be sent from it.",
    ),
)

@Preview
@Composable
private fun SendSentPreview() = SendPreview(SendUiState.Sent("Sepolia", "0x" + "5e".repeat(32)))

/** Every preview above, which SendScreenPreviewsTest renders: a new preview goes in here too. */
internal val sendScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SendComposeEmptyPreview() },
    { SendComposeEmptyDarkPreview() },
    { SendComposedTokenToNamePreview() },
    { SendComposeResolvingPreview() },
    { SendComposeInputErrorsPreview() },
    { SendComposeGnosisNoNamesPreview() },
    { SendComposeNameUnavailablePreview() },
    { SendComposeQuotingPreview() },
    { SendComposeRefusedPreview() },
    { SendComposeEarlierMaybeSentPreview() },
    { SendComposeTokenNotFoundPreview() },
    { SendReviewPreview() },
    { SendReviewDarkPreview() },
    { SendReviewTokenWithBiometricsPreview() },
    { SendReviewWrongPasswordPreview() },
    { SendReviewBiometricsCancelledPreview() },
    { SendReviewSigningPreview() },
    { SendReviewRefreshingPreview() },
    { SendReviewNotSentPreview() },
    { SendReviewRejectedPreview() },
    { SendLoadingPreview() },
    { SendWatchOnlyPreview() },
    { SendSentPreview() },
)
