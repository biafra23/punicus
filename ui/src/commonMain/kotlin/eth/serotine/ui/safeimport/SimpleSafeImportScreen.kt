package eth.serotine.ui.safeimport

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.ui.components.LightClientItem

/**
 * Import a request in simple mode (docs/simple-ui.md): [SafeImportScreen] for the same [state],
 * with no light-client strip, and a refusal said with no block. The request, its checks against the
 * chain and Import are as in Detailed view.
 * [syncLine] is its network's sync line (docs/simple-ui.md, *The sync line*), drawn while it says something.
 */
@Composable
fun SimpleSafeImportScreen(
    state: SafeImportUiState,
    onTextChange: (String) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    syncLine: LightClientItem? = null,
) = SafeImportScreen(
    state = (state as? SafeImportUiState.Entering)?.let { it.copy(error = it.simpleError ?: it.error) } ?: state,
    lightClient = null,
    onTextChange = onTextChange,
    onImport = onImport,
    onBack = onBack,
    modifier = modifier,
    syncLine = syncLine,
)
