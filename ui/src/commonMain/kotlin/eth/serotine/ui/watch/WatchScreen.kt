package eth.serotine.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.AddressLine
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.ControlBadge
import eth.serotine.ui.components.HiddenDetails
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NETWORK_CHOICE_TAG
import eth.serotine.ui.components.NetworkMark
import eth.serotine.ui.components.Note
import eth.serotine.ui.components.RetryLookupButton
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.components.done
import eth.serotine.ui.components.next
import eth.serotine.ui.components.nextTo
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.components.submitOnDone
import eth.serotine.ui.lightclient.LightClientViewModel
import eth.serotine.ui.mode.LocalInterfaceMode

/** Test tag of the address field, for UI tests. */
const val WATCH_ADDRESS_TAG = "watch-address"

/** Test tag of the name field, for UI tests. */
const val WATCH_NAME_TAG = "watch-name"

/** Test tag of the detection card, for UI tests. */
const val WATCH_DETECTION_TAG = "watch-detection"

/** Test tag of the *Details* that keep the blocks in simple mode, for UI tests. */
const val WATCH_DETAILS_TAG = "watch-details"

/** Test tag of the checksummed address on the detection card, for UI tests. */
const val WATCH_CHECKSUMMED_TAG = "watch-checksummed"

/** Test tag of the network named above the one detection card, for UI tests. */
const val WATCH_CARD_NETWORK_TAG = "watch-card-network"

/** What the address field says while nothing else is to be said of it. */

/** What the name field says while an empty name leaves the account named by the ENS name typed. */
private const val NAME_FOLLOWS_ENS = "Leave empty to name it by its ENS name, for as long as the name points to it"

/** Binds [WatchScreen] to its ViewModel; an added address ends the screen through [onAdded]. */
@Composable
fun WatchRoute(
    viewModel: WatchViewModel,
    lightClient: LightClientViewModel,
    onBack: () -> Unit,
    onAdded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val network by viewModel.stripNetwork.collectAsStateWithLifecycle()
    val added by rememberUpdatedState(onAdded)
    LaunchedEffect(state.done) {
        if (state.done) added()
    }
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleWatchScreen(
            state,
            viewModel::onAddressChange,
            viewModel::onNameChange,
            viewModel::add,
            onBack,
            modifier,
            onRetryLookup = viewModel::onRetryLookup,
            onChooseNetworks = viewModel::onChooseNetworks,
            onNetworkToggle = viewModel::onNetworkToggle,
            onTurnOnNetwork = viewModel::onTurnOnNetwork,
            syncLine = lightClient.item(network).collectAsStateWithLifecycle().value,
        )
        // The strip in Detailed view; in simple mode the same item, drawn as the sync line while it says something.
        InterfaceMode.DETAILED -> WatchScreen(
            state,
            lightClient.item(network).collectAsStateWithLifecycle().value,
            viewModel::onAddressChange,
            viewModel::onNameChange,
            viewModel::add,
            onBack,
            modifier,
            onRetryLookup = viewModel::onRetryLookup,
            onChooseNetworks = viewModel::onChooseNetworks,
            onNetworkToggle = viewModel::onNetworkToggle,
            onTurnOnNetwork = viewModel::onTurnOnNetwork,
        )
    }
}

