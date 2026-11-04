package eth.serotine.ui.safetx

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.ActionAddress
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.FALLBACK_HANDLER_REMOVED
import eth.serotine.ui.components.GasTermsItem
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.MODULE_ENABLED_REFUSAL
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.SafeSwapItem
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.ShareItem
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Safe transaction screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. SafeTxScreenPreviewsTest renders each one in safeTxScreenPreviews, at the end. The
// addresses are EIP-55's own examples; the hash, request text, amounts, blocks and the password
// are made up. A preview has no window to keep out of screen capture, so a step's password field
// shows the line saying so, which a device that blocks capture does not.

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

private val owners = OwnersItem.Shown(
    rows = listOf(
        OwnerStateRow(DAILY, "Daily", heldHere = true, OwnerState.MISSING, "Not yet"),
        OwnerStateRow(SAVINGS, "Savings", heldHere = false, OwnerState.SIGNED, "Signed"),
        OwnerStateRow(
            FRIEND,
            null,
            heldHere = false,
            OwnerState.UNKNOWN,
            "No signature; whether it approved on chain cannot be verified right now",
        ),
    ),
    threshold = "2 of 3 owners must confirm",
    confirmations = "1 of 2 confirmations",
    verifiedAt = "Owners verified at block 8,412,307",
)

private val overview = SafeTxUiState.Overview(
    network = "Sepolia",
    safe = safe,
    action = usdcTransfer,
    nonce = "Safe nonce 12 · next to run",
    hash = HASH,
    owners = owners,
    canSign = true,
    canApprove = true,
    canExecute = true,
)

private val share = ShareItem(
    text = "{\"v\":1,\"chainId\":11155111,\"safe\":\"0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb\",\"tx\":{…}}",
    qr = true,
    note = "The raw transaction and 1 signature: the other device decodes it and checks it against the chain itself.",
)

private val signing = SafeTxUiState.Signing(
    network = "Sepolia",
    safe = safe,
    action = usdcTransfer,
    nonce = "Safe nonce 12 · next to run",
    hash = HASH,
    owners = listOf(SignerChoice("Daily", DAILY)),
    owner = DAILY,
    unlock = UnlockStep(),
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
    feeDetail = "Normal fee: 80,000 gas × at most 22 gwei, from the verified fee history up to block 8,412,306. " +
        "Only what the block charges is paid.",
    balance = BalanceChange(
        "ETH balance of Daily, which pays the fee",
        "1.25 ETH",
        "at least 1.24824 ETH",
        "verified at block 8,412,307",
    ),
    simulation = "The whole execution — the Safe's checks of the confirmations and the nonce, its guard if it " +
        "has one, and the call itself — run as a verified call from this account at block 8,412,307, goes through.",
    confirmations = "Executed with: Daily, by sending the execution; Savings's signature",
    unlock = UnlockStep(),
)

private val approval = review.copy(
    kind = ReviewKind.APPROVAL,
    maxFee = "0.00105 ETH",
    feeDetail = "Normal fee: 47,500 gas × at most 22 gwei, from the verified fee history up to block 8,412,306. " +
        "Only what the block charges is paid.",
    balance = review.balance.copy(after = "at least 1.24895 ETH"),
    simulation = "The Safe's approveHash of this transaction, run as a verified call from this account at block " +
        "8,412,307, goes through.",
    confirmations = null,
)

@Composable
private fun SafeTxPreview(
    state: SafeTxUiState,
    darkTheme: Boolean = false,
    lightClient: LightClientItem = verifiedStripSample,
) {
    SerotineTheme(darkTheme = darkTheme) {
        SafeTxScreen(
            state = state,
            lightClient = lightClient,
            actions = SafeTxActions(),
            onBack = {},
            onCopy = {},
            onShareText = {},
        )
    }
}

@Preview
@Composable
private fun SafeTxOverviewPreview() = SafeTxPreview(overview)

@Preview
@Composable
private fun SafeTxOverviewDarkPreview() = SafeTxPreview(overview, darkTheme = true)

@Preview
@Composable
private fun SafeTxSharePreview() = SafeTxPreview(overview.copy(share = share))

@Preview
@Composable
private fun SafeTxShareAsTextPreview() = SafeTxPreview(
    overview.copy(
        share = share.copy(
            qr = false,
            note = "Too large for one QR code: share it as text. The raw transaction and 3 signatures: the other " +
                "device decodes it and checks it against the chain itself.",
        ),
    ),
)

