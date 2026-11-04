package eth.serotine.ui.safesettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.CHECKING_SAFE
import eth.serotine.ui.components.FromLine
import eth.serotine.ui.components.Heading
import eth.serotine.ui.components.HiddenDetails
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.Note
import eth.serotine.ui.components.OwnerChoices
import eth.serotine.ui.components.RequestShareContent
import eth.serotine.ui.components.SafeTxActionCard
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.components.UnlockFields
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.mode.LocalInterfaceMode
import eth.serotine.ui.platform.KeepScreenOn
import eth.serotine.ui.platform.rememberCopyText
import eth.serotine.ui.platform.rememberShareText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/** Test tag of an owner's row on the settings, for UI tests. */
const val SETTINGS_OWNER_TAG = "safe-settings-owner"

/** Test tag of Add an owner, for UI tests. */
const val SETTINGS_ADD_OWNER_TAG = "safe-settings-add-owner"

/** Test tag of the new owner's address field, for UI tests. */
const val SETTINGS_ADDRESS_TAG = "safe-settings-address"

/** Test tag of the threshold the form leaves, for UI tests. */
const val SETTINGS_THRESHOLD_TAG = "safe-settings-threshold"

/** Test tag of the form's Review button, for UI tests. */
const val SETTINGS_REVIEW_TAG = "safe-settings-review"

/** Test tag of the wallet password field, for UI tests. */
const val SETTINGS_PASSWORD_TAG = "safe-settings-password"

/** Test tag of the Sign and queue button, for UI tests. */
const val SETTINGS_SIGN_TAG = "safe-settings-sign"

/** Test tag of the *Details* that keep the blocks in simple mode, for UI tests. */
const val SETTINGS_DETAILS_TAG = "safe-settings-details"

/**
 * Binds [SafeSettingsScreen] to its ViewModel. A change signed and queued ends the screen through
 * [onProposed] with its Safe transaction hash; the screen stopping being shown becomes
 * [SafeSettingsViewModel.onHidden] (the password goes) and being shown again
 * [SafeSettingsViewModel.onShown] (the review is proposed anew).
 */
@Composable
fun SafeSettingsRoute(
    viewModel: SafeSettingsViewModel,
    /** The strip of the network this screen is about: its account's. */
    lightClient: StateFlow<LightClientItem>,
    onBack: () -> Unit,
    onProposed: (hash: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: as on Send, an edit reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onShown() }
    val proposed = (state as? SafeSettingsUiState.Proposed)?.hash
    val currentOnProposed by rememberUpdatedState(onProposed)
    LaunchedEffect(proposed) {
        if (proposed != null) currentOnProposed(proposed)
    }
    val actions = remember(viewModel) {
        SafeSettingsActions(
            onAddOwner = viewModel::onAddOwner,
            onReplaceOwner = viewModel::onReplaceOwner,
            onRemoveOwner = viewModel::onRemoveOwner,
            onChangeThreshold = viewModel::onChangeThreshold,
            onDisableModule = viewModel::onDisableModule,
            onRemoveGuard = viewModel::onRemoveGuard,
            onCancel = viewModel::cancel,
            onAddressChange = viewModel::onAddressChange,
            onPickAccount = viewModel::onPickAccount,
            onThresholdChange = viewModel::onThresholdChange,
            onReview = viewModel::review,
            onEdit = viewModel::edit,
            onRefresh = viewModel::refresh,
            onOwnerChange = viewModel::onOwnerChange,
            onPasswordChange = viewModel::onPasswordChange,
            onSign = viewModel::sign,
            onSignWithBiometrics = viewModel::signWithBiometrics,
            onDropUnsaved = viewModel::dropUnsaved,
            onKeepUnsaved = viewModel::keepUnsaved,
        )
    }
    val copy = rememberCopyText()
    val share = rememberShareText()
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleSafeSettingsScreen(
            state,
            actions,
            onBack,
            copy,
            share,
            modifier,
            syncLine = lightClient.collectAsStateWithLifecycle().value,
        )
        // The strip in Detailed view; in simple mode the same item, drawn as the sync line while it says something.
        InterfaceMode.DETAILED -> SafeSettingsScreen(
            state = state,
            lightClient = lightClient.collectAsStateWithLifecycle().value,
            actions = actions,
            onBack = onBack,
            onCopy = copy,
            onShareText = share,
            modifier = modifier,
        )
    }
}

