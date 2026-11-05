package eth.serotine.ui.accountdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.accounts.DERIVE_ACTION
import eth.serotine.ui.accounts.deriveBlockedReason
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.accounts.UnreachableNetwork
import eth.serotine.ui.activity.ActivityKey
import eth.serotine.ui.activity.ActivityOrder
import eth.serotine.ui.activity.ActivityUiState
import eth.serotine.ui.activity.activityItems
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.ActionDropdown
import eth.serotine.ui.components.AddressLine
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.ControlBadge
import eth.serotine.ui.components.GHOST_ROWS_UNKNOWN
import eth.serotine.ui.components.GhostCircle
import eth.serotine.ui.components.GhostLine
import eth.serotine.ui.components.GhostRows
import eth.serotine.ui.components.HideAmountsButton
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.MenuAction
import eth.serotine.ui.components.NetworkMark
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.UnderLightClientStrip
import eth.serotine.ui.components.WithActionMenu
import eth.serotine.ui.components.addOnNetworkOffReason
import eth.serotine.ui.components.networkOffNotice
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.mode.LocalInterfaceMode
import eth.serotine.ui.platform.rememberCopyText
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Test tag of the account's total, for UI tests. */
const val ACCOUNT_TOTAL_TAG = "account-total"

/** Test tag of simple mode's freshness line, for UI tests. */
const val ACCOUNT_FRESHNESS_TAG = "account-freshness"

/** Test tag of simple mode's Refresh button, for UI tests. */
const val ACCOUNT_REFRESH_TAG = "account-refresh"

/** Test tag of the account's full address, for UI tests. */
const val ACCOUNT_ADDRESS_TAG = "account-address"

/** Test tag of a Safe's owners, for UI tests. */
const val ACCOUNT_OWNERS_TAG = "account-owners"

/** Test tag of a Safe's Safe settings button, for UI tests. */
const val ACCOUNT_SAFE_SETTINGS_TAG = "account-safe-settings"

/** Test tag of each asset row, for UI tests. */
const val ACCOUNT_ASSET_TAG = "account-asset"

/** The account's NFTs section, and each NFT's row in it (docs/wallet-ux.md, *NFTs*). */
const val ACCOUNT_NFTS_TAG = "account-nfts"
const val ACCOUNT_NFT_TAG = "account-nft"

/** Test tag of the Swap button, for UI tests. */
const val ACCOUNT_SWAP_TAG = "account-swap"

/** Test tag of each queued Safe transaction, for UI tests. */
const val ACCOUNT_QUEUE_ROW_TAG = "account-queue-row"

/**
 * Binds [AccountDetailScreen], or in simple mode [SimpleAccountDetailScreen], to its ViewModel; a
 * row of its Activity opens its detail ([onOpenActivity]). An account that leaves the wallet while
 * the screen is shown (removed from here or elsewhere) ends the screen through [onGone] — once the
 * screen is the one in front: under the remove dialog it waits, so the dialog can say how the
 * removal went before both close. Send is [onSend] from an account and [onPropose] from a Safe,
 * whose transfers are Safe transactions its owners sign; Swap likewise [onSwap] and [onSafeSwap].
 */
