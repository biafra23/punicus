package eth.serotine.ui.safepropose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.ui.components.LightClientItem

/**
 * Send from a Safe in simple mode (docs/simple-ui.md): [SafeProposeScreen] for the same [state], with
 * no light-client strip and no block. The form says each balance's age in place of its block, as
 * Send does; the review says the Safe's call was checked on chain, and keeps the blocks behind
 * *Details*. The Safe transaction, decoded here, the owner who signs and every refusal are as in
 * Detailed view.
 * [syncLine] is its network's sync line (docs/simple-ui.md, *The sync line*), drawn while it says something.
 */
@Composable
fun SimpleSafeProposeScreen(
    state: SafeProposeUiState,
    actions: SafeProposeActions,
    onBack: () -> Unit,
    onCopy: (String) -> Unit = {},
    onShareText: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    syncLine: LightClientItem? = null,
) = SafeProposeScreen(
    state = state.simple(),
    lightClient = null,
    actions = actions,
    onBack = onBack,
    onCopy = onCopy,
    onShareText = onShareText,
    modifier = modifier,
    details = (state as? SafeProposeUiState.Review)?.details().orEmpty(),
    syncLine = syncLine,
)

/** [this] in simple mode's words: each block-naming text in its block-free form. */
private fun SafeProposeUiState.simple(): SafeProposeUiState = when (this) {
    is SafeProposeUiState.Compose -> copy(
        form = form.copy(
            assets = form.assets.map { it.copy(verifiedAt = null) },
            asset = form.asset?.copy(verifiedAt = null),
            recipient = form.recipient?.copy(resolvedAt = null),
            error = form.simpleError ?: form.error,
        ),
    )
    is SafeProposeUiState.Review -> copy(
        recipient = recipient.copy(resolvedAt = null),
        balance = balance.copy(verifiedAt = ""),
        simulation = simpleSimulation ?: simulation,
        error = simpleError ?: error,
    )
    else -> this
}

/** What the review keeps behind *Details* in simple mode: the blocks Detailed view names. */
private fun SafeProposeUiState.Review.details(): List<String> = listOfNotNull(
    recipient.resolvedAt,
    "${balance.what}: ${balance.verifiedAt}",
    simulation.takeIf { simpleSimulation != null },
    error.takeIf { simpleError != null },
)
