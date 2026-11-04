package eth.serotine.ui.safeswap

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.ui.components.LightClientItem

/**
 * A Safe's swap in simple mode (docs/simple-ui.md): [SafeSwapScreen] for the same [state], with no
 * light-client strip and no block. The form says each balance's age in place of its block, as Swap
 * does; the review says its quote and its runs were checked on chain, and keeps the blocks behind
 * *Details*. The Safe transactions, decoded here, the owner who signs and every refusal are as in
 * Detailed view.
 * [syncLine] is its network's sync line (docs/simple-ui.md, *The sync line*), drawn while it says something.
 */
@Composable
fun SimpleSafeSwapScreen(
    state: SafeSwapUiState,
    actions: SafeSwapActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    syncLine: LightClientItem? = null,
) = SafeSwapScreen(
    state = state.simple(),
    lightClient = null,
    actions = actions,
    onBack = onBack,
    modifier = modifier,
    details = (state as? SafeSwapUiState.Review)?.details().orEmpty(),
    syncLine = syncLine,
)

/** [this] in simple mode's words: each block-naming text in its block-free form. */
private fun SafeSwapUiState.simple(): SafeSwapUiState = when (this) {
    is SafeSwapUiState.Compose -> copy(
        form = form.copy(
            sellOptions = form.sellOptions.map { it.copy(verifiedAt = null) },
            sell = form.sell?.copy(verifiedAt = null),
            buyOptions = form.buyOptions.map { it.copy(verifiedAt = null) },
            buy = form.buy?.copy(verifiedAt = null),
            error = form.simpleError ?: form.error,
        ),
    )
    is SafeSwapUiState.Review -> copy(
        quotedAt = simpleQuotedAt ?: quotedAt,
        balance = balance.copy(verifiedAt = ""),
        simulation = simpleSimulation ?: simulation,
        error = simpleError ?: error,
    )
    else -> this
}

/** What the review keeps behind *Details* in simple mode: the blocks Detailed view names. */
private fun SafeSwapUiState.Review.details(): List<String> = listOfNotNull(
    "$quoted: $quotedAt".takeIf { simpleQuotedAt != null },
    "${balance.what}: ${balance.verifiedAt}",
    simulation.takeIf { simpleSimulation != null },
    error.takeIf { simpleError != null },
)
