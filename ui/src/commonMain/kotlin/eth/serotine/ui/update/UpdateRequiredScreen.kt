package eth.serotine.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Test tag of the update screen, for UI tests. */
const val UPDATE_REQUIRED_TAG = "update-required"

/** Test tag of the update screen's button to the update, for UI tests. */
const val UPDATE_BUTTON_TAG = "update-button"

/**
 * Binds [UpdateRequiredScreen] to its ViewModel: the only composable that knows it exists
 * besides the app, which shows this screen and nothing else while an update is required.
 * Between the end of that and the app moving on, it draws nothing.
 */
@Composable
fun UpdateRequiredRoute(viewModel: UpdateRequiredViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val required = state as? UpdateRequiredUiState.Required ?: return
    val uriHandler = LocalUriHandler.current
    UpdateRequiredScreen(required, onGetUpdate = { openLink(required.updateUrl, uriHandler::openUri) }, modifier)
}

/**
 * The update screen as a pure function of [state]: why this version can no longer follow the
 * network, that updating keeps the wallet, and the way to the update ([onGetUpdate], and the
 * address as text to copy where no link opens).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateRequiredScreen(
    state: UpdateRequiredUiState.Required,
    onGetUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("Punicus") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag(UPDATE_REQUIRED_TAG),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text("Update Punicus", style = MaterialTheme.typography.headlineSmall)
            Text(state.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Text(state.keepsData, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Button(onClick = onGetUpdate, modifier = Modifier.testTag(UPDATE_BUTTON_TAG)) { Text("Get the update") }
            SelectionContainer {
                Text(state.updateUrl, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            Text(
                state.details,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                state.version,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
