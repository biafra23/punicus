package eth.serotine.ui.transferstatus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NotSentFields
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.UnderLightClientStrip
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.mode.LocalInterfaceMode
import kotlinx.coroutines.flow.StateFlow

/** Test tag of simple mode's Details button, for UI tests. */
const val TRANSFER_DETAILS_TAG = "transfer-details"

/** Test tag of each step, for UI tests. */
const val TRANSFER_STEP_TAG = "transfer-step"

/** Test tag of the transaction hash, for UI tests. */
const val TRANSFER_HASH_TAG = "transfer-hash"

/** Test tag of what a swap paid, for UI tests. */
const val TRANSFER_RECEIVED_TAG = "transfer-received"

/** Test tag of the address of the Safe a deployment created, for UI tests. */
const val TRANSFER_SAFE_TAG = "transfer-safe"

/**
 * Binds [TransferStatusScreen] to its ViewModel; Done and Back end it through [onDone], and Try
 * again is [TransferStatusViewModel.retry].
 */
@Composable
fun TransferStatusRoute(
    viewModel: TransferStatusViewModel,
    /** The strip of the network this screen is about: its account's. */
    lightClient: StateFlow<LightClientItem>,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The same state in the view the user chose (docs/simple-ui.md): the strip in Detailed view; in simple
    // mode the same item, drawn as the sync line while it says something.
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> {
            val strip by lightClient.collectAsStateWithLifecycle()
            SimpleTransferStatusScreen(state, onDone, viewModel::retry, modifier, syncLine = strip)
        }
        InterfaceMode.DETAILED -> {
            val strip by lightClient.collectAsStateWithLifecycle()
            TransferStatusScreen(state, strip, onDone, viewModel::retry, modifier)
        }
    }
}