/**
 * Watch address (docs/wallet-ux.md, screen 10) as a pure function of [state]: the address or
 * ENS name and a name, the detection card built from verified code, and Add, enabled only for
 * a verified account, under the network it was read on. [onRetryLookup] resolves a name again that
 * could not be verified. With two or more networks on, a card per network (docs/networks.md, N10 and
 * N11): an address with no code on all of them is offered on all of them, with [onChooseNetworks] to
 * tick them instead ([onNetworkToggle]); a Safe only where it is one, ticked. A network that is off is listed greyed
 * out, turned on through [onTurnOnNetwork]. [lightClient] is the strip above it, Detailed view's;
 * simple mode ([SimpleWatchScreen]) has none, and keeps the blocks behind *Details* ([details]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(
    state: WatchUiState,
    lightClient: LightClientItem?,
    onAddressChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryLookup: () -> Unit = {},
    onChooseNetworks: () -> Unit = {},
    onNetworkToggle: (chainId: Long) -> Unit = {},
    onTurnOnNetwork: (chainId: Long) -> Unit = {},
    details: List<String> = emptyList(),
    syncLine: LightClientItem? = null,
) {
    val nameField = remember { FocusRequester() }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Watch address") },
            )
        },
    ) { padding ->
        UnderLightClientStripIfAny(lightClient, Modifier.padding(padding), syncLine) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.address,
                    onValueChange = onAddressChange,
                    modifier = Modifier.fillMaxWidth().testTag(WATCH_ADDRESS_TAG),
                    enabled = !state.adding,
                    label = { Text(state.addressLabel) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    isError = state.addressError != null,
                    supportingText = { Text(state.addressError ?: state.addressNote ?: state.addressHint) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ).next(),
                    keyboardActions = nextTo(nameField),
                )
                if (state.canRetryLookup) RetryLookupButton(state.retryLookupWaits, onRetryLookup)
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(nameField).testTag(WATCH_NAME_TAG),
                    enabled = !state.adding,
                    label = { Text("Name (optional)") },
                    placeholder = state.nameDefault?.let { default -> { Text(default) } },
                    singleLine = true,
                    isError = state.nameError != null,
                    supportingText = (state.nameError ?: NAME_FOLLOWS_ENS.takeIf { state.followsEns })
                        ?.let { text -> { Text(text) } },
                    keyboardOptions = KeyboardOptions.Default.done(),
                    keyboardActions = submitOnDone(state.canAdd, onAdd),
                )
                state.noNetwork?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                // The one card names its network, the one Add adds it on: a name may answer on one registry alone.
                state.detection?.let { detection ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.cardNetwork?.let { NetworkMark(it, Modifier.testTag(WATCH_CARD_NETWORK_TAG)) }
                        DetectionCard(detection)
                    }
                }
                // With two or more networks on: what each says, ticked where the user chooses (N10, N11).
                val ticking = state.question == null || state.choosing
                state.networks.forEach { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ticking && row.offered) {
                                Checkbox(
                                    checked = row.chosen,
                                    onCheckedChange = { onNetworkToggle(row.network.chainId) },
                                    enabled = !state.adding,
                                    modifier = Modifier.testTag(NETWORK_CHOICE_TAG + row.network.chainId),
                                )
                            }
                            NetworkMark(row.network)
                        }
                        DetectionCard(row.detection)
                    }
                }
                // A network that is off: not checked, and one more to choose once turned on.
                state.offNetworks.forEach { network ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NetworkMark(network, Modifier.weight(1f).alpha(0.6f))
                        Text(
                            "Off in this wallet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { onTurnOnNetwork(network.chainId) }, enabled = !state.adding) {
                            Text("Turn on")
                        }
                    }
                }
                state.question?.takeIf { !state.choosing }
                    ?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (details.isNotEmpty()) HiddenDetails(WATCH_DETAILS_TAG) { details.forEach { Note(it) } }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.question != null && !state.choosing) {
                        TextButton(onClick = onChooseNetworks, enabled = !state.adding) { Text("Choose networks") }
                    }
                    Button(onClick = onAdd, enabled = state.canAdd) {
                        Text(if (state.question != null && !state.choosing) "Add on all" else "Add")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetectionCard(detection: Detection) {
    Surface(
        Modifier.fillMaxWidth().testTag(WATCH_DETECTION_TAG),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (detection) {
                Detection.Checking -> Text("Checking the address on chain…")
                is Detection.Account -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AccountAvatar(detection.address, size = 36.dp)
                        Column {
                            Text(if (detection.delegate == null) "Account" else "Account with a delegation")
                            Text(
                                detection.checkedAt,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // In full and checksummed: this is the address the user confirms.
                    Text(
                        detection.address,
                        Modifier.testTag(WATCH_CHECKSUMMED_TAG),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (detection.delegate == null) {
                        Text(
                            "No contract code: an account a key controls (or a contract not deployed yet).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    detection.delegate?.let { delegate ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Blockie(delegate, size = 20.dp)
                            Text(
                                "Its code delegates to ${shortAddress(delegate)} (EIP-7702), which can act for it.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ControlBadge(Control.WATCH_ONLY)
                        Text("Detected from verified code", style = MaterialTheme.typography.labelMedium)
                    }
                }
                is Detection.Safe -> SafeCard(detection)
                is Detection.Contract -> Text(detection.message)
                is Detection.InWallet -> Text("Already in your wallet as “${detection.label}”.")
                is Detection.Unavailable -> Text(detection.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * What the light client proved of a Safe (docs/wallet-ux.md, screen 10): its version, who must
 * sign, the owners' blockies, and the modules and guard, which act without them or on every
 * transaction.
 */
@Composable
private fun SafeCard(safe: Detection.Safe) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AccountAvatar(safe.address, size = 36.dp, threshold = safe.threshold)
        Column {
            Text("${safe.title} · ${safe.signers}")
            Text(
                safe.checkedAt,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        safe.address,
        Modifier.testTag(WATCH_CHECKSUMMED_TAG),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    Text("Owners", style = MaterialTheme.typography.labelMedium)
    safe.owners.forEach { owner ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Blockie(owner, size = 20.dp)
            Text(shortAddress(owner), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
    if (safe.modules.isNotEmpty()) {
        Text(
            if (safe.modules.size == 1) {
                "1 module is enabled: it can move this Safe's funds without the owners' signatures."
            } else {
                "${safe.modules.size} modules are enabled: each can move this Safe's funds without the " +
                    "owners' signatures."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        safe.modules.forEach { module -> AddressLine(module) }
    }
    safe.guard?.let { guard -> AddressLine(guard, "A guard checks every transaction the owners sign:") }
    safe.moduleGuard?.let { guard -> AddressLine(guard, "A module guard checks the modules' transactions:") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ControlBadge(safe.control)
        Text("Detected from verified code and storage", style = MaterialTheme.typography.labelMedium)
    }
}