@Composable
fun AccountDetailRoute(
    viewModel: AccountDetailViewModel,
    /** The strip of the network this screen is about: its account's. */
    lightClient: StateFlow<LightClientItem>,
    onBack: () -> Unit,
    onGone: () -> Unit,
    onSend: (contract: String?) -> Unit,
    onPropose: (contract: String?) -> Unit,
    onSwap: (contract: String?) -> Unit,
    onSafeSwap: (contract: String?) -> Unit,
    onReceive: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onOpenSafeTx: (hash: String) -> Unit,
    onImportRequest: () -> Unit,
    onOpenActivity: (ActivityKey) -> Unit,
    modifier: Modifier = Modifier,
    onAddOnNetwork: () -> Unit = {},
    onSafeSettings: () -> Unit = {},
    onDerive: () -> Unit = {},
    onContinueSwap: (sell: String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gone by rememberUpdatedState(onGone)
    // A dialog destination over this one holds it at STARTED; it is RESUMED when in front.
    val inFront = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
        .isAtLeast(Lifecycle.State.RESUMED)
    val leave = state is AccountDetailUiState.Gone && inFront
    LaunchedEffect(leave) {
        if (leave) gone()
    }
    val isSafe = (state as? AccountDetailUiState.Ready)?.kind == AccountKind.SAFE
    val send: (String?) -> Unit = { contract -> if (isSafe) onPropose(contract) else onSend(contract) }
    val swap: (String?) -> Unit = { contract -> if (isSafe) onSafeSwap(contract) else onSwap(contract) }
    val copy = rememberCopyText()
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleAccountDetailScreen(
            state = state,
            onBack = onBack,
            onSend = send,
            onSwap = swap,
            onReceive = onReceive,
            onCopy = copy,
            onRename = onRename,
            onRemove = onRemove,
            onHideToken = viewModel::hideToken,
            onMessageShown = viewModel::messageShown,
            onSort = viewModel::sortBy,
            onSegment = viewModel::show,
            onOpenSafeTx = onOpenSafeTx,
            onImportRequest = onImportRequest,
            onOpenActivity = onOpenActivity,
            onRefresh = viewModel::refresh,
            modifier = modifier,
            onTurnOnNetwork = viewModel::turnOnNetwork,
            onAddOnNetwork = onAddOnNetwork,
            onSafeSettings = onSafeSettings,
            onContinueSwap = onContinueSwap,
            onDismissWaiting = viewModel::dismissWaiting,
            onDerive = onDerive,
            onToggleAmounts = viewModel::toggleAmounts,
            onOrderActivity = viewModel::orderActivity,
            onToggleUnlistedNfts = viewModel::toggleUnlistedNfts,
        )
        // The strip is collected only where it is drawn: shared while subscribed, it stays idle otherwise.
        InterfaceMode.DETAILED -> AccountDetailScreen(
            state = state,
            chrome = AccountChrome.Strip(lightClient.collectAsStateWithLifecycle().value),
            onBack = onBack,
            onSend = send,
            onSwap = swap,
            onReceive = onReceive,
            onCopy = copy,
            onRename = onRename,
            onRemove = onRemove,
            onHideToken = viewModel::hideToken,
            onMessageShown = viewModel::messageShown,
            onSort = viewModel::sortBy,
            onSegment = viewModel::show,
            onOpenSafeTx = onOpenSafeTx,
            onImportRequest = onImportRequest,
            onOpenActivity = onOpenActivity,
            modifier = modifier,
            onTurnOnNetwork = viewModel::turnOnNetwork,
            onAddOnNetwork = onAddOnNetwork,
            onSafeSettings = onSafeSettings,
            onContinueSwap = onContinueSwap,
            onDismissWaiting = viewModel::dismissWaiting,
            onDerive = onDerive,
            onToggleAmounts = viewModel::toggleAmounts,
            onOrderActivity = viewModel::orderActivity,
            onToggleUnlistedNfts = viewModel::toggleUnlistedNfts,
        )
    }
}

