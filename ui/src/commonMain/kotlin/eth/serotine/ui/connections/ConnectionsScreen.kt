package eth.serotine.ui.connections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Test tag of a proposal's row, followed by its key, for UI tests. */
const val CONNECTIONS_PROPOSAL_TAG = "connections-proposal-"

/** Test tag of a request's row, followed by its key, for UI tests. */
const val CONNECTIONS_REQUEST_TAG = "connections-request-"

/** Binds [ConnectionsScreen] to its ViewModel. */
@Composable
fun ConnectionsRoute(
    viewModel: ConnectionsViewModel,
    onBack: () -> Unit,
    onPair: () -> Unit,
    onProposal: (String) -> Unit,
    onRequest: (String) -> Unit,
    onTransaction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ConnectionsScreen(
        state,
        onBack = onBack,
        onPair = onPair,
        onProposal = onProposal,
        onRequest = onRequest,
        onTransaction = onTransaction,
        onExtend = viewModel::extend,
        onDisconnect = viewModel::disconnect,
        modifier = modifier,
    )
}

/**
 * Connected dApps (docs/walletconnect.md, screen D1) as a pure function of [state]: the relay's
 * link, the proposals waiting (each opens its review through [onProposal]), the requests to sign
 * waiting (each opens its review through [onRequest]), the open sessions with
 * Extend and Disconnect, and Connect a dApp ([onPair], screen D2). A dApp's name and URL are its own
 * claim, and say so. Off in a build without a project ID, and saying so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    state: ConnectionsUiState,
    onBack: () -> Unit,
    onPair: () -> Unit,
    onProposal: (String) -> Unit,
    onRequest: (String) -> Unit,
    onTransaction: (String) -> Unit,
    onExtend: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Connected dApps") },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (state.off) {
                Text(
                    "This build has dApp connections switched off: it was built without a WalletConnect " +
                        "project ID.",
                    Modifier.padding(16.dp),
                )
                return@Column
            }
            Text(
                state.link,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            state.notice?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            }
            if (state.proposals.isNotEmpty() || state.requests.isNotEmpty()) {
                Heading("Waiting for you")
                state.requests.forEach { request ->
                    ListItem(
                        modifier = Modifier
                            .clickable {
                                if (request.transaction) onTransaction(request.key) else onRequest(request.key)
                            }
                            .testTag(CONNECTIONS_REQUEST_TAG + request.key),
                        headlineContent = { Text(request.name) },
                        supportingContent = {
                            Column {
                                Text(request.url)
                                Text(request.line)
                            }
                        },
                    )
                }
                state.proposals.forEach { proposal ->
                    ListItem(
                        modifier = Modifier
                            .clickable { onProposal(proposal.key) }
                            .testTag(CONNECTIONS_PROPOSAL_TAG + proposal.key),
                        headlineContent = { Text(proposal.name) },
                        supportingContent = {
                            Column {
                                Text(proposal.url)
                                Text(proposal.line)
                            }
                        },
                    )
                }
            }
            Heading("Connected")
            if (state.sessions.isEmpty()) {
                Text("No dApp is connected.", Modifier.padding(horizontal = 16.dp))
            }
            state.sessions.forEach { session ->
                ListItem(
                    headlineContent = { Text(session.name) },
                    supportingContent = {
                        Column {
                            Text(session.url)
                            Text(session.line)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onExtend(session.key) }) { Text("Extend") }
                                TextButton(onClick = { onDisconnect(session.key) }) { Text("Disconnect") }
                            }
                        }
                    },
                )
            }
            Button(onClick = onPair, modifier = Modifier.padding(16.dp).fillMaxWidth()) { Text("Connect a dApp") }
            Text(
                "A dApp's name and address are what it says of itself: Serotine cannot check them. dApps " +
                    "connect through WalletConnect's relay, a service run by Reown and a selected group of " +
                    "operators. It carries encrypted messages: it sees this device's IP address and when it " +
                    "is connected, never what you sign. A connection holds Ethereum and Gnosis Chain, or the " +
                    "Sepolia test network alone, never both.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
