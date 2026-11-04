package eth.serotine.ui.safetx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.AddressLine
import eth.serotine.ui.components.CHECKING_SAFE
import eth.serotine.ui.components.GAS_TERMS_WARNING
import eth.serotine.ui.components.GHOST_ROWS_UNKNOWN
import eth.serotine.ui.components.GhostCircle
import eth.serotine.ui.components.GhostLine
import eth.serotine.ui.components.GhostRows
import eth.serotine.ui.components.Heading
import eth.serotine.ui.components.HiddenDetails
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NotSentFields
import eth.serotine.ui.components.Note
import eth.serotine.ui.components.QrCode
import eth.serotine.ui.components.RequestShareContent
import eth.serotine.ui.components.SafeSwapBlock
import eth.serotine.ui.components.SafeTxActionCard
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.components.UnlockFields
import eth.serotine.ui.components.levelName
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.mode.LocalInterfaceMode
import eth.serotine.ui.platform.KeepScreenOn
import eth.serotine.ui.platform.rememberCopyText
import eth.serotine.ui.platform.rememberShareText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Test tag of the *Details* that keep the blocks in simple mode, for UI tests. */
const val SAFE_TX_DETAILS_TAG = "safe-tx-details"

/** Test tag of the transaction's hash, for UI tests. */
const val SAFE_TX_HASH_TAG = "safe-tx-hash"

/** Test tag of each owner's row, for UI tests. */
const val SAFE_TX_OWNER_TAG = "safe-tx-owner"

/** Test tag of the Share request button, for UI tests. */
const val SAFE_TX_SHARE_TAG = "safe-tx-share"

/** Test tag of the wallet password field, for UI tests. */
const val SAFE_TX_PASSWORD_TAG = "safe-tx-password"

/** Test tag of the button that signs (the hash, or the approval or execution under review), for UI tests. */
const val SAFE_TX_SIGN_TAG = "safe-tx-sign"

/**
 * Binds [SafeTxScreen] to its ViewModel. An approval or execution broadcast ends the screen through
 * [onSent] with its hash, and a removal through [onBack]; [onProposeAgain] opens Swap for a Safe on
 * this swap, to propose it again in its place. The screen stopping being shown becomes
 * [SafeTxViewModel.onHidden] (the password goes) and being shown again [SafeTxViewModel.onShown]
 * (the owners are read again, a review quoted anew).
 */
