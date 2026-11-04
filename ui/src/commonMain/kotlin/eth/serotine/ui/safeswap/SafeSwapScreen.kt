package eth.serotine.ui.safeswap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
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
import eth.serotine.ui.components.AmountLine
import eth.serotine.ui.components.CHECKING_SAFE
import eth.serotine.ui.components.FromLine
import eth.serotine.ui.components.Heading
import eth.serotine.ui.components.HiddenDetails
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.Note
import eth.serotine.ui.components.OwnerChoices
import eth.serotine.ui.components.RouteSteps
import eth.serotine.ui.components.SafeTxActionCard
import eth.serotine.ui.components.Spender
import eth.serotine.ui.components.SwapFormFields
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.components.UnlockFields
import eth.serotine.ui.mode.LocalInterfaceMode
import eth.serotine.ui.platform.KeepScreenOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/** Test tag of the Review button, for UI tests. */
const val SAFE_SWAP_REVIEW_TAG = "safe-swap-review"

/** Test tag of the reviewed minimum, for UI tests. */
const val SAFE_SWAP_MINIMUM_TAG = "safe-swap-minimum"

/** Test tag of the review's *Details* in simple mode, for UI tests. */
const val SAFE_SWAP_DETAILS_TAG = "safe-swap-details"

/** Test tag of each Safe transaction the swap takes, for UI tests. */
const val SAFE_SWAP_TRANSACTION_TAG = "safe-swap-transaction"

/** Test tag of the wallet password field, for UI tests. */
const val SAFE_SWAP_PASSWORD_TAG = "safe-swap-password"

/** Test tag of the button that signs, for UI tests. */
const val SAFE_SWAP_SIGN_TAG = "safe-swap-sign"

/**
 * Binds [SafeSwapScreen] to its ViewModel. Signed and queued ends the screen through [onProposed]
 * with the swap's Safe transaction's hash; the screen stopping being shown becomes
 * [SafeSwapViewModel.onHidden] (the password goes) and being shown again
 * [SafeSwapViewModel.onShown] (the review is proposed anew).
 */
