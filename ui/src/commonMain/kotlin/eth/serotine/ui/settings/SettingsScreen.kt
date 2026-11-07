package eth.serotine.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.components.LightClientHealthDot
import eth.serotine.ui.components.StaleAnchorBanner
import eth.serotine.ui.components.StaleAnchorDialog
import eth.serotine.ui.components.StaleAnchorWarning
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.mode.LocalInterfaceMode

/** Test tag of the app's version line, for UI tests. */
const val SETTINGS_VERSION_TAG = "settings-version"

/** Test tag of the debug build's screen-recording row, for UI tests. */
const val SETTINGS_SCREEN_CAPTURE_TAG = "settings-screen-capture"

/** Test tag of Detailed view's row, for UI tests. */
const val SETTINGS_DETAILED_VIEW_TAG = "settings-detailed-view"

/** Test tag of the Debug tab's row, for UI tests. */
const val SETTINGS_DEBUG_SCREEN_TAG = "settings-debug-screen"

/** Test tag of *Sign while syncing*'s row, for UI tests. */
const val SETTINGS_SIGN_WHILE_SYNCING_TAG = "settings-sign-while-syncing"

/** Test tag of the row that switches the heavy downloads on a metered connection, for UI tests. */
const val SETTINGS_METERED_TAG = "settings-metered"

/**
 * What a tap on Connected dApps says in a build without a WalletConnect project ID
 * (docs/walletconnect.md, ruling 2).
 */
internal const val CONNECTIONS_OFF =
    "This build has dApp connections switched off: it was built without a WalletConnect project ID."

/** What *Sign while syncing* does, the line under its switch (docs/lenient-mode.md, *The setting*). */
internal const val SIGN_WHILE_SYNCING = "Prepare and sign transactions before the light client has caught up. " +
    "They wait to be sent until they check out on chain. If something changed, you're asked to sign again."

/** Test tag of a network's row, followed by its chain id, for UI tests. */
const val SETTINGS_NETWORK_TAG = "settings-network-"

/** Binds [SettingsScreen], or in simple mode [SimpleSettingsScreen], to its ViewModel. */
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    onCurrency: () -> Unit,
    onTokens: () -> Unit,
    onSecurity: () -> Unit,
    modifier: Modifier = Modifier,
    onConnections: () -> Unit = {},
    connectionsOff: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleSettingsScreen(
            state,
            onCurrency,
            onTokens,
            onSecurity,
            viewModel::allowScreenCapture,
            viewModel::showDebugScreen,
            modifier,
            onNetworkChange = viewModel::switchNetwork,
            onConfirmTurnOff = viewModel::confirmTurnOff,
            onCancelTurnOff = viewModel::cancelTurnOff,
            onSyncPastStaleAnchor = viewModel::syncPastStaleAnchor,
            onDetailedViewChange = viewModel::showDetailedView,
            onConnections = onConnections,
            connectionsOff = connectionsOff,
            onSignWhileSyncingChange = viewModel::signWhileSyncing,
            onMeteredDownloadsChange = viewModel::allowHeavyDownloadsOnMetered,
        )
        InterfaceMode.DETAILED -> SettingsScreen(
            state,
            onCurrency,
            onTokens,
            onSecurity,
            viewModel::allowScreenCapture,
            viewModel::showDebugScreen,
            modifier,
            onNetworkChange = viewModel::switchNetwork,
            onConfirmTurnOff = viewModel::confirmTurnOff,
            onCancelTurnOff = viewModel::cancelTurnOff,
            onSyncPastStaleAnchor = viewModel::syncPastStaleAnchor,
            onDetailedViewChange = viewModel::showDetailedView,
            onConnections = onConnections,
            connectionsOff = connectionsOff,
            onSignWhileSyncingChange = viewModel::signWhileSyncing,
            onMeteredDownloadsChange = viewModel::allowHeavyDownloadsOnMetered,
        )
    }
}

