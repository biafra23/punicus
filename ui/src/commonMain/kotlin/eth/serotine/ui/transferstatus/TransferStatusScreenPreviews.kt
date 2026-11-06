package eth.serotine.ui.transferstatus

import androidx.compose.runtime.Composable
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.catchingUpStripSample
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The transfer status screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. TransferStatusScreenPreviewsTest renders each one in transferStatusScreenPreviews,
// at the end. The addresses are EIP-55's own examples; the hash, amounts, times and blocks are
// made up.

private const val HASH = "0x5e1f3c0a9b7d2e4f6a8c0b1d3e5f7a9c2b4d6e8f0a1c3e5b7d9f2a4c6e8b0d1f"

private val sent = SentSummary(
    amount = "0.1 ETH",
    symbol = "ETH",
    icon = "eth",
    to = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
    from = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
)

private val signed = StatusStep("Signed", StepState.DONE, "On this device")
private val broadcast = StatusStep("Broadcast", StepState.DONE, "Handed to the network's peers, Oct 17, 2026, 3:04 PM")
private val waitingFinal = StatusStep("Finalized", StepState.WAITING, "Once the beacon chain finalizes its block")

private val pending = TransferStatusUiState.Shown(
    network = "Sepolia",
    hash = HASH,
    sent = sent,
    steps = listOf(
        signed,
        broadcast,
        StatusStep("Included", StepState.WAITING, "Waiting for a block to include it"),
        waitingFinal,
    ),
)

private val included = pending.copy(
    steps = listOf(
        signed,
        broadcast,
        StatusStep("Included", StepState.DONE, "In block 8,412,311, its receipt verified"),
        waitingFinal,
    ),
)

private val maybeSentStep = StatusStep(
    "Broadcast",
    StepState.UNCONFIRMED,
    "No peer confirmed it, Oct 17, 2026, 3:04 PM: it may be on its way, or may not have reached the network",
)

private val maybeSent = pending.copy(
    steps = listOf(
        signed,
        maybeSentStep,
        StatusStep("Included", StepState.WAITING, "Waiting for a block to include it"),
        waitingFinal,
    ),
    notSent = NotSentItem(
        "No peer answered when this transfer was broadcast, so it may not have reached the network, or it may " +
            "be on its way. Try again sends the same signed transfer; nothing new is sent from this account " +
            "meanwhile, since a new one could pay twice.",
        canRetry = true,
    ),
)

// A transfer queued in lenient mode (docs/lenient-mode.md, *The outbox*): its step before Signed, in
// each state it draws differently; the simple previews draw the same samples.

private const val QUEUED_ID = "0x9a3c5e7f1b2d4f6a8c0e2b4d6f8a1c3e5b7d9f0a2c4e6b8d1f3a5c7e9b0d2f4a"

internal val queuedSample = TransferStatusUiState.Queued(
    network = "Sepolia",
    id = QUEUED_ID,
    sent = sent,
    steps = listOf(
        StatusStep("Queued", StepState.WAITING, "Waits for Sepolia to catch up. Keep Serotine open to send it sooner."),
        StatusStep("Signed", StepState.DONE, "On this device, Nov 7, 2026, 9:12 AM"),
        StatusStep(
            "Broadcast",
            StepState.WAITING,
            "Once it checks out at a fresh verified head",
            simpleLabel = "Sent",
            simpleDetail = "Once it checks out on chain",
        ),
    ),
    again = SendAgain(
        chainId = 11_155_111,
        from = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed",
        asset = null,
        to = "0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359",
        amount = "0.1",
        fee = FeeLevel.NORMAL,
    ),
    againLabel = "Edit",
    againNote = "Edit discards it and opens Send with it filled in. Discard deletes its signature: nothing is sent.",
)

internal val queuedSendingSample = queuedSample.copy(
    steps = listOf(StatusStep("Sending…", StepState.WAITING, "It's being checked and sent.")) +
        queuedSample.steps.drop(1),
    busy = "It's being checked and sent.",
)

internal val queuedPastItsHourSample = queuedSample.copy(
    steps = listOf(StatusStep("Queued", StepState.UNKNOWN, "Not sent by itself after an hour: it waits for you.")) +
        queuedSample.steps.drop(1),
    question = "Still send this? You signed it 2 h ago.",
)

internal val queuedConflictSample = queuedSample.copy(
    steps = listOf(
        StatusStep(
            "Needs your signature again",
            StepState.FAILED,
            "Fees rose since you signed: the most it can cost is now 0.0031 ETH. Nothing was sent, and its " +
                "signature was deleted.",
        ),
        StatusStep("Signed", StepState.UNKNOWN, "On this device, Nov 7, 2026, 9:12 AM: deleted since"),
        StatusStep("Broadcast", StepState.UNKNOWN, "Not sent", simpleLabel = "Sent", simpleDetail = "Not sent"),
    ),
    againLabel = "Sign again",
    againNote = "Sign again discards it and opens Send with it filled in, to be reviewed and signed anew.",
)

