package eth.serotine.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.HideAmountsButton
import eth.serotine.ui.components.LightClientHealthDot
import eth.serotine.ui.components.MenuAction
import eth.serotine.ui.components.NameWithControlBadge
import eth.serotine.ui.components.NetworkMark
import eth.serotine.ui.components.SEND_FROM_ANOTHER_WALLET
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.components.SpeedDialAction
import eth.serotine.ui.components.SpeedDialFab
import eth.serotine.ui.components.StaleAnchorBanner
import eth.serotine.ui.components.StaleAnchorDialog
import eth.serotine.ui.components.StaleAnchorWarning
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableFix
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.WithActionMenu
import eth.serotine.ui.components.addOnNetworkOffReason
import eth.serotine.ui.components.networkOffNotice
import eth.serotine.ui.mode.LocalInterfaceMode
import eth.serotine.ui.platform.rememberCopyText
import eth.serotine.ui.update.UpgradeBanner
import kotlinx.coroutines.launch

/** Test tag of the account list, for UI tests. */
const val ACCOUNTS_LIST_TAG = "accounts-list"

/** Test tag of the notice when the stored list is not what is shown: set aside, or unavailable. */
const val ACCOUNTS_NOTICE_TAG = "accounts-notice"

/** Test tag of a network's section header, followed by its chain id, for UI tests. */
const val ACCOUNTS_SECTION_TAG = "accounts-section-"

/** Test tag prefix of a network's light-client line on the empty Accounts screen (#66), plus its chain id. */
const val ACCOUNTS_EMPTY_LIGHT_CLIENT_TAG = "accounts-empty-light-client-"

/** Test tag of the portfolio's total, for UI tests. */
const val ACCOUNTS_TOTAL_TAG = "accounts-total"

/**
 * Binds [AccountsScreen], or in simple mode [SimpleAccountsScreen], to its ViewModel: the only
 * composable that knows the ViewModel exists. Copy is done here, on the platform's clipboard;
 * every other action is [onAccountAction]'s, which navigates.
 */
@Composable
fun AccountsRoute(
    viewModel: AccountsViewModel,
    onWatchAddress: () -> Unit,
    onImportPhrase: () -> Unit,
    onCreateAccount: () -> Unit,
    onCreateSafe: () -> Unit,
    onAccountAction: (AccountAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val copy = rememberCopyText()
    val act: (AccountAction) -> Unit = { action ->
        if (action is AccountAction.Copy) copy(action.address) else onAccountAction(action)
    }
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleAccountsScreen(
            state = state,
            onWatchAddress = onWatchAddress,
            onImportPhrase = onImportPhrase,
            onCreateAccount = onCreateAccount,
            onCreateSafe = onCreateSafe,
            onAccountAction = act,
            onRefresh = viewModel::refresh,
            modifier = modifier,
            onTurnOnNetwork = viewModel::turnOnNetwork,
            onSyncPastStaleAnchor = viewModel::syncPastStaleAnchor,
            onMessageShown = viewModel::messageShown,
            onToggleAmounts = viewModel::toggleAmounts,
            onDismissLost = viewModel::dismissLost,
        )
        InterfaceMode.DETAILED -> AccountsScreen(
            state = state,
            onWatchAddress = onWatchAddress,
            onImportPhrase = onImportPhrase,
            onCreateAccount = onCreateAccount,
            onCreateSafe = onCreateSafe,
            onAccountAction = act,
            modifier = modifier,
            onTurnOnNetwork = viewModel::turnOnNetwork,
            onSyncPastStaleAnchor = viewModel::syncPastStaleAnchor,
            onMessageShown = viewModel::messageShown,
            onToggleAmounts = viewModel::toggleAmounts,
            onDismissLost = viewModel::dismissLost,
        )
    }
}