/**
 * One account (docs/wallet-ux.md, screens 4 and 16) as a pure function of [state]: its blockie,
 * balance, address and control, Send (where it can send), Swap and Receive as buttons, and Copy,
 * Rename and Remove in the top bar's menu, so none of them depends on the long press of the
 * account list; while the asset rows are shown, the menu also sorts them the other way
 * ([onSort]), and while the Activity is shown, runs its rows the other way ([onOrderActivity]:
 * "Oldest first", then "Newest first" back). Under the buttons are its tabs, Assets | Activity
 * and a Safe's Owners | Queue.
 * [onSend] opens Send with the token at a contract chosen, or the network's currency (null). The
 * Activity tab's rows open their detail through [onOpenActivity]; a swap waiting there for its next
 * step opens Swap through [onContinueSwap], or goes through [onDismissWaiting]. A Safe's Queue opens a
 * transaction through [onOpenSafeTx] by its hash, and Import a request through [onImportRequest];
 * its Owners tab opens its settings through [onSafeSettings].
 * The account's network is named beside its control (docs/networks.md, N3 and N4); while it is off,
 * Send and Swap say so and offer to turn it on through [onTurnOnNetwork] (N19). An externally owned
 * account's menu adds it on another network through [onAddOnNetwork] (N5), and the next account of
 * its recovery phrase through [onDerive], greyed out while its key is not on this device.
 * How the light client's state is shown is its [chrome]: Detailed view's strip under the top bar,
 * or simple mode's ([SimpleAccountDetailScreen]) pull-to-refresh. Once the account is read, the eye
 * beside Back hides or shows every amount ([onToggleAmounts]; docs/wallet-ux.md, *Hiding amounts*).
 * Below the assets are the account's NFTs, the unlisted collections' opened or collapsed by
 * [onToggleUnlistedNfts] (docs/wallet-ux.md, *NFTs*).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    state: AccountDetailUiState,
    chrome: AccountChrome,
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
    modifier: Modifier = Modifier,
    onTurnOnNetwork: () -> Unit = {},
    onAddOnNetwork: () -> Unit = {},
    onSafeSettings: () -> Unit = {},
    onContinueSwap: (sell: String) -> Unit = {},
    onDismissWaiting: (sell: String) -> Unit = {},
    onDerive: () -> Unit = {},
    onToggleAmounts: () -> Unit = {},
    onOrderActivity: (ActivityOrder) -> Unit = {},
    onToggleUnlistedNfts: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val message = (state as? AccountDetailUiState.Ready)?.message
    val shown by rememberUpdatedState(onMessageShown)
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            shown()
        }
    }
    val copy: (String) -> Unit = { address ->
        onCopy(address)
        scope.launch { snackbar.showSnackbar("Address copied") }
    }
    // Send greyed out where it cannot be done: a tap says why (owner ruling, 2026-10-18, #55).
    var unavailable by remember { mutableStateOf<UnavailableNotice?>(null) }
    unavailable?.let { notice -> UnavailableDialog(notice, onDismiss = { unavailable = null }) }
    // On a network that is off, the way to an action is to turn the network on (N19).
    val notice: (String, String) -> UnavailableNotice = { action, reason ->
        val ready = state as? AccountDetailUiState.Ready
        if (ready != null && !ready.networkOn) {
            val network = ready.network.name
            networkOffNotice("$action needs $network", reason, network, onTurnOnNetwork)
        } else {
            UnavailableNotice(action, reason)
        }
    }
    val safeSettings: () -> Unit = {
        val reason = (state as? AccountDetailUiState.Ready)?.safeSettingsReason
        if (reason == null) onSafeSettings() else unavailable = notice("Safe settings", reason)
    }
    val send: (String?) -> Unit = { contract ->
        val reason = (state as? AccountDetailUiState.Ready)?.sendReason
        if (reason == null) onSend(contract) else unavailable = notice("Send", reason)
    }
    val swap: (String?) -> Unit = { contract ->
        val reason = (state as? AccountDetailUiState.Ready)?.swapReason
        if (reason == null) onSwap(contract) else unavailable = notice("Swap", reason)
    }
    // A waiting swap's Continue needs what Swap needs, and is greyed out with Swap's reason (#55).
    val continueSwap: (String) -> Unit = { sell ->
        val reason = (state as? AccountDetailUiState.Ready)?.swapReason
        if (reason == null) onContinueSwap(sell) else unavailable = notice("Continue", reason)
    }
    // On a network that is off nothing is checked: Refresh is greyed out, with Turn on (#55).
    val refresh: () -> Unit = {
        val ready = state as? AccountDetailUiState.Ready
        if (ready != null && !ready.networkOn) {
            unavailable = notice("Refresh", refreshOffReason(ready.network.name))
        } else {
            (chrome as? AccountChrome.Refresh)?.onRefresh?.invoke()
        }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Row {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        // Top left, beside Back, while there are amounts to hide.
                        (state as? AccountDetailUiState.Ready)?.let { HideAmountsButton(it.amountsHidden, onToggleAmounts) }
                    }
                },
                title = { Text((state as? AccountDetailUiState.Ready)?.label ?: "") },
                actions = {
                    // Pulling down is not the only way: a mouse, a screen reader, a keyboard get a button.
                    if (chrome is AccountChrome.Refresh && state is AccountDetailUiState.Ready) {
                        IconButton(
                            onClick = refresh,
                            Modifier.testTag(ACCOUNT_REFRESH_TAG).unavailable(!state.networkOn),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    if (state is AccountDetailUiState.Ready) {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                        }
                        // The order of the rows, offered while they are shown: the other order than the
                        // current one. The Activity's run by date, and turn round the same way.
                        val onActivity = state.segment == DetailSegment.ACTIVITY
                        val sort = when {
                            state.showsAssets && state.sort == AssetSort.VALUE ->
                                MenuAction("Sort by name", SerotineIcons.Sort) { onSort(AssetSort.NAME) }
                            state.showsAssets ->
                                MenuAction("Sort by value", SerotineIcons.Sort) { onSort(AssetSort.VALUE) }
                            onActivity && state.activityOrder == ActivityOrder.NEWEST_FIRST ->
                                MenuAction("Oldest first", SerotineIcons.Sort) {
                                    onOrderActivity(ActivityOrder.OLDEST_FIRST)
                                }
                            onActivity ->
                                MenuAction("Newest first", SerotineIcons.Sort) {
                                    onOrderActivity(ActivityOrder.NEWEST_FIRST)
                                }
                            else -> null
                        }
                        ActionDropdown(
                            actions = listOfNotNull(
                                sort,
                                MenuAction("Copy address", SerotineIcons.ContentCopy) { copy(state.address) },
                                MenuAction("Rename", Icons.Filled.Edit, onClick = onRename),
                                // An EOA's address is the same account on every chain (N5); from a
                                // network that is off, greyed out with Turn on (N18).
                                MenuAction(
                                    "Add on another network…",
                                    Icons.Filled.Add,
                                    unavailable = !state.networkOn,
                                ) {
                                    if (state.networkOn) {
                                        onAddOnNetwork()
                                    } else {
                                        val network = state.network.name
                                        unavailable = networkOffNotice(
                                            "Add on another network",
                                            addOnNetworkOffReason(network, state.label),
                                            network,
                                            onTurnOnNetwork,
                                        )
                                    }
                                }.takeIf { state.kind == AccountKind.EOA },
                                // The next account of its phrase; greyed out while its key is not here.
                                deriveBlockedReason(state.control).let { reason ->
                                    MenuAction("$DERIVE_ACTION…", SerotineIcons.Key, unavailable = reason != null) {
                                        if (reason == null) {
                                            onDerive()
                                        } else {
                                            unavailable = UnavailableNotice(DERIVE_ACTION, reason)
                                        }
                                    }
                                }.takeIf { state.kind == AccountKind.EOA },
                                MenuAction("Remove", Icons.Filled.Delete, destructive = true, onClick = onRemove),
                            ),
                            expanded = menu,
                            onDismiss = { menu = false },
                        )
                    }
                },
            )
        },
    ) { padding ->
        val body: @Composable BoxScope.() -> Unit = {
            when (state) {
                AccountDetailUiState.Loading, AccountDetailUiState.Gone ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is AccountDetailUiState.Unavailable -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                is AccountDetailUiState.Ready -> Details(
                    state,
                    send,
                    swap,
                    onReceive,
                    copy,
                    onCopyContract = { contract ->
                        onCopy(contract)
                        scope.launch { snackbar.showSnackbar("Contract address copied") }
                    },
                    onHideToken = { asset -> asset.contract?.let { onHideToken(it, asset.symbol) } },
                    onSegment = onSegment,
                    onOpenSafeTx = onOpenSafeTx,
                    onImportRequest = onImportRequest,
                    onOpenActivity = onOpenActivity,
                    onContinueSwap = continueSwap,
                    onDismissWaiting = onDismissWaiting,
                    onSafeSettings = safeSettings,
                    simple = chrome is AccountChrome.Refresh,
                    onToggleUnlistedNfts = onToggleUnlistedNfts,
                )
            }
        }
        when (chrome) {
            is AccountChrome.Strip -> UnderLightClientStrip(chrome.item, Modifier.padding(padding), body)
            is AccountChrome.Refresh -> PullToRefreshBox(
                isRefreshing = (state as? AccountDetailUiState.Ready)?.refreshing == true,
                onRefresh = refresh,
                modifier = Modifier.padding(padding).fillMaxSize(),
                content = body,
            )
        }
    }
}

/**
 * The account below the top bar, as one list: its header, buttons and tabs, then the tab shown.
 * Lazy, so that a long Activity is composed as it scrolls into view.
 */
