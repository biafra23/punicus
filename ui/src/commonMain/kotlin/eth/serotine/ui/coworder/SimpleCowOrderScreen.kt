package eth.serotine.ui.coworder

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.ui.components.LightClientItem

/**
 * A CoW order's status in simple mode (docs/simple-ui.md): [CowOrderScreen] for the same [state],
 * with no light-client strip and no block. How the order ended says it was proven from this
 * account's verified logs, its block behind *Details*; the cancellation's review says its run was
 * checked on chain and keeps the fee's make-up and the blocks behind *Details* too. CoW's word, labelled as
 * CoW's, Cancel on chain and the same swap on Uniswap are as in Detailed view.
 * [syncLine] is its network's sync line (docs/simple-ui.md, *The sync line*), drawn while it says something.
 */
@Composable
fun SimpleCowOrderScreen(
    state: CowOrderUiState,
    onCancel: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
    onRetry: () -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
    onSwapAgain: (SwapAgain) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    syncLine: LightClientItem? = null,
) = CowOrderScreen(
    state = state.simple(),
    lightClient = null,
    onCancel = onCancel,
    onEdit = onEdit,
    onRefresh = onRefresh,
    onPasswordChange = onPasswordChange,
    onSign = onSign,
    onSignWithBiometrics = onSignWithBiometrics,
    onRetry = onRetry,
    onOpenTransfer = onOpenTransfer,
    onSwapAgain = onSwapAgain,
    onBack = onBack,
    modifier = modifier,
    details = when (state) {
        is CowOrderUiState.Shown -> listOfNotNull(
            state.end.takeIf { state.simpleEnd != null },
            state.error.takeIf { state.simpleError != null },
        )
        is CowOrderUiState.CancelReview -> state.details()
        else -> emptyList()
    },
    syncLine = syncLine,
)

/** What the cancellation's review keeps behind *Details* in simple mode: the fee's make-up and the blocks. */
private fun CowOrderUiState.CancelReview.details(): List<String> = listOf(
    feeDetail,
    "${balance.what}: ${balance.before} → ${balance.after}, ${balance.verifiedAt}",
    simulation,
)

/** [this] in simple mode's words: each block-naming text in its block-free form. */
private fun CowOrderUiState.simple(): CowOrderUiState = when (this) {
    is CowOrderUiState.Shown -> copy(end = simpleEnd ?: end, error = simpleError ?: error)
    is CowOrderUiState.CancelReview -> copy(
        feeDetail = "",
        balance = balance.copy(verifiedAt = ""),
        simulation = simpleSimulation,
    )
    else -> this
}
