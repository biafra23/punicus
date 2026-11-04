package eth.serotine.ui.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.FeeChoice
import eth.serotine.ui.components.FromLine
import eth.serotine.ui.components.Heading
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NotSentFields
import eth.serotine.ui.components.Note
import eth.serotine.ui.components.RecipientCard
import eth.serotine.ui.components.ReviewButton
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.TransferFormFields
import eth.serotine.ui.components.UnderLightClientStrip
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.components.UnlockFields
import eth.serotine.ui.mode.LocalInterfaceMode
import eth.serotine.ui.platform.protectFromScreenCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/** Test tag of the reviewed amount, for UI tests. */
const val SEND_AMOUNT_REVIEWED_TAG = "send-amount-reviewed"

/** Test tag of the wallet password field, for UI tests. */
const val SEND_PASSWORD_TAG = "send-password"

/** Test tag of the review's line naming the network it sends on, for UI tests. */
const val SEND_NETWORK_TAG = "send-network"

/** Test tag of simple mode's simulation line, for UI tests. */
const val SEND_SIMULATION_TAG = "send-simulation"

/** Test tag of simple mode's Details button, for UI tests. */
const val SEND_DETAILS_TAG = "send-details"

/** Test tag of the Sign and send button, for UI tests. */
const val SEND_SIGN_TAG = "send-sign"

/** Test tag of the button that opens an earlier transaction still maybe on its way, for UI tests. */
const val SEND_EARLIER_TAG = "send-earlier"

/**
 * Binds [SendScreen], or in simple mode [SimpleSendScreen], to its ViewModel. A transfer broadcast
 * ends the screen through [onSent] with its hash, and [onOpenTransfer] opens the status of an
 * earlier one that may still be on its way; the screen stopping being shown becomes
 * [SendViewModel.onHidden] (the password goes) and being shown again [SendViewModel.onShown] (the
 * review is quoted anew).
 */
