package eth.serotine.ui.transferstatus

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.ui.components.LightClientItem

/**
 * A transfer's status in simple mode (docs/simple-ui.md, S3): [TransferStatusScreen] for the same
 * [state], with no light-client strip and each step in simple mode's words, Sent → Confirmed →
 * Final, without a block; Detailed view's words, the blocks among them, are behind *Details*. The
 * hash, what was sent and Try again are as in Detailed view.
 * [syncLine] is its network's sync line (docs/simple-ui.md, *The sync line*), drawn while it says something.
 */
@Composable
fun SimpleTransferStatusScreen(
    state: TransferStatusUiState,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    syncLine: LightClientItem? = null,
) = TransferStatusScreen(
    state.simple(),
    lightClient = null,
    onDone,
    onRetry,
    modifier,
    details = (state as? TransferStatusUiState.Shown)?.steps.orEmpty()
        .mapNotNull { step -> step.detail?.let { "${step.label}: $it" } },
    syncLine = syncLine,
)

/** [this] with each step's simple label and detail in place of Detailed view's. */
private fun TransferStatusUiState.simple(): TransferStatusUiState = when (this) {
    is TransferStatusUiState.Shown -> copy(
        steps = steps.map {
            it.copy(label = it.simpleLabel ?: it.label, detail = it.simpleDetail ?: it.detail)
        },
    )
    is TransferStatusUiState.Loading -> this
}
