package eth.serotine.ui.safepropose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.components.CHECKING_SAFE
import eth.serotine.ui.components.FromLine
import eth.serotine.ui.components.Heading
import eth.serotine.ui.components.HiddenDetails
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.Note
import eth.serotine.ui.components.OwnerChoices
import eth.serotine.ui.components.RecipientCard
import eth.serotine.ui.components.RequestShareContent
import eth.serotine.ui.components.ReviewButton
import eth.serotine.ui.components.SafeTxActionCard
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.TransferFormFields
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.components.UnlockFields
import eth.serotine.ui.mode.LocalInterfaceMode
import eth.serotine.ui.platform.KeepScreenOn
import eth.serotine.ui.platform.rememberCopyText
import eth.serotine.ui.platform.rememberShareText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/** Test tag of the reviewed amount, for UI tests. */
const val PROPOSE_AMOUNT_REVIEWED_TAG = "propose-amount-reviewed"

/** Test tag of the review's *Details* in simple mode, for UI tests. */
const val PROPOSE_DETAILS_TAG = "propose-details"

/** Test tag of the wallet password field, for UI tests. */
const val PROPOSE_PASSWORD_TAG = "propose-password"

/** Test tag of the Sign and queue button, for UI tests. */
const val PROPOSE_SIGN_TAG = "propose-sign"

/** Test tag of the button that leaves a signature not saved behind, for UI tests. */
const val PROPOSE_DROP_UNSAVED_TAG = "propose-drop-unsaved"

/**
 * Binds [SafeProposeScreen] to its ViewModel. A proposal signed and queued ends the screen through
 * [onProposed] with its Safe transaction hash; the screen stopping being shown becomes
 * [SafeProposeViewModel.onHidden] (the password goes) and being shown again
 * [SafeProposeViewModel.onShown] (the review is proposed anew).
 */