@Preview
@Composable
private fun SafeTxSignedPreview() = SafeTxPreview(
    overview.copy(
        owners = owners.copy(
            rows = owners.rows.mapIndexed { i, row ->
                if (i == 0) row.copy(state = OwnerState.SIGNED, detail = "Signed") else row
            },
            confirmations = "2 of 2 confirmations",
        ),
        canSign = false,
        canApprove = false,
        message = "Signed as Daily. Share the request with the other owners, or execute it once the threshold is met.",
    ),
)

@Preview
@Composable
private fun SafeTxNotNextPreview() = SafeTxPreview(
    overview.copy(
        nonce = "Safe nonce 14 · 2 transactions run before it",
        canExecute = false,
        executeNote = "Executed only once the 2 transactions before it have run: only the Safe's next transaction " +
            "can be executed.",
    ),
)

@Preview
@Composable
private fun SafeTxDelegateCallPreview() = SafeTxPreview(
    overview.copy(
        action = SafeTxActionItem(
            title = "Delegate call",
            addresses = listOf(ActionAddress("Code run as the Safe", FRIEND)),
            notes = listOf("Function 0x8d80ff0a, 356 bytes of call data"),
            warning = "A delegate call: the Safe would run this contract's code as itself, with its storage, owners " +
                "and funds, and the code can change any of them. Serotine shows it and decodes it no further, and " +
                "does not sign, approve or execute it.",
        ),
        refusal = "A delegate call: Serotine does not sign, approve or execute one yet. " +
            "Batches through the pinned MultiSendCallOnly come later.",
        canSign = false,
        canApprove = false,
        canExecute = false,
    ),
)

@Preview
@Composable
private fun SafeTxUnpinnedFallbackHandlerPreview() = SafeTxPreview(
    overview.copy(
        action = SafeTxActionItem(
            title = "Set the fallback handler",
            addresses = listOf(ActionAddress("Fallback handler", FRIEND)),
            notes = listOf("It answers the calls the Safe has no function for."),
            warning = "The fallback handler answers the signature checks (EIP-1271) other contracts make for this " +
                "Safe: one that accepts every signature lets anyone spend what the Safe has approved (Permit2, " +
                "permits, orders), without its owners.",
        ),
        refusal = "It sets a fallback handler Serotine does not pin, $FRIEND: the handler answers the signature " +
            "checks other contracts make for the Safe, so only the canonical CompatibilityFallbackHandler 1.4.1, " +
            "or none, is signed, approved or executed here.",
        canSign = false,
        canApprove = false,
        canExecute = false,
    ),
)

@Preview
@Composable
private fun SafeTxFallbackHandlerRemovedPreview() = SafeTxPreview(
    overview.copy(
        action = SafeTxActionItem(title = "Remove the fallback handler", warning = FALLBACK_HANDLER_REMOVED),
    ),
)

@Preview
@Composable
private fun SafeTxModuleEnabledPreview() = SafeTxPreview(
    overview.copy(
        action = SafeTxActionItem(
            title = "Enable a module",
            addresses = listOf(ActionAddress("Module", FRIEND)),
            warning = "A module can move the Safe's funds without the owners' signatures.",
        ),
        refusal = MODULE_ENABLED_REFUSAL,
        canSign = false,
        canApprove = false,
        canExecute = false,
    ),
)

/** The dialog a tap on a greyed-out Execute opens (owner ruling, 2026-10-18, #55). */
@Preview
@Composable
private fun SafeTxExecuteReasonPreview() = SerotineTheme {
    UnavailableDialog(
        UnavailableNotice(
            "Execute",
            "Executed only once the 2 transactions before it have run: only the Safe's next transaction can be " +
                "executed.",
        ),
        onDismiss = {},
    )
}

@Preview
@Composable
private fun SafeTxGasTermsPreview() = SafeTxPreview(
    overview.copy(
        gasTerms = GasTermsItem(
            lines = listOf(
                "safeTxGas: 50,000",
                "baseGas: 0",
                "gasPrice: 1,000,000,000 wei",
                "A refund is paid in ETH",
                "A refund goes to the refund receiver",
            ),
            addresses = listOf(ActionAddress("Refund receiver", FRIEND)),
        ),
        refusal = "It carries gas or refund terms: Serotine does not sign, approve or execute those.",
        canSign = false,
        canApprove = false,
        canExecute = false,
    ),
)

