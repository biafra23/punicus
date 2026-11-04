package eth.serotine.ui.safesettings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.ui.components.LightClientItem

/**
 * A Safe's settings in simple mode (docs/simple-ui.md; owner's request, 2026-10-28):
 * [SafeSettingsScreen] for the same [state], with no light-client strip and no block. The settings
 * say they were checked on chain, a change's run that it should go through, and a refusal is said
 * without its block; the blocks are behind *Details*. The owners, the threshold, the modules, the
 * guard, every change offered or greyed out, and every check are as in Detailed view.
 * [syncLine] is its network's sync line (docs/simple-ui.md, *The sync line*), drawn while it says something.
 */
@Composable
fun SimpleSafeSettingsScreen(
    state: SafeSettingsUiState,
    actions: SafeSettingsActions,
    onBack: () -> Unit,
    onCopy: (String) -> Unit = {},
    onShareText: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    syncLine: LightClientItem? = null,
) = SafeSettingsScreen(
    state = state.simple(),
    lightClient = null,
    actions = actions,
    onBack = onBack,
    onCopy = onCopy,
    onShareText = onShareText,
    modifier = modifier,
    details = state.details(),
    syncLine = syncLine,
)

/** [this] in simple mode's words: each block-naming text in its block-free form. */
private fun SafeSettingsUiState.simple(): SafeSettingsUiState = when (this) {
    is SafeSettingsUiState.Overview -> copy(checkedAt = simpleCheckedAt ?: checkedAt)
    is SafeSettingsUiState.Edit -> copy(form = form.copy(error = form.simpleError ?: form.error))
    is SafeSettingsUiState.Review -> copy(
        simulation = simpleSimulation ?: simulation,
        error = simpleError ?: error,
    )
    else -> this
}

/** What each step keeps behind *Details* in simple mode: the blocks Detailed view names. */
private fun SafeSettingsUiState.details(): List<String> = when (this) {
    is SafeSettingsUiState.Overview -> listOfNotNull(checkedAt.takeIf { simpleCheckedAt != null })
    is SafeSettingsUiState.Edit -> listOfNotNull(form.error.takeIf { form.simpleError != null })
    is SafeSettingsUiState.Review -> listOfNotNull(
        simulation.takeIf { simpleSimulation != null },
        error.takeIf { simpleError != null },
    )
    else -> emptyList()
}
