package eth.serotine.ui.dappproposal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.unavailable

/** Test tag of an account's row, followed by its address, for UI tests. */
const val DAPP_PROPOSAL_ACCOUNT_TAG = "dapp-proposal-account-"

/**
 * Binds [DappProposalScreen] to its ViewModel; an answer, or a proposal no longer waiting, ends it
 * through [onDone].
 */
@Composable
fun DappProposalRoute(
    viewModel: DappProposalViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(state.done, state.gone) {
        if (state.done || state.gone) done()
    }
    DappProposalScreen(
        state,
        onToggle = viewModel::toggle,
        onApprove = viewModel::approve,
        onReject = viewModel::reject,
        onDismissExplanation = viewModel::dismissExplanation,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * A dApp's session proposal (docs/walletconnect.md, screen D3) as a pure function of [state]: what
 * the dApp says of itself, marked as its claim, the networks the connection would hold, each with its kind
 * of funds, what it asks to do, and the accounts to choose, each with its network, those this dApp may not
 * be offered greyed out, a tap saying why. Approve and
 * Reject; a proposal that asks for too much can only be rejected, and says why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DappProposalScreen(
    state: DappProposalUiState,
    onToggle: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDismissExplanation: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
                title = { Text("Connection request") },
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
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(16.dp)) {
                Text(state.name, style = MaterialTheme.typography.headlineSmall)
                Text(state.url, style = MaterialTheme.typography.bodyMedium)
                if (state.description.isNotEmpty()) Text(state.description, Modifier.padding(top = 4.dp))
                Text(
                    "Stated by the dApp, not verified: Punicus cannot check who is asking.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.networks.isNotEmpty()) {
                Heading(if (state.networks.size == 1) "Network" else "Networks")
                state.networks.forEach { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
                state.networksNote?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.asks.isNotEmpty()) {
                Heading("It may")
                state.asks.forEach { Text("· $it", Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
            }
            state.refusal?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            Heading("Accounts it sees")
            state.accounts.forEach { account ->
                val unavailable = account.unavailable != null
                val color = if (unavailable) Unavailable.contentColor() else MaterialTheme.colorScheme.onSurface
                ListItem(
                    modifier = Modifier
                        .toggleable(
                            value = account.chosen,
                            role = Role.Checkbox,
                            onValueChange = { onToggle(account.key) },
                        )
                        .unavailable(unavailable)
                        .testTag(DAPP_PROPOSAL_ACCOUNT_TAG + account.key),
                    leadingContent = {
                        Checkbox(checked = account.chosen, onCheckedChange = null, enabled = !unavailable)
                    },
                    headlineContent = { Text(account.name, color = color) },
                    supportingContent = {
                        val where = when {
                            account.network.isEmpty() -> account.address
                            else -> "${account.network} · ${account.address}"
                        }
                        Text(account.unavailable ?: where, color = color)
                    },
                    colors = ListItemDefaults.colors(),
                )
            }
            state.notice?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            // What Approve waits for, said beside it as a tap on it would.
            if (state.refusal == null && state.accounts.any { it.chosen }) {
                state.missingRequired?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReject, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text("Reject")
                }
                // Greyed out while it cannot be done, and still tappable: the tap says why.
                Button(
                    onClick = onApprove,
                    enabled = !state.busy,
                    colors = if (state.approveUnavailable) {
                        Unavailable.buttonColors()
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.weight(1f).unavailable(state.approveUnavailable),
                ) { Text("Approve") }
            }
        }
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
