package eth.serotine.ui.handoff

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.FromLine
import eth.serotine.ui.components.Heading
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.Note
import eth.serotine.ui.components.RecipientCard
import eth.serotine.ui.components.ReviewButton
import eth.serotine.ui.components.SEND_FROM_ANOTHER_WALLET
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.TransferFormFields
import eth.serotine.ui.components.UnderLightClientStrip
import kotlinx.coroutines.flow.StateFlow

/** Test tag of the button that hands the transfer to another wallet, for UI tests. */
const val HANDOFF_OPEN_TAG = "handoff-open"

/** Test tag of the outcome's title, for UI tests. */
const val HANDOFF_OUTCOME_TAG = "handoff-outcome"

/**
 * Binds [HandoffScreen] to its ViewModel. Simple mode draws the same screen: it has no simple
 * version yet (`.claude/rules/ui.md`, *Two views*). [onDone] leaves it, to where it was opened from.
 */
@Composable
fun HandoffRoute(
    viewModel: HandoffViewModel,
    /** The strip of the network this screen is about: its account's. */
    lightClient: StateFlow<LightClientItem>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HandoffScreen(
        state = state,
        lightClient = lightClient.collectAsStateWithLifecycle().value,
        onAssetChange = viewModel::onAssetChange,
        onToChange = viewModel::onToChange,
        onPickAccount = viewModel::onPickAccount,
        onRetryLookup = viewModel::onRetryLookup,
        onAmountChange = viewModel::onAmountChange,
        onMax = viewModel::onMax,
        onReview = viewModel::review,
        onEdit = viewModel::edit,
        onOpen = viewModel::open,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Send from another wallet (issue #255; docs/wallet-ux.md, screen 7H) as a pure function of
 * [state]: the transfer composed as on Send, then what is handed to the other wallet and what to
 * do there, then the chain watched for it, then what the chain shows. It takes no password: this
 * device signs nothing. Back from the review is to the compose step, unless the link is being
 * handed over; anywhere else it leaves.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun HandoffScreen(
    state: HandoffUiState,
    lightClient: LightClientItem,
    onAssetChange: (contract: String?) -> Unit,
    onToChange: (String) -> Unit,
    onPickAccount: (address: String) -> Unit,
    onRetryLookup: () -> Unit,
    onAmountChange: (String) -> Unit,
    onMax: () -> Unit,
    onReview: () -> Unit,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val review = state as? HandoffUiState.Review
    BackHandler(enabled = review != null) { if (review?.opening != true) onEdit() }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = if (review != null) onEdit else onBack, enabled = review?.opening != true) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        when (state) {
                            is HandoffUiState.Review -> "Review"
                            is HandoffUiState.Waiting -> "Waiting for the transfer"
                            is HandoffUiState.Outcome -> state.title
                            else -> SEND_FROM_ANOTHER_WALLET
                        },
                    )
                },
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
                is HandoffUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is HandoffUiState.Cannot -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                is HandoffUiState.Compose -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Note(
                        "This account's key is not on this device. Another wallet app that holds it signs and " +
                            "sends the transfer; Serotine then checks the chain for it.",
                    )
                    TransferFormFields(
                        state.form,
                        onAssetChange,
                        onToChange,
                        onPickAccount,
                        onRetryLookup,
                        onAmountChange,
                        onMax,
                        onReview = onReview,
                    )
                    state.form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    ReviewButton(state.form, onReview, Modifier.align(Alignment.End))
                }
                is HandoffUiState.Review -> ReviewContent(state, onOpen)
                is HandoffUiState.Waiting -> WaitingContent(state, onBack)
                is HandoffUiState.Outcome -> OutcomeContent(state, onBack)
            }
        }
        UnderLightClientStrip(lightClient, Modifier.padding(padding), body)
    }
}

@Composable
private fun ReviewContent(state: HandoffUiState.Review, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The network first: the link names it, and the other wallet must send on it alone.
        Text("On ${state.network}", style = MaterialTheme.typography.titleMedium)
        Heading("The other wallet sends")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TokenIcon(state.asset.icon, state.asset.symbol, size = 40.dp)
            SelectionContainer { Text(state.amount, style = MaterialTheme.typography.headlineSmall) }
        }
        state.asset.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Heading("To")
        RecipientCard(state.recipient)
        Heading("From")
        FromLine(state.from)
        Heading("Before you open it")
        state.notes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        state.limit?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = onOpen,
            enabled = !state.opening,
            modifier = Modifier.align(Alignment.End).testTag(HANDOFF_OPEN_TAG),
        ) {
            if (state.opening) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Open the other wallet")
            }
        }
    }
}

@Composable
private fun WaitingContent(state: HandoffUiState.Waiting, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Text("Confirm the transfer in the other wallet", style = MaterialTheme.typography.titleMedium)
        }
        FromLine(state.from)
        Text(state.summary)
        Note(state.progress)
        state.problem?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        Note(
            "If you leave now, Serotine stops looking. The account's balance still shows the transfer once a " +
                "block has it.",
        )
        OutlinedButton(onClick = onBack, Modifier.align(Alignment.End)) { Text("Stop waiting") }
    }
}

@Composable
private fun OutcomeContent(state: HandoffUiState.Outcome, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (state.kind) {
        OutcomeKind.SENT -> colors.primaryContainer to colors.onPrimaryContainer
        OutcomeKind.FAILED, OutcomeKind.NOT_THE_ONE -> colors.errorContainer to colors.onErrorContainer
        OutcomeKind.NOT_SEEN -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = container,
            contentColor = content,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.title, Modifier.testTag(HANDOFF_OUTCOME_TAG), style = MaterialTheme.typography.titleMedium)
                Text(state.message)
            }
        }
        state.hash?.let { hash ->
            Heading("Transaction")
            SelectionContainer {
                Text(hash, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        Button(onClick = onDone, Modifier.align(Alignment.End)) { Text("Done") }
    }
}