/**
 * The Accounts screen as a pure function of [state]; stateless, so a test can render any
 * state directly. Its floating action button fans out into Watch address ([onWatchAddress]),
 * Import recovery phrase ([onImportPhrase]), Create account ([onCreateAccount]) and Create Safe
 * ([onCreateSafe]), greyed out until an account's key is known to be held here to pay for its
 * deployment (while the list is read, too), a tap then saying why, with the ways to get one; a
 * row's tap and its long-press menu (a right click on desktop) ask [onAccountAction], its Send
 * greyed out with its reason where it cannot be done (owner ruling, 2026-10-18, #55). The accounts
 * are in a section per network (docs/networks.md, N1), each headed by its network, its light client
 * and its total; on a network that is off, Send's reason offers to turn it on ([onTurnOnNetwork],
 * N18). A network whose light client refuses a stale checkpoint warns in its section, and its
 * "Catch up anyway…" asks first, then [onSyncPastStaleAnchor] (owner's request, 2026-10-24).
 * [fabExpandedAtStart] opens the button's fan from the start (a preview). Once the list is read, the
 * eye at the top left hides or shows every amount ([onToggleAmounts]; docs/wallet-ux.md, *Hiding
 * amounts*). What lenient mode's outbox holds is said under each network's line, and a queued
 * transaction lost once, until [onDismissLost].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    state: AccountsUiState,
    onWatchAddress: () -> Unit,
    onImportPhrase: () -> Unit,
    onCreateAccount: () -> Unit,
    onCreateSafe: () -> Unit,
    onAccountAction: (AccountAction) -> Unit,
    modifier: Modifier = Modifier,
    fabExpandedAtStart: Boolean = false,
    onTurnOnNetwork: (chainId: Long) -> Unit = {},
    onSyncPastStaleAnchor: (chainId: Long) -> Unit = {},
    onMessageShown: () -> Unit = {},
    onToggleAmounts: () -> Unit = {},
    onDismissLost: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    // Said once: a network that could not be turned on.
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
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            AddAccountButton(state, onWatchAddress, onImportPhrase, onCreateAccount, onCreateSafe, fabExpandedAtStart) {
                unavailable = it
            }
        },
        // No network in the top bar: each section names its own (docs/networks.md, N1).
        topBar = {
            TopAppBar(
                // Top left, while there are amounts to hide.
                navigationIcon = {
                    (state as? AccountsUiState.Ready)?.let { HideAmountsButton(it.amountsHidden, onToggleAmounts) }
                },
                title = { Text("Accounts") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.upgrades.forEach { UpgradeBanner(it) }
            // With accounts listed, the notice scrolls with them: a long one never squeezes them out.
            (state as? AccountsUiState.Ready)?.takeIf { it.accounts.isEmpty() }?.lostNotice
                ?.let { LostQueuedNotice(it, onDismissLost) }
            (state as? AccountsUiState.Ready)?.let { ready ->
                ready.total?.let { PortfolioTotal(it, ready.totalLabel, ready.totalNote) }
            }
            (state as? AccountsUiState.Ready)?.notice?.let { notice ->
                Text(
                    notice,
                    Modifier.padding(16.dp).testTag(ACCOUNTS_NOTICE_TAG),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Rows(state, act, onTurnOnNetwork, onDismissLost, onCatchUp = { asking = it }) { unavailable = it }
            }
        }
    }
}

/**
 * The + button of the Accounts screen, in either view: Watch address ([onWatchAddress]), Import
 * recovery phrase ([onImportPhrase]), Create account ([onCreateAccount]) and Create Safe
 * ([onCreateSafe]), greyed out until an account's key is known to be held here to pay for its
 * deployment, a tap then saying why through [onUnavailable] (owner ruling, 2026-10-18, #55). None
 * while the stored list cannot be read: an add would be refused.
 */
@Composable
internal fun AddAccountButton(
    state: AccountsUiState,
    onWatchAddress: () -> Unit,
    onImportPhrase: () -> Unit,
    onCreateAccount: () -> Unit,
    onCreateSafe: () -> Unit,
    expandedAtStart: Boolean,
    onUnavailable: (UnavailableNotice) -> Unit,
) {
    if (state is AccountsUiState.ListUnavailable) return
    // An account held here pays for the deployment (docs/wallet-ux.md, screen 2): until one is known
    // to be, Create Safe is greyed out, and says so; the ways to get one only once the list is read
    // and has none.
    val createSafeReason = state.createSafeReason
    val createSafeFixes = if (state is AccountsUiState.Ready) {
        listOf(
            UnavailableFix("Import recovery phrase", onImportPhrase),
            UnavailableFix("Create account", onCreateAccount),
        )
    } else {
        emptyList()
    }
    SpeedDialFab(
        actions = listOf(
            SpeedDialAction("Watch address", SerotineIcons.Visibility, onClick = onWatchAddress),
            SpeedDialAction("Import recovery phrase", SerotineIcons.Download, onClick = onImportPhrase),
            SpeedDialAction("Create account", Icons.Filled.Add, onClick = onCreateAccount),
            SpeedDialAction("Create Safe", Icons.Filled.Lock, unavailable = createSafeReason != null) {
                if (createSafeReason == null) {
                    onCreateSafe()
                } else {
                    onUnavailable(UnavailableNotice("Create Safe", createSafeReason, createSafeFixes))
                }
            },
        ),
        description = "Add account",
        expandedAtStart = expandedAtStart,
    )
}

/**
 * The portfolio's total in the display currency, over the networks [label] names, and what it
 * leaves out (docs/wallet-ux.md, screen 1; docs/networks.md, N1).
 */
