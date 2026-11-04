package eth.serotine.ui.coworder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.components.AmountLine
import eth.serotine.ui.components.FromLine
import eth.serotine.ui.components.Heading
import eth.serotine.ui.components.HiddenDetails
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NotSentFields
import eth.serotine.ui.components.Note
import eth.serotine.ui.components.Spender
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.components.UnlockFields
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.mode.LocalInterfaceMode
import kotlinx.coroutines.flow.StateFlow

/** Test tag of the button that cancels the order on chain, for UI tests. */
const val COW_ORDER_CANCEL_TAG = "cow-order-cancel"

/** Test tag of the button that opens the same swap on Uniswap, for UI tests. */
const val COW_ORDER_UNISWAP_TAG = "cow-order-uniswap"

/** Test tag of the button that opens the status of a transaction that holds the order back, for UI tests. */
const val COW_ORDER_EARLIER_TAG = "cow-order-earlier"

/** Test tag of the wallet password field, for UI tests. */
const val COW_ORDER_PASSWORD_TAG = "cow-order-password"

/** Test tag of the button that signs the cancellation, for UI tests. */
const val COW_ORDER_SIGN_TAG = "cow-order-sign"

/** Test tag of the cancellation's *Details* in simple mode, for UI tests. */
const val COW_ORDER_DETAILS_TAG = "cow-order-details"

/**
 * Binds [CowOrderScreen] to its ViewModel. A cancellation broadcast ends the screen through
 * [onCancelSent] with its hash; [onOpenTransfer] opens the status of a cancellation already on its
 * way, and [onSwapAgain] opens Swap with the same swap filled in, on Uniswap. The screen stopping
 * being shown becomes [CowOrderViewModel.onHidden] (the password goes) and being shown again
 * [CowOrderViewModel.onShown] (a review is quoted anew).
 */