/**
 * Send, status (docs/wallet-ux.md, screen 9) as a pure function of [state]: what was sent, and
 * Signed → Broadcast → Included → Finalized, each step with its block once it has one; for one
 * maybe sent, Try again ([onRetry]), which sends the same signed bytes. [lightClient] is the
 * network's strip, Detailed view's; simple mode ([SimpleTransferStatusScreen]) draws none (null),
 * and offers its steps' blocks behind *Details* ([details]; none: no Details).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferStatusScreen(
    state: TransferStatusUiState,
    lightClient: LightClientItem?,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    details: List<String> = emptyList(),
    syncLine: LightClientItem? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Transfer") },
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
        val body: @Composable BoxScope.() -> Unit = {
            when (state) {
                is TransferStatusUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is TransferStatusUiState.Shown -> Shown(state, onDone, onRetry, details)
            }
        }
        UnderLightClientStripIfAny(lightClient, Modifier.padding(padding), syncLine, body)
    }
}

/** A token approval: the amount, the spender it lets take it, and the account that sent it. */
@Composable
private fun ApprovalBlock(approval: ApprovalSummary) {
    Text(approval.title, style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TokenIcon(approval.icon, approval.symbol, size = 40.dp)
        Text(approval.amount, style = MaterialTheme.typography.headlineSmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Blockie(approval.spender, size = 32.dp)
        Column {
            Text(approval.spenderLabel, style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(approval.spender, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
    FromRow(approval.from, "From")
}

/** A swap: what it sells, the least it buys, the router it went through and the account paid. */
@Composable
private fun SwapBlock(swap: SwapSummary) {
    Text(swap.title, style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TokenIcon(swap.soldIcon, swap.soldSymbol, size = 40.dp)
        Text(swap.sold, style = MaterialTheme.typography.headlineSmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TokenIcon(swap.boughtIcon, swap.boughtSymbol, size = 40.dp)
        Text(swap.bought, style = MaterialTheme.typography.titleLarge)
    }
    swap.received?.let {
        Text(it, Modifier.testTag(TRANSFER_RECEIVED_TAG), style = MaterialTheme.typography.bodyLarge)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Blockie(swap.router, size = 32.dp)
        Column {
            Text("Through the router", style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(swap.router, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
    FromRow(swap.from, "From and paid to")
}

/** A CoW order's cancellation on chain: the order, the settlement contract and the account that sent it. */
@Composable
private fun CancellationBlock(cancellation: CancellationSummary) {
    Text(cancellation.title, style = MaterialTheme.typography.titleMedium)
    Column {
        Text("CoW order", style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(cancellation.order, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Blockie(cancellation.settlement, size = 32.dp)
        Column {
            Text("Settlement contract", style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(
                    cancellation.settlement,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
    FromRow(cancellation.from, "From")
}

@Composable
private fun FromRow(from: String, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Blockie(from, size = 24.dp)
        Text(
            "$label ${shortAddress(from)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun Shown(state: TransferStatusUiState.Shown, onDone: () -> Unit, onRetry: () -> Unit, details: List<String>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val sent = state.sent
        if (sent != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TokenIcon(sent.icon, sent.symbol, size = 40.dp)
                Text(sent.amount, style = MaterialTheme.typography.headlineSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Blockie(sent.to, size = 32.dp)
                Column {
                    Text("To", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(sent.to, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            FromRow(sent.from, "From")
        }
        val safeTx = state.safeTx
        if (safeTx != null) {
            Text(safeTx.title, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Blockie(safeTx.safe, size = 32.dp)
                Column {
                    Text("Safe · ${safeTx.nonce}", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(safeTx.safe, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            FromRow(safeTx.from, "From")
        }
        val deployment = state.deployment
        if (deployment != null) {
            Text(deployment.title, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar(deployment.safe, size = 40.dp, threshold = deployment.signers)
                Column {
                    Text(deployment.shape, style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(
                            deployment.safe,
                            Modifier.testTag(TRANSFER_SAFE_TAG),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            FromRow(deployment.from, "Paid from")
            Text(
                deployment.listing,
                color = if (deployment.listingFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        state.approval?.let { ApprovalBlock(it) }
        state.swap?.let { SwapBlock(it) }
        state.cancellation?.let { CancellationBlock(it) }
        if (sent == null && safeTx == null && deployment == null && state.approval == null && state.swap == null &&
            state.cancellation == null
        ) {
            Text(
                "This device has no record of what was sent: only where the transaction stands is shown.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            state.steps.forEach { step -> StepRow(step) }
        }
        state.notSent?.let { NotSentFields(it, state.retrying, onRetry) }
        state.note?.let { note ->
            Text(
                note,
                color = if (state.steps.any { it.state == StepState.FAILED }) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text("Transaction", style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(
                state.hash,
                Modifier.testTag(TRANSFER_HASH_TAG),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (details.isNotEmpty()) {
            var open by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { open = !open }, Modifier.testTag(TRANSFER_DETAILS_TAG)) {
                Text(if (open) "Hide details" else "Details")
            }
            if (open) {
                details.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Button(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text("Done") }
    }
}

@Composable
private fun StepRow(step: StatusStep) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        modifier = Modifier.fillMaxWidth().testTag(TRANSFER_STEP_TAG),
        leadingContent = {
            val mark = Modifier.size(24.dp).semantics { contentDescription = step.state.description }
            when (step.state) {
                StepState.DONE -> Icon(Icons.Filled.CheckCircle, null, mark, tint = colors.primary)
                StepState.FAILED -> Icon(Icons.Filled.Warning, null, mark, tint = colors.error)
                StepState.UNKNOWN -> Icon(Icons.Filled.Info, null, mark, tint = colors.onSurfaceVariant)
                StepState.UNCONFIRMED -> Icon(Icons.Filled.Warning, null, mark, tint = colors.tertiary)
                StepState.WAITING -> Box(mark, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        },
        headlineContent = { Text(step.label) },
        supportingContent = step.detail?.let { detail -> { Text(detail) } },
    )
}

/** What a step's mark says to accessibility services. */
private val StepState.description: String
    get() = when (this) {
        StepState.DONE -> "Done"
        StepState.WAITING -> "Waiting"
        StepState.FAILED -> "Failed"
        StepState.UNKNOWN -> "Not verified right now"
        StepState.UNCONFIRMED -> "Not confirmed by a peer"
    }
