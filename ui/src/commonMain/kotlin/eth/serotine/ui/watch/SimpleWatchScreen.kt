package eth.serotine.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.ui.components.LightClientItem

/**
 * Watch address in simple mode (docs/simple-ui.md): [WatchScreen] for the same [state], with no
 * light-client strip and no block: each card says it was checked on chain, and a name says what it
 * resolves to the same way; the blocks are behind *Details*. What is at the address, and whether it
 * can be added, are the same as in Detailed view.
 * [syncLine] is its network's sync line (docs/simple-ui.md, *The sync line*), drawn while it says something.
 */
@Composable
fun SimpleWatchScreen(
    state: WatchUiState,
    onAddressChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryLookup: () -> Unit = {},
    onChooseNetworks: () -> Unit = {},
    onNetworkToggle: (chainId: Long) -> Unit = {},
    onTurnOnNetwork: (chainId: Long) -> Unit = {},
    syncLine: LightClientItem? = null,
) = WatchScreen(
    state.simple(),
    lightClient = null,
    onAddressChange,
    onNameChange,
    onAdd,
    onBack,
    modifier,
    onRetryLookup = onRetryLookup,
    onChooseNetworks = onChooseNetworks,
    onNetworkToggle = onNetworkToggle,
    onTurnOnNetwork = onTurnOnNetwork,
    details = state.details(),
    syncLine = syncLine,
)

/** [this] in simple mode's words: the cards checked on chain, and the field's note, with no block. */
private fun WatchUiState.simple(): WatchUiState = copy(
    addressNote = simpleAddressNote ?: addressNote,
    detection = detection?.simple(),
    networks = networks.map { it.copy(detection = it.detection.simple()) },
)

private fun Detection.simple(): Detection = when (this) {
    is Detection.Account -> copy(checkedAt = simpleCheckedAt)
    is Detection.Safe -> copy(checkedAt = simpleCheckedAt)
    else -> this
}

/** What simple mode keeps behind *Details*: the blocks the cards and a resolved name were verified at. */
private fun WatchUiState.details(): List<String> = listOfNotNull(
    addressNote.takeIf { simpleAddressNote != null },
    detection?.checkedAtBlock(),
) + networks.mapNotNull { row -> row.detection.checkedAtBlock()?.let { "${row.network.name}: $it" } }

private fun Detection.checkedAtBlock(): String? = when (this) {
    is Detection.Account -> checkedAt.takeIf { it != simpleCheckedAt }
    is Detection.Safe -> checkedAt.takeIf { it != simpleCheckedAt }
    else -> null
}
