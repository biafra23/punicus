package eth.serotine.ui.safeimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.CHECKING_SAFE
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.components.done
import eth.serotine.ui.components.submitOnDone
import eth.serotine.ui.mode.LocalInterfaceMode
import eth.serotine.ui.platform.KeepScreenOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/** Test tag of the request's field, for UI tests. */
const val IMPORT_REQUEST_FIELD_TAG = "import-request-field"

/** Test tag of the Import button, for UI tests. */
const val IMPORT_REQUEST_BUTTON_TAG = "import-request-button"

/** Binds [SafeImportScreen] to its ViewModel; a request imported ends the screen through [onImported] with its hash. */
@Composable
fun SafeImportRoute(
    viewModel: SafeImportViewModel,
    /** The strip of the network this screen is about: its account's. */
    lightClient: StateFlow<LightClientItem>,
    onBack: () -> Unit,
    onImported: (hash: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: a paste reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val imported = (state as? SafeImportUiState.Imported)?.hash
    val currentOnImported by rememberUpdatedState(onImported)
    LaunchedEffect(imported) {
        if (imported != null) currentOnImported(imported)
    }
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleSafeImportScreen(
            state,
            viewModel::onTextChange,
            viewModel::import,
            onBack,
            modifier,
            syncLine = lightClient.collectAsStateWithLifecycle().value,
        )
        // The strip in Detailed view; in simple mode the same item, drawn as the sync line while it says something.
        InterfaceMode.DETAILED -> SafeImportScreen(
            state,
            lightClient.collectAsStateWithLifecycle().value,
            viewModel::onTextChange,
            viewModel::import,
            onBack,
            modifier,
        )
    }
}

/**
 * Import a request (docs/wallet-ux.md, screen 16) as a pure function of [state]: the Safe it is
 * for, the request's text pasted as another device shared it, and Import, which checks it against
 * the chain; why one is refused is said under the field. [lightClient] is the strip above it,
 * Detailed view's; simple mode ([SimpleSafeImportScreen]) has none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafeImportScreen(
    state: SafeImportUiState,
    lightClient: LightClientItem?,
    onTextChange: (String) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    syncLine: LightClientItem? = null,
) {
    // The Safe's check can take up to 45 s: the display stays on while it runs.
    if ((state as? SafeImportUiState.Entering)?.importing == true) KeepScreenOn()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Import a request") },
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
                is SafeImportUiState.Imported -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SafeImportUiState.Entering -> Entering(state, onTextChange, onImport)
            }
        }
    }
}

@Composable
private fun Entering(state: SafeImportUiState.Entering, onTextChange: (String) -> Unit, onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(state.address, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(state.label ?: "A Safe not in this wallet", style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(state.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Text(
            "Paste a signing request another owner shared for this Safe: the text its Copy text gives. Serotine " +
                "decodes it here and checks it against the chain — the Safe, its nonce, every signature — before " +
                "any of it is kept.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Scanning its QR code is not built yet: paste the text.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth().testTag(IMPORT_REQUEST_FIELD_TAG),
            enabled = !state.importing,
            label = { Text("Signing request") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 4,
            maxLines = 8,
            isError = state.error != null,
            supportingText = state.error?.let { error -> { Text(error) } },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
            ).done(),
            keyboardActions = submitOnDone(state.canImport, onImport),
        )
        Button(
            onClick = onImport,
            enabled = state.canImport,
            modifier = Modifier.align(Alignment.End).testTag(IMPORT_REQUEST_BUTTON_TAG),
        ) {
            if (state.importing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(CHECKING_SAFE)
            } else {
                Text("Import")
            }
        }
    }
}
