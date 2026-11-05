package eth.serotine.ui.handoff

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NO_WALLET
import eth.serotine.ui.components.RecipientItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.TransferForm
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Send from another wallet in Android Studio's preview pane, one preview per state it draws
// differently, built from display-ready values as the screen tests are, never from the ViewModel.
// HandoffScreenPreviewsTest renders each one in handoffScreenPreviews, at the end. The addresses are
// EIP-55's own examples; the names, amounts, blocks and hashes are made up.

private const val WATCHED = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val FRIEND = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
private const val OTHER = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val USDC = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"
private const val HASH = "0x5e1f0c9a7d3b2e8f4a6c1d0b9e8f7a6c5d4b3a2f1e0d9c8b7a6f5e4d3c2b1a09"

private val from = SendFrom("Hardware wallet", WATCHED)

private val ether = AssetChoice("ETH", "Ether", "eth", "1.25 ETH", verifiedAt = "block 8,412,307")

private val usdc = AssetChoice("USDC", "USDC", "usdc", "250 USDC", contract = USDC, verifiedAt = "block 8,412,305")

private val friend =
    RecipientItem(FRIEND, name = "friend-example.eth", resolvedAt = "Resolved at finalized block 8,412,240")

private val compose = HandoffUiState.Compose(
    TransferForm(
        network = "Sepolia",
        from = from,
        assets = listOf(ether, usdc),
        asset = usdc,
        to = "friend-example.eth",
        accounts = listOf(AccountChoice("Savings", OTHER)),
        amount = "25",
        recipient = friend,
        canMax = true,
        canReview = true,
    ),
)

private val notes = listOf(
    "Serotine signs nothing. The wallet that opens signs and sends this transfer, and its fee is that wallet's to set.",
    "In that wallet, choose “Hardware wallet” (0x5aAe…eAed) before you confirm: it sends from whichever of its " +
        "accounts is chosen.",
    "That wallet sends the transfer through its own provider, which learns the transfer and this device's IP address.",
    "Nothing comes back from that wallet. Serotine checks each new block for this transfer for 10 minutes after the " +
        "wallet opens.",
    "Sepolia is a test network: its ETH has no value.",
)

private val review = HandoffUiState.Review(
    network = "Sepolia",
    from = from,
    amount = "25 USDC",
    asset = usdc,
    recipient = friend,
    notes = notes,
)

private val waiting = HandoffUiState.Waiting(
    network = "Sepolia",
    from = from,
    summary = "25 USDC to $FRIEND, from “Hardware wallet”, as handed over.",
    progress = "No block up to 8,412,311 has it yet. Serotine keeps looking for 10 minutes after the handoff.",
)