@Composable
private fun PortfolioTotal(total: String, label: String?, note: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        label?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(total, Modifier.testTag(ACCOUNTS_TOTAL_TAG), style = MaterialTheme.typography.headlineMedium)
        note?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BoxScope.Rows(
    state: AccountsUiState,
    onAccountAction: (AccountAction) -> Unit,
    onTurnOnNetwork: (Long) -> Unit,
    onDismissLost: () -> Unit,
    onCatchUp: (StaleAnchorWarning) -> Unit,
    onUnavailable: (UnavailableNotice) -> Unit,
) {
    when (state) {
        is AccountsUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        is AccountsUiState.ListUnavailable -> Text(
            state.message,
            Modifier.align(Alignment.Center).padding(24.dp).testTag(ACCOUNTS_NOTICE_TAG),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        is AccountsUiState.Ready ->
            if (state.accounts.isEmpty()) {
                Column(Modifier.fillMaxSize()) {
                    state.lightClients.forEach { EmptyWalletLightClient(it) }
                    Box(Modifier.weight(1f).fillMaxWidth()) { NoAccounts(Modifier.align(Alignment.Center)) }
                }
            } else {
                AccountList(
                    state.sections,
                    state.lostNotice,
                    onAccountAction,
                    onTurnOnNetwork,
                    onDismissLost,
                    onCatchUp,
                    onUnavailable,
                )
            }
    }
}

/**
 * What the empty Accounts screen says: every way the + button adds an account, and why Create Safe
 * is greyed out there until an account whose key is on this device can pay for its deployment
 * (docs/wallet-ux.md, screen 2).
 */
internal const val NO_ACCOUNTS_HINT = "Add an account with the + button: watch an address, import a recovery " +
    "phrase or create an account. Create Safe is there too, and can be used once an account's key is on this " +
    "device, because that account pays for the Safe's deployment."

@Composable
internal fun NoAccounts(modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No accounts yet", style = MaterialTheme.typography.titleMedium)
        Text(
            NO_ACCOUNTS_HINT,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A network's light client on the empty Accounts screen (#66): its mark and its status strip's
 * line, as a section header draws them, with no total and no accounts.
 */
@Composable
private fun EmptyWalletLightClient(network: NetworkLightClient) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(ACCOUNTS_EMPTY_LIGHT_CLIENT_TAG + network.chainId),
    ) {
        NetworkMark(network.chainId, network.name, network.testnet)
        Row(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightClientHealthDot(network.status.health)
            Text(
                network.status.text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccountList(
    sections: List<NetworkSection>,
    lostNotice: String?,
    onAccountAction: (AccountAction) -> Unit,
    onTurnOnNetwork: (Long) -> Unit,
    onDismissLost: () -> Unit,
    onCatchUp: (StaleAnchorWarning) -> Unit,
    onUnavailable: (UnavailableNotice) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().testTag(ACCOUNTS_LIST_TAG)) {
        lostNotice?.let { lost -> item(key = "lost") { LostQueuedNotice(lost, onDismissLost) } }
        sections.forEach { section ->
            item(key = "section/${section.chainId}") { SectionHeader(section, onCatchUp) }
            items(section.accounts, key = { "${it.chainId}/${it.address}" }) { account ->
                WithActionMenu(
                    actions = accountMenu(account, onAccountAction, onTurnOnNetwork, onUnavailable),
                    menuLabel = "Actions for ${account.label}",
                    onClick = { onAccountAction(AccountAction.Open(account.chainId, account.address)) },
                    clickLabel = "Open ${account.label}",
                ) {
                    // An account on a network that is off stays listed, dimmed (N18).
                    AccountRow(account, Modifier.alpha(if (section.on) 1f else DIMMED))
                }
                HorizontalDivider()
            }
        }
    }
}

/** How opaque a row on a network that is off is drawn. */
private const val DIMMED = 0.6f

/**
 * A network's section header (docs/networks.md, N1): its mark and total, and below them its light
 * client's line, which is the section's status strip, with what the total leaves out, and a stale
 * checkpoint's warning with its "Catch up anyway…" ([onCatchUp]).
 */
@Composable
private fun SectionHeader(section: NetworkSection, onCatchUp: (StaleAnchorWarning) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val alarmed = section.blocked != null || section.staleAnchor != null
    // On the error container, its own content colour; on the usual surface, the muted one.
    val secondary = if (alarmed) colors.onErrorContainer else colors.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth()
            // A network this version can no longer follow, or that refuses a stale checkpoint, is
            // drawn in the error colour.
            .background(if (alarmed) colors.errorContainer else colors.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { heading() }
            .testTag(ACCOUNTS_SECTION_TAG + section.chainId),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetworkMark(section.chainId, section.name, section.testnet, Modifier.weight(1f))
            section.total?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        }
        Row(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightClientHealthDot(section.status.health)
            Text(
                section.status.text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = secondary,
            )
        }
        section.totalNote?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = secondary)
        }
        // What its accounts have queued in lenient mode, under its light client's line.
        section.queued?.let {
            Text(
                it,
                Modifier.testTag(ACCOUNTS_QUEUED_TAG),
                style = MaterialTheme.typography.labelMedium,
                color = if (alarmed) secondary else colors.tertiary,
            )
        }
        section.blocked?.let {
            Text(
                it,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        section.staleAnchor?.let { warning ->
            StaleAnchorBanner(warning, colors.onErrorContainer) { onCatchUp(warning) }
        }
    }
}

/**
 * The long-press menu of an account row: docs/wallet-ux.md, screen 3. Send greyed out where the
 * account cannot send, a tap saying why ([AccountItem.sendReason]); its detail screen has the
 * same as buttons.
 */
internal fun accountMenu(
    account: AccountItem,
    act: (AccountAction) -> Unit,
    onTurnOnNetwork: (Long) -> Unit,
    onUnavailable: (UnavailableNotice) -> Unit,
): List<MenuAction> {
    val chainId = account.chainId
    val address = account.address
    val sendReason = account.sendReason
    // On a network that is off, the way to Send is to turn it on (docs/networks.md, N19).
    val sendNotice = sendReason?.let { reason ->
        account.networkOff?.let { network ->
            networkOffNotice("Send needs $network", reason, network) { onTurnOnNetwork(chainId) }
        } ?: UnavailableNotice("Send", reason)
    }
    return listOfNotNull(
        MenuAction("Send", Icons.AutoMirrored.Filled.Send, unavailable = sendNotice != null) {
            when {
                sendNotice != null -> onUnavailable(sendNotice)
                account.kind == AccountKind.SAFE -> act(AccountAction.Propose(chainId, address))
                else -> act(AccountAction.Send(chainId, address))
            }
        },
        // Send from another wallet (#255): for an EOA whose key is not here, beside the greyed-out Send.
        account.handoff?.let { handoff ->
            MenuAction(SEND_FROM_ANOTHER_WALLET, Icons.AutoMirrored.Filled.Send, unavailable = !handoff.available) {
                val reason = handoff.reason
                val off = account.networkOff
                when {
                    reason == null -> act(AccountAction.SendElsewhere(chainId, address))
                    off != null -> onUnavailable(
                        networkOffNotice("$SEND_FROM_ANOTHER_WALLET needs $off", reason, off) {
                            onTurnOnNetwork(chainId)
                        },
                    )
                    else -> onUnavailable(UnavailableNotice(SEND_FROM_ANOTHER_WALLET, reason))
                }
            }
        },
        MenuAction("Receive", SerotineIcons.QrCode) { act(AccountAction.Receive(chainId, address)) },
        MenuAction("Copy address", SerotineIcons.ContentCopy) { act(AccountAction.Copy(chainId, address)) },
        MenuAction("Rename", Icons.Filled.Edit) { act(AccountAction.Rename(chainId, address)) },
        // An EOA's address is the same account on every chain (N5); a Safe exists where it was
        // deployed. From a network that is off, greyed out with Turn on (docs/networks.md, N18).
        MenuAction("Add on another network…", Icons.Filled.Add, unavailable = account.networkOff != null) {
            val off = account.networkOff
            if (off == null) {
                act(AccountAction.AddOnNetwork(chainId, address))
            } else {
                onUnavailable(
                    networkOffNotice("Add on another network", addOnNetworkOffReason(off, account.label), off) {
                        onTurnOnNetwork(chainId)
                    },
                )
            }
        }.takeIf { account.kind == AccountKind.EOA },
        // The next account of its phrase, from the seed stored here; greyed out while its key is not.
        deriveBlockedReason(account.control).let { reason ->
            MenuAction("$DERIVE_ACTION…", SerotineIcons.Key, unavailable = reason != null) {
                if (reason == null) {
                    act(AccountAction.Derive(chainId, address))
                } else {
                    onUnavailable(UnavailableNotice(DERIVE_ACTION, reason))
                }
            }
        }.takeIf { account.kind == AccountKind.EOA },
        MenuAction("Remove", Icons.Filled.Delete, destructive = true) { act(AccountAction.Remove(chainId, address)) },
    )
}

@Composable
private fun AccountRow(account: AccountItem, modifier: Modifier = Modifier) {
    ListItem(
        modifier = modifier,
        leadingContent = { AccountAvatar(account.address, threshold = account.signers) },
        headlineContent = { NameWithControlBadge(account.label, account.control) },
        supportingContent = {
            Column {
                Text(account.kindNote?.let { "${account.shortAddress} · $it" } ?: account.shortAddress)
                // The ENS name the row was named by, which no longer points here: why it shows the address.
                account.nameNote?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                // A balance last verified, not current: said under the row, where there is room.
                account.balanceNote?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    account.balance,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (account.balanceVerified) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                account.value?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