@Composable
fun SafeProposeRoute(
    viewModel: SafeProposeViewModel,
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
    val proposed = (state as? SafeProposeUiState.Proposed)?.hash
    val currentOnProposed by rememberUpdatedState(onProposed)
    LaunchedEffect(proposed) {
        if (proposed != null) currentOnProposed(proposed)
    }
    val actions = remember(viewModel) {
        SafeProposeActions(
            onAssetChange = viewModel::onAssetChange,
            onToChange = viewModel::onToChange,
            onPickAccount = viewModel::onPickAccount,
            onRetryLookup = viewModel::onRetryLookup,
            onAmountChange = viewModel::onAmountChange,
            onMax = viewModel::onMax,
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
        InterfaceMode.SIMPLE -> SimpleSafeProposeScreen(
            state,
            actions,
            onBack,
            copy,
            share,
            modifier,
            syncLine = lightClient.collectAsStateWithLifecycle().value,
        )
        // The strip in Detailed view; in simple mode the same item, drawn as the sync line while it says something.
        InterfaceMode.DETAILED -> SafeProposeScreen(
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
 * What the user can ask of [SafeProposeScreen]: Send's form (the asset, the recipient typed or
 * picked, the amount, Max, Review), and in the review back to the form ([onEdit]), Refresh, the
 * owner who signs, the password, and Sign ([onSign], or [onSignWithBiometrics]); with a signature
 * the queue could not save, leaving it behind ([onDropUnsaved]) or staying to share it
 * ([onKeepUnsaved]).
 */
class SafeProposeActions(
    val onAssetChange: (contract: String?) -> Unit = {},
    val onToChange: (String) -> Unit = {},
    val onPickAccount: (address: String) -> Unit = {},
    val onRetryLookup: () -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onMax: () -> Unit = {},
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
 * Send for a Safe (docs/wallet-ux.md, screens 7 and 16) as a pure function of [state]: Send's form
 * — from the Safe, asset, to, amount; no fee, which the account that executes it pays — then the
 * review of the proposal: the Safe transaction it is, decoded here, its nonce and those queued
 * before it, the Safe's balance before, queued and after, whether its call was simulated, and the
 * owner held here who signs it. While the review takes the wallet password the window is kept
 * out of screen capture where the platform allows; where it does not, the screen says so. A
 * request signed that the queue could not keep is shared from the review: [onCopy] puts its text
 * on the clipboard, [onShareText] hands it to the share sheet (null where there is none).
 * [lightClient] is the strip above it, Detailed view's; simple mode ([SimpleSafeProposeScreen]) has
 * none, and keeps the review's blocks behind *Details* ([details]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SafeProposeScreen(
    state: SafeProposeUiState,
    lightClient: LightClientItem?,
    actions: SafeProposeActions,
    onBack: () -> Unit,
    onCopy: (String) -> Unit = {},
    onShareText: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    details: List<String> = emptyList(),
    syncLine: LightClientItem? = null,
) {
    val review = state as? SafeProposeUiState.Review
    // In the review, back is to the form; while a signature runs, back waits for it. While the Safe
    // is still checked before it, nothing is signed yet, and back stops the check.
    val busy = review?.signing == true && !review.checking
    // A check of the Safe can take up to 45 s: a screen that times out meanwhile is a screen left,
    // which stops it.
    if (review?.checking == true) KeepScreenOn()
    BackHandler(enabled = review != null) {
        if (!busy) actions.onEdit()
    }
    if (review?.askingToDrop == true) {
        AlertDialog(
            onDismissRequest = actions.onKeepUnsaved,
            title = { Text("Leave the signature behind?") },
            text = {
                Text(
                    "The signature made here is not saved on this device: share the request first, or it is " +
                        "lost, and the proposal must be signed again.",
                )
            },
            confirmButton = {
                TextButton(onClick = actions.onDropUnsaved, Modifier.testTag(PROPOSE_DROP_UNSAVED_TAG)) {
                    Text("Leave it")
                }
            },
            dismissButton = { TextButton(onClick = actions.onKeepUnsaved) { Text("Stay and share") } },
        )
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = if (review != null) actions.onEdit else onBack,
                        enabled = !busy,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(if (review != null) "Review proposal" else "Send from a Safe") },
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
                is SafeProposeUiState.Loading, is SafeProposeUiState.Proposed ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SafeProposeUiState.CannotPropose -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                is SafeProposeUiState.Compose -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val form = state.form
                    TransferFormFields(
                        form,
                        actions.onAssetChange,
                        actions.onToChange,
                        actions.onPickAccount,
                        actions.onRetryLookup,
                        actions.onAmountChange,
                        actions.onMax,
                        onReview = actions.onReview,
                    )
                    Note(
                        "A Safe transaction: the owners sign it, then any account of this wallet executes it " +
                            "and pays its fee. Nothing is queued until an owner held here signs it.",
                    )
                    form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    ReviewButton(form, actions.onReview, Modifier.align(Alignment.End))
                }
                is SafeProposeUiState.Review -> ReviewContent(state, actions, onCopy, onShareText, details)
            }
        }
    }
}

@Composable
private fun ReviewContent(
    state: SafeProposeUiState.Review,
    actions: SafeProposeActions,
    onCopy: (String) -> Unit,
    onShareText: ((String) -> Unit)?,
    details: List<String>,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("The Safe sends")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TokenIcon(state.asset.icon, state.asset.symbol, size = 40.dp)
            SelectionContainer {
                Text(
                    state.amount,
                    Modifier.testTag(PROPOSE_AMOUNT_REVIEWED_TAG),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        state.asset.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Heading("To")
        RecipientCard(state.recipient)
        Heading("From")
        FromLine(state.from)
        Heading("The Safe transaction, decoded here")
        SafeTxActionCard(state.action)
        Text(state.nonce, style = MaterialTheme.typography.titleSmall)
        state.queued?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Heading("The Safe's balance")
        Column {
            Text(state.balance.what, style = MaterialTheme.typography.labelLarge)
            Text(state.balance.before)
            state.balance.queued?.let { Text("− $it") }
            Text("→ ${state.balance.after}")
            // Empty in simple mode, which keeps it behind Details.
            state.balance.verifiedAt.takeIf { it.isNotEmpty() }?.let { Note(it) }
        }
        Text(state.simulation, style = MaterialTheme.typography.bodyMedium)
        if (details.isNotEmpty()) HiddenDetails(PROPOSE_DETAILS_TAG) { details.forEach { Note(it) } }
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
            passwordTag = PROPOSE_PASSWORD_TAG,
            signTag = PROPOSE_SIGN_TAG,
        )
    }
}
