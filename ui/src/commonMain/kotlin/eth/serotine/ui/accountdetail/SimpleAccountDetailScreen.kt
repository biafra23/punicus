package eth.serotine.ui.accountdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.ui.activity.ActivityKey
import eth.serotine.ui.activity.ActivityOrder
import eth.serotine.ui.activity.simple

/**
 * One account in simple mode (docs/simple-ui.md, H4 and H5): [AccountDetailScreen] for the same
 * [state], drawn without the light client's strip and without block numbers. How fresh the values
 * are stands under the total, an amount last verified says how old it is, the Activity says when
 * and how each row ended in words, and the blockie carries the network's icon. Pulling the screen
 * down, or the Refresh button, asks [onRefresh]. Every action and its greyed-out reason is as in
 * [AccountDetailScreen].
 */
@Composable
fun SimpleAccountDetailScreen(
    state: AccountDetailUiState,
    onBack: () -> Unit,
    onSend: (contract: String?) -> Unit,
    onSwap: (contract: String?) -> Unit,
    onReceive: () -> Unit,
    onCopy: (String) -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onHideToken: (contract: String, symbol: String) -> Unit,
    onMessageShown: () -> Unit,
    onSort: (AssetSort) -> Unit,
    onSegment: (DetailSegment) -> Unit,
    onOpenSafeTx: (hash: String) -> Unit,
    onImportRequest: () -> Unit,
    onOpenActivity: (ActivityKey) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onTurnOnNetwork: () -> Unit = {},
    onAddOnNetwork: () -> Unit = {},
    onSafeSettings: () -> Unit = {},
    onContinueSwap: (sell: String) -> Unit = {},
    onDismissWaiting: (sell: String) -> Unit = {},
    onDerive: () -> Unit = {},
    onToggleAmounts: () -> Unit = {},
    onOrderActivity: (ActivityOrder) -> Unit = {},
) {
    val ready = state as? AccountDetailUiState.Ready
    AccountDetailScreen(
        state = ready?.simple() ?: state,
        chrome = AccountChrome.Refresh(onRefresh),
        onBack = onBack,
        onSend = onSend,
        onSwap = onSwap,
        onReceive = onReceive,
        onCopy = onCopy,
        onRename = onRename,
        onRemove = onRemove,
        onHideToken = onHideToken,
        onMessageShown = onMessageShown,
        onSort = onSort,
        onSegment = onSegment,
        onOpenSafeTx = onOpenSafeTx,
        onImportRequest = onImportRequest,
        onOpenActivity = onOpenActivity,
        modifier = modifier,
        onTurnOnNetwork = onTurnOnNetwork,
        onAddOnNetwork = onAddOnNetwork,
        onSafeSettings = onSafeSettings,
        onContinueSwap = onContinueSwap,
        onDismissWaiting = onDismissWaiting,
        onDerive = onDerive,
        onToggleAmounts = onToggleAmounts,
        onOrderActivity = onOrderActivity,
    )
}

/**
 * [this] in simple mode's words: each asset's age ([AssetRow.ageNote]) for when it was last verified
 * and no block, the Activity's simple texts, and the Safe's owners checked on chain with no block.
 */
private fun AccountDetailUiState.Ready.simple(): AccountDetailUiState.Ready = copy(
    assets = assets.map { it.copy(verifiedAt = null, lastKnown = it.ageNote ?: it.lastKnown) },
    activity = activity.simple(),
    safe = (safe as? SafeDetails.Shown)?.copy(checkedAt = "Checked on chain") ?: safe,
)
