package eth.serotine.ui.send

import androidx.compose.runtime.Composable
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.LightClientHealth
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.RecipientItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.TransferForm
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Send in simple mode (docs/simple-ui.md, S1 and S2) in Android Studio's preview pane, one preview
// per state it draws differently. Built from display-ready values, as the screen tests are, never
// from the ViewModel. SimpleSendScreenPreviewsTest renders each one in simpleSendScreenPreviews, at
// the end. The addresses are EIP-55's own examples; the name and amounts are made up.

private const val MAIN = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val FRIEND = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
private const val USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"

// The blocks are in the state; simple mode keeps them out of the form, which says each balance's
// age instead, and behind Details in the review.
private val ether =
    AssetChoice("ETH", "Ether", "eth", "3.2041 ETH", verifiedAt = "block 23,614,077", age = "updated 2 min ago")
private val usdc = AssetChoice(
    "USDC",
    "USD Coin",
    "usdc",
    "1,250 USDC",
    contract = USDC,
    verifiedAt = "block 23,614,077",
    age = "updated 2 min ago",
)
private val friend =
    RecipientItem(FRIEND, name = "friend-example.eth", resolvedAt = "Resolved at finalized block 23,613,990")

private val composed = SendUiState.Compose(
    form = TransferForm(
        network = "Ethereum",
        from = SendFrom("Main", MAIN),
        assets = listOf(ether, usdc),
        asset = usdc,
        to = "friend-example.eth",
        accounts = listOf(AccountChoice("Savings", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359")),
        amount = "250",
        recipient = friend,
        amountHint = "≈ €230.25",
        canMax = true,
        canReview = true,
    ),
    fee = FeeLevel.NORMAL,
)

private val review = SendUiState.Review(
    network = "Ethereum",
    from = SendFrom("Main", MAIN),
    amount = "250 USDC",
    asset = usdc,
    recipient = friend,
    maxFee = "0.00021 ETH",
    feeDetail = "Normal fee: 51,000 gas × at most 4.1 gwei, from the verified fee history up to block " +
        "23,614,076. Only what the block charges is paid.",
    balances = listOf(
        BalanceChange("USDC balance", "1,250 USDC", "1,000 USDC", "verified at block 23,614,077"),
        BalanceChange(
            "ETH balance, which pays the fee",
            "3.2041 ETH",
            "at least 3.20389 ETH",
            "verified at block 23,614,077",
            short = "ETH balance",
        ),
    ),
    simulation = "The token's transfer, run as a verified call from this account at block 23,614,077, goes through.",
    unlock = UnlockStep(biometrics = true),
)

@Composable
private fun SimpleSendPreview(state: SendUiState, darkTheme: Boolean = false, syncLine: LightClientItem? = null) {
    SerotineTheme(darkTheme = darkTheme) {
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
            syncLine = syncLine,
        )
    }
}

/** S1: the form, each balance with its age and no block beside it or the name. */
@Preview
@Composable
private fun SimpleSendComposePreview() = SimpleSendPreview(composed)

/** S2: the review, the check said in words, the blocks behind Details. */
@Preview
@Composable
private fun SimpleSendReviewPreview() = SimpleSendPreview(review)

@Preview
@Composable
private fun SimpleSendReviewDarkPreview() = SimpleSendPreview(review, darkTheme = true)

/** The form after a review was refused, said in simple mode's words: no block named. */
@Preview
@Composable
private fun SimpleSendComposeRefusedPreview() = SimpleSendPreview(
    composed.copy(
        // A short form, so the refusal under the fee is in the picture.
        form = composed.form.copy(
            assets = listOf(usdc),
            to = FRIEND,
            accounts = emptyList(),
            recipient = null,
            amountHint = null,
            error = "The transfer reverts, run as a verified call at block 23,614,077: the token's contract " +
                "refuses it. Nothing was signed.",
            simpleError = "Checked on chain, the token's contract refuses this transfer. Nothing was signed.",
        ),
    ),
)

/**
 * The form while Ethereum still syncs: the sync line under the top bar, with its progress, and a name
 * that cannot be resolved yet, its Try again greyed out.
 */
@Preview
@Composable
private fun SimpleSendSyncingPreview() = SimpleSendPreview(
    composed.copy(
        form = composed.form.copy(
            to = "savings-example.eth",
            recipient = null,
            toNote = "The light client is still syncing, so the name can't be resolved yet. It is looked up " +
                "again once it has caught up.",
            toError = true,
            canRetryLookup = true,
            retryLookupWaits = "The light client is still syncing. The name is looked up again by itself every " +
                "few seconds until it has caught up.",
        ),
    ),
    syncLine = LightClientItem(
        "Verifying sync committees · 2 of 6 periods",
        2f / 6,
        true,
        LightClientHealth.NOT_VERIFIED,
        simpleText = "Syncing Ethereum · 2 of 6 periods",
    ),
)

/** Every preview above, which SimpleSendScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleSendScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SimpleSendComposePreview() },
    { SimpleSendSyncingPreview() },
    { SimpleSendReviewPreview() },
    { SimpleSendReviewDarkPreview() },
    { SimpleSendComposeRefusedPreview() },
)
