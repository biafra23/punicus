package eth.serotine.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.HideAmountsButton
import eth.serotine.ui.components.NameWithControlBadge
import eth.serotine.ui.components.StaleAnchorBanner
import eth.serotine.ui.components.StaleAnchorDialog
import eth.serotine.ui.components.StaleAnchorWarning
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.WithActionMenu
import eth.serotine.ui.update.UpgradeBanner
import kotlinx.coroutines.launch

/** Test tag of simple mode's freshness line, for UI tests. */
const val ACCOUNTS_FRESHNESS_TAG = "accounts-freshness"

/** Test tag of simple mode's Refresh button, for UI tests. */
const val ACCOUNTS_REFRESH_TAG = "accounts-refresh"

/** Test tag of a network's "can't check" banner in simple mode, followed by its chain id, for UI tests. */
const val ACCOUNTS_UNREACHABLE_TAG = "accounts-unreachable-"

/**
 * The Accounts screen in simple mode (docs/simple-ui.md, H1 to H3), a pure function of the same
 * [state] as [AccountsScreen]: the total with how fresh the values are under it, and one list of
 * the accounts, each with its network's icon on its blockie and its network named under its name.
 * No section per network and no light-client strip: a network that cannot be checked while it shows
 * balances from before gets a banner, and those rows are dimmed with their age. Pulling the list
 * down, or the Refresh button, asks [onRefresh]. Everything else is as [AccountsScreen] has it: the
 * + button, a row's tap and long-press menu, the greyed-out actions and their reasons, the upgrade
 * banners and a stale checkpoint's warning with its "Catch up anyway…", and the eye at the top left
 * that hides or shows every amount ([onToggleAmounts]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleAccountsScreen(
    state: AccountsUiState,
    onWatchAddress: () -> Unit,
    onImportPhrase: () -> Unit,
    onCreateAccount: () -> Unit,
    onCreateSafe: () -> Unit,
    onAccountAction: (AccountAction) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    fabExpandedAtStart: Boolean = false,
    onTurnOnNetwork: (chainId: Long) -> Unit = {},
    onSyncPastStaleAnchor: (chainId: Long) -> Unit = {},
    onMessageShown: () -> Unit = {},
    onToggleAmounts: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val message = (state as? AccountsUiState.Ready)?.message
    val shown by rememberUpdatedState(onMessageShown)
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            shown()
        }
    }
    var unavailable by remember { mutableStateOf<UnavailableNotice?>(null) }
    unavailable?.let { notice -> UnavailableDialog(notice, onDismiss = { unavailable = null }) }
    var asking by remember { mutableStateOf<StaleAnchorWarning?>(null) }
    asking?.let { warning ->
        StaleAnchorDialog(
            warning,
            onConfirm = {
                asking = null
                onSyncPastStaleAnchor(warning.chainId)
            },
            onDismiss = { asking = null },
        )
    }
    val scope = rememberCoroutineScope()
    val act: (AccountAction) -> Unit = { action ->
        onAccountAction(action)
        if (action is AccountAction.Copy) scope.launch { snackbar.showSnackbar("Address copied") }
    }
    val ready = state as? AccountsUiState.Ready
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            AddAccountButton(state, onWatchAddress, onImportPhrase, onCreateAccount, onCreateSafe, fabExpandedAtStart) {
                unavailable = it
            }
        },
        topBar = {
            TopAppBar(
                // Top left, while there are amounts to hide.
                navigationIcon = { ready?.let { HideAmountsButton(it.amountsHidden, onToggleAmounts) } },
                title = { Text("Accounts") },
                actions = {
                    // Pulling down is not the only way: a mouse, a screen reader, a keyboard get a button.
                    if (ready != null && ready.accounts.isNotEmpty()) {
                        IconButton(onClick = onRefresh, Modifier.testTag(ACCOUNTS_REFRESH_TAG)) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = ready?.refreshing == true,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when (state) {
                is AccountsUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is AccountsUiState.ListUnavailable -> Column(Modifier.fillMaxSize()) {
                    state.upgrades.forEach { UpgradeBanner(it) }
                    Text(
                        state.message,
                        Modifier.padding(24.dp).testTag(ACCOUNTS_NOTICE_TAG),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                is AccountsUiState.Ready -> if (state.accounts.isEmpty()) {
                    Column(Modifier.fillMaxSize()) {
                        state.upgrades.forEach { UpgradeBanner(it) }
                        // A damaged list set aside is said exactly when the list that follows is empty.
                        state.notice?.let { Notice(it) }
                        Box(Modifier.weight(1f).fillMaxWidth()) { NoAccounts(Modifier.align(Alignment.Center)) }
                    }
                } else {
                    SimpleAccountList(state, act, onTurnOnNetwork, onCatchUp = { asking = it }) { unavailable = it }
                }
            }
        }
    }
}

@Composable
private fun SimpleAccountList(
    state: AccountsUiState.Ready,
    onAccountAction: (AccountAction) -> Unit,
    onTurnOnNetwork: (Long) -> Unit,
    onCatchUp: (StaleAnchorWarning) -> Unit,
    onUnavailable: (UnavailableNotice) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().testTag(ACCOUNTS_LIST_TAG)) {
        items(state.upgrades) { UpgradeBanner(it) }
        items(state.unreachable, key = { "unreachable/${it.chainId}" }) { Unreachable(it) }
        // A network this version can no longer follow, or that refuses a stale checkpoint: said in both views.
        items(state.sections.filter { it.blocked != null || it.staleAnchor != null }, key = { "alarm/${it.chainId}" }) {
            NetworkAlarm(it, onCatchUp)
        }
        state.notice?.let { notice -> item(key = "notice") { Notice(notice) } }
        val totalNote = state.simpleTotalNote ?: state.totalNote
        if (state.total != null || state.freshness != null || totalNote != null) {
            item(key = "total") { SimpleTotal(state.total, state.freshness, totalNote) }
        }
        items(state.accounts, key = { "${it.chainId}/${it.address}" }) { account ->
            WithActionMenu(
                actions = accountMenu(account, onAccountAction, onTurnOnNetwork, onUnavailable),
                menuLabel = "Actions for ${account.label}",
                onClick = { onAccountAction(AccountAction.Open(account.chainId, account.address)) },
                clickLabel = "Open ${account.label}",
            ) {
                // A balance from before, or a network that is off: the row dimmed, its age said.
                SimpleAccountRow(account, Modifier.alpha(if (account.ageNote != null) DIMMED else 1f))
            }
            HorizontalDivider()
        }
    }
}

/** The stored list damaged and set aside, so these are only the accounts added since: in the error colour. */
@Composable
private fun Notice(notice: String) {
    Text(
        notice,
        Modifier.padding(16.dp).testTag(ACCOUNTS_NOTICE_TAG),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/** How opaque a row showing a balance from before, or on a network that is off, is drawn. */
private const val DIMMED = 0.6f

/**
 * The total, large, with what it leaves out right under it ([note]: "Testnet prices", "2 Ethereum
 * accounts not counted") and how fresh the values are ("Updated 2 min ago · pull down to refresh").
 * Without a total (only a testnet beside networks that are off), the note and the freshness alone.
 */
@Composable
private fun SimpleTotal(total: String?, freshness: String?, note: String?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        total?.let {
            Text("Total", style = MaterialTheme.typography.labelMedium, color = muted)
            Text(it, Modifier.testTag(ACCOUNTS_TOTAL_TAG), style = MaterialTheme.typography.displaySmall)
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = muted, textAlign = TextAlign.Center)
        }
        freshness?.let {
            Text(
                it,
                Modifier.testTag(ACCOUNTS_FRESHNESS_TAG),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A network that cannot be checked right now while its balances from before are shown (H3). */
@Composable
private fun Unreachable(network: UnreachableNetwork) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(ACCOUNTS_UNREACHABLE_TAG + network.chainId),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(network.title, style = MaterialTheme.typography.titleSmall)
            Text(network.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * A network this version can no longer follow, or whose light client refuses a stale checkpoint,
 * with its "Catch up anyway…" ([onCatchUp]): in the error colour, as its section is in Detailed view.
 */
@Composable
private fun NetworkAlarm(section: NetworkSection, onCatchUp: (StaleAnchorWarning) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(ACCOUNTS_SECTION_TAG + section.chainId),
        shape = MaterialTheme.shapes.medium,
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(section.name, style = MaterialTheme.typography.titleSmall)
            section.blocked?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            section.staleAnchor?.let { warning ->
                StaleAnchorBanner(warning, colors.onErrorContainer) { onCatchUp(warning) }
            }
        }
    }
}

@Composable
private fun SimpleAccountRow(account: AccountItem, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        modifier = modifier,
        leadingContent = { AccountAvatar(account.address, threshold = account.signers, network = account.network) },
        headlineContent = { NameWithControlBadge(account.label, account.control) },
        supportingContent = {
            Column {
                if (account.network?.testnet == true) {
                    // A testnet's accounts never look like real funds (owner ruling, 2026-10-22).
                    Surface(shape = MaterialTheme.shapes.small, color = colors.tertiaryContainer) {
                        Text(
                            account.networkLine,
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onTertiaryContainer,
                        )
                    }
                } else {
                    Text(account.networkLine)
                }
                account.nameNote?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.tertiary)
                }
                account.ageNote?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.tertiary)
                }
            }
        },
        trailingContent = {
            // The account's value first, its native balance under it; on a testnet, which has no
            // real value, the balance first.
            val testnet = account.network?.testnet == true
            val first = if (testnet) account.balance else account.accountTotal ?: account.balance
            val second = if (testnet) account.accountTotal else account.balance.takeIf { account.accountTotal != null }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    first,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (account.balanceVerified) colors.onSurface else colors.onSurfaceVariant,
                )
                second?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
            }
        },
    )
}