internal val discardedSample =
    TransferStatusUiState.Discarded("Sepolia", "Discarded: its signature was deleted, and nothing was sent.")

@Preview
@Composable
private fun TransferStatusQueuedPreview() = TransferStatusPreview(queuedSample, lightClient = catchingUpStripSample)

@Preview
@Composable
private fun TransferStatusQueuedDarkPreview() =
    TransferStatusPreview(queuedSample, darkTheme = true, lightClient = catchingUpStripSample)

@Preview
@Composable
private fun TransferStatusQueuedSendingPreview() = TransferStatusPreview(queuedSendingSample)

@Preview
@Composable
private fun TransferStatusQueuedPastItsHourPreview() = TransferStatusPreview(queuedPastItsHourSample)

@Preview
@Composable
private fun TransferStatusQueuedConflictPreview() = TransferStatusPreview(queuedConflictSample)

@Preview
@Composable
private fun TransferStatusDiscardedPreview() = TransferStatusPreview(discardedSample)

@Composable
private fun TransferStatusPreview(
    state: TransferStatusUiState,
    darkTheme: Boolean = false,
    lightClient: LightClientItem = verifiedStripSample,
) {
    SerotineTheme(darkTheme = darkTheme) {
        TransferStatusScreen(state, lightClient, onDone = {}, onRetry = {})
    }
}

@Preview
@Composable
private fun TransferStatusPendingPreview() = TransferStatusPreview(pending)

@Preview
@Composable
private fun TransferStatusPendingDarkPreview() = TransferStatusPreview(pending, darkTheme = true)

@Preview
@Composable
private fun TransferStatusIncludedPreview() = TransferStatusPreview(included)

@Preview
@Composable
private fun TransferStatusFinalizedPreview() = TransferStatusPreview(
    included.copy(
        sent = sent.copy(amount = "40 USDC", symbol = "USDC", icon = "usdc"),
        steps = listOf(
            signed,
            broadcast,
            StatusStep("Included", StepState.DONE, "In block 8,412,311, its receipt verified"),
            StatusStep("Finalized", StepState.DONE, "Block 8,412,311 is finalized"),
        ),
        finished = true,
    ),
)

@Preview
@Composable
private fun TransferStatusFailedPreview() = TransferStatusPreview(
    pending.copy(
        steps = listOf(
            signed,
            broadcast,
            StatusStep("Included", StepState.FAILED, "In block 8,412,311, its receipt verified: it reverted"),
            waitingFinal,
        ),
        note = "It failed: the transfer reverted in its block, so nothing was moved. Its fee is paid all the same.",
    ),
)

@Preview
@Composable
private fun TransferStatusUnavailablePreview() = TransferStatusPreview(
    pending.copy(
        steps = listOf(
            signed,
            broadcast,
            StatusStep("Included", StepState.UNKNOWN, "Cannot be verified right now"),
            StatusStep("Finalized", StepState.UNKNOWN, "Cannot be verified right now"),
        ),
        note = "Where it stands cannot be verified right now: the light client refuses to sync.",
    ),
)

@Preview
@Composable
private fun TransferStatusNoRecordPreview() = TransferStatusPreview(
    included.copy(sent = null, steps = listOf(signed.copy(detail = null)) + included.steps.drop(1)),
)

@Preview
@Composable
private fun TransferStatusLoadingPreview() = TransferStatusPreview(TransferStatusUiState.Loading("Sepolia"))

@Preview
@Composable
private fun TransferStatusSafeExecutionPreview() = TransferStatusPreview(
    pending.copy(
        sent = null,
        safeTx = SafeTxSummary(
            title = "Execution of a Safe transaction",
            safe = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
            nonce = "Safe nonce 12",
            from = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        ),
    ),
)

private val deployment = DeploymentSummary(
    title = "Deployment of a Safe",
    safe = "0x2Ca0226086a7C24808217F7F8707827608B17Dc5",
    signers = "2/3",
    shape = "2 of 3 owners · Safe 1.4.1",
    from = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
    listing = "Added to your wallet once a block has it and the light client verifies the Safe there, whether " +
        "this screen is open or not.",
)

@Preview
@Composable
private fun TransferStatusSafeDeploymentPreview() =
    TransferStatusPreview(pending.copy(sent = null, deployment = deployment))

@Preview
@Composable
private fun TransferStatusSafeListedPreview() = TransferStatusPreview(
    included.copy(sent = null, deployment = deployment.copy(listing = "In your wallet as Family vault.")),
)

