package eth.serotine.ui.pairdapp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.unavailable

/** Test tag of the link field, for UI tests. */
const val PAIR_DAPP_FIELD_TAG = "pair-dapp-field"

/** Binds [PairDappScreen] to its ViewModel; a pairing that took ends the screen through [onPaired]. */
@Composable
fun PairDappRoute(
    viewModel: PairDappViewModel,
    onBack: () -> Unit,
    onPaired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val paired by rememberUpdatedState(onPaired)
    LaunchedEffect(state.paired) {
        if (state.paired) paired()
    }
    PairDappScreen(
        state,
        onEdit = viewModel::edit,
        onConnect = viewModel::connect,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Pair (docs/walletconnect.md, screen D2) as a pure function of [state]: the `wc:` link the dApp
 * shows, pasted, and Connect. What pairing answered, when it did not take, below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairDappScreen(
    state: PairDappUiState,
    onEdit: (String) -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
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
                title = { Text("Connect a dApp") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "In the dApp, choose WalletConnect and copy its link. Paste it here. The dApp then asks to " +
                    "connect, and you choose which accounts it sees.",
            )
            OutlinedTextField(
                value = state.text,
                onValueChange = onEdit,
                label = { Text("WalletConnect link (wc:…)") },
                enabled = !state.busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(PAIR_DAPP_FIELD_TAG),
            )
            state.message?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
            // Greyed out without a link, and still tappable: the tap says what is missing.
            Button(
                onClick = onConnect,
                enabled = !state.busy,
                colors = if (state.missingLink) Unavailable.buttonColors() else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).unavailable(state.missingLink),
            ) {
                Text(if (state.busy) "Connecting…" else "Connect")
            }
            Text(
                "Only on Sepolia for now. The link holds the key of this connection: paste it only here.",
                Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
