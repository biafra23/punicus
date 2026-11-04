package eth.serotine.ui.addtoken

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.components.HiddenDetails
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NetworkChips
import eth.serotine.ui.components.Note
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.components.submitOnDone
import eth.serotine.ui.lightclient.LightClientViewModel
import eth.serotine.ui.mode.LocalInterfaceMode

/** Test tag of the address field, for UI tests. */
const val ADD_TOKEN_ADDRESS_TAG = "add-token-address"

/** Test tag of the *Details* that keep the block in simple mode, for UI tests. */
const val ADD_TOKEN_DETAILS_TAG = "add-token-details"

/** Test tag of the contract's card, for UI tests. */
const val ADD_TOKEN_CARD_TAG = "add-token-card"

/** Binds [AddTokenScreen] to its ViewModel; an added token ends the screen through [onAdded]. */
@Composable
fun AddTokenRoute(
    viewModel: AddTokenViewModel,
    lightClient: LightClientViewModel,
    onBack: () -> Unit,
    onAdded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val network by viewModel.stripNetwork.collectAsStateWithLifecycle()
    val added by rememberUpdatedState(onAdded)
    LaunchedEffect(state.done) {
        if (state.done) added()
    }
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleAddTokenScreen(
            state,
            viewModel::onAddressChange,
            viewModel::add,
            onBack,
            modifier,
            onNetwork = viewModel::onNetwork,
            onTurnOnNetwork = viewModel::onTurnOnNetwork,
            syncLine = lightClient.item(network).collectAsStateWithLifecycle().value,
        )
        // The strip in Detailed view; in simple mode the same item, drawn as the sync line while it says something.
        InterfaceMode.DETAILED -> AddTokenScreen(
            state,
            lightClient.item(network).collectAsStateWithLifecycle().value,
            viewModel::onAddressChange,
            viewModel::add,
            onBack,
            modifier,
            onNetwork = viewModel::onNetwork,
            onTurnOnNetwork = viewModel::onTurnOnNetwork,
        )
    }
}

/**
 * Add token (docs/wallet-ux.md, screen 20) as a pure function of [state]: the contract's
 * address, then what the contract says about itself, labelled as its claim, with the monogram
 * it will be drawn with; Add only for a contract that answers as a token. The network comes first
 * (docs/networks.md, N17), chosen through [onNetwork], one that is off turned on through
 * [onTurnOnNetwork]. [lightClient] is the strip above it, Detailed view's; simple mode
 * ([SimpleAddTokenScreen]) has none, and keeps the block behind *Details* ([details]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTokenScreen(
    state: AddTokenUiState,
    lightClient: LightClientItem?,
    onAddressChange: (String) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNetwork: (chainId: Long) -> Unit = {},
    onTurnOnNetwork: (chainId: Long) -> Unit = {},
    details: List<String> = emptyList(),
    syncLine: LightClientItem? = null,
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
                title = { Text("Add token") },
            )
        },
    ) { padding ->
        UnderLightClientStripIfAny(lightClient, Modifier.padding(padding), syncLine) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.networks.isNotEmpty()) {
                    NetworkChips(state.networks, "Add a token on", onNetwork, onTurnOnNetwork)
                }
                OutlinedTextField(
                    value = state.address,
                    onValueChange = onAddressChange,
                    modifier = Modifier.fillMaxWidth().testTag(ADD_TOKEN_ADDRESS_TAG),
                    enabled = !state.adding,
                    label = { Text("Contract address") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    isError = state.addressError != null,
                    supportingText = { Text(state.addressError ?: "0x and 40 hexadecimal digits") },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = submitOnDone(state.canAdd, onAdd),
                )
                state.card?.let { Card(it) }
                if (details.isNotEmpty()) HiddenDetails(ADD_TOKEN_DETAILS_TAG) { details.forEach { Note(it) } }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "Name and symbol are the contract's own claim: a token is identified by its address only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onAdd, enabled = state.canAdd, modifier = Modifier.align(Alignment.End)) {
                    Text("Add token")
                }
            }
        }
    }
}

@Composable
private fun Card(card: TokenCard) {
    Surface(
        Modifier.fillMaxWidth().testTag(ADD_TOKEN_CARD_TAG),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (card) {
                TokenCard.Checking -> Text("Reading the contract…")
                is TokenCard.Claim -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TokenIcon(icon = null, symbol = card.symbol, size = 40.dp)
                        Column {
                            Text("${card.name} · ${card.symbol}")
                            Text(
                                "${card.decimals} decimals, as the contract says",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(card.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(card.checkedAt, style = MaterialTheme.typography.labelMedium)
                    card.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                is TokenCard.Known -> Text("The wallet knows this token already, as ${card.symbol}.")
                is TokenCard.Unavailable -> Text(card.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
