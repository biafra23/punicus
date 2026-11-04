package eth.serotine.ui.safetx

import androidx.compose.runtime.Composable
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.ActionAddress
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.SafeSwapItem
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// A Safe transaction in simple mode in Android Studio's preview pane, one preview per state it draws
// differently from Detailed view. Built from display-ready values, never from the ViewModel.
// SimpleSafeTxScreenPreviewsTest renders each one in simpleSafeTxScreenPreviews, at the end. The
// addresses are EIP-55's own examples; the hash, amounts and blocks are made up.

private const val TREASURY = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val SAVINGS = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val FRIEND = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"
private const val USDC = "0x52908400098527886E0F7030069857D2E4169EE7"
private const val HASH = "0x3c9f1a7e5b2d8c4f6a0e9b1d3c5f7a9e2b4d6f8a0c1e3b5d7f9a2c4e6b8d0f1a"

private val safe = SafeHeader("Treasury", TREASURY, "2/3")

private val usdcTransfer = SafeTxActionItem(
    title = "Send 40 USDC",
    addresses = listOf(ActionAddress("To", FRIEND), ActionAddress("Token contract", USDC)),
    icon = "usdc",
    symbol = "USDC",
)

private val overview = SafeTxUiState.Overview(
    network = "Sepolia",
    safe = safe,
    action = usdcTransfer,
    nonce = "Safe nonce 12 · next to run",
    hash = HASH,
    owners = OwnersItem.Shown(
        rows = listOf(
            OwnerStateRow(DAILY, "Daily", heldHere = true, OwnerState.MISSING, "Not yet"),
            OwnerStateRow(
                SAVINGS,
                "Savings",
                heldHere = false,
                OwnerState.APPROVED,
                "Approved on chain, verified at block 8,412,290",
                simpleDetail = "Approved on chain",
            ),
        ),
        threshold = "2 of 3 owners must confirm",
        confirmations = "1 of 2 confirmations",
        verifiedAt = "Owners verified at block 8,412,307",
        simpleVerifiedAt = "Owners checked on chain",
    ),
    canSign = true,
    canApprove = true,
    canExecute = true,
)

private val review = SafeTxUiState.Review(
    network = "Sepolia",
    safe = safe,
    action = usdcTransfer,
    nonce = "Safe nonce 12 · next to run",
    kind = ReviewKind.EXECUTION,
    accounts = listOf(SignerChoice("Daily", DAILY)),
    account = DAILY,
    fee = FeeLevel.NORMAL,
    maxFee = "0.00176 ETH",
    feeDetail = "Normal fee: 80,000 gas × at most 22 gwei, from the verified fee history up to block 8,412,306.",
    balance = BalanceChange(
        "ETH balance of Daily",
        "1.25 ETH",
        "at least 1.24824 ETH",
        "verified at block 8,412,307",
    ),
    simulation = "The whole execution, run as a verified call from this account at block 8,412,307, goes through.",
    simpleSimulation = "Checked on chain: the whole execution — the Safe's checks of the confirmations and the " +
        "nonce, its guard if it has one, and the call itself — should go through.",
    confirmations = "Executed with: Daily, by sending the execution; Savings's approval on chain",
    unlock = UnlockStep(),
)

@Composable
private fun SimpleSafeTxPreview(state: SafeTxUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleSafeTxScreen(state, SafeTxActions(), onBack = {}, onCopy = {}, onShareText = null)
    }
}

/** The overview: the owners checked on chain, their blocks behind Details. */
@Preview
@Composable
private fun SimpleSafeTxOverviewPreview() = SimpleSafeTxPreview(overview)

@Preview
@Composable
private fun SimpleSafeTxOverviewDarkPreview() = SimpleSafeTxPreview(overview, darkTheme = true)

/** The execution's review: its run checked on chain, the fee's make-up and the blocks behind Details. */
@Preview
@Composable
private fun SimpleSafeTxReviewPreview() = SimpleSafeTxPreview(review)

private val swapNow = SafeSwapItem(
    lines = listOf(
        "Through Uniswap's SwapRouter02, its code proven by the light client.",
        "Returns 0.0402 ETH now, by a verified QuoterV2 quote at block 8,412,307: it meets its minimum.",
        "Refused by the router after 20 Oct 2026, 14:32.",
    ),
    simpleLines = listOf(
        "Through Uniswap's SwapRouter02, its code proven by the light client.",
        "Returns 0.0402 ETH now, as checked on chain: it meets its minimum.",
        "Refused by the router after 20 Oct 2026, 14:32.",
    ),
)

/** A swap: what it returns now, checked on chain, its quote's block behind Details. */
@Preview
@Composable
private fun SimpleSafeTxSwapPreview() = SimpleSafeTxPreview(
    overview.copy(
        action = SafeTxActionItem(title = "Swap 100 USDC for at least 0.04 ETH", addresses = emptyList()),
        swap = swapNow,
    ),
)

/** A quote refused: said with no block, the detailed words behind Details. */
@Preview
@Composable
private fun SimpleSafeTxRefusedPreview() = SimpleSafeTxPreview(
    overview.copy(
        message = "It reverts, run as a verified call at block 8,412,307. Nothing was signed.",
        simpleMessage = "It reverts, checked on chain. Nothing was signed.",
    ),
)

/** Signing a swap's hash: its stand without the block. */
@Preview
@Composable
private fun SimpleSafeTxSigningPreview() = SimpleSafeTxPreview(
    SafeTxUiState.Signing(
        network = "Sepolia",
        safe = safe,
        action = SafeTxActionItem(title = "Swap 100 USDC for at least 0.04 ETH", addresses = emptyList()),
        nonce = "Safe nonce 12 · next to run",
        hash = HASH,
        owners = listOf(SignerChoice("Daily", DAILY)),
        owner = DAILY,
        unlock = UnlockStep(),
        swap = swapNow,
    ),
)

/** An approval on chain under review: its run checked on chain. */
@Preview
@Composable
private fun SimpleSafeTxApprovalPreview() = SimpleSafeTxPreview(
    review.copy(
        kind = ReviewKind.APPROVAL,
        simulation = "The Safe's approveHash of this transaction, run as a verified call from this account at block " +
            "8,412,307, goes through.",
        simpleSimulation = "Checked on chain: the Safe's approveHash of this transaction should go through.",
        confirmations = null,
    ),
)

/** Every preview above, which SimpleSafeTxScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleSafeTxScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SimpleSafeTxOverviewPreview() },
    { SimpleSafeTxOverviewDarkPreview() },
    { SimpleSafeTxReviewPreview() },
    { SimpleSafeTxSwapPreview() },
    { SimpleSafeTxRefusedPreview() },
    { SimpleSafeTxSigningPreview() },
    { SimpleSafeTxApprovalPreview() },
)
