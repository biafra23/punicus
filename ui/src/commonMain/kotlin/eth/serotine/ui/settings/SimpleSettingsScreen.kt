package eth.serotine.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The Settings tab in simple mode (docs/simple-ui.md, T1): [SettingsScreen] as it is, each
 * network's row saying how it stands without a block number ([NetworkRow.simpleLine]: "Up to date",
 * "Test network · live", "Can't check right now"), and the switch for heavy downloads on a
 * metered connection in simple mode's words ([MeteredDownloadsItem.simpleText] and
 * [MeteredDownloadsItem.simpleUnavailableReason]). The rest, Detailed view's switch and *Sign
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
    onMeteredDownloadsChange: (Boolean) -> Unit = {},
    onFetchFreshCheckpoint: (chainId: Long, useUnconfirmed: Boolean) -> Unit = { _, _ -> },
) = SettingsScreen(
    state.copy(
        networks = state.networks.map { it.copy(line = it.simpleLine) },
        meteredDownloads = state.meteredDownloads?.let {
            it.copy(text = it.simpleText, unavailableReason = it.simpleUnavailableReason)
        },
    ),
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
    onMeteredDownloadsChange,
    onFetchFreshCheckpoint,
)