@Preview
@Composable
private fun TransferStatusSafeListedDarkPreview() = TransferStatusPreview(
    included.copy(sent = null, deployment = deployment.copy(listing = "In your wallet as Family vault.")),
    darkTheme = true,
)

@Preview
@Composable
private fun TransferStatusSafeNotListedPreview() = TransferStatusPreview(
    pending.copy(
        sent = null,
        steps = listOf(
            signed,
            broadcast,
            StatusStep("Included", StepState.FAILED, "In block 8,412,311, its receipt verified: it reverted"),
            waitingFinal,
        ),
        note = "It failed: this transaction reverted in its block and created no Safe itself. Its fee is paid all " +
            "the same.",
        deployment = deployment.copy(
            listing = "This transaction created no Safe, and none is at its address: nothing is added to your wallet.",
            listingFailed = true,
        ),
    ),
)

@Preview
@Composable
private fun TransferStatusMaybeSentPreview() = TransferStatusPreview(maybeSent)

@Preview
@Composable
private fun TransferStatusRetryingPreview() = TransferStatusPreview(maybeSent.copy(retrying = true))

@Preview
@Composable
private fun TransferStatusTurnedAwayPreview() = TransferStatusPreview(
    maybeSent.copy(
        notSent = NotSentItem(
            "Turned away this time (the peer said: nonce too low). That says nothing sure of the first broadcast, " +
                "which may be on its way or in a block past the light client's head, so the signed transfer is " +
                "kept, and nothing new is sent from this account until a block has it or three hours have " +
                "passed. Try again sends the same signed transfer.",
            canRetry = true,
        ),
    ),
)

private val swap = SwapSummary(
    title = "Swap of 100 USDC for at least 4.28845 LINK on Uniswap",
    sold = "100 USDC",
    soldSymbol = "USDC",
    soldIcon = "usdc",
    bought = "at least 4.28845 LINK",
    boughtSymbol = "LINK",
    boughtIcon = "link",
    router = "0x3bFA4769FB09eefC5a80d6E87c3B9C650f7Ae48E",
    from = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
)

@Preview
@Composable
private fun TransferStatusSwapPreview() = TransferStatusPreview(pending.copy(sent = null, swap = swap))

@Preview
@Composable
private fun TransferStatusSwapFailedPreview() = TransferStatusPreview(
    pending.copy(
        sent = null,
        swap = swap,
        steps = listOf(
            signed,
            broadcast,
            StatusStep("Included", StepState.FAILED, "In block 8,412,311, its receipt verified: it reverted"),
            waitingFinal,
        ),
        note = "It failed: the swap reverted in its block, so nothing was swapped: the price moved past its " +
            "minimum, or its deadline passed. Its fee is paid all the same.",
    ),
)

@Preview
@Composable
private fun TransferStatusApprovalPreview() = TransferStatusPreview(
    included.copy(
        sent = null,
        approval = ApprovalSummary(
            title = "Approval for a swap",
            amount = "100 USDC",
            symbol = "USDC",
            icon = "usdc",
            spenderLabel = "May be taken by the swap's spender",
            spender = "0x3bFA4769FB09eefC5a80d6E87c3B9C650f7Ae48E",
            from = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        ),
    ),
)

@Preview
@Composable
private fun TransferStatusAllowanceResetPreview() = TransferStatusPreview(
    pending.copy(
        sent = null,
        approval = ApprovalSummary(
            title = "Allowance reset for a swap",
            amount = "0 USDT",
            symbol = "USDT",
            icon = null,
            spenderLabel = "The swap's spender may take none, until the whole amount is approved",
            spender = "0x3bFA4769FB09eefC5a80d6E87c3B9C650f7Ae48E",
            from = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        ),
    ),
)

@Preview
@Composable
private fun TransferStatusSwapDarkPreview() =
    TransferStatusPreview(pending.copy(sent = null, swap = swap), darkTheme = true)

@Preview
@Composable
private fun TransferStatusSwapReceivedPreview() = TransferStatusPreview(
    included.copy(
        sent = null,
        swap = swap.copy(
            title = "Swap of 100 USDC for 4.30912 LINK on Uniswap",
            bought = "4.30912 LINK",
            received = "Received, as the verified receipt's logs say; it was signed for at least 4.28845 LINK.",
        ),
    ),
)

@Preview
@Composable
private fun TransferStatusMaybeSentNonceUsedPreview() = TransferStatusPreview(
    pending.copy(
        steps = listOf(
            signed,
            StatusStep("Broadcast", StepState.UNCONFIRMED, "No peer confirmed it, Oct 17, 2026, 3:04 PM"),
            StatusStep(
                "Included",
                StepState.UNKNOWN,
                "Its nonce was used on chain while the app was not watching: by this transaction, unless " +
                    "another one took its place, such as one sent from this account elsewhere",
            ),
            StatusStep("Finalized", StepState.UNKNOWN, "Not known on this device"),
        ),
    ),
)

