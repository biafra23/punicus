package eth.serotine.ui.transferstatus

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// A transfer's status in simple mode (docs/simple-ui.md, S3) in Android Studio's preview pane, one
// preview per state it draws differently. Built from display-ready values, never from the ViewModel.
// SimpleTransferStatusScreenPreviewsTest renders each one in simpleTransferStatusScreenPreviews, at
// the end. The addresses are EIP-55's own examples, and the hash and amounts are made up.

private val sent = SentSummary(
    amount = "250 USDC",
    symbol = "USDC",
    icon = "usdc",
    to = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
    from = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
)

private val signed = StatusStep("Signed", StepState.DONE, "On this device")
private val broadcast = StatusStep(
    "Broadcast",
    StepState.DONE,
    "Handed to the network's peers, Oct 27, 2026, 2:02 PM",
    simpleLabel = "Sent",
    simpleDetail = "Oct 27, 2026, 2:02 PM",
)
private val confirmed = StatusStep(
    "Included",
    StepState.DONE,
    "In block 23,614,012, its receipt verified",
    simpleLabel = "Confirmed",
    simpleDetail = "Its receipt checked on chain",
)
private val waitingFinal = StatusStep(
    "Finalized",
    StepState.WAITING,
    "Once the beacon chain finalizes its block",
    simpleLabel = "Final",
    simpleDetail = "Once the network finalizes it, its outcome can't be reversed",
)

private val confirmedState = TransferStatusUiState.Shown(
    network = "Ethereum",
    hash = "0x" + "a1".repeat(32),
    sent = sent,
    steps = listOf(signed, broadcast, confirmed, waitingFinal),
)

@Composable
private fun SimpleTransferStatusPreview(state: TransferStatusUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleTransferStatusScreen(state, onDone = {}, onRetry = {})
    }
}

/** S3: sent and confirmed, final soon, no block named. */
@Preview
@Composable
private fun SimpleTransferConfirmedPreview() = SimpleTransferStatusPreview(confirmedState)

@Preview
@Composable
private fun SimpleTransferConfirmedDarkPreview() = SimpleTransferStatusPreview(confirmedState, darkTheme = true)

/** Final: it can't be reversed. */
@Preview
@Composable
private fun SimpleTransferFinalPreview() = SimpleTransferStatusPreview(
    confirmedState.copy(
        steps = listOf(
            signed,
            broadcast,
            confirmed,
            StatusStep(
                "Finalized",
                StepState.DONE,
                "Block 23,614,012 is finalized",
                simpleLabel = "Final",
                simpleDetail = "Its outcome can't be reversed by the network",
            ),
        ),
        finished = true,
    ),
)

/** Failed: it reverted, its fee paid. */
@Preview
@Composable
private fun SimpleTransferFailedPreview() = SimpleTransferStatusPreview(
    confirmedState.copy(
        steps = listOf(
            signed,
            broadcast,
            StatusStep(
                "Included",
                StepState.FAILED,
                "In block 23,614,012, its receipt verified: it reverted",
                simpleLabel = "Failed",
                simpleDetail = "Its receipt checked on chain: it reverted",
            ),
            waitingFinal,
        ),
        note = "It failed: the transfer reverted in its block, so nothing was moved. Its fee is paid all the same.",
    ),
)

/** Maybe sent: no peer answered its broadcast, and Try again sends the same signed bytes. */
@Preview
@Composable
private fun SimpleTransferMaybeSentPreview() = SimpleTransferStatusPreview(
    confirmedState.copy(
        steps = listOf(
            signed,
            StatusStep(
                "Broadcast",
                StepState.UNCONFIRMED,
                "No peer confirmed it, Oct 27, 2026, 2:02 PM: it may be on its way, or may not have reached " +
                    "the network",
                simpleLabel = "Sent",
                simpleDetail = "No peer answered, Oct 27, 2026, 2:02 PM: it may be on its way, or may not have " +
                    "reached the network",
            ),
            StatusStep(
                "Included",
                StepState.WAITING,
                "Waiting for a block to include it",
                simpleLabel = "Confirmed",
                simpleDetail = "Waiting to be confirmed",
            ),
            waitingFinal,
        ),
        notSent = NotSentItem(
            "No peer answered when this transfer was broadcast, so it may not have reached the network, or it may " +
                "be on its way. Try again sends the same signed transfer.",
            canRetry = true,
        ),
    ),
)

/** A transfer queued in lenient mode: Signed, then its step, in simple mode's words. */
@Preview
@Composable
private fun SimpleTransferQueuedPreview() = SimpleTransferStatusPreview(queuedSample)

@Preview
@Composable
private fun SimpleTransferQueuedDarkPreview() = SimpleTransferStatusPreview(queuedSample, darkTheme = true)

/** Past its hour without a tap: Still send this?, with Send now. */
@Preview
@Composable
private fun SimpleTransferQueuedPastItsHourPreview() = SimpleTransferStatusPreview(queuedPastItsHourSample)

/** A conflict found by the check: its reason, Sign again and Discard. */
@Preview
@Composable
private fun SimpleTransferQueuedConflictPreview() = SimpleTransferStatusPreview(queuedConflictSample)

/** A Safe's approval on chain queued in lenient mode: the Safe it is of, its steps in simple mode's words. */
@Preview
@Composable
private fun SimpleTransferQueuedSafeApprovalPreview() = SimpleTransferStatusPreview(queuedSafeApprovalSample)

/** Its check passed and it is being sent: Discard and Edit greyed out. */
@Preview
@Composable
private fun SimpleTransferQueuedSendingPreview() = SimpleTransferStatusPreview(queuedSendingSample)

/** A Discard tapped, waiting on the outbox's lock. */
@Preview
@Composable
private fun SimpleTransferQueuedDiscardingPreview() = SimpleTransferStatusPreview(queuedSample.copy(discarding = true))

/** Discarded: its signature deleted, and nothing sent. */
@Preview
@Composable
private fun SimpleTransferDiscardedPreview() = SimpleTransferStatusPreview(discardedSample)

/** Every preview above, which SimpleTransferStatusScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleTransferStatusScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("confirmed") { SimpleTransferConfirmedPreview() },
    ScreenPreview("confirmed-dark") { SimpleTransferConfirmedDarkPreview() },
    ScreenPreview("final") { SimpleTransferFinalPreview() },
    ScreenPreview("failed") { SimpleTransferFailedPreview() },
    ScreenPreview("maybe-sent") { SimpleTransferMaybeSentPreview() },
    ScreenPreview("queued") { SimpleTransferQueuedPreview() },
    ScreenPreview("queued-dark") { SimpleTransferQueuedDarkPreview() },
    ScreenPreview("queued-past-its-hour") { SimpleTransferQueuedPastItsHourPreview() },
    ScreenPreview("queued-conflict") { SimpleTransferQueuedConflictPreview() },
    ScreenPreview("queued-safe-approval") { SimpleTransferQueuedSafeApprovalPreview() },
    ScreenPreview("queued-sending") { SimpleTransferQueuedSendingPreview() },
    ScreenPreview("queued-discarding") { SimpleTransferQueuedDiscardingPreview() },
    ScreenPreview("discarded") { SimpleTransferDiscardedPreview() },
)