@Composable
fun SendRoute(
    viewModel: SendViewModel,
    /** The strip of the network this screen is about: its account's. */
    lightClient: StateFlow<LightClientItem>,
    onBack: () -> Unit,
    onSent: (hash: String) -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: as on the seed screens, an edit reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onShown() }
    val sent = (state as? SendUiState.Sent)?.hash
    val currentOnSent by rememberUpdatedState(onSent)
    LaunchedEffect(sent) {
        if (sent != null) currentOnSent(sent)
    }
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleSendScreen(
            state = state,
            onAssetChange = viewModel::onAssetChange,
            onToChange = viewModel::onToChange,
            onPickAccount = viewModel::onPickAccount,
            onRetryLookup = viewModel::onRetryLookup,
            onAmountChange = viewModel::onAmountChange,
            onMax = viewModel::onMax,
            onFeeChange = viewModel::onFeeChange,
            onReview = viewModel::review,
            onEdit = viewModel::edit,
            onRefresh = viewModel::refresh,
            onPasswordChange = viewModel::onPasswordChange,
            onSign = viewModel::sign,
            onSignWithBiometrics = viewModel::signWithBiometrics,
            onRetry = viewModel::retry,
            onOpenTransfer = onOpenTransfer,
            onBack = onBack,
            modifier = modifier,
            syncLine = lightClient.collectAsStateWithLifecycle().value,
        )
        // The strip in Detailed view; in simple mode the same item, drawn as the sync line while it says something.
        InterfaceMode.DETAILED -> SendScreen(
            state = state,
            chrome = SendChrome.Strip(lightClient.collectAsStateWithLifecycle().value),
            onAssetChange = viewModel::onAssetChange,
            onToChange = viewModel::onToChange,
            onPickAccount = viewModel::onPickAccount,
            onRetryLookup = viewModel::onRetryLookup,
            onAmountChange = viewModel::onAmountChange,
            onMax = viewModel::onMax,
            onFeeChange = viewModel::onFeeChange,
            onReview = viewModel::review,
            onEdit = viewModel::edit,
            onRefresh = viewModel::refresh,
            onPasswordChange = viewModel::onPasswordChange,
            onSign = viewModel::sign,
            onSignWithBiometrics = viewModel::signWithBiometrics,
            onRetry = viewModel::retry,
            onOpenTransfer = onOpenTransfer,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

/**
 * Send (docs/wallet-ux.md, screens 7 and 8) as a pure function of [state]: the compose step —
 * from, asset, to, amount and fee — then the review of the verified quote, with the unlock that
 * signs it. While the review takes the wallet password, the window is kept out of screen capture
 * where the platform allows ([protectFromScreenCapture]); where it does not, the screen says so.
 * Its [chrome] is Detailed view's light-client strip, or simple mode's ([SimpleSendScreen]): no
 * strip and the simple review.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SendScreen(
    state: SendUiState,
    chrome: SendChrome,
    onAssetChange: (contract: String?) -> Unit,
    onToChange: (String) -> Unit,
    onPickAccount: (address: String) -> Unit,
    onRetryLookup: () -> Unit,
    onAmountChange: (String) -> Unit,
    onMax: () -> Unit,
    onFeeChange: (FeeLevel) -> Unit,
    onReview: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
    onRetry: () -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val review = state as? SendUiState.Review
    // In the review, back is to the compose step, until bytes may be out there: after a broadcast
    // no peer answered, only Try again may send them, so back leaves Send. While a signature
    // runs, back waits for it: leaving would not stop it, only hide what it did.
    val toCompose = review != null && review.notSent?.canRetry != true
    BackHandler(enabled = review != null) {
        when {
            review?.signing == true -> Unit
            toCompose -> onEdit()
            else -> onBack()
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = if (toCompose) onEdit else onBack, enabled = review?.signing != true) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        when (state) {
                            is SendUiState.Review -> "Review"
                            is SendUiState.Sent -> "Sent"
                            else -> "Send"
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
                is SendUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SendUiState.Sent -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Sent", Modifier.padding(top = 12.dp))
                }
                is SendUiState.CannotSend -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                is SendUiState.Compose -> ComposeContent(
                    state,
                    onAssetChange,
                    onToChange,
                    onPickAccount,
                    onRetryLookup,
                    onAmountChange,
                    onMax,
                    onFeeChange,
                    onReview,
                    onOpenTransfer,
                )
                is SendUiState.Review -> when (chrome) {
                    is SendChrome.Strip ->
                        ReviewContent(state, onRefresh, onPasswordChange, onSign, onSignWithBiometrics, onRetry)
                    is SendChrome.Simple ->
                        SimpleReviewContent(state, onRefresh, onPasswordChange, onSign, onSignWithBiometrics, onRetry)
                }
            }
        }
        when (chrome) {
            is SendChrome.Strip -> UnderLightClientStrip(chrome.item, Modifier.padding(padding), body)
            is SendChrome.Simple -> UnderLightClientStripIfAny(null, Modifier.padding(padding), chrome.sync, body)
        }
    }
}

/**
 * What Send shows of its network's light client and how it reviews: Detailed view's [Strip] of
 * [Strip.item] under the top bar with the full review, or simple mode's [Simple], with neither the
 * strip nor a block in sight and the review's detail behind *Details* (docs/simple-ui.md, S1 and S2).
 */
sealed interface SendChrome {
    /** Detailed view: the light client's strip and the full review. */
    data class Strip(val item: LightClientItem) : SendChrome

    /** Simple mode: no strip but the [sync] line while it has something to say, and the simple review. */
    data class Simple(val sync: LightClientItem? = null) : SendChrome
}

@Composable
private fun ComposeContent(
    state: SendUiState.Compose,
    onAssetChange: (String?) -> Unit,
    onToChange: (String) -> Unit,
    onPickAccount: (String) -> Unit,
    onRetryLookup: () -> Unit,
    onAmountChange: (String) -> Unit,
    onMax: () -> Unit,
    onFeeChange: (FeeLevel) -> Unit,
    onReview: () -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
        Heading("Fee")
        FeeChoice(state.fee, enabled = !state.form.quoting, onFeeChange)
        Note("The fee is paid in the network's own currency; the review shows the most it can be.")
        state.form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.earlier?.let { hash ->
            OutlinedButton(onClick = { onOpenTransfer(hash) }, Modifier.testTag(SEND_EARLIER_TAG)) {
                Text("Open the earlier transaction")
            }
        }
        ReviewButton(state.form, onReview, Modifier.align(Alignment.End))
    }
}

