package eth.serotine.ui.addtoken

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.ui.components.LightClientItem

/**
 * Add token in simple mode (docs/simple-ui.md): [AddTokenScreen] for the same [state], with no
 * light-client strip, and what the contract says of itself read on chain with no block. The claim,
 * labelled as the contract's, and Add are as in Detailed view; the block it was read at is behind
 * *Details*.
 * [syncLine] is its network's sync line (docs/simple-ui.md, *The sync line*), drawn while it says something.
 */
@Composable
fun SimpleAddTokenScreen(
    state: AddTokenUiState,
    onAddressChange: (String) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNetwork: (chainId: Long) -> Unit = {},
    onTurnOnNetwork: (chainId: Long) -> Unit = {},
    syncLine: LightClientItem? = null,
) = AddTokenScreen(
    state.copy(card = (state.card as? TokenCard.Claim)?.let { it.copy(checkedAt = it.simpleCheckedAt) } ?: state.card),
    lightClient = null,
    onAddressChange,
    onAdd,
    onBack,
    modifier,
    onNetwork = onNetwork,
    onTurnOnNetwork = onTurnOnNetwork,
    // The block, where one was named: a claim read without one has nothing more to say there.
    details = listOfNotNull(
        (state.card as? TokenCard.Claim)?.let { claim -> claim.checkedAt.takeIf { it != claim.simpleCheckedAt } },
    ),
    syncLine = syncLine,
)
