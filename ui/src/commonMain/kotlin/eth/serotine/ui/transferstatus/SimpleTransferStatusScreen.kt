package eth.serotine.ui.transferstatus

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.ui.components.LightClientItem

/**
 * A transfer's status in simple mode (docs/simple-ui.md, S3): [TransferStatusScreen] for the same
 * [state], with no light-client strip and each step in simple mode's words, Sent → Confirmed →
 * Final, without a block; Detailed view's words, the blocks among them, are behind *Details*. The
 * hash, what was sent and Try again are as in Detailed view, and so are a queued transfer's step
 * before Signed, Discard, Sign again or Edit, and Send now ([onDiscard], [onSignAgain], [onConfirm]).
 * [syncLine] is its network's sync line (docs/simple-ui.md, *The sync line*), drawn while it says something.
 */
@Composable
fun SimpleTransferStatusScreen(
    state: TransferStatusUiState,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    syncLine: LightClientItem? = null,
    onDiscard: () -> Unit = {},
    onSignAgain: () -> Unit = {},
    onConfirm: () -> Unit = {},
) = TransferStatusScreen(
    state.simple(),
    lightClient = null,
    onDone,
    onRetry,
    modifier,
    details = state.steps.mapNotNull { step -> step.detail?.let { "${step.label}: $it" } },
    syncLine = syncLine,
    onDiscard = onDiscard,
    onSignAgain = onSignAgain,
    onConfirm = onConfirm,
)

/** The steps [this] draws, in Detailed view's words; none for a state without steps. */
private val TransferStatusUiState.steps: List<StatusStep>
    get() = when (this) {
        is TransferStatusUiState.Shown -> steps
        is TransferStatusUiState.Queued -> steps
        is TransferStatusUiState.Loading, is TransferStatusUiState.Discarded -> emptyList()
    }

/** [this] with each step's simple label and detail in place of Detailed view's. */
private fun TransferStatusUiState.simple(): TransferStatusUiState = when (this) {
    is TransferStatusUiState.Shown -> copy(steps = steps.map(StatusStep::simple))
    is TransferStatusUiState.Queued -> copy(steps = steps.map(StatusStep::simple))
    is TransferStatusUiState.Loading, is TransferStatusUiState.Discarded -> this
}

/** This step in simple mode's words. */
private fun StatusStep.simple(): StatusStep = copy(label = simpleLabel ?: label, detail = simpleDetail ?: detail)
