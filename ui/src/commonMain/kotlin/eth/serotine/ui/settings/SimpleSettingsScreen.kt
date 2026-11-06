package eth.serotine.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The Settings tab in simple mode (docs/simple-ui.md, T1): [SettingsScreen] as it is, each
 * network's row saying how it stands without a block number ([NetworkRow.simpleLine]: "Up to date",
 * "Test network · live", "Can't check right now"). The rest, Detailed view's switch and *Sign
 * while syncing* among it, is the same in both views.
 */
@Composable
fun SimpleSettingsScreen(
    state: SettingsUiState,
    onCurrency: () -> Unit,
    onTokens: () -> Unit,
    onSecurity: () -> Unit,
    onScreenCaptureChange: (Boolean) -> Unit,
    onDebugScreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onNetworkChange: (chainId: Long, on: Boolean) -> Unit = { _, _ -> },
    onConfirmTurnOff: () -> Unit = {},
    onCancelTurnOff: () -> Unit = {},
    onSyncPastStaleAnchor: (chainId: Long) -> Unit = {},
    onDetailedViewChange: (Boolean) -> Unit = {},
    onConnections: () -> Unit = {},
    connectionsOff: Boolean = false,
    onSignWhileSyncingChange: (Boolean) -> Unit = {},
) = SettingsScreen(
    state.copy(networks = state.networks.map { it.copy(line = it.simpleLine) }),
    onCurrency,
    onTokens,
    onSecurity,
    onScreenCaptureChange,
    onDebugScreenChange,
    modifier,
    onNetworkChange,
    onConfirmTurnOff,
    onCancelTurnOff,
    onSyncPastStaleAnchor,
    onDetailedViewChange,
    onConnections,
    connectionsOff,
    onSignWhileSyncingChange,
)
