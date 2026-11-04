package eth.serotine.ui.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.NetworkMark
import eth.serotine.ui.components.QrCode
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.platform.rememberCopyText
import eth.serotine.ui.platform.rememberShareText
import kotlinx.coroutines.launch

/** Test tag of the full address under the QR code, for UI tests. */
const val RECEIVE_ADDRESS_TAG = "receive-address"

/** Test tag of the QR code, for UI tests. */
const val RECEIVE_QR_TAG = "receive-qr"

/** Binds [ReceiveScreen] to its ViewModel; an account that leaves the wallet ends the screen through [onGone]. */
@Composable
fun ReceiveRoute(viewModel: ReceiveViewModel, onBack: () -> Unit, onGone: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gone by rememberUpdatedState(onGone)
    LaunchedEffect(state is ReceiveUiState.Gone) {
        if (state is ReceiveUiState.Gone) gone()
    }
    ReceiveScreen(state, onBack, onCopy = rememberCopyText(), onShare = rememberShareText(), modifier = modifier)
}

/**
 * The address to receive to (docs/wallet-ux.md, screen 6), as a pure function of [state]: a
 * QR code of the checksummed address, the address in full, the network warning, Copy, and
 * Share where the platform has a share sheet ([onShare] not null).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    state: ReceiveUiState,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Receive") },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                ReceiveUiState.Loading, ReceiveUiState.Gone ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ReceiveUiState.Unavailable -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                is ReceiveUiState.Ready -> Address(
                    state,
                    onCopy = {
                        onCopy(state.address)
                        scope.launch { snackbar.showSnackbar("Address copied") }
                    },
                    onShare = onShare?.let { share -> { share(state.address) } },
                )
            }
        }
    }
}

@Composable
private fun Address(state: ReceiveUiState.Ready, onCopy: () -> Unit, onShare: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Blockie(state.address, size = 28.dp)
            Text(state.label, style = MaterialTheme.typography.titleMedium)
        }
        // The network it receives on (docs/networks.md, N13): the same address elsewhere is another account.
        NetworkMark(state.network, style = MaterialTheme.typography.labelLarge)
        QrCode(
            state.address,
            description = "QR code of the address",
            modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().testTag(RECEIVE_QR_TAG),
        )
        SelectionContainer {
            Text(
                state.address,
                Modifier.testTag(RECEIVE_ADDRESS_TAG),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
        }
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
            Text(
                state.notice,
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        listOfNotNull(state.note, state.networkOff).forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCopy) {
                Icon(SerotineIcons.ContentCopy, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Copy")
            }
            if (onShare != null) {
                OutlinedButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Share")
                }
            }
        }
    }
}