@Composable
private fun HandoffPreview(
    state: HandoffUiState,
    darkTheme: Boolean = false,
    lightClient: LightClientItem = verifiedStripSample,
) {
    SerotineTheme(darkTheme = darkTheme) {
        HandoffScreen(
            state = state,
            lightClient = lightClient,
            onAssetChange = {},
            onToChange = {},
            onPickAccount = {},
            onRetryLookup = {},
            onAmountChange = {},
            onMax = {},
            onReview = {},
            onEdit = {},
            onOpen = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun HandoffComposePreview() = HandoffPreview(compose)

@Preview
@Composable
private fun HandoffComposeDarkPreview() = HandoffPreview(compose, darkTheme = true)

@Preview
@Composable
private fun HandoffComposeOverBalancePreview() = HandoffPreview(
    compose.copy(
        form = compose.form.copy(
            amount = "300",
            amountError = "This account holds 250 USDC, verified: less than that.",
            canReview = false,
        ),
    ),
)

@Preview
@Composable
private fun HandoffReviewPreview() = HandoffPreview(review)

@Preview
@Composable
private fun HandoffReviewDarkPreview() = HandoffPreview(review, darkTheme = true)

@Preview
@Composable
private fun HandoffReviewMetaMaskLimitPreview() = HandoffPreview(
    review.copy(
        network = "Ethereum",
        amount = "1,500 ETH",
        asset = ether,
        notes = notes.dropLast(1),
        limit = "MetaMask cannot open a payment link for 1,000 ETH or more. Another wallet may.",
    ),
)

@Preview
@Composable
private fun HandoffReviewOpeningPreview() = HandoffPreview(review.copy(opening = true))

@Preview
@Composable
private fun HandoffReviewNoWalletPreview() = HandoffPreview(review.copy(error = NO_WALLET))

@Preview
@Composable
private fun HandoffWaitingPreview() = HandoffPreview(waiting)

@Preview
@Composable
private fun HandoffWaitingDarkPreview() = HandoffPreview(waiting, darkTheme = true)

@Preview
@Composable
private fun HandoffWaitingSyncingPreview() = HandoffPreview(
    waiting.copy(problem = "The light client is still syncing: the transfer is checked once it has caught up."),
)

@Preview
@Composable
private fun HandoffSentPreview() = HandoffPreview(
    HandoffUiState.Outcome(
        "Sepolia",
        OutcomeKind.SENT,
        "Sent",
        "25 USDC from “Hardware wallet” to $FRIEND is in block 8,412,313, its receipt verified.",
        hash = HASH,
        block = "8,412,313",
    ),
)

@Preview
@Composable
private fun HandoffFailedPreview() = HandoffPreview(
    HandoffUiState.Outcome(
        "Sepolia",
        OutcomeKind.FAILED,
        "It failed",
        "The transfer is in block 8,412,313, but it reverted: nothing was sent, and the other wallet paid its fee.",
        hash = HASH,
        block = "8,412,313",
    ),
)

@Preview
@Composable
private fun HandoffOtherSenderPreview() = HandoffPreview(
    HandoffUiState.Outcome(
        "Sepolia",
        OutcomeKind.NOT_THE_ONE,
        "Not from this account",
        "A transfer of 25 USDC to $FRIEND is in block 8,412,313, from $OTHER and not from “Hardware wallet”. It may " +
            "come from another account of the other wallet, or be someone else's payment. It is not counted as this " +
            "account's. Its verified receipt says it went through.",
        hash = HASH,
        block = "8,412,313",
    ),
    darkTheme = true,
)

@Preview
@Composable
private fun HandoffOtherAmountPreview() = HandoffPreview(
    HandoffUiState.Outcome(
        "Sepolia",
        OutcomeKind.NOT_THE_ONE,
        "Not what you handed over",
        "A transfer of 0.000000000000000025 USDC, not 25 USDC, from “Hardware wallet” to $FRIEND is in block " +
            "8,412,313. Its verified receipt says it went through.",
        hash = HASH,
        block = "8,412,313",
    ),
)

@Preview
@Composable
private fun HandoffNotSeenPreview() = HandoffPreview(
    HandoffUiState.Outcome(
        "Sepolia",
        OutcomeKind.NOT_SEEN,
        "No matching transfer seen yet",
        "No block up to 8,412,360 has 25 USDC from “Hardware wallet” to $FRIEND. If it is sent later, the account's " +
            "balance shows it once a block has it.",
    ),
)

@Preview
@Composable
private fun HandoffLoadingPreview() = HandoffPreview(HandoffUiState.Loading("Sepolia"))

@Preview
@Composable
private fun HandoffCannotPreview() = HandoffPreview(HandoffUiState.Cannot("Sepolia", NO_WALLET))

/** Every preview above, which HandoffScreenPreviewsTest renders: a new preview goes in here too. */
internal val handoffScreenPreviews: List<@Composable () -> Unit> = listOf(
    { HandoffComposePreview() },
    { HandoffComposeDarkPreview() },
    { HandoffComposeOverBalancePreview() },
    { HandoffReviewPreview() },
    { HandoffReviewDarkPreview() },
    { HandoffReviewMetaMaskLimitPreview() },
    { HandoffReviewOpeningPreview() },
    { HandoffReviewNoWalletPreview() },
    { HandoffWaitingPreview() },
    { HandoffWaitingDarkPreview() },
    { HandoffWaitingSyncingPreview() },
    { HandoffSentPreview() },
    { HandoffFailedPreview() },
    { HandoffOtherSenderPreview() },
    { HandoffOtherAmountPreview() },
    { HandoffNotSeenPreview() },
    { HandoffLoadingPreview() },
    { HandoffCannotPreview() },
)
