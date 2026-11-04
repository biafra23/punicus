package eth.serotine.ui.activitydetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.activity.statusColor
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.HiddenDetails
import eth.serotine.ui.components.HideAmountsButton
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.mode.LocalInterfaceMode
import eth.serotine.ui.platform.rememberCopyText
import kotlinx.coroutines.flow.StateFlow

/** Test tag of each field of the detail, for UI tests. */
const val ACTIVITY_DETAIL_FIELD_TAG = "activity-detail-field"

/** Test tag of the *Details* that keep the block in simple mode, for UI tests. */
const val ACTIVITY_DETAIL_DETAILS_TAG = "activity-detail-details"

/**
 * Binds [ActivityDetailScreen] to its ViewModel; Copy is done here, on the platform's clipboard.
 * [onStatus] opens the status of a transaction sent from here (by its hash), [onAddToken] Add
 * token by address with the contract (EIP-55) filled in.
 */
@Composable
fun ActivityDetailRoute(
    viewModel: ActivityDetailViewModel,
    /** The strip of the network this screen is about: its account's. */
    lightClient: StateFlow<LightClientItem>,
    onBack: () -> Unit,
    onStatus: (hash: String) -> Unit,
    onAddToken: (contract: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val copy = rememberCopyText()
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleActivityDetailScreen(
            state,
            onBack,
            copy,
            onStatus,
            onAddToken,
            modifier,
            onToggleAmounts = viewModel::toggleAmounts,
            syncLine = lightClient.collectAsStateWithLifecycle().value,
        )
        // The strip in Detailed view; in simple mode the same item, drawn as the sync line while it says something.
        InterfaceMode.DETAILED -> ActivityDetailScreen(
            state,
            lightClient.collectAsStateWithLifecycle().value,
            onBack = onBack,
            onCopy = copy,
            onStatus = onStatus,
            onAddToken = onAddToken,
            modifier = modifier,
            onToggleAmounts = viewModel::toggleAmounts,
        )
    }
}

/**
 * The detail of one row of an account's Activity (docs/wallet-ux.md, screen 26) as a pure function
 * of [state]: what it did and how it ended, as its row says, then every address and hash it
 * concerns in full, each with Copy ([onCopy]); Status ([onStatus]) for a transaction sent from
 * here, and Add token ([onAddToken]) for an ERC-20 contract the wallet does not list.
 * [lightClient] is the strip above it, Detailed view's; simple mode ([SimpleActivityDetailScreen])
 * has none, and keeps the block behind *Details* ([details]). Once it is shown, the eye beside Back
 * hides or shows its amounts ([onToggleAmounts]; docs/wallet-ux.md, *Hiding amounts*).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(
    state: ActivityDetailUiState,
    lightClient: LightClientItem?,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onStatus: (String) -> Unit,
    onAddToken: (String) -> Unit,
    modifier: Modifier = Modifier,
    onToggleAmounts: () -> Unit = {},
    details: List<DetailField> = emptyList(),
    syncLine: LightClientItem? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Row {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        // Top left, beside Back, while there are amounts to hide.
                        (state as? ActivityDetailUiState.Shown)?.let { HideAmountsButton(it.amountsHidden, onToggleAmounts) }
                    }
                },
                title = {
                    Column {
                        Text("Transaction")
                        Text(state.title, style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        },
    ) { padding ->
        UnderLightClientStripIfAny(lightClient, Modifier.padding(padding), syncLine) {
            DetailContent(state, onCopy, onStatus, onAddToken, details)
        }
    }
}

/** What the detail says, under the strip. */
@Composable
private fun BoxScope.DetailContent(
    state: ActivityDetailUiState,
    onCopy: (String) -> Unit,
    onStatus: (String) -> Unit,
    onAddToken: (String) -> Unit,
    details: List<DetailField>,
) {
    when (state) {
        is ActivityDetailUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        is ActivityDetailUiState.Unavailable -> Text(
            state.message,
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error,
        )
        is ActivityDetailUiState.Shown -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar(state.about)
                Column(Modifier.weight(1f)) {
                    Text(state.headline, style = MaterialTheme.typography.titleMedium)
                    Text(state.status, style = MaterialTheme.typography.bodySmall, color = statusColor(state.state))
                }
            }
            state.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.opensStatus != null || state.addToken != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.addToken?.let { contract ->
                        FilledTonalButton(onClick = { onAddToken(contract) }) {
                            Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Add token")
                        }
                    }
                    state.opensStatus?.let { hash ->
                        OutlinedButton(onClick = { onStatus(hash) }) { Text("Status") }
                    }
                }
            }
            HorizontalDivider()
            state.fields.forEach { DetailLine(it, onCopy) }
            if (details.isNotEmpty()) {
                HiddenDetails(ACTIVITY_DETAIL_DETAILS_TAG) { details.forEach { DetailLine(it, onCopy) } }
            }
        }
    }
}

/** One field: its label, then its value; an address with its blockie and name, and Copy for an address or a hash. */
@Composable
private fun DetailLine(field: DetailField, onCopy: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(ACTIVITY_DETAIL_FIELD_TAG)) {
        Text(
            field.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (field.kind == DetailKind.TEXT || field.kind == DetailKind.BLOCK) {
            Text(field.value, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        field.name?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (field.kind == DetailKind.ADDRESS) Blockie(field.value, size = 20.dp)
            // In full, and selectable: the value is what Copy copies too.
            SelectionContainer(Modifier.weight(1f)) {
                Text(field.value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = { onCopy(field.value) }) {
                Icon(
                    SerotineIcons.ContentCopy,
                    contentDescription = "Copy ${field.label.replaceFirstChar { it.lowercase() }}",
                    Modifier.size(18.dp),
                )
            }
        }
    }
}
