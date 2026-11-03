package eth.serotine.ui.dapptransaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.NotSentFields
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.UnlockFields
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.dapprequest.SignInField

/** Test tags of D6's password field, its Send button and its Refuse button, for UI tests. */
const val DAPP_TRANSACTION_PASSWORD_TAG = "dapp-transaction-password"
const val DAPP_TRANSACTION_SEND_TAG = "dapp-transaction-send"
const val DAPP_TRANSACTION_REFUSE_TAG = "dapp-transaction-refuse"
const val DAPP_TRANSACTION_OPEN_SAFE_TX_TAG = "dapp-transaction-open-safe-tx"

/**
 * Binds [DappTransactionScreen] to its ViewModel; an answer, or a request no longer waiting, ends it
 * through [onDone]. The password goes whenever the screen is not shown, and the quote is made again.
 * A Safe's transaction once queued opens through [onOpenSafeTx], with the Safe's address and its hash.
 */
@Composable
fun DappTransactionRoute(
    viewModel: DappTransactionViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOpenSafeTx: (safe: String, hash: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onShown() }
    val done by rememberUpdatedState(onDone)
    val open by rememberUpdatedState(onOpenSafeTx)
    LaunchedEffect(state.done, state.gone) {
        if (state.done || state.gone) done()
    }
    // Just signed and queued: its Safe transaction's screen, which collects the rest and executes it.
    LaunchedEffect(state.openQueued) {
        val queued = state.queued
        if (state.openQueued && queued != null) {
            viewModel.onQueuedOpened()
            open(state.accountAddress, queued)
        }
    }
    DappTransactionScreen(
        state,
        onPasswordChange = viewModel::onPasswordChange,
        onSend = viewModel::send,
        onSendWithBiometrics = viewModel::sendWithBiometrics,
        onRetry = viewModel::retry,
        onRequote = { viewModel.requote() },
        onRefuse = viewModel::refuse,
        onDismissExplanation = viewModel::dismissExplanation,
        onOpenQueued = { state.queued?.let { open(state.accountAddress, it) } },
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * A dApp's transaction (docs/walletconnect.md, screen D6) as a pure function of [state]: who asks,
 * marked as the dApp's claim; the account it is sent from and the recipient, in full; the value; the call,
 * decoded or raw and marked not decoded; the quote from verified state (the fee at most, the balance left,
 * the verified run) or why there is none; the warnings; and the unlock, with Refuse beside it. One that
 * cannot be sent shows why, its Send greyed out and still tappable. From a Safe it says it is proposed as
 * a Safe transaction, quotes the Safe's side, and signs and queues it; once queued, it opens its screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DappTransactionScreen(
    state: DappTransactionUiState,
    onPasswordChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendWithBiometrics: () -> Unit,
    onRetry: () -> Unit,
    onRequote: () -> Unit,
    onRefuse: () -> Unit,
    onDismissExplanation: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenQueued: () -> Unit = {},
) {
    state.explaining?.let { UnavailableDialog(UnavailableNotice(it.title, it.reason), onDismissExplanation) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Transaction request") },
            )
        },
    ) { padding ->
        if (state.loading || state.gone) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (state.ended) {
            Text(
                state.notice.orEmpty(),
                Modifier.padding(padding).padding(16.dp),
                color = MaterialTheme.colorScheme.error,
            )
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(16.dp)) {
                Text(state.name, style = MaterialTheme.typography.headlineSmall)
                Text(state.url, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Stated by the dApp, not verified: Serotine cannot check who is asking.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Heading("From")
            Party(state.accountName, state.accountAddress)
            Text("On Sepolia, a test network", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (state.fromSafe) {
                Text(
                    DappTransactionViewModel.SAFE_NOTE,
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Heading("To")
            Party(null, state.to)
            Field(SignInField("Value", state.value))
            Heading("What it does")
            state.meaning?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            state.meaningFields.forEach { Field(it) }
            state.dataHex?.let { hex ->
                Text(
                    "Call data (${state.dataSize})" + if (state.meaning == null) ", not decoded" else "",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    SelectionContainer {
                        Text(
                            "0x$hex",
                            Modifier.padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            state.warnings.forEach {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
            }
            state.refusal?.let {
                Text(
                    "This request cannot be sent. $it Refuse answers the dApp.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.refusal == null && state.queued == null) {
                Heading("Worked out from verified state")
                when {
                    state.quoting -> Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            if (state.fromSafe) "Proposing it and running the call from the Safe…"
                            else "Quoting the fee and running the call…",
                        )
                    }
                    state.notQuoted != null -> Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(state.notQuoted, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRequote) { Text("Try again") }
                    }
                    else -> state.quote.forEach { Field(it) }
                }
            }
            Text(
                state.waits,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            state.notice?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val notSent = state.notSent
                when {
                    notSent != null -> NotSentFields(notSent, retrying = state.busy, onRetry = onRetry)
                    state.queued != null -> {
                        Text(DappTransactionViewModel.QUEUED_NOTE)
                        Button(
                            onClick = onOpenQueued,
                            modifier = Modifier.fillMaxWidth().testTag(DAPP_TRANSACTION_OPEN_SAFE_TX_TAG),
                        ) { Text("Open the Safe transaction") }
                    }
                    state.sendUnavailable -> Button(
                        // Greyed out while it cannot be done, and still tappable: the tap says why.
                        onClick = onSend,
                        colors = Unavailable.buttonColors(),
                        modifier = Modifier.fillMaxWidth().unavailable(true).testTag(DAPP_TRANSACTION_SEND_TAG),
                    ) { Text(state.signLabel) }
                    else -> UnlockFields(
                        unlock = state.unlock,
                        signing = state.busy,
                        canSign = !state.busy && state.unlock.password.text.isNotEmpty(),
                        canSignWithBiometrics = !state.busy,
                        signLabel = state.signLabel,
                        signingLabel = if (state.fromSafe) "Signing…" else "Sending…",
                        onPasswordChange = onPasswordChange,
                        onSign = onSend,
                        onSignWithBiometrics = onSendWithBiometrics,
                        passwordTag = DAPP_TRANSACTION_PASSWORD_TAG,
                        signTag = DAPP_TRANSACTION_SEND_TAG,
                    )
                }
                // Greyed out, still tappable, while a transaction signed for it may be on its way: the tap says why.
                OutlinedButton(
                    onClick = onRefuse,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().unavailable(state.refuseUnavailable)
                        .testTag(DAPP_TRANSACTION_REFUSE_TAG),
                ) {
                    Text("Refuse", color = if (state.refuseUnavailable) Unavailable.contentColor() else Color.Unspecified)
                }
            }
        }
    }
}

@Composable
private fun Party(name: String?, address: String) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Blockie(address)
        Column {
            name?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            SelectionContainer {
                Text(address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Field(field: SignInField) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            field.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer { Text(field.value) }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