/**
 * The Settings tab as a pure function of [state]; the display currency opens through
 * [onCurrency], Tokens through [onTokens], and Security (the wallet password, biometric unlock)
 * through [onSecurity]. A switch draws the screens in Detailed view through
 * [onDetailedViewChange] (docs/simple-ui.md, T1), and, where the state offers it (Detailed view),
 * another shows the Debug tab through [onDebugScreenChange]. In a debug
 * build, a switch allows screen recordings of the protected screens through [onScreenCaptureChange].
 * Each network's switch turns it on or off through [onNetworkChange] (its chain id, and whether
 * on); the confirmation before a network with accounts is turned off answers through
 * [onConfirmTurnOff] and [onCancelTurnOff] (docs/networks.md, N16 and N20). *Sign while syncing*
 * turns lenient mode on or off through [onSignWhileSyncingChange] (docs/lenient-mode.md). The
 * switch for the heavy downloads on a metered connection calls [onMeteredDownloadsChange] with the
 * new choice; greyed out, a tap says why it cannot be changed (owner ruling, 2026-10-20).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
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
) {
    var asking by remember { mutableStateOf<StaleAnchorWarning?>(null) }
    var explainingConnections by remember { mutableStateOf(false) }
    var explainingMetered by remember { mutableStateOf<String?>(null) }
    explainingMetered?.let { reason ->
        UnavailableDialog(UnavailableNotice(METERED_DOWNLOADS_TITLE, reason), onDismiss = { explainingMetered = null })
    }
    if (explainingConnections) {
        UnavailableDialog(
            UnavailableNotice("Connected dApps", CONNECTIONS_OFF),
            onDismiss = { explainingConnections = false },
        )
    }
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
    state.turningOff?.let { asked ->
        AlertDialog(
            onDismissRequest = onCancelTurnOff,
            title = { Text(asked.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(asked.text)
                    asked.waiting?.let { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = onConfirmTurnOff) { Text("Turn off") } },
            dismissButton = { TextButton(onClick = onCancelTurnOff) { Text("Cancel") } },
        )
    }
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Display")
            ListItem(
                modifier = Modifier.clickable(onClick = onCurrency),
                headlineContent = { Text("Display currency") },
                supportingContent = { Text(state.currency) },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onTokens),
                headlineContent = { Text("Tokens") },
                supportingContent = { Text(state.tokens) },
            )
            Section("Keys")
            ListItem(
                modifier = Modifier.clickable(onClick = onSecurity),
                headlineContent = { Text("Security") },
                supportingContent = { Text("Wallet password · Biometric unlock") },
            )
            Section("dApps")
            // Greyed out in a build without a WalletConnect project ID, and still tappable: the tap says why.
            ListItem(
                modifier = Modifier
                    .clickable(onClick = { if (connectionsOff) explainingConnections = true else onConnections() })
                    .unavailable(connectionsOff),
                headlineContent = {
                    Text(
                        "Connected dApps",
                        color = if (connectionsOff) Unavailable.contentColor() else Color.Unspecified,
                    )
                },
                supportingContent = {
                    Text(
                        if (connectionsOff) "Switched off in this build" else "WalletConnect · Ethereum, Gnosis Chain or Sepolia",
                        color = if (connectionsOff) Unavailable.contentColor() else Color.Unspecified,
                    )
                },
            )
            Section("Networks")
            state.networks.forEach { network ->
                ListItem(
                    modifier = Modifier
                        .toggleable(
                            value = network.on,
                            role = Role.Switch,
                            onValueChange = { onNetworkChange(network.chainId, it) },
                        )
                        .testTag(SETTINGS_NETWORK_TAG + network.chainId),
                    leadingContent = { LightClientHealthDot(network.health) },
                    headlineContent = { Text(if (network.testnet) "${network.name} · Testnet" else network.name) },
                    supportingContent = {
                        Column {
                            Text(network.line)
                            if (network.notSaved) {
                                Text("The change could not be saved.", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    // The row is the toggle: the switch only shows its state.
                    trailingContent = { Switch(checked = network.on, onCheckedChange = null) },
                )
                // Below the row, outside its toggle: a tap beside the button never turns the network off.
                network.staleAnchor?.let { warning ->
                    Box(Modifier.padding(start = 40.dp, end = 16.dp, bottom = 8.dp)) {
                        StaleAnchorBanner(warning, MaterialTheme.colorScheme.error) { asking = warning }
                    }
                }
            }
            Text(
                "A network that is off checks nothing; its accounts stay in your list.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MeteredDownloads(state.meteredDownloads, onMeteredDownloadsChange) { explainingMetered = it }
            Section("Advanced")
            ListItem(
                modifier = Modifier
                    .toggleable(value = state.detailedView, role = Role.Switch, onValueChange = onDetailedViewChange)
                    .testTag(SETTINGS_DETAILED_VIEW_TAG),
                headlineContent = { Text("Detailed view") },
                supportingContent = {
                    Column {
                        Text("Show the light client's state, blocks and proofs on every screen.")
                        state.detailedViewNote?.let { Text(it) }
                        if (state.detailedViewNotSaved) {
                            Text("The change could not be saved.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                // The row is the toggle: the switch only shows its state.
                trailingContent = { Switch(checked = state.detailedView, onCheckedChange = null) },
            )
            state.debugScreen?.let { debugScreen ->
                ListItem(
                    modifier = Modifier
                        .toggleable(value = debugScreen, role = Role.Switch, onValueChange = onDebugScreenChange)
                        .testTag(SETTINGS_DEBUG_SCREEN_TAG),
                    headlineContent = { Text("Debug tab") },
                    supportingContent = {
                        Column {
                            Text(
                                "Adds a tab with a log of the light client's requests, kept in memory on this " +
                                    "device only. Turning it off empties the log.",
                            )
                            if (state.debugScreenNotSaved) {
                                Text("The change could not be saved.", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    // The row is the toggle: the switch only shows its state.
                    trailingContent = { Switch(checked = debugScreen, onCheckedChange = null) },
                )
            }
            ListItem(
                modifier = Modifier
                    .toggleable(
                        value = state.signWhileSyncing,
                        role = Role.Switch,
                        onValueChange = onSignWhileSyncingChange,
                    )
                    .testTag(SETTINGS_SIGN_WHILE_SYNCING_TAG),
                headlineContent = { Text("Sign while syncing") },
                supportingContent = {
                    Column {
                        Text(SIGN_WHILE_SYNCING)
                        state.signWhileSyncingNote?.let { Text(it) }
                        if (state.signWhileSyncingNotSaved) {
                            Text("The change could not be saved.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                // The row is the toggle: the switch only shows its state.
                trailingContent = { Switch(checked = state.signWhileSyncing, onCheckedChange = null) },
            )
            state.screenCaptureAllowed?.let { allowed ->
                Section("Debug build")
                ListItem(
                    modifier = Modifier
                        .toggleable(value = allowed, role = Role.Switch, onValueChange = onScreenCaptureChange)
                        .testTag(SETTINGS_SCREEN_CAPTURE_TAG),
                    headlineContent = { Text("Allow screen recording") },
                    supportingContent = {
                        Text(
                            "Lets screenshots and screen recordings capture the screens that show a recovery " +
                                "phrase or take the wallet password. Off again when the app restarts.",
                        )
                    },
                    // The row is the toggle: the switch only shows its state.
                    trailingContent = { Switch(checked = allowed, onCheckedChange = null) },
                )
            }
            Section("About")
            ListItem(
                // One merged node: a screen reader announces "Punicus, version 0.1.0".
                modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(SETTINGS_VERSION_TAG),
                headlineContent = { Text("Punicus") },
                supportingContent = { Text("Version ${state.version}") },
            )
        }
    }
}

private const val METERED_DOWNLOADS_TITLE = "Heavy downloads on metered connections"

/**
 * The switch for the heavy downloads on a metered connection: the whole row toggles it, or, greyed
 * out, says why it cannot through [onUnavailable]; while [item] is not read yet
 * ([SettingsUiState.meteredDownloads] null) it is greyed out and takes no tap.
 */
@Composable
private fun MeteredDownloads(
    item: MeteredDownloadsItem?,
    onChange: (Boolean) -> Unit,
    onUnavailable: (String) -> Unit,
) {
    val reason = item?.unavailableReason
    val enabled = item != null && reason == null
    val row = when {
        item == null -> Modifier
        reason == null -> Modifier.toggleable(value = item.on, role = Role.Switch, onValueChange = onChange)
        else -> Modifier.clickable { onUnavailable(reason) }.unavailable(true)
    }
    val color = if (enabled) Color.Unspecified else Unavailable.contentColor()
    ListItem(
        modifier = row.testTag(SETTINGS_METERED_TAG),
        headlineContent = { Text(METERED_DOWNLOADS_TITLE, color = color) },
        supportingContent = item?.let { { Text(it.text, color = color) } },
        // The row takes the tap, so a greyed switch still says why.
        trailingContent = { Switch(checked = item?.on == true, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}