@Composable
fun SafeSwapRoute(
    viewModel: SafeSwapViewModel,
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
    val proposed = (state as? SafeSwapUiState.Proposed)?.hash
    val currentOnProposed by rememberUpdatedState(onProposed)
    LaunchedEffect(proposed) {
        if (proposed != null) currentOnProposed(proposed)
    }
    val actions = remember(viewModel) {
        SafeSwapActions(
            onSellChange = viewModel::onSellChange,
            onBuyChange = viewModel::onBuyChange,
            onAmountChange = viewModel::onAmountChange,
            onMax = viewModel::onMax,
            onToleranceChange = viewModel::onToleranceChange,
            onReview = viewModel::review,
            onEdit = viewModel::edit,
            onRefresh = viewModel::refresh,
            onOwnerChange = viewModel::onOwnerChange,
            onPasswordChange = viewModel::onPasswordChange,
            onSign = viewModel::sign,
            onSignWithBiometrics = viewModel::signWithBiometrics,
        )
    }
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleSafeSwapScreen(
            state,
            actions,
            onBack,
            modifier,
            syncLine = lightClient.collectAsStateWithLifecycle().value,
        )
        // The strip in Detailed view; in simple mode the same item, drawn as the sync line while it says something.
        InterfaceMode.DETAILED -> SafeSwapScreen(
            state = state,
            lightClient = lightClient.collectAsStateWithLifecycle().value,
            actions = actions,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

/**
 * What the user can ask of [SafeSwapScreen]: Swap's form (sell, buy, amount, Max, tolerance,
 * Review), and in the review back to the form ([onEdit]), Refresh, the owner who signs, the
 * password, and Sign ([onSign], or [onSignWithBiometrics]).
 */
class SafeSwapActions(
    val onSellChange: (contract: String?) -> Unit = {},
    val onBuyChange: (contract: String?) -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onMax: () -> Unit = {},
    val onToleranceChange: (String) -> Unit = {},
    val onReview: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOwnerChange: (address: String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onSign: () -> Unit = {},
    val onSignWithBiometrics: () -> Unit = {},
)

/**
 * Swap for a Safe (docs/swaps.md, *A Safe's swap*) as a pure function of [state]: Swap's form —
 * from the Safe, sell, amount, buy, tolerance; no fee, which whoever executes it pays — then the
 * review of the proposal: what it sells and at least returns, the route, the deadline, the Safe
 * transactions it takes, decoded here, at their nonces, the Safe's balance before and after, and
 * the owner held here who signs every one. While the review takes the wallet password the window
 * is kept out of screen capture where the platform allows; where it does not, the screen says so.
 * [lightClient] is the strip above it, Detailed view's; simple mode ([SimpleSafeSwapScreen]) has
 * none, and keeps the review's blocks behind *Details* ([details]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SafeSwapScreen(
    state: SafeSwapUiState,
    lightClient: LightClientItem?,
    actions: SafeSwapActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    details: List<String> = emptyList(),
    syncLine: LightClientItem? = null,
) {
    val review = state as? SafeSwapUiState.Review
    // In the review, back is to the form; while a signature runs, back waits for it. While the Safe
    // is still checked before it, nothing is signed yet, and back stops the check.
    val busy = review?.signing == true && !review.checking
    // A check of the Safe can take up to 45 s: a screen that times out meanwhile is a screen left,
    // which stops it.
    if (review?.checking == true) KeepScreenOn()
    BackHandler(enabled = review != null) {
        if (!busy) actions.onEdit()
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = if (review != null) actions.onEdit else onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(if (review != null) "Review proposal" else "Swap from a Safe") },
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
                is SafeSwapUiState.Loading, is SafeSwapUiState.Proposed ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SafeSwapUiState.CannotSwap -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                is SafeSwapUiState.Compose -> ComposeContent(state, actions)
                is SafeSwapUiState.Review -> ReviewContent(state, actions, details)
            }
        }
    }
}

@Composable
private fun ComposeContent(state: SafeSwapUiState.Compose, actions: SafeSwapActions) {
    val form = state.form
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("From")
        FromLine(form.from)
        state.replacing?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        SwapFormFields(
            form,
            actions.onSellChange,
            actions.onBuyChange,
            actions.onAmountChange,
            actions.onMax,
            actions.onToleranceChange,
            onReview = actions.onReview,
        )
        form.info?.let { Note(it) }
        form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = actions.onReview,
            enabled = form.canReview,
            modifier = Modifier.align(Alignment.End).testTag(SAFE_SWAP_REVIEW_TAG),
        ) {
            if (form.quoting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Checking on chain…")
            } else {
                Text("Review")
            }
        }
    }
}

@Composable
private fun ReviewContent(state: SafeSwapUiState.Review, actions: SafeSwapActions, details: List<String>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("The Safe sells")
        AmountLine(state.sell.icon, state.sell.symbol, state.sold)
        Heading("And receives")
        SelectionContainer {
            Text(
                state.minimum,
                Modifier.testTag(SAFE_SWAP_MINIMUM_TAG),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        Note("${state.quoted}: ${state.quotedAt}")
        Note(state.tolerance)
        Heading("Route")
        RouteSteps(state.route)
        Note(state.routeNote)
        Text(state.priceImpact, style = MaterialTheme.typography.bodyMedium)
        state.priceHint?.let { Note(it) }
        Heading("Through")
        Spender(state.spender)
        Note(state.deadline)
        Heading("From, and paid to")
        FromLine(state.from)
        val decoded = if (state.transactions.size > 1) "The Safe transactions" else "The Safe transaction"
        Heading("$decoded, decoded here")
        state.transactions.forEach { transaction ->
            Column(Modifier.testTag(SAFE_SWAP_TRANSACTION_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(transaction.nonce, style = MaterialTheme.typography.titleSmall)
                SafeTxActionCard(transaction.action)
            }
        }
        state.approvalNote?.let { Note(it) }
        state.queued?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        state.replaces?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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
        if (details.isNotEmpty()) HiddenDetails(SAFE_SWAP_DETAILS_TAG) { details.forEach { Note(it) } }
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
        Note(
            when (state.transactions.size) {
                1 -> "Signed, it is queued on this device, from where it is shared with the other owners."
                2 -> "Both are signed, one after the other, and queued on this device, from where they are shared " +
                    "with the other owners."
                else -> "All three are signed, one after the other, and queued on this device, from where they " +
                    "are shared with the other owners."
            },
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        UnlockFields(
            unlock = state.unlock,
            signing = state.signing,
            canSign = state.canSign,
            canSignWithBiometrics = state.canSignWithBiometrics,
            signLabel = when (state.transactions.size) {
                1 -> "Sign and queue"
                2 -> "Sign both and queue"
                else -> "Sign all three and queue"
            },
            signingLabel = when {
                state.checking -> CHECKING_SAFE
                state.progress != null -> "Signing ${state.progress.lowercase()}…"
                else -> "Signing…"
            },
            onPasswordChange = actions.onPasswordChange,
            onSign = actions.onSign,
            onSignWithBiometrics = actions.onSignWithBiometrics,
            passwordTag = SAFE_SWAP_PASSWORD_TAG,
            signTag = SAFE_SWAP_SIGN_TAG,
        )
    }
}