@Composable
fun CowOrderRoute(
    viewModel: CowOrderViewModel,
    lightClient: StateFlow<LightClientItem>,
    onBack: () -> Unit,
    onCancelSent: (hash: String) -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
    onSwapAgain: (SwapAgain) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onShown() }
    val sent = (state as? CowOrderUiState.CancelSent)?.hash
    val currentOnSent by rememberUpdatedState(onCancelSent)
    LaunchedEffect(sent) {
        if (sent != null) currentOnSent(sent)
    }
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleCowOrderScreen(
            state = state,
            onCancel = viewModel::cancel,
            onEdit = viewModel::edit,
            onRefresh = viewModel::refresh,
            onPasswordChange = viewModel::onPasswordChange,
            onSign = viewModel::sign,
            onSignWithBiometrics = viewModel::signWithBiometrics,
            onRetry = viewModel::retry,
            onOpenTransfer = onOpenTransfer,
            onSwapAgain = onSwapAgain,
            onBack = onBack,
            modifier = modifier,
            syncLine = lightClient.collectAsStateWithLifecycle().value,
        )
        // The strip in Detailed view; in simple mode the same item, drawn as the sync line while it says something.
        InterfaceMode.DETAILED -> CowOrderScreen(
            state = state,
            lightClient = lightClient.collectAsStateWithLifecycle().value,
            onCancel = viewModel::cancel,
            onEdit = viewModel::edit,
            onRefresh = viewModel::refresh,
            onPasswordChange = viewModel::onPasswordChange,
            onSign = viewModel::sign,
            onSignWithBiometrics = viewModel::signWithBiometrics,
            onRetry = viewModel::retry,
            onOpenTransfer = onOpenTransfer,
            onSwapAgain = onSwapAgain,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

/**
 * A CoW order's status (docs/swaps.md, *Proving an order's end*; screen 9 for an order) as a pure
 * function of [state]: what the order sells and buys, its end as the chain proves it, CoW's own
 * word labelled as CoW's, until when it may fill, and "Cancel on chain" while it may; then the
 * cancellation's review with the unlock that signs it. Once it is proven not to have swapped, the
 * same swap on Uniswap. [lightClient] is the strip above it, Detailed view's; simple mode
 * ([SimpleCowOrderScreen]) has none, and keeps the blocks, the order's and its cancellation's, and the
 * fee's make-up behind *Details* ([details]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun CowOrderScreen(
    state: CowOrderUiState,
    lightClient: LightClientItem?,
    onCancel: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
    onRetry: () -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
    onSwapAgain: (SwapAgain) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    details: List<String> = emptyList(),
    syncLine: LightClientItem? = null,
) {
    val review = state as? CowOrderUiState.CancelReview
    // Back from the review is to the order, until bytes may be out there, and waits while a signature runs.
    val toOrder = review != null && review.notSent?.canRetry != true
    BackHandler(enabled = review != null) {
        when {
            review?.signing == true -> Unit
            toOrder -> onEdit()
            else -> onBack()
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = if (toOrder) onEdit else onBack, enabled = review?.signing != true) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(if (review != null) "Cancel on chain" else "CoW order") },
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
                is CowOrderUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is CowOrderUiState.CancelSent -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Sent", Modifier.padding(top = 12.dp))
                }
                is CowOrderUiState.Missing -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                is CowOrderUiState.Shown -> Content {
                    OrderContent(state, onCancel, onOpenTransfer, onSwapAgain)
                    if (details.isNotEmpty()) HiddenDetails(COW_ORDER_DETAILS_TAG) { details.forEach { Note(it) } }
                }
                is CowOrderUiState.CancelReview -> Content {
                    CancelContent(state)
                    if (details.isNotEmpty()) HiddenDetails(COW_ORDER_DETAILS_TAG) { details.forEach { Note(it) } }
                    CancelFooter(state, onRefresh, onPasswordChange, onSign, onSignWithBiometrics, onRetry)
                }
            }
        }
    }
}

@Composable
private fun Content(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

/** The order, where it stands, CoW's word, and what can be done. */
@Composable
private fun ColumnScope.OrderContent(
    state: CowOrderUiState.Shown,
    onCancel: () -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
    onSwapAgain: (SwapAgain) -> Unit,
) {
    Text(stateName(state.state), style = MaterialTheme.typography.titleLarge, color = stateColor(state.state))
    Text(state.end, style = MaterialTheme.typography.bodyMedium)
    state.held?.let { Note(it) }
    Heading("Sells")
    AmountLine(state.soldIcon, state.soldSymbol, state.sold)
    Heading(if (state.state == OrderState.FILLED) "Bought" else "Buys")
    AmountLine(state.boughtIcon, state.boughtSymbol, state.bought)
    Note(state.validUntil)
    state.cow?.let {
        Heading("CoW's word")
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }
    Note(state.placement)
    Heading("From, and paid to")
    FromLine(state.from)
    Heading("Settled by")
    Spender(state.settlement)
    Heading("Order")
    SelectionContainer {
        Text(state.uid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.earlier?.let { earlier ->
        earlier.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        OutlinedButton(onClick = { onOpenTransfer(earlier.hash) }, Modifier.testTag(COW_ORDER_EARLIER_TAG)) {
            Text(earlier.label)
        }
    }
    state.uniswapAgain?.let { again ->
        Text(
            "It did not swap. The same swap on Uniswap is one tap away, to review as any.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { onSwapAgain(again) }, Modifier.testTag(COW_ORDER_UNISWAP_TAG)) {
            Text("Swap on Uniswap")
        }
    }
    // Shown beside a transaction on its way too: a tap quotes again, which says whether it still holds.
    if (state.canCancel) {
        var unavailable by remember { mutableStateOf<String?>(null) }
        val reason = state.cancelReason
        Note(
            "Cancelling on chain is a transaction of its own, with its fee: once a block has it, nothing can fill " +
                "the order. Or wait: it ends by itself at its validity.",
        )
        OutlinedButton(
            onClick = { if (reason == null) onCancel() else unavailable = reason },
            enabled = !state.quoting,
            modifier = Modifier.align(Alignment.End).testTag(COW_ORDER_CANCEL_TAG).unavailable(reason != null),
            colors = if (reason == null) {
                ButtonDefaults.outlinedButtonColors()
            } else {
                ButtonDefaults.outlinedButtonColors(contentColor = Unavailable.contentColor())
            },
        ) {
            if (state.quoting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Checking on chain…")
            } else {
                Text("Cancel on chain")
            }
        }
        unavailable?.let {
            UnavailableDialog(UnavailableNotice("Cancel on chain", it), onDismiss = { unavailable = null })
        }
    }
}

/** The cancellation: what it calls, from where, at what fee. */
@Composable
private fun CancelContent(state: CowOrderUiState.CancelReview) {
    Text(
        "Cancels the order on chain: the settlement contract's invalidateOrder, from the account that signed it.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Heading("Order")
    SelectionContainer {
        Text(state.uid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
    Heading("On")
    Spender(state.settlement)
    Heading("From")
    FromLine(state.from)
    Heading("Network fee")
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
}

/** Refresh, then the unlock that signs, or what became of a signature not broadcast. */
@Composable
private fun CancelFooter(
    state: CowOrderUiState.CancelReview,
    onRefresh: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
    onRetry: () -> Unit,
) {
    TextButton(onClick = onRefresh, enabled = state.idle) {
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
        NotSentFields(notSent, state.signing, onRetry)
    } else {
        UnlockFields(
            unlock = state.unlock,
            signing = state.signing,
            canSign = state.idle && state.unlock.password.text.isNotEmpty(),
            canSignWithBiometrics = state.idle && state.unlock.biometrics,
            signLabel = "Sign and cancel on chain",
            signingLabel = "Signing and sending…",
            onPasswordChange = onPasswordChange,
            onSign = onSign,
            onSignWithBiometrics = onSignWithBiometrics,
            passwordTag = COW_ORDER_PASSWORD_TAG,
            signTag = COW_ORDER_SIGN_TAG,
        )
    }
}

private fun stateName(state: OrderState): String = when (state) {
    OrderState.LIVE -> "Live"
    OrderState.FILLED -> "Filled"
    OrderState.NOT_FILLED -> "Not filled"
    OrderState.CANCELLED -> "Cancelled"
    OrderState.NOT_PROVABLE -> "Not provable"
}

@Composable
private fun stateColor(state: OrderState): Color = when (state) {
    OrderState.LIVE -> MaterialTheme.colorScheme.primary
    OrderState.FILLED -> MaterialTheme.colorScheme.tertiary
    OrderState.NOT_FILLED, OrderState.CANCELLED, OrderState.NOT_PROVABLE -> MaterialTheme.colorScheme.onSurfaceVariant
}
