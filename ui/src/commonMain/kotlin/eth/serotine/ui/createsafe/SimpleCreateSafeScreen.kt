package eth.serotine.ui.createsafe

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.ui.components.LightClientItem

/**
 * Create Safe in simple mode (docs/simple-ui.md): [CreateSafeScreen] for the same [state], with no
 * light-client strip and no block. An owner resolved from a name says it was checked on chain; the
 * review says the factory's run, checked on chain, returns the address, and keeps the blocks, the
 * fee's make-up, the payer's balance block and the run's block behind *Details*. The owners, the
 * threshold, the payer, the fee and every check are as in Detailed view.
 * [syncLine] is its network's sync line (docs/simple-ui.md, *The sync line*), drawn while it says something.
 */
@Composable
fun SimpleCreateSafeScreen(
    state: CreateSafeUiState,
    actions: CreateSafeActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    syncLine: LightClientItem? = null,
) = CreateSafeScreen(
    state = state.simple(),
    lightClient = null,
    actions = actions,
    onBack = onBack,
    modifier = modifier,
    details = state.details(),
    syncLine = syncLine,
)

/** [this] in simple mode's words: each block-naming text in its block-free form. */
private fun CreateSafeUiState.simple(): CreateSafeUiState = when (this) {
    is CreateSafeUiState.Owners -> copy(
        accounts = accounts.map { it.simple() },
        typed = typed.map { it.simple() },
        inputOwner = inputOwner?.simple(),
    )
    is CreateSafeUiState.Threshold -> copy(owners = owners.map { it.simple() })
    is CreateSafeUiState.Review -> copy(
        prediction = simplePrediction ?: prediction,
        error = simpleError ?: error,
        owners = owners.map { it.simple() },
        quote = quote?.let {
            it.copy(
                feeDetail = "",
                balance = it.balance.copy(verifiedAt = ""),
                simulation = it.simpleSimulation,
            )
        },
    )
    else -> this
}

private fun OwnerChoice.simple() = copy(note = simpleNote ?: note)

private fun OwnerItem.simple() = copy(note = simpleNote ?: note)

/**
 * What each step keeps behind *Details* in simple mode: the blocks an owner's name was resolved at
 * and, on the review, what Detailed view says of the address, the quote and a refusal.
 */
private fun CreateSafeUiState.details(): List<String> = when (this) {
    is CreateSafeUiState.Owners -> (accounts + typed + listOfNotNull(inputOwner))
        .filter { it.simpleNote != null }
        .map { "${it.label ?: it.address}: ${it.note}" }
    is CreateSafeUiState.Threshold -> owners.resolvedAt()
    is CreateSafeUiState.Review -> owners.resolvedAt() + listOfNotNull(
        prediction.takeIf { simplePrediction != null },
        quote?.feeDetail,
        quote?.balance?.let { "${it.what}: ${it.before} → ${it.after}, ${it.verifiedAt}" },
        quote?.simulation,
        error.takeIf { simpleError != null },
    )
    else -> emptyList()
}

private fun List<OwnerItem>.resolvedAt(): List<String> =
    filter { it.simpleNote != null }.map { "${it.label ?: it.address}: ${it.note}" }