@Composable
private fun ReviewContent(
    state: SendUiState.Review,
    onRefresh: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The network first: what is signed is a transaction on it alone (docs/networks.md, N14).
        Text("On ${state.network}", Modifier.testTag(SEND_NETWORK_TAG), style = MaterialTheme.typography.titleMedium)
        Heading("You send")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TokenIcon(state.asset.icon, state.asset.symbol, size = 40.dp)
            SelectionContainer {
                Text(
                    state.amount,
                    Modifier.testTag(SEND_AMOUNT_REVIEWED_TAG),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        state.asset.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Heading("To")
        RecipientCard(state.recipient)
        Heading("From")
        FromLine(state.from)
        Heading("Network fee")
        Text("At most ${state.maxFee}")
        Note(state.feeDetail)
        Heading("Verified simulation")
        state.balances.forEach { change ->
            Column {
                Text(change.what, style = MaterialTheme.typography.labelLarge)
                Text("${change.before} → ${change.after}")
                Note(change.verifiedAt)
            }
        }
        Text(state.simulation, style = MaterialTheme.typography.bodyMedium)
        state.pending?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onRefresh, enabled = state.idle) {
            if (state.refreshing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
            }
            Spacer(Modifier.size(8.dp))
            Text("Refresh the quote")
        }
        HorizontalDivider()
        val notSent = state.notSent
        if (notSent != null) {
            NotSentFields(notSent, state.signing, onRetry)
        } else {
            UnlockContent(state, onPasswordChange, onSign, onSignWithBiometrics)
        }
    }
}

/**
 * The review in simple mode (docs/simple-ui.md, S2): what is sent and to whom in full, from where,
 * the most the fee can be and the balances after, the verified simulation said in words ("Checked
 * on chain: this transfer should go through", owner's request, 2026-10-27), and its detail (the fee's
 * make-up, the balances before, the blocks) behind *Details*. Then the unlock, as in Detailed view.
 */
@Composable
private fun SimpleReviewContent(
    state: SendUiState.Review,
    onRefresh: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
    onRetry: () -> Unit,
) {
    var details by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The network first: what is signed is a transaction on it alone (docs/networks.md, N14).
        Text("On ${state.network}", Modifier.testTag(SEND_NETWORK_TAG), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TokenIcon(state.asset.icon, state.asset.symbol, size = 40.dp)
            SelectionContainer {
                Text(
                    "Send ${state.amount}",
                    Modifier.testTag(SEND_AMOUNT_REVIEWED_TAG),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        state.asset.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Heading("To")
        // In full, as everywhere the user confirms an address; the block it was resolved at is in Details.
        RecipientCard(state.recipient.copy(resolvedAt = null))
        Heading("From")
        FromLine(state.from)
        ReviewLine("Network fee, at most", state.maxFee)
        // "ETH balance after": what pays the fee is in Details.
        state.balances.forEach { change -> ReviewLine("${change.short} after", change.after) }
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                SIMULATION_CHECKED,
                Modifier.padding(12.dp).testTag(SEND_SIMULATION_TAG),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.pending?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { details = !details }, Modifier.testTag(SEND_DETAILS_TAG)) {
            Text(if (details) "Hide details" else "Details")
        }
        if (details) {
            Note(state.feeDetail)
            state.balances.forEach { change ->
                Note("${change.what}: ${change.before} → ${change.after}, ${change.verifiedAt}")
            }
            state.recipient.resolvedAt?.let { Note(it) }
            Note(state.simulation)
        }
        TextButton(onClick = onRefresh, enabled = state.idle) {
            if (state.refreshing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
            }
            Spacer(Modifier.size(8.dp))
            Text("Refresh the quote")
        }
        HorizontalDivider()
        val notSent = state.notSent
        if (notSent != null) {
            NotSentFields(notSent, state.signing, onRetry)
        } else {
            UnlockContent(state, onPasswordChange, onSign, onSignWithBiometrics)
        }
    }
}

/** A line of the simple review: what it is, and its value at the end. */
@Composable
private fun ReviewLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The verified simulation in simple mode's words: it ran at the latest verified head, and another
 * transaction can still land first, so it "should" go through (owner's request, 2026-10-27).
 */
internal const val SIMULATION_CHECKED = "Checked on chain: this transfer should go through"

/** The unlock: a fingerprint or face where the user turned it on, the wallet password always. */
@Composable
private fun UnlockContent(
    state: SendUiState.Review,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
) {
    UnlockFields(
        unlock = state.unlock,
        signing = state.signing,
        canSign = state.canSign,
        canSignWithBiometrics = state.canSignWithBiometrics,
        signLabel = "Sign and send on ${state.network}",
        signingLabel = "Signing and sending…",
        onPasswordChange = onPasswordChange,
        onSign = onSign,
        onSignWithBiometrics = onSignWithBiometrics,
        passwordTag = SEND_PASSWORD_TAG,
        signTag = SEND_SIGN_TAG,
    )
}