/**
 * What the user can ask of [SafeSettingsScreen]: on the settings, each change (an owner added,
 * replaced or removed, the threshold, a module disabled, the guard removed); in its form, back to
 * the settings ([onCancel]), the new owner typed or picked, the threshold, Review; in the review,
 * back to the form ([onEdit]), Refresh, the owner who signs, the password, and Sign ([onSign], or
 * [onSignWithBiometrics]); with a signature the queue could not save, leaving it behind
 * ([onDropUnsaved]) or staying to share it ([onKeepUnsaved]).
 */
class SafeSettingsActions(
    val onAddOwner: () -> Unit = {},
    val onReplaceOwner: (address: String) -> Unit = {},
    val onRemoveOwner: (address: String) -> Unit = {},
    val onChangeThreshold: () -> Unit = {},
    val onDisableModule: (address: String) -> Unit = {},
    val onRemoveGuard: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onAddressChange: (String) -> Unit = {},
    val onPickAccount: (address: String) -> Unit = {},
    val onThresholdChange: (Int) -> Unit = {},
    val onReview: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOwnerChange: (address: String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onSign: () -> Unit = {},
    val onSignWithBiometrics: () -> Unit = {},
    val onDropUnsaved: () -> Unit = {},
    val onKeepUnsaved: () -> Unit = {},
)

/**
 * A Safe's settings (docs/wallet-ux.md, screen 28) as a pure function of [state]: its owners, the
 * threshold, its modules and guard as verified, each with the changes an owner held here can
 * propose — greyed out, a tap saying why, when none can — and Enable a module and Update the
 * version, greyed out with the reason they are never offered here; then a change's form, and the
 * review of its proposal: the Safe transaction it is, decoded here, its nonce and those queued
 * before it, whether its call was simulated, and the owner held here who signs it. While the
 * review takes the wallet password the window is kept out of screen capture where the platform
 * allows. A request signed that the queue could not keep is shared from the review: [onCopy] puts
 * its text on the clipboard, [onShareText] hands it to the share sheet (null where there is none).
 * [lightClient] is the strip above it, Detailed view's; simple mode ([SimpleSafeSettingsScreen]) has
 * none, and keeps the blocks behind *Details* ([details]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SafeSettingsScreen(
    state: SafeSettingsUiState,
    lightClient: LightClientItem?,
    actions: SafeSettingsActions,
    onBack: () -> Unit,
    onCopy: (String) -> Unit = {},
    onShareText: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    details: List<String> = emptyList(),
    syncLine: LightClientItem? = null,
) {
    val review = state as? SafeSettingsUiState.Review
    val edit = state as? SafeSettingsUiState.Edit
    // In the review, back is to the form; while a signature runs, back waits for it. While the Safe
    // is still checked before it, nothing is signed yet, and back stops the check.
    val busy = review?.signing == true && !review.checking
    // A check of the Safe can take up to 45 s: a screen that times out meanwhile is a screen left,
    // which stops it.
    if (review?.checking == true) KeepScreenOn()
    val back = when {
        review != null -> actions.onEdit
        edit != null -> actions.onCancel
        else -> onBack
    }
    BackHandler(enabled = review != null || edit != null) {
        if (!busy) back()
    }
    if (review?.askingToDrop == true) {
        AlertDialog(
            onDismissRequest = actions.onKeepUnsaved,
            title = { Text("Leave the signature behind?") },
            text = {
                Text(
                    "The signature made here is not saved on this device: share the request first, or it is " +
                        "lost, and the change must be signed again.",
                )
            },
            confirmButton = { TextButton(onClick = actions.onDropUnsaved) { Text("Leave it") } },
            dismissButton = { TextButton(onClick = actions.onKeepUnsaved) { Text("Stay and share") } },
        )
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = back, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        when {
                            review != null -> "Review change"
                            edit != null -> edit.form.title
                            else -> "Safe settings"
                        },
                    )
                },
                actions = {
                    Text(
                        state.network,
                        Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        },
    ) { padding ->
        UnderLightClientStripIfAny(lightClient, Modifier.padding(padding), syncLine) {
            when (state) {
                is SafeSettingsUiState.Loading, is SafeSettingsUiState.Proposed ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SafeSettingsUiState.Unavailable -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                is SafeSettingsUiState.Overview -> OverviewContent(state, actions, details)
                is SafeSettingsUiState.Edit -> FormContent(state, actions, details)
                is SafeSettingsUiState.Review -> ReviewContent(state, actions, onCopy, onShareText, details)
            }
        }
    }
}

@Composable
private fun OverviewContent(state: SafeSettingsUiState.Overview, actions: SafeSettingsActions, details: List<String>) {
    var unavailable by remember { mutableStateOf<UnavailableNotice?>(null) }
    unavailable?.let { notice -> UnavailableDialog(notice, onDismiss = { unavailable = null }) }
    // A change runs when it can be proposed; otherwise the tap says why ([reason]).
    val guarded: (String, String?, () -> Unit) -> () -> Unit = { title, reason, run ->
        { reason?.let { unavailable = UnavailableNotice(title, it) } ?: run() }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FromLine(state.safe)
        Text(state.title, style = MaterialTheme.typography.titleSmall)
        state.changeReason?.let { Note(it) }
        Heading("Owners")
        Column {
            state.owners.forEach { owner ->
                ListItem(
                    modifier = Modifier.testTag(SETTINGS_OWNER_TAG),
                    leadingContent = { AccountAvatar(owner.address, size = 32.dp) },
                    headlineContent = { Text(owner.label ?: shortAddress(owner.address)) },
                    supportingContent = {
                        Column {
                            Text(
                                if (owner.label != null) shortAddress(owner.address) else "Not in this wallet",
                                fontFamily = FontFamily.Monospace,
                            )
                            if (owner.heldHere) Text("This device", style = MaterialTheme.typography.labelMedium)
                            Row {
                                SettingsAction(
                                    "Replace",
                                    state.canChange,
                                    guarded("Replace an owner", state.changeReason) {
                                        actions.onReplaceOwner(owner.address)
                                    },
                                )
                                SettingsAction(
                                    "Remove",
                                    state.removeBlocked == null,
                                    guarded("Remove an owner", state.removeBlocked) {
                                        actions.onRemoveOwner(owner.address)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
        SettingsAction(
            "Add an owner",
            state.canChange,
            guarded("Add an owner", state.changeReason, actions.onAddOwner),
            Modifier.testTag(SETTINGS_ADD_OWNER_TAG),
        )
        Heading("Threshold")
        Text(state.signers)
        SettingsAction(
            "Change the threshold",
            state.thresholdBlocked == null,
            guarded("Change the threshold", state.thresholdBlocked, actions.onChangeThreshold),
        )
        Heading("Modules")
        if (state.modules.isEmpty()) {
            Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                "Each can move this Safe's funds without the owners' signatures.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.modules.forEach { module ->
            Column {
                AddressText(module)
                SettingsAction(
                    "Disable",
                    state.canChange,
                    guarded("Disable a module", state.changeReason) { actions.onDisableModule(module) },
                )
            }
        }
        SettingsAction("Enable a module", available = false, onClick = {
            unavailable = UnavailableNotice("Enable a module", state.enableModuleReason)
        })
        Heading("Guard")
        state.guard?.let { guard ->
            Column {
                Text("A guard checks every transaction the owners sign:", style = MaterialTheme.typography.bodySmall)
                AddressText(guard)
                SettingsAction(
                    "Remove the guard",
                    state.canChange,
                    guarded("Remove the guard", state.changeReason, actions.onRemoveGuard),
                )
            }
        } ?: Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.moduleGuard?.let { guard ->
            Column {
                Text("A module guard checks the modules' transactions:", style = MaterialTheme.typography.bodySmall)
                AddressText(guard)
            }
        }
        Heading("Version")
        SettingsAction("Update the Safe's version", available = false, onClick = {
            unavailable = UnavailableNotice("Update the Safe's version", state.updateReason)
        })
        Note(
            "A change is a Safe transaction: the owners sign it, then any account of this wallet executes it and " +
                "pays its fee. Nothing changes until it is executed, and nothing is queued until an owner held " +
                "here signs it.",
        )
        Text(
            state.checkedAt,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (details.isNotEmpty()) HiddenDetails(SETTINGS_DETAILS_TAG) { details.forEach { Note(it) } }
    }
}

@Composable
private fun FormContent(state: SafeSettingsUiState.Edit, actions: SafeSettingsActions, details: List<String>) {
    val form = state.form
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FromLine(state.safe)
        Text(form.explanation, style = MaterialTheme.typography.bodyMedium)
        form.subject?.let { subject ->
            Heading(
                when (form.kind) {
                    SettingsChangeKind.DISABLE_MODULE -> "Module"
                    SettingsChangeKind.REMOVE_GUARD -> "Guard"
                    SettingsChangeKind.REPLACE_OWNER -> "Owner replaced"
                    else -> "Owner removed"
                },
            )
            ListItem(
                leadingContent = { AccountAvatar(subject.address, size = 32.dp) },
                headlineContent = { Text(subject.label ?: shortAddress(subject.address)) },
                supportingContent = {
                    Column {
                        SelectionContainer {
                            Text(subject.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        if (subject.heldHere) Text("This device", style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        }
        form.address?.let { field ->
            Heading("New owner")
            OutlinedTextField(
                value = field.text,
                onValueChange = actions.onAddressChange,
                modifier = Modifier.fillMaxWidth().testTag(SETTINGS_ADDRESS_TAG),
                label = { Text("The new owner's address") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
                isError = field.isError,
                supportingText = field.note?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
            )
            if (field.accounts.isNotEmpty()) {
                Text("Or one of this wallet's accounts:", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field.accounts.forEach { account ->
                        TextButton(onClick = { actions.onPickAccount(account.address) }) { Text(account.label) }
                    }
                }
            }
        }
        form.threshold?.let { threshold ->
            Heading("Threshold")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = { actions.onThresholdChange(threshold.value - 1) },
                    enabled = threshold.canDecrease && !form.proposing,
                    modifier = Modifier.semantics { contentDescription = "Lower the threshold" },
                ) { Text("−") }
                Text(
                    "${threshold.value} of ${threshold.owners}",
                    Modifier.testTag(SETTINGS_THRESHOLD_TAG),
                    style = MaterialTheme.typography.headlineSmall,
                )
                FilledTonalButton(
                    onClick = { actions.onThresholdChange(threshold.value + 1) },
                    enabled = threshold.canIncrease && !form.proposing,
                    modifier = Modifier.semantics { contentDescription = "Raise the threshold" },
                ) { Text("+") }
            }
            Text(threshold.shape, style = MaterialTheme.typography.bodyLarge)
        }
        form.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        form.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (details.isNotEmpty()) HiddenDetails(SETTINGS_DETAILS_TAG) { details.forEach { Note(it) } }
        FilledTonalButton(
            onClick = actions.onReview,
            enabled = form.canReview,
            modifier = Modifier.align(Alignment.End).testTag(SETTINGS_REVIEW_TAG),
        ) {
            if (form.proposing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(CHECKING_SAFE)
            } else {
                Text("Review")
            }
        }
    }
}

@Composable
private fun ReviewContent(
    state: SafeSettingsUiState.Review,
    actions: SafeSettingsActions,
    onCopy: (String) -> Unit,
    onShareText: ((String) -> Unit)?,
    details: List<String>,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("Of")
        FromLine(state.safe)
        Heading("The Safe transaction, decoded here")
        SafeTxActionCard(state.action)
        state.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(state.nonce, style = MaterialTheme.typography.titleSmall)
        state.queued?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        state.changesQueued?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(state.simulation, style = MaterialTheme.typography.bodyMedium)
        if (details.isNotEmpty()) HiddenDetails(SETTINGS_DETAILS_TAG) { details.forEach { Note(it) } }
        TextButton(onClick = actions.onRefresh, enabled = state.idle) {
            if (state.refreshing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
            }
            Spacer(Modifier.size(8.dp))
            Text("Propose it again")
        }
        HorizontalDivider()
        Heading("Signed by")
        OwnerChoices(state.owners, state.owner, enabled = state.idle, onChoose = actions.onOwnerChange)
        Note("Signed, it is queued on this device, from where it is shared with the other owners.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.share?.let { RequestShareContent(it, onCopy, onShareText, onClose = null) }
        UnlockFields(
            unlock = state.unlock,
            signing = state.signing,
            canSign = state.canSign,
            canSignWithBiometrics = state.canSignWithBiometrics,
            signLabel = "Sign and queue",
            signingLabel = if (state.checking) CHECKING_SAFE else "Signing…",
            onPasswordChange = actions.onPasswordChange,
            onSign = actions.onSign,
            onSignWithBiometrics = actions.onSignWithBiometrics,
            passwordTag = SETTINGS_PASSWORD_TAG,
            signTag = SETTINGS_SIGN_TAG,
        )
    }
}

/** An address in full, selectable. */
@Composable
private fun AddressText(address: String) {
    SelectionContainer {
        Text(address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

/**
 * One change on the settings, as a text button: greyed out unless [available], a tap then saying
 * why ([onClick] decides; owner ruling, 2026-10-18, #55).
 */
@Composable
private fun SettingsAction(label: String, available: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.unavailable(!available),
        colors = if (available) ButtonDefaults.textButtonColors() else
            ButtonDefaults.textButtonColors(contentColor = Unavailable.contentColor()),
    ) { Text(label) }
}