@Preview
@Composable
private fun TransferStatusNonceUsedPreview() = TransferStatusPreview(
    pending.copy(
        steps = listOf(
            signed,
            broadcast,
            StatusStep(
                "Included",
                StepState.UNKNOWN,
                "Its nonce was used on chain while the app was not watching: by this transaction, unless " +
                    "another one took its place, such as one sent from this account elsewhere",
            ),
            StatusStep("Finalized", StepState.UNKNOWN, "Not known on this device"),
        ),
    ),
)

/** Every preview above, which TransferStatusScreenPreviewsTest renders: a new preview goes in here too. */
@Preview
@Composable
private fun TransferStatusCowCancellationPreview() = TransferStatusPreview(
    pending.copy(
        sent = null,
        cancellation = CancellationSummary(
            title = "Cancellation of a CoW order on chain",
            order = "0x" + "4c".repeat(32) + "5aaeb6053f3e94c9b9a09f33669435e7ef1beaed" + "6ad9e7f4",
            settlement = "0x9008D19f58AAbD9eD0D60971565AA8510560ab41",
            from = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        ),
    ),
)

@Preview
@Composable
private fun TransferStatusWrapPreview() = TransferStatusPreview(
    pending.copy(
        sent = null,
        swap = SwapSummary(
            title = "Wrap of 0.1 ETH into WETH",
            sold = "0.1 ETH",
            soldSymbol = "ETH",
            soldIcon = "eth",
            bought = "0.1 WETH",
            boughtSymbol = "WETH",
            boughtIcon = "weth",
            router = "0x8617E340B3D01FA5F11F306F4090FD50E238070D",
            from = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            through = "Through WETH9, one for one",
        ),
    ),
)

internal val transferStatusScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("pending") { TransferStatusPendingPreview() },
    ScreenPreview("pending-dark") { TransferStatusPendingDarkPreview() },
    ScreenPreview("included") { TransferStatusIncludedPreview() },
    ScreenPreview("finalized") { TransferStatusFinalizedPreview() },
    ScreenPreview("failed") { TransferStatusFailedPreview() },
    ScreenPreview("unavailable") { TransferStatusUnavailablePreview() },
    ScreenPreview("no-record") { TransferStatusNoRecordPreview() },
    ScreenPreview("safe-execution") { TransferStatusSafeExecutionPreview() },
    ScreenPreview("safe-deployment") { TransferStatusSafeDeploymentPreview() },
    ScreenPreview("safe-listed") { TransferStatusSafeListedPreview() },
    ScreenPreview("safe-listed-dark") { TransferStatusSafeListedDarkPreview() },
    ScreenPreview("safe-not-listed") { TransferStatusSafeNotListedPreview() },
    ScreenPreview("maybe-sent") { TransferStatusMaybeSentPreview() },
    ScreenPreview("retrying") { TransferStatusRetryingPreview() },
    ScreenPreview("turned-away") { TransferStatusTurnedAwayPreview() },
    ScreenPreview("loading") { TransferStatusLoadingPreview() },
    ScreenPreview("swap") { TransferStatusSwapPreview() },
    ScreenPreview("swap-failed") { TransferStatusSwapFailedPreview() },
    ScreenPreview("approval") { TransferStatusApprovalPreview() },
    ScreenPreview("allowance-reset") { TransferStatusAllowanceResetPreview() },
    ScreenPreview("swap-dark") { TransferStatusSwapDarkPreview() },
    ScreenPreview("swap-received") { TransferStatusSwapReceivedPreview() },
    ScreenPreview("nonce-used") { TransferStatusNonceUsedPreview() },
    ScreenPreview("maybe-sent-nonce-used") { TransferStatusMaybeSentNonceUsedPreview() },
    ScreenPreview("cow-cancellation") { TransferStatusCowCancellationPreview() },
    ScreenPreview("wrap") { TransferStatusWrapPreview() },
    ScreenPreview("queued") { TransferStatusQueuedPreview() },
    ScreenPreview("queued-dark") { TransferStatusQueuedDarkPreview() },
    ScreenPreview("queued-sending") { TransferStatusQueuedSendingPreview() },
    ScreenPreview("queued-past-its-hour") { TransferStatusQueuedPastItsHourPreview() },
    ScreenPreview("queued-conflict") { TransferStatusQueuedConflictPreview() },
    ScreenPreview("discarded") { TransferStatusDiscardedPreview() },
)
