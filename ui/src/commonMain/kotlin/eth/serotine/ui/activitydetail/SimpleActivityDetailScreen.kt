package eth.serotine.ui.activitydetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.ui.components.LightClientItem

/**
 * The detail of an Activity row in simple mode (docs/simple-ui.md, H5): [ActivityDetailScreen] for
 * the same [state], with no light-client strip, how it ended in words ("Confirmed"), and its block
 * behind *Details*. Every address and hash it concerns, Copy, Status and Add token are as in
 * Detailed view.
 * [syncLine] is its network's sync line (docs/simple-ui.md, *The sync line*), drawn while it says something.
 */
@Composable
fun SimpleActivityDetailScreen(
    state: ActivityDetailUiState,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onStatus: (String) -> Unit,
    onAddToken: (String) -> Unit,
    modifier: Modifier = Modifier,
    onToggleAmounts: () -> Unit = {},
    syncLine: LightClientItem? = null,
) {
    val shown = state as? ActivityDetailUiState.Shown
    ActivityDetailScreen(
        state = shown?.copy(
            status = shown.simpleStatus ?: shown.status,
            fields = shown.fields.filterNot { it.kind == DetailKind.BLOCK },
        ) ?: state,
        lightClient = null,
        onBack = onBack,
        onCopy = onCopy,
        onStatus = onStatus,
        onAddToken = onAddToken,
        modifier = modifier,
        onToggleAmounts = onToggleAmounts,
        details = shown?.fields.orEmpty().filter { it.kind == DetailKind.BLOCK },
        syncLine = syncLine,
    )
}