@Composable
private fun Details(
    state: AccountDetailUiState.Ready,
    onSend: (String?) -> Unit,
    onSwap: (String?) -> Unit,
    onReceive: () -> Unit,
    onCopy: (String) -> Unit,
    onCopyContract: (String) -> Unit,
    onHideToken: (AssetRow) -> Unit,
    onSegment: (DetailSegment) -> Unit,
    onOpenSafeTx: (String) -> Unit,
    onImportRequest: () -> Unit,
    onOpenActivity: (ActivityKey) -> Unit,
    onContinueSwap: (String) -> Unit,
    onDismissWaiting: (String) -> Unit,
    onSafeSettings: () -> Unit,
    /** Simple mode's chrome: the network on the blockie, the freshness line and the "can't check" banner. */
    simple: Boolean,
    onToggleUnlistedNfts: () -> Unit,
) {
    // Each item ends with the space before the next, but an Activity's rows, which follow one another.
    val spaced = Modifier.padding(bottom = 16.dp)
    // Back from a row's detail, the restored position waits for the rows it points into: measured
    // against the spinner of an Activity read again, it would be cut back to the top for good.
    val position = rememberLazyListState()
    val whileReading = remember { LazyListState() }
    val reading = state.segment == DetailSegment.ACTIVITY && state.activity == ActivityUiState.Loading
    LazyColumn(
        Modifier.fillMaxSize(),
        state = if (reading) whileReading else position,
        contentPadding = PaddingValues(16.dp),
    ) {
        // Simple mode, in place of the strip: the network cannot be checked right now.
        state.cantCheck?.takeIf { simple }?.let { banner -> item { CantCheck(banner, spaced) } }
        item {
            Row(
                spaced,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountAvatar(
                    state.address,
                    size = 56.dp,
                    threshold = state.threshold,
                    network = state.network.takeIf { simple },
                )
                Column {
                    Text(
                        state.total,
                        Modifier.testTag(ACCOUNT_TOTAL_TAG),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    // Right under the total it qualifies: testnet prices, what it leaves out.
                    state.totalNote?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Simple mode: how fresh the values are, in place of the strip's block.
                    state.freshness?.takeIf { simple }?.let {
                        Text(
                            it,
                            Modifier.testTag(ACCOUNT_FRESHNESS_TAG),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The account's network beside its control (N3, N4): the same address on
                        // another network is another account.
                        NetworkMark(state.network, style = MaterialTheme.typography.labelMedium)
                        ControlBadge(state.control)
                        state.kindNote?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    }
                    if (!state.networkOn) {
                        Text(
                            "${state.network.name} is off in this wallet: nothing is checked.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    // Why the title is the address: the ENS name it was named by no longer points here.
                    state.nameNote?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
        // In full, as docs/wallet-ux.md's screen 4 has it, with Copy beside it.
        item {
            Row(
                spaced,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        state.address,
                        Modifier.testTag(ACCOUNT_ADDRESS_TAG),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                IconButton(onClick = { onCopy(state.address) }) {
                    Icon(SerotineIcons.ContentCopy, contentDescription = "Copy address", Modifier.size(18.dp))
                }
            }
        }
        item { Actions(state, onSend, onSwap, onReceive, spaced) }
        item { SegmentChoice(state.segments, state.segment, state.queue, onSegment, spaced) }
        // The same predicate as the top bar's sort entry, so the menu sorts exactly when the rows are shown.
        when {
            state.showsAssets -> {
                item { Assets(state, onSend, onSwap, onCopyContract, onHideToken) }
                state.nfts?.let { nfts -> item { Nfts(nfts, onToggleUnlistedNfts, spaced) } }
            }
            state.segment == DetailSegment.ACTIVITY -> activityItems(
                state.activity,
                onOpenActivity,
                onContinue = onContinueSwap,
                onDismiss = onDismissWaiting,
                continueAvailable = state.canSwap,
            )
            state.segment == DetailSegment.OWNERS -> item { SafeOwners(checkNotNull(state.safe), state.safeSettingsReason == null, onSafeSettings) }
            else -> item { SafeQueueList(checkNotNull(state.queue), onOpenSafeTx, onImportRequest) }
        }
    }
}

/**
 * Send, Swap and Receive in one line, sharing its width: three equal buttons fit a phone as
 * narrow as an iPhone mini's 375 points, where wrapping would push Receive onto a line of its own.
 * Where a label and its icon no longer fit a third of the line, measured in the device's own font
 * and text size (iOS's system font is wider than Android's; the phone's text size setting, iOS's
 * Dynamic Type, makes it wider still), they leave out their icons, so that each still says what
 * it does in full; only past that does a label end in an ellipsis.
 */
@Composable
private fun Actions(
    state: AccountDetailUiState.Ready,
    onSend: (String?) -> Unit,
    onSwap: (String?) -> Unit,
    onReceive: () -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.labelLarge
        val density = LocalDensity.current
        // The widest label as the buttons draw it (Material's buttons set their labels in labelLarge).
        val widest = remember(measurer, style, density) {
            with(density) { ActionLabels.maxOf { measurer.measure(it, style).size.width }.toDp() }
        }
        val third = (maxWidth - ActionGap * 2) / 3 - ActionPadding * 2
        // A dp of slack: the layout rounds each part to whole pixels, and a label a pixel short ends
        // in an ellipsis, while icons left out a little early are not missed.
        val icons = third >= ActionIcon + ActionIconGap + widest + 1.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ActionGap)) {
            ActionButton(
                SEND_LABEL,
                Icons.AutoMirrored.Filled.Send.takeIf { icons },
                onClick = { onSend(null) },
                Modifier.weight(1f).unavailable(!state.canSend),
                available = state.canSend,
            )
            ActionButton(
                SWAP_LABEL,
                SerotineIcons.Swap.takeIf { icons },
                onClick = { onSwap(null) },
                Modifier.weight(1f).testTag(ACCOUNT_SWAP_TAG).unavailable(!state.canSwap),
                available = state.canSwap,
            )
            ActionButton(RECEIVE_LABEL, SerotineIcons.QrCode.takeIf { icons }, onClick = onReceive, Modifier.weight(1f))
        }
    }
}

/** [Actions]' labels: what its buttons draw, and what is measured for whether their icons fit. */
private const val SEND_LABEL = "Send"
private const val SWAP_LABEL = "Swap"
private const val RECEIVE_LABEL = "Receive"
private val ActionLabels = listOf(SEND_LABEL, SWAP_LABEL, RECEIVE_LABEL)

/** The space between [Actions]' buttons. */
private val ActionGap = 8.dp

/** Each [ActionButton]'s padding on either side, less than a button's own. */
private val ActionPadding = 8.dp

/** The size of an [ActionButton]'s icon, and the space between it and its label. */
private val ActionIcon = 18.dp
private val ActionIconGap = 6.dp

/**
 * One of [Actions]' buttons: its [icon] (none: left out) and [label] on one line, with less side
 * padding than a button's own so that three share a phone's width; greyed out unless
 * [available], a tap then saying why (owner ruling, 2026-10-18, #55).
 */
@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier,
    available: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        colors = if (available) ButtonDefaults.filledTonalButtonColors() else Unavailable.buttonColors(),
        contentPadding = PaddingValues(horizontal = ActionPadding, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(ActionIcon))
            Spacer(Modifier.size(ActionIconGap))
        }
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The asset rows (docs/wallet-ux.md, screen 4), each with the block its amount was verified
 * at, a ghost row per token not read yet, and what is not known yet about the other tokens. A
 * tap on a row opens Send with its asset chosen, a long press its menu.
 */
@Composable
private fun Assets(
    state: AccountDetailUiState.Ready,
    onSend: (String?) -> Unit,
    onSwap: (String?) -> Unit,
    onCopyContract: (String) -> Unit,
    onHideToken: (AssetRow) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.assets.forEach { asset ->
            val contract = asset.contract
            // Long press (right click): docs/wallet-ux.md, screen 5. Each action is also behind a
            // visible button: Send and Swap above (the asset chosen there), the others in Settings → Tokens.
            val actions = listOfNotNull(
                MenuAction("Send ${asset.symbol}", Icons.AutoMirrored.Filled.Send, unavailable = !state.canSend) {
                    onSend(contract)
                },
                MenuAction("Swap ${asset.symbol}", SerotineIcons.Swap, unavailable = !state.canSwap) {
                    onSwap(contract)
                },
                contract?.let { MenuAction("Copy contract", SerotineIcons.ContentCopy) { onCopyContract(it) } },
                contract?.let { MenuAction("Hide token", SerotineIcons.Visibility) { onHideToken(asset) } },
            )
            // A tap sends the asset. The row stays as it is where Send cannot be done (its balance is
            // still to be read, and its menu still works): the tap then says why, as the greyed-out
            // Send button does, and accessibility services announce it as such.
            WithActionMenu(
                actions = actions,
                menuLabel = "Actions for ${asset.symbol}",
                onClick = { onSend(contract) },
                clickLabel = if (state.canSend) "Send ${asset.symbol}" else "Why Send ${asset.symbol} is unavailable",
            ) {
                AssetItem(asset)
            }
        }
        // A row's place for each token not read yet: one that holds some takes a place, one that holds
        // none gives one up at the bottom, so the list grows no longer as the tokens are read.
        if (state.pendingAssets > 0) {
            GhostRows(state.pendingAssets, "Tokens not read yet", spacing = 4.dp) {
                AssetGhost()
            }
        }
        state.tokensNote?.let { note ->
            Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.pricesNote?.let { note ->
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The account's NFTs below its assets (docs/wallet-ux.md, *NFTs*; owner rulings, 2026-11-05, #246):
 * the built-in collections' rows, then a row for the unlisted collections, which [onToggle] opens
 * and collapses, and what the list covers. Nothing is fetched to draw them: no image is shown that
 * is not on chain.
 */
@Composable
private fun Nfts(nfts: NftSection, onToggle: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(top = 16.dp).testTag(ACCOUNT_NFTS_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("NFTs", style = MaterialTheme.typography.titleSmall)
        nfts.empty?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        nfts.shown.forEach { NftItem(it, unconfirmed = false) }
        if (nfts.unlisted.isNotEmpty()) {
            val count = nfts.unlisted.size
            ListItem(
                modifier = Modifier.clickable(
                    onClickLabel = if (nfts.unlistedOpen) "Collapse unlisted collections" else "Show unlisted collections",
                    onClick = onToggle,
                ),
                headlineContent = { Text("Unlisted collections ($count)") },
                supportingContent = {
                    Text(
                        "Not confirmed: anyone can send an NFT to any address, and a name is only the contract's claim.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                trailingContent = { Text(if (nfts.unlistedOpen) "Hide" else "Show") },
            )
            if (nfts.unlistedOpen) nfts.unlisted.forEach { NftItem(it, unconfirmed = true) }
        }
        nfts.note?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One NFT: its collection, its id and an amount above one; [unconfirmed] for an unlisted collection's. */
@Composable
private fun NftItem(row: NftRow, unconfirmed: Boolean) {
    ListItem(
        modifier = Modifier.testTag(ACCOUNT_NFT_TAG),
        headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                if (unconfirmed) "${row.tokenId} · not confirmed · ${shortAddress(row.contract)}" else row.tokenId,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = row.amount?.let { amount ->
            @Composable { Text(amount, style = MaterialTheme.typography.bodyLarge) }
        },
    )
}

/**
 * The account's tabs, [segments]: Assets | Activity, and a Safe's Owners | Queue (n)
 * (docs/wallet-ux.md, screens 4 and 16).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentChoice(
    segments: List<DetailSegment>,
    segment: DetailSegment,
    queue: SafeQueue?,
    onSegment: (DetailSegment) -> Unit,
    modifier: Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        segments.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = segment == choice,
                onClick = { onSegment(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, segments.size),
                // Four tabs share a phone's width: no check mark beside the chosen one (its fill says it),
                // and less side padding, so that "Queue (12)" stays on one line.
                contentPadding = PaddingValues(horizontal = 8.dp),
                icon = {},
                label = {
                    Text(
                        when (choice) {
                            DetailSegment.ASSETS -> "Assets"
                            DetailSegment.ACTIVITY -> "Activity"
                            DetailSegment.OWNERS -> "Owners"
                            DetailSegment.QUEUE -> (queue as? SafeQueue.Shown)?.let { "Queue (${it.rows.size})" }
                                ?: "Queue"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

/**
 * A Safe's transactions waiting for its owners on this device, in nonce order (docs/wallet-ux.md,
 * screen 16): each with what it does, its nonce, its signatures and why this wallet will not sign
 * it, if it will not; a tap opens it. Import a request brings in one from another device.
 */
@Composable
private fun SafeQueueList(queue: SafeQueue, onOpenSafeTx: (String) -> Unit, onImportRequest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onImportRequest) {
            Icon(SerotineIcons.QrCode, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Import a request")
        }
        when (queue) {
            SafeQueue.Checking ->
                GhostRows(GHOST_ROWS_UNKNOWN, "Reading this device's queue", spacing = 8.dp) { QueueGhost() }
            is SafeQueue.Unavailable -> Text(queue.message, color = MaterialTheme.colorScheme.error)
            is SafeQueue.Shown -> {
                if (queue.rows.isEmpty()) {
                    Text(
                        "Nothing queued on this device. Send proposes a transaction; Import a request brings in " +
                            "one another owner shared.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                queue.rows.forEach { row -> QueueItem(row) { onOpenSafeTx(row.hash) } }
                queue.note?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                Text(
                    "Signatures as this device has them; approvals made on chain, and signatures shared " +
                        "elsewhere, show on each transaction.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A [QueueItem] not read yet, of its height. */
@Composable
private fun QueueGhost() {
    ListItem(
        leadingContent = { GhostCircle(32.dp) },
        headlineContent = { GhostLine(160.dp) },
        supportingContent = { GhostLine(220.dp) },
    )
}

@Composable
private fun QueueItem(row: QueueRow, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.testTag(ACCOUNT_QUEUE_ROW_TAG).clickable(onClick = onClick),
        leadingContent = { row.about?.let { Blockie(it, size = 32.dp) } },
        headlineContent = { Text(row.action) },
        supportingContent = {
            Column {
                Text("${row.nonce} · ${row.signatures}")
                listOfNotNull(row.warning, row.refusal).forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

/**
 * A Safe's owners with their blockies, "this device" on the ones whose keys are held here, and
 * the threshold; the modules and the guard follow them (docs/wallet-ux.md, screen 16). Safe
 * settings, above them, opens the screen that shows and changes them ([onSafeSettings], screen 28),
 * which says itself when they cannot be read or changed; greyed out unless [settingsAvailable]
 * (the network off), a tap then saying why.
 */
@Composable
private fun SafeOwners(safe: SafeDetails, settingsAvailable: Boolean, onSafeSettings: () -> Unit) {
    Column(Modifier.testTag(ACCOUNT_OWNERS_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onSafeSettings,
            modifier = Modifier.testTag(ACCOUNT_SAFE_SETTINGS_TAG).unavailable(!settingsAvailable),
            colors = if (settingsAvailable) {
                ButtonDefaults.outlinedButtonColors()
            } else {
                ButtonDefaults.outlinedButtonColors(contentColor = Unavailable.contentColor())
            },
        ) {
            Text("Safe settings")
        }
        when (safe) {
            SafeDetails.Checking -> {
                // The title and the threshold, then owners: as many as a Safe's are, until they are read.
                GhostLine(160.dp, style = MaterialTheme.typography.titleSmall)
                GhostLine(200.dp)
                GhostRows(GHOST_ROWS_UNKNOWN, "Reading the Safe's storage", spacing = 8.dp) { OwnerGhost() }
            }
            is SafeDetails.Unavailable -> Text(safe.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is SafeDetails.Shown -> {
                Text(safe.title, style = MaterialTheme.typography.titleSmall)
                Text(safe.signers)
                safe.owners.forEach { owner ->
                    ListItem(
                        leadingContent = { AccountAvatar(owner.address, size = 32.dp) },
                        headlineContent = { Text(owner.label ?: shortAddress(owner.address)) },
                        supportingContent = {
                            Text(
                                if (owner.label != null) shortAddress(owner.address) else "Not in this wallet",
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        trailingContent = {
                            if (owner.heldHere) Text("This device", style = MaterialTheme.typography.labelMedium)
                        },
                    )
                }
                if (safe.modules.isNotEmpty()) {
                    Text(
                        "Modules: each can move this Safe's funds without the owners' signatures.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    safe.modules.forEach { AddressLine(it) }
                }
                safe.guard?.let { AddressLine(it, "A guard checks every transaction the owners sign:") }
                safe.moduleGuard?.let { AddressLine(it, "A module guard checks the modules' transactions:") }
                Text(
                    safe.checkedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** An owner's row of [SafeOwners] not read yet, of its height. */
@Composable
private fun OwnerGhost() {
    ListItem(
        leadingContent = { GhostCircle(32.dp) },
        headlineContent = { GhostLine(96.dp) },
        supportingContent = { GhostLine(120.dp) },
    )
}

/** An [AssetItem] whose balance is still being checked, of its height. */
@Composable
private fun AssetGhost() {
    ListItem(
        leadingContent = { GhostCircle(32.dp) },
        headlineContent = { GhostLine(56.dp) },
        supportingContent = { GhostLine(180.dp) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                GhostLine(72.dp, style = MaterialTheme.typography.bodyLarge)
                GhostLine(48.dp, style = MaterialTheme.typography.labelMedium)
            }
        },
    )
}

@Composable
private fun AssetItem(asset: AssetRow) {
    ListItem(
        modifier = Modifier.testTag(ACCOUNT_ASSET_TAG),
        leadingContent = { TokenIcon(asset.icon, asset.symbol) },
        headlineContent = { Text(asset.symbol) },
        supportingContent = {
            Column {
                Text(asset.verifiedAt?.let { "${asset.name} · verified at $it" } ?: asset.name)
                asset.lastKnown?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                asset.warning?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        trailingContent = {
            // At most this wide, so a value with notes beside it ("€110.00 · Uniswap 30-min
            // average") wraps under itself rather than squeezing the token's name.
            Column(Modifier.widthIn(max = TRAILING_MAX_WIDTH), horizontalAlignment = Alignment.End) {
                Text(
                    asset.amount,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (asset.verified) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                asset.value?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        },
    )
}

/**
 * What the account screen shows of its network's light client: Detailed view's [Strip] of [Strip.item]
 * under the top bar, or simple mode's [Refresh], pull-to-refresh and a Refresh button asking
 * [Refresh.onRefresh], with the freshness line and the "can't check" banner in the strip's place.
 */
sealed interface AccountChrome {
    /** Detailed view: the light client's strip. */
    data class Strip(val item: LightClientItem) : AccountChrome

    /** Simple mode: pull-to-refresh, its button, and how fresh the values are. */
    data class Refresh(val onRefresh: () -> Unit) : AccountChrome
}

/** Why Refresh is greyed out on [network], which is off in this wallet. */
private fun refreshOffReason(network: String): String =
    "$network is off in this wallet, so nothing on it is checked. Turned on, it checks this account again."

/** A network that cannot be checked right now (simple mode's banner in the strip's place). */
@Composable
private fun CantCheck(banner: UnreachableNetwork, modifier: Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(banner.title, style = MaterialTheme.typography.titleSmall)
            Text(banner.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The widest an asset row's amount and value may be. */
private val TRAILING_MAX_WIDTH = 184.dp