@Preview
@Composable
private fun SafeTxOwnersCheckingPreview() = SafeTxPreview(
    overview.copy(owners = OwnersItem.Checking, safe = safe.copy(threshold = null), canSign = false),
)

@Preview
@Composable
private fun SafeTxOwnersUnavailablePreview() = SafeTxPreview(
    overview.copy(
        owners = OwnersItem.Unavailable("The light client is not synced yet: try again in a moment."),
        canSign = false,
        canApprove = false,
        canExecute = false,
    ),
)

@Preview
@Composable
private fun SafeTxQuotingPreview() = SafeTxPreview(overview.copy(quoting = true))

@Preview
@Composable
private fun SafeTxSigningPreview() = SafeTxPreview(signing)

@Preview
@Composable
private fun SafeTxSigningDarkPreview() = SafeTxPreview(signing, darkTheme = true)

@Preview
@Composable
private fun SafeTxSigningWrongPasswordPreview() = SafeTxPreview(
    signing.copy(
        unlock = UnlockStep(biometrics = true, error = "That is not the wallet password. Nothing was signed."),
    ),
)

@Preview
@Composable
private fun SafeTxSigningRunsPreview() = SafeTxPreview(signing.copy(signing = true))

@Preview
@Composable
private fun SafeTxSigningCheckingPreview() = SafeTxPreview(signing.copy(signing = true, checking = true))

@Preview
@Composable
private fun SafeTxReviewExecutionPreview() = SafeTxPreview(
    review.copy(unlock = UnlockStep(WalletPassword("made-up preview password"))),
)

@Preview
@Composable
private fun SafeTxReviewExecutionDarkPreview() = SafeTxPreview(review, darkTheme = true)

@Preview
@Composable
private fun SafeTxReviewApprovalPreview() = SafeTxPreview(approval.copy(unlock = UnlockStep(biometrics = true)))

@Preview
@Composable
private fun SafeTxReviewRefreshingPreview() = SafeTxPreview(approval.copy(refreshing = true))

@Preview
@Composable
private fun SafeTxReviewSigningPreview() = SafeTxPreview(review.copy(signing = true))

@Preview
@Composable
private fun SafeTxReviewNotSentPreview() = SafeTxPreview(
    review.copy(
        notSent = NotSentItem(
            "Signed, but not sent: no peer took it right now (no peers). Try again sends the same signed " +
                "execution, not a new one.",
            canRetry = true,
        ),
    ),
)

@Preview
@Composable
private fun SafeTxLoadingPreview() = SafeTxPreview(SafeTxUiState.Loading("Sepolia"))

@Preview
@Composable
private fun SafeTxNotQueuedPreview() = SafeTxPreview(
    SafeTxUiState.NotQueued(
        "Sepolia",
        "No longer pending: the Safe's nonce has passed it, so it ran, or another transaction used its nonce.",
    ),
)

private const val ROUTER = "0x27b1fdb04752bbc536007a920d24acb045561c26"

private val swapAction = SafeTxActionItem(
    title = "Swap 100 USDC for at least 0.04 ETH",
    addresses = listOf(ActionAddress("Router", ROUTER), ActionAddress("Token sold", USDC)),
    notes = listOf(
        "Through one pool at 0.05 %.",
        "Paid to the Safe.",
        "Whether the router is Uniswap's is checked against the chain once the transaction is opened: until " +
            "then, what it does with the call is up to its code.",
    ),
    icon = "usdc",
    symbol = "USDC",
)

private val swapNow = SafeSwapItem(
    listOf(
        "Through Uniswap's SwapRouter02, its code proven by the light client.",
        "Returns 0.0402 ETH now, by a verified QuoterV2 quote at block 8,412,307: it meets its minimum.",
        "Refused by the router after 20 Oct 2026, 14:32.",
    ),
)

@Preview
@Composable
private fun SafeTxSwapPreview() = SafeTxPreview(overview.copy(action = swapAction, swap = swapNow))

@Preview
@Composable
private fun SafeTxSwapMinimumBehindPreview() = SafeTxPreview(
    review.copy(
        action = swapAction,
        swap = swapNow.copy(
            lines = listOf(
                swapNow.lines[0],
                "Returns 0.05 ETH now, by a verified QuoterV2 quote at block 8,412,307: it meets its minimum.",
                swapNow.lines[2],
            ),
            warning = "Its minimum has fallen behind the market: it returns 0.01 ETH more than its minimum now, " +
                "more than the default 0.5 % tolerance would leave, and a sandwich could take all of it. Propose " +
                "it again with a fresh minimum, or execute it as signed.",
            proposeAgain = true,
        ),
    ),
)

