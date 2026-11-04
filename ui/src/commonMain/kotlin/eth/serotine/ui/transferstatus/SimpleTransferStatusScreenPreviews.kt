package eth.serotine.ui.transferstatus

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.NotSentItem
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

/** Every preview above, which SimpleTransferStatusScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleTransferStatusScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SimpleTransferConfirmedPreview() },
    { SimpleTransferConfirmedDarkPreview() },
    { SimpleTransferFinalPreview() },
    { SimpleTransferFailedPreview() },
    { SimpleTransferMaybeSentPreview() },
)