@Composable
fun SafeTxRoute(
    viewModel: SafeTxViewModel,
    /** The strip of the network this screen is about: its account's. */
    lightClient: StateFlow<LightClientItem>,
    onBack: () -> Unit,
    onSent: (hash: String) -> Unit,
    onProposeAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: as on Send, an edit reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onShown() }
    val sent = (state as? SafeTxUiState.Sent)?.hash
    val removed = state is SafeTxUiState.Removed
    val currentOnSent by rememberUpdatedState(onSent)
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(sent, removed) {
        when {
            sent != null -> currentOnSent(sent)
            removed -> currentOnBack()
        }
    }
    val actions = remember(viewModel, onProposeAgain) {
        SafeTxActions(
            onShare = viewModel::onShare,
            onShareClosed = viewModel::onShareClosed,
            onCheckAgain = viewModel::checkAgain,
            onSign = viewModel::startSigning,
            onApprove = viewModel::startApproval,
            onExecute = viewModel::startExecution,
            onRemove = viewModel::remove,
            onSignerChange = viewModel::onSignerChange,
            onAccountChange = viewModel::onAccountChange,
            onFeeChange = viewModel::onFeeChange,
            onRefresh = viewModel::refresh,
            onPasswordChange = viewModel::onPasswordChange,
            onConfirm = viewModel::sign,
            onConfirmWithBiometrics = viewModel::signWithBiometrics,
            onRetry = viewModel::retry,
            onStepBack = viewModel::back,
            onProposeAgain = onProposeAgain,
        )
    }
    val copy = rememberCopyText()
    val share = rememberShareText()
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleSafeTxScreen(
            state,
            actions,
            onBack,
            copy,
            share,
            modifier,
            syncLine = lightClient.collectAsStateWithLifecycle().value,
        )
        // The strip in Detailed view; in simple mode the same item, drawn as the sync line while it says something.
        InterfaceMode.DETAILED -> SafeTxScreen(
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
 * What the user can ask of [SafeTxScreen]: open and close the request's sharing, read the owners
 * again, start signing the hash, an approval on chain or the execution, remove the transaction;
 * in a step, choose the owner or account and the fee, refresh the quote, type the password, sign
 * ([onConfirm], or [onConfirmWithBiometrics]), retry a broadcast, and go back to the overview;
 * and, for a swap worth it, propose it again in its place ([onProposeAgain]).
 */
class SafeTxActions(
    val onShare: () -> Unit = {},
    val onShareClosed: () -> Unit = {},
    val onCheckAgain: () -> Unit = {},
    val onSign: () -> Unit = {},
    val onApprove: () -> Unit = {},
    val onExecute: () -> Unit = {},
    val onRemove: () -> Unit = {},
    val onSignerChange: (address: String) -> Unit = {},
    val onAccountChange: (address: String) -> Unit = {},
    val onFeeChange: (FeeLevel) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onConfirmWithBiometrics: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onStepBack: () -> Unit = {},
    val onProposeAgain: () -> Unit = {},
)

/**
 * A Safe transaction (docs/wallet-ux.md, screen 17) as a pure function of [state]: what it does,
 * decoded here, its nonce and hash, its gas terms and why this wallet will not sign it where it
 * will not, each owner's stand, and Share request · Sign · Approve on chain · Execute · Remove
 * from this device; then the step that signs. [onCopy] puts the request's text on the clipboard
 * and [onShareText] hands it to the platform's share sheet (null where there is none). While a
 * step takes the wallet password the window is kept out of screen capture where the platform
 * allows; where it does not, the screen says so. [lightClient] is the strip above it, Detailed
 * view's; simple mode ([SimpleSafeTxScreen]) has none, and keeps the blocks behind *Details*
 * ([details]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SafeTxScreen(
    state: SafeTxUiState,
    lightClient: LightClientItem?,
    actions: SafeTxActions,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onShareText: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    details: List<String> = emptyList(),
    syncLine: LightClientItem? = null,
) {
    val review = state as? SafeTxUiState.Review
    val signing = state as? SafeTxUiState.Signing
    val quoting = (state as? SafeTxUiState.Overview)?.quoting == true
    // In a step, back is to the overview, until bytes may be out there: after a broadcast no peer
    // answered, only Try again may send them, so back leaves the screen. While a signature runs,
    // back waits for it: leaving would not stop it, only hide what it did. While the Safe is still
    // checked before a signature, nothing is signed yet, and back stops the check.
    val busy = review?.signing == true || (signing?.signing == true && !signing.checking)
    // A check of the Safe can take up to 45 s: a screen that times out meanwhile is a screen left,
    // which stops a check before Sign.
    if (signing?.checking == true || quoting) KeepScreenOn()
    val leaves = review?.notSent?.canRetry == true
    val inStep = review != null || signing != null || quoting
    BackHandler(enabled = inStep) {
        when {
            busy -> Unit
            leaves -> onBack()
            else -> actions.onStepBack()
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copy: (String) -> Unit = { text ->
        onCopy(text)
        scope.launch { snackbar.showSnackbar("Request copied") }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = if (inStep && !leaves) actions.onStepBack else onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        when {
                            signing != null -> "Sign"
                            review?.kind == ReviewKind.APPROVAL -> "Approve on chain"
                            review?.kind == ReviewKind.EXECUTION -> "Execute"
                            else -> "Safe transaction"
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
                is SafeTxUiState.Loading, is SafeTxUiState.Sent, is SafeTxUiState.Removed ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SafeTxUiState.NotQueued -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                is SafeTxUiState.Overview -> OverviewContent(state, actions, copy, onShareText, details)
                is SafeTxUiState.Signing -> SigningContent(state, actions, details)
                is SafeTxUiState.Review -> ReviewContent(state, actions, details)
            }
        }
    }
}

@Composable
private fun OverviewContent(
    state: SafeTxUiState.Overview,
    actions: SafeTxActions,
    onCopy: (String) -> Unit,
    onShareText: ((String) -> Unit)?,
    details: List<String>,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SafeLine(state.safe)
        SafeTxActionCard(state.action)
        // Not while Approve or Execute is quoted: the screen would be left mid-quote.
        state.swap?.let { SafeSwapBlock(it, onProposeAgain = actions.onProposeAgain.takeUnless { state.quoting }) }
        Text(state.nonce, style = MaterialTheme.typography.titleSmall)
        HashLine(state.hash)
        state.gasTerms?.let { terms ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Heading("Gas and refund terms")
                terms.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                terms.addresses.forEach { AddressLine(it.address, "${it.what}:") }
                Text(
                    GAS_TERMS_WARNING,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        state.refusal?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Owners(state.owners, actions.onCheckAgain)
        if (details.isNotEmpty()) HiddenDetails(SAFE_TX_DETAILS_TAG) { details.forEach { Note(it) } }
        state.message?.let { message ->
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        HorizontalDivider()
        val share = state.share
        if (share != null) {
            RequestShareContent(share, onCopy, onShareText, actions.onShareClosed)
        } else if (!state.canShare) {
            Note(
                "Shared once the Safe is verified again: what is shared carries only the signatures of its " +
                    "current owners, checked first.",
            )
        } else {
            OutlinedButton(
                onClick = actions.onShare,
                modifier = Modifier.fillMaxWidth().testTag(SAFE_TX_SHARE_TAG),
            ) {
                Icon(SerotineIcons.QrCode, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Share request")
            }
        }
        if (state.canSign) {
            Button(onClick = actions.onSign, enabled = !state.quoting, modifier = Modifier.fillMaxWidth()) {
                Text("Sign")
            }
        }
        if (state.canApprove) {
            FilledTonalButton(
                onClick = actions.onApprove,
                enabled = !state.quoting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Approve on chain")
            }
        }
        // Execute shown greyed out while it cannot be done, a tap saying why (owner ruling, 2026-10-18,
        // #55); hidden only while there is no reading to say anything from.
        val executeReason = state.executeNote.takeUnless { state.canExecute }
        if (state.canExecute || executeReason != null) {
            Button(
                onClick = { if (executeReason == null) actions.onExecute() else unavailable = executeReason },
                enabled = !state.quoting,
                modifier = Modifier.fillMaxWidth().unavailable(executeReason != null),
                colors = if (executeReason == null) ButtonDefaults.buttonColors() else Unavailable.buttonColors(),
            ) {
                Text("Execute")
            }
        }
        state.executeNote?.let { Note(it) }
        unavailable?.let { reason ->
            UnavailableDialog(UnavailableNotice("Execute", reason), onDismiss = { unavailable = null })
        }
        if (state.quoting) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                // Approve and Execute quote from the Safe's verified state: its check first.
                Text(CHECKING_SAFE)
            }
        }
        TextButton(onClick = { confirmRemove = true }, enabled = !state.quoting) {
            Text("Remove from this device", color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove from this device?") },
            text = {
                Text(
                    "Only this device forgets it, with the signatures it collected here. A signature already " +
                        "shared stays valid elsewhere until the Safe's nonce passes it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    actions.onRemove()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** The Safe: its blockie with its threshold, its name in the wallet, its address in full. */
@Composable
private fun SafeLine(safe: SafeHeader) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AccountAvatar(safe.address, size = 40.dp, threshold = safe.threshold)
        Column(Modifier.weight(1f)) {
            Text(safe.label ?: "A Safe not in this wallet", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(safe.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun HashLine(hash: String) {
    Column {
        Text("Safe transaction hash, computed here", style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(
                hash,
                Modifier.testTag(SAFE_TX_HASH_TAG),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** Every owner's stand, with blockies and "This device" on the ones held here, and the threshold. */
@Composable
private fun Owners(owners: OwnersItem, onCheckAgain: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Heading("Owners")
        when (owners) {
            OwnersItem.Checking -> {
                // The threshold, the confirmations and a few owners, in their shapes, until they are read.
                GhostLine(200.dp)
                GhostLine(160.dp, style = MaterialTheme.typography.titleSmall)
                GhostRows(GHOST_ROWS_UNKNOWN, "Reading the owners and their approvals", spacing = 4.dp) {
                    OwnerGhost()
                }
            }
            is OwnersItem.Unavailable -> Text(owners.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is OwnersItem.Shown -> {
                Text(owners.threshold)
                Text(owners.confirmations, style = MaterialTheme.typography.titleSmall)
                owners.rows.forEach { OwnerItem(it) }
                Note(owners.verifiedAt)
            }
        }
        TextButton(onClick = onCheckAgain) {
            Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Check again")
        }
    }
}

/** An [OwnerItem] not read yet, of its height. */
@Composable
private fun OwnerGhost() {
    ListItem(
        leadingContent = { GhostCircle(32.dp) },
        headlineContent = { GhostLine(96.dp) },
        supportingContent = {
            Column {
                GhostLine(120.dp)
                GhostLine(160.dp)
            }
        },
    )
}

@Composable
private fun OwnerItem(owner: OwnerStateRow) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        modifier = Modifier.testTag(SAFE_TX_OWNER_TAG),
        leadingContent = { AccountAvatar(owner.address, size = 32.dp) },
        headlineContent = { Text(owner.label ?: shortAddress(owner.address)) },
        supportingContent = {
            Column {
                Text(shortAddress(owner.address), fontFamily = FontFamily.Monospace)
                Text(owner.detail)
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                val mark = Modifier.size(20.dp).semantics { contentDescription = owner.state.description }
                when (owner.state) {
                    OwnerState.SIGNED, OwnerState.APPROVED ->
                        Icon(Icons.Filled.CheckCircle, null, mark, tint = colors.primary)
                    OwnerState.UNKNOWN -> Icon(Icons.Filled.Info, null, mark, tint = colors.onSurfaceVariant)
                    OwnerState.MISSING -> Unit
                }
                if (owner.heldHere) Text("This device", style = MaterialTheme.typography.labelMedium)
            }
        },
    )
}

/** What an owner's mark says to accessibility services. */
private val OwnerState.description: String
    get() = when (this) {
        OwnerState.SIGNED -> "Signed"
        OwnerState.APPROVED -> "Approved on chain"
        OwnerState.MISSING -> "Not yet"
        OwnerState.UNKNOWN -> "Not verifiable right now"
    }

@Composable
private fun SigningContent(state: SafeTxUiState.Signing, actions: SafeTxActions, details: List<String>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SafeLine(state.safe)
        SafeTxActionCard(state.action)
        state.swap?.let { SafeSwapBlock(it) }
        if (details.isNotEmpty()) HiddenDetails(SAFE_TX_DETAILS_TAG) { details.forEach { Note(it) } }
        Text(state.nonce, style = MaterialTheme.typography.titleSmall)
        HashLine(state.hash)
        Note("The owner signs this hash, computed here from the transaction's fields: nothing else.")
        Heading("Sign as")
        Choices(state.owners, state.owner, enabled = !state.signing, onChoose = actions.onSignerChange)
        HorizontalDivider()
        UnlockFields(
            unlock = state.unlock,
            signing = state.signing,
            canSign = state.canSign,
            canSignWithBiometrics = state.canSignWithBiometrics,
            signLabel = "Sign",
            signingLabel = if (state.checking) CHECKING_SAFE else "Signing…",
            onPasswordChange = actions.onPasswordChange,
            onSign = actions.onConfirm,
            onSignWithBiometrics = actions.onConfirmWithBiometrics,
            passwordTag = SAFE_TX_PASSWORD_TAG,
            signTag = SAFE_TX_SIGN_TAG,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewContent(state: SafeTxUiState.Review, actions: SafeTxActions, details: List<String>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SafeLine(state.safe)
        SafeTxActionCard(state.action)
        // Not while the approval or the execution is signed or sent.
        state.swap?.let { SafeSwapBlock(it, onProposeAgain = actions.onProposeAgain.takeIf { state.idle }) }
        Text(state.nonce, style = MaterialTheme.typography.titleSmall)
        Heading(if (state.kind == ReviewKind.APPROVAL) "Approve as" else "Send the execution from")
        Choices(state.accounts, state.account, enabled = state.idle, onChoose = actions.onAccountChange)
        state.confirmations?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        state.notNeeded?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Heading("Fee")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            FeeLevel.entries.forEachIndexed { index, level ->
                SegmentedButton(
                    selected = state.fee == level,
                    onClick = { actions.onFeeChange(level) },
                    enabled = state.idle,
                    shape = SegmentedButtonDefaults.itemShape(index, FeeLevel.entries.size),
                    label = { Text(levelName(level)) },
                )
            }
        }
        Text("At most ${state.maxFee}")
        // Empty in simple mode, which keeps it behind Details.
        state.feeDetail.takeIf { it.isNotEmpty() }?.let { Note(it) }
        Heading("Verified simulation")
        Column {
            Text(state.balance.what, style = MaterialTheme.typography.labelLarge)
            Text("${state.balance.before} → ${state.balance.after}")
            state.balance.verifiedAt.takeIf { it.isNotEmpty() }?.let { Note(it) }
        }
        Text(state.simulation, style = MaterialTheme.typography.bodyMedium)
        state.pending?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (details.isNotEmpty()) HiddenDetails(SAFE_TX_DETAILS_TAG) { details.forEach { Note(it) } }
        TextButton(onClick = actions.onRefresh, enabled = state.idle) {
            if (state.refreshing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
            }
            Spacer(Modifier.size(8.dp))
            Text("Refresh the quote")
        }
        HorizontalDivider()
        val notSent = state.notSent
        if (notSent != null) {
            NotSentFields(notSent, state.signing, actions.onRetry)
        } else {
            UnlockFields(
                unlock = state.unlock,
                signing = state.signing,
                canSign = state.canSign,
                canSignWithBiometrics = state.canSignWithBiometrics,
                signLabel = "Sign and send",
                signingLabel = "Signing and sending…",
                onPasswordChange = actions.onPasswordChange,
                onSign = actions.onConfirm,
                onSignWithBiometrics = actions.onConfirmWithBiometrics,
                passwordTag = SAFE_TX_PASSWORD_TAG,
                signTag = SAFE_TX_SIGN_TAG,
            )
        }
    }
}

/** The accounts to choose from, the [chosen] one marked; a row is the control. */
@Composable
private fun Choices(choices: List<SignerChoice>, chosen: String, enabled: Boolean, onChoose: (String) -> Unit) {
    Column {
        choices.forEach { choice ->
            val selected = choice.address == chosen
            ListItem(
                modifier = Modifier.selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { onChoose(choice.address) },
                ),
                leadingContent = { AccountAvatar(choice.address, size = 32.dp) },
                headlineContent = { Text(choice.label) },
                supportingContent = {
                    Column {
                        Text(shortAddress(choice.address), fontFamily = FontFamily.Monospace)
                        choice.note?.let { Text(it) }
                    }
                },
                trailingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
            )
        }
    }
}