@Preview
@Composable
private fun SafeTxSwapExpiredPreview() = SafeTxPreview(
    overview.copy(
        action = swapAction,
        canExecute = false,
        executeNote = "Its deadline has passed: the router refuses it, so it can never run as signed.",
        swap = swapNow.copy(
            warning = "Its deadline has passed: the router refuses it, so it can never run as signed. Replace it " +
                "at its nonce: propose the swap again, or reject it.",
            proposeAgain = true,
        ),
    ),
)

@Preview
@Composable
private fun SafeTxSwapProposeAgainGreyedPreview() = SafeTxPreview(
    overview.copy(
        action = swapAction,
        canExecute = false,
        executeNote = "Its deadline has passed: the router refuses it, so it can never run as signed.",
        swap = swapNow.copy(
            warning = "Its deadline has passed: the router refuses it, so it can never run as signed. Replace it " +
                "at its nonce: propose the swap again, or reject it.",
            proposeAgain = true,
            proposeAgainReason = "No owner of this Safe has its key on this device, so the swap cannot be " +
                "proposed again from here. Import an owner's recovery phrase to propose and sign its transactions.",
        ),
    ),
)

@Preview
@Composable
private fun SafeTxSwapNotUniswapPreview() = SafeTxPreview(
    overview.copy(
        action = swapAction,
        swap = SafeSwapItem(
            listOf("Signed with a deadline of 20 Oct 2026, 14:32."),
            warning = "The router is not Uniswap's pinned SwapRouter02: what it does with the Safe's tokens is " +
                "not known here.",
        ),
    ),
)

@Preview
@Composable
private fun SafeTxSwapApprovalPreview() = SafeTxPreview(
    overview.copy(
        action = SafeTxActionItem(
            title = "Allow a contract to take 100 USDC",
            addresses = listOf(ActionAddress("Spender", ROUTER), ActionAddress("Token contract", USDC)),
            notes = listOf(
                "It moves nothing on its own: the spender may take up to this much of the Safe's tokens later.",
            ),
            icon = "usdc",
            symbol = "USDC",
        ),
        swap = SafeSwapItem(listOf("The spender is Uniswap's SwapRouter02, its code proven by the light client.")),
    ),
)

/** Every preview above, which SafeTxScreenPreviewsTest renders: a new preview goes in here too. */
internal val safeTxScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SafeTxOverviewPreview() },
    { SafeTxOverviewDarkPreview() },
    { SafeTxSharePreview() },
    { SafeTxShareAsTextPreview() },
    { SafeTxSignedPreview() },
    { SafeTxNotNextPreview() },
    { SafeTxDelegateCallPreview() },
    { SafeTxUnpinnedFallbackHandlerPreview() },
    { SafeTxModuleEnabledPreview() },
    { SafeTxExecuteReasonPreview() },
    { SafeTxGasTermsPreview() },
    { SafeTxOwnersCheckingPreview() },
    { SafeTxOwnersUnavailablePreview() },
    { SafeTxQuotingPreview() },
    { SafeTxSigningPreview() },
    { SafeTxSigningDarkPreview() },
    { SafeTxSigningWrongPasswordPreview() },
    { SafeTxSigningRunsPreview() },
    { SafeTxSigningCheckingPreview() },
    { SafeTxReviewExecutionPreview() },
    { SafeTxReviewExecutionDarkPreview() },
    { SafeTxReviewApprovalPreview() },
    { SafeTxReviewRefreshingPreview() },
    { SafeTxReviewSigningPreview() },
    { SafeTxReviewNotSentPreview() },
    { SafeTxLoadingPreview() },
    { SafeTxNotQueuedPreview() },
    { SafeTxSwapPreview() },
    { SafeTxSwapMinimumBehindPreview() },
    { SafeTxSwapExpiredPreview() },
    { SafeTxSwapNotUniswapPreview() },
    { SafeTxSwapApprovalPreview() },
    { SafeTxSwapProposeAgainGreyedPreview() },
    { SafeTxFallbackHandlerRemovedPreview() },
)
