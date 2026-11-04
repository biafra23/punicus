package eth.serotine.ui.createsafe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.Heading
import eth.serotine.ui.components.HiddenDetails
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NetworkChips
import eth.serotine.ui.components.NotSentFields
import eth.serotine.ui.components.Note
import eth.serotine.ui.components.RetryLookupButton
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.components.UnlockFields
import eth.serotine.ui.components.done
import eth.serotine.ui.components.levelName
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.lightclient.LightClientViewModel
import eth.serotine.ui.mode.LocalInterfaceMode
import kotlinx.coroutines.Dispatchers

/** Test tag of the Safe's name field, for UI tests. */
const val CREATE_SAFE_NAME_TAG = "create-safe-name"

/** Test tag of each owner row (the wallet's accounts and those typed), for UI tests. */
const val CREATE_SAFE_OWNER_TAG = "create-safe-owner"

/** Test tag of the field an owner is typed in, for UI tests. */
const val CREATE_SAFE_INPUT_TAG = "create-safe-input"

/** Test tag of the button that adds the owner typed, for UI tests. */
const val CREATE_SAFE_ADD_TAG = "create-safe-add"

/** Test tag of the button that goes on to the next step, for UI tests. */
const val CREATE_SAFE_CONTINUE_TAG = "create-safe-continue"

/** Test tag of the threshold shown by the stepper, for UI tests. */
const val CREATE_SAFE_THRESHOLD_TAG = "create-safe-threshold"

/** Test tag of the predicted address on the review, for UI tests. */
const val CREATE_SAFE_ADDRESS_TAG = "create-safe-address"

/** Test tag of the review's *Details* in simple mode, for UI tests. */
const val CREATE_SAFE_DETAILS_TAG = "create-safe-details"

/** Test tag of the wallet password field, for UI tests. */
const val CREATE_SAFE_PASSWORD_TAG = "create-safe-password"

/** Test tag of the Deploy button, for UI tests. */
const val CREATE_SAFE_DEPLOY_TAG = "create-safe-deploy"

/**
 * Binds [CreateSafeScreen] to its ViewModel. A deployment broadcast ends the screen through
 * [onSent] with its network and its transaction hash; the screen stopping being shown becomes
 * [CreateSafeViewModel.onHidden] (the password goes) and being shown again
 * [CreateSafeViewModel.onShown] (the review is quoted anew).
 */
@Composable
fun CreateSafeRoute(
    viewModel: CreateSafeViewModel,
    lightClient: LightClientViewModel,
    onBack: () -> Unit,
    onSent: (network: Network, hash: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: as on the other screens that take typing, an edit reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val network by viewModel.stripNetwork.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onShown() }
    val sent = (state as? CreateSafeUiState.Sent)?.hash
    val currentOnSent by rememberUpdatedState(onSent)
    LaunchedEffect(sent) {
        // The network the Safe was created on is the one the screen shows: the one chosen for it.
        if (sent != null) currentOnSent(network, sent)
    }
    val actions = remember(viewModel) {
        CreateSafeActions(
            onNameChange = viewModel::onNameChange,
            onToggleOwner = viewModel::onToggleOwner,
            onInputChange = viewModel::onInputChange,
            onRetryLookup = viewModel::onRetryLookup,
            onAddOwner = viewModel::onAddOwner,
            onRemoveTyped = viewModel::onRemoveTyped,
            onThresholdChange = viewModel::onThresholdChange,
            onContinue = viewModel::onContinue,
            onStepBack = viewModel::back,
            onPayerChange = viewModel::onPayerChange,
            onFeeChange = viewModel::onFeeChange,
            onRefresh = viewModel::refresh,
            onPasswordChange = viewModel::onPasswordChange,
            onDeploy = viewModel::sign,
            onDeployWithBiometrics = viewModel::signWithBiometrics,
            onRetry = viewModel::retry,
            onNetwork = viewModel::onNetwork,
            onTurnOnNetwork = viewModel::onTurnOnNetwork,
        )
    }
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleCreateSafeScreen(
            state,
            actions,
            onBack,
            modifier,
            syncLine = lightClient.item(network).collectAsStateWithLifecycle().value,
        )
        // The strip in Detailed view; in simple mode the same item, drawn as the sync line while it says something.
        InterfaceMode.DETAILED -> CreateSafeScreen(
            state = state,
            lightClient = lightClient.item(network).collectAsStateWithLifecycle().value,
            actions = actions,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

/**
 * What the user can ask of [CreateSafeScreen]: name the Safe, choose, type, add and take out
 * owners, set the threshold, go on or back a step; on the review, choose the account that pays and
 * the fee, refresh the quote, type the password, deploy ([onDeploy], or [onDeployWithBiometrics])
 * and retry a broadcast. First of all, the network to create it on ([onNetwork]; one that is off
 * turned on through [onTurnOnNetwork]).
 */
class CreateSafeActions(
    val onNameChange: (String) -> Unit = {},
    val onToggleOwner: (address: String) -> Unit = {},
    val onInputChange: (String) -> Unit = {},
    val onRetryLookup: () -> Unit = {},
    val onAddOwner: () -> Unit = {},
    val onRemoveTyped: (address: String) -> Unit = {},
    val onThresholdChange: (Int) -> Unit = {},
    val onContinue: () -> Unit = {},
    val onStepBack: () -> Unit = {},
    val onPayerChange: (address: String) -> Unit = {},
    val onFeeChange: (FeeLevel) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onDeploy: () -> Unit = {},
    val onDeployWithBiometrics: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onNetwork: (chainId: Long) -> Unit = {},
    val onTurnOnNetwork: (chainId: Long) -> Unit = {},
)

/**
 * Create Safe (docs/wallet-ux.md, screens 13 to 15) as a pure function of [state]: the owners
 * (1/3), the threshold under the recovery shapes (2/3), then the review of the deployment with
 * the address computed here, the account that pays and the unlock that signs it (3/3). While the
 * review takes the wallet password, the window is kept out of screen capture where the platform
 * allows; where it does not, the screen says so. [lightClient] is the strip above it, Detailed
 * view's; simple mode ([SimpleCreateSafeScreen]) has none, and keeps the blocks, the review's fee
 * make-up and the factory's verified run behind *Details* ([details]) on each step.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun CreateSafeScreen(
    state: CreateSafeUiState,
    lightClient: LightClientItem?,
    actions: CreateSafeActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    details: List<String> = emptyList(),
    syncLine: LightClientItem? = null,
) {
    val review = state as? CreateSafeUiState.Review
    // Back is a step back, until bytes may be out there: after a broadcast no peer answered, only
    // Try again may send them, so back leaves Create Safe. While a signature runs, back waits.
    val leaves = review?.notSent?.canRetry == true
    val inStep = state is CreateSafeUiState.Threshold || review != null
    BackHandler(enabled = inStep) {
        when {
            review?.signing == true -> Unit
            leaves -> onBack()
            else -> actions.onStepBack()
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = if (inStep && !leaves) actions.onStepBack else onBack,
                        enabled = review?.signing != true,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        when (state) {
                            is CreateSafeUiState.Owners -> "Create Safe 1/3"
                            is CreateSafeUiState.Threshold -> "Create Safe 2/3"
                            is CreateSafeUiState.Review -> "Create Safe 3/3"
                            else -> "Create Safe"
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
        UnderLightClientStripIfAny(lightClient, Modifier.padding(padding), syncLine) {
            when (state) {
                is CreateSafeUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is CreateSafeUiState.Sent -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Sent", Modifier.padding(top = 12.dp))
                }
                is CreateSafeUiState.CannotCreate -> Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    if (state.networks.isNotEmpty()) {
                        NetworkChips(state.networks, "Create a Safe on", actions.onNetwork, actions.onTurnOnNetwork)
                    }
                    Text(state.message, Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                is CreateSafeUiState.Owners -> OwnersContent(state, actions, details)
                is CreateSafeUiState.Threshold -> ThresholdContent(state, actions, details)
                is CreateSafeUiState.Review -> ReviewContent(state, actions, details)
            }
        }
    }
}

@Composable
private fun OwnersContent(state: CreateSafeUiState.Owners, actions: CreateSafeActions, details: List<String>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.networks.isNotEmpty()) {
            NetworkChips(state.networks, "Create a Safe on", actions.onNetwork, actions.onTurnOnNetwork)
        }
        Heading("Name")
        OutlinedTextField(
            value = state.name,
            onValueChange = actions.onNameChange,
            modifier = Modifier.fillMaxWidth().testTag(CREATE_SAFE_NAME_TAG),
            label = { Text("The Safe's name in your wallet") },
            singleLine = true,
            isError = state.nameError != null,
            supportingText = { Text(state.nameError ?: "Optional: only this device sees it.") },
        )
        Heading("Owners")
        Text(state.summary, style = MaterialTheme.typography.bodyMedium)
        Column {
            state.accounts.forEach { owner ->
                ListItem(
                    modifier = Modifier.testTag(CREATE_SAFE_OWNER_TAG).toggleable(
                        value = owner.selected,
                        role = Role.Checkbox,
                        onValueChange = { actions.onToggleOwner(owner.address) },
                    ),
                    leadingContent = { AccountAvatar(owner.address, size = 32.dp) },
                    headlineContent = { Text(owner.label ?: shortAddress(owner.address)) },
                    supportingContent = {
                        Column {
                            Text(shortAddress(owner.address), fontFamily = FontFamily.Monospace)
                            Text(owner.note)
                        }
                    },
                    // The row is the control; the box only shows whether it is chosen.
                    trailingContent = { Checkbox(checked = owner.selected, onCheckedChange = null) },
                )
            }
            state.typed.forEach { owner ->
                ListItem(
                    modifier = Modifier.testTag(CREATE_SAFE_OWNER_TAG),
                    leadingContent = { Blockie(owner.address, size = 32.dp) },
                    headlineContent = { Text(owner.label ?: "Typed address") },
                    supportingContent = {
                        Column {
                            // In full: an address typed is confirmed here.
                            SelectionContainer {
                                Text(owner.address, fontFamily = FontFamily.Monospace)
                            }
                            Text(owner.note)
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = { actions.onRemoveTyped(owner.address) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove ${shortAddress(owner.address)}")
                        }
                    },
                )
            }
            ListItem(
                headlineContent = {
                    Text("This device (hardware key)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                supportingContent = { Text(state.deviceKeyNote) },
            )
        }
        Heading("Add an owner")
        OutlinedTextField(
            value = state.input,
            onValueChange = actions.onInputChange,
            modifier = Modifier.fillMaxWidth().testTag(CREATE_SAFE_INPUT_TAG),
            label = { Text(state.inputLabel) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            singleLine = true,
            // The blockie once the text is an address, typed or resolved.
            leadingIcon = state.inputOwner?.let { owner -> { Blockie(owner.address, size = 24.dp) } },
            isError = state.inputError,
            supportingText = { Text(state.inputNote ?: "Any address: another device's account, a hardware wallet") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
            ).done(),
            // Done adds the owner typed, as the button under the field does, and keeps the keyboard
            // for the next one; with nothing to add it only closes the keyboard.
            keyboardActions = KeyboardActions(
                onDone = { if (state.canAdd) actions.onAddOwner() else defaultKeyboardAction(ImeAction.Done) },
            ),
        )
        state.inputOwner?.let { owner ->
            Column {
                owner.label?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                SelectionContainer {
                    Text(owner.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                Note(owner.note)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = actions.onAddOwner,
                enabled = state.canAdd,
                modifier = Modifier.testTag(CREATE_SAFE_ADD_TAG),
            ) { Text("Add as an owner") }
            if (state.canRetryLookup) RetryLookupButton(state.retryLookupWaits, actions.onRetryLookup)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (details.isNotEmpty()) HiddenDetails(CREATE_SAFE_DETAILS_TAG) { details.forEach { Note(it) } }
        ContinueButton("Next: the threshold", state.canContinue, actions.onContinue, Modifier.align(Alignment.End))
    }
}

@Composable
private fun ThresholdContent(state: CreateSafeUiState.Threshold, actions: CreateSafeActions, details: List<String>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("Threshold")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = { actions.onThresholdChange(state.threshold - 1) },
                enabled = state.canDecrease,
                modifier = Modifier.semantics { contentDescription = "Lower the threshold" },
            ) { Text("−") }
            Text(
                "${state.threshold} of ${state.ownerCount}",
                Modifier.testTag(CREATE_SAFE_THRESHOLD_TAG),
                style = MaterialTheme.typography.headlineSmall,
            )
            FilledTonalButton(
                onClick = { actions.onThresholdChange(state.threshold + 1) },
                enabled = state.canIncrease,
                modifier = Modifier.semantics { contentDescription = "Raise the threshold" },
            ) { Text("+") }
        }
        Text(state.shape, style = MaterialTheme.typography.bodyLarge)
        state.explanation?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        state.refusal?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Heading("The shapes Serotine creates")
        state.rules.forEach { rule -> Text("• $rule", style = MaterialTheme.typography.bodySmall) }
        Heading("Owners")
        Column { state.owners.forEach { OwnerRow(it, full = false) } }
        if (details.isNotEmpty()) HiddenDetails(CREATE_SAFE_DETAILS_TAG) { details.forEach { Note(it) } }
        ContinueButton("Next: review", state.canContinue, actions.onContinue, Modifier.align(Alignment.End))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewContent(state: CreateSafeUiState.Review, actions: CreateSafeActions, details: List<String>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("The new Safe")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(state.address, size = 48.dp, threshold = state.signers)
            Column {
                Text(state.name, style = MaterialTheme.typography.titleMedium)
                Text(state.version, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // In full: this is the address the Safe gets.
        SelectionContainer {
            Text(
                state.address,
                Modifier.testTag(CREATE_SAFE_ADDRESS_TAG),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Note(state.prediction)
        Heading("Owners")
        Text(state.shape, style = MaterialTheme.typography.bodyMedium)
        Column { state.owners.forEach { OwnerRow(it, full = true) } }
        Heading("Paid from")
        Column {
            state.payers.forEach { payer ->
                val selected = payer.address == state.payer
                ListItem(
                    modifier = Modifier.selectable(
                        selected = selected,
                        enabled = state.idle,
                        role = Role.RadioButton,
                        onClick = { actions.onPayerChange(payer.address) },
                    ),
                    leadingContent = { AccountAvatar(payer.address, size = 32.dp) },
                    headlineContent = { Text(payer.label) },
                    supportingContent = { Text(shortAddress(payer.address), fontFamily = FontFamily.Monospace) },
                    trailingContent = { RadioButton(selected = selected, onClick = null, enabled = state.idle) },
                )
            }
        }
        Heading("Network fee")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            FeeLevel.entries.forEachIndexed { index, level ->
                SegmentedButton(
                    selected = state.fee == level,
                    onClick = { actions.onFeeChange(level) },
                    enabled = state.idle,
                    shape = SegmentedButtonDefaults.itemShape(index, FeeLevel.entries.size),
                    label = { Text(levelName(level)) },
                )
            }
        }
        val quote = state.quote
        if (quote != null) {
            Text("At most ${quote.maxFee}")
            // Empty in simple mode, which keeps it behind Details.
            quote.feeDetail.takeIf { it.isNotEmpty() }?.let { Note(it) }
            Heading("Verified simulation")
            Column {
                Text(quote.balance.what, style = MaterialTheme.typography.labelLarge)
                Text("${quote.balance.before} → ${quote.balance.after}")
                quote.balance.verifiedAt.takeIf { it.isNotEmpty() }?.let { Note(it) }
            }
            Text(quote.simulation, style = MaterialTheme.typography.bodyMedium)
            quote.pending?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else if (state.quoting) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Checking on chain…")
            }
        }
        if (details.isNotEmpty()) HiddenDetails(CREATE_SAFE_DETAILS_TAG) { details.forEach { Note(it) } }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = actions.onRefresh, enabled = state.idle) {
            if (state.quoting && quote != null) {
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
            NotSentFields(notSent, state.signing, actions.onRetry)
        } else {
            UnlockFields(
                unlock = state.unlock,
                signing = state.signing,
                canSign = state.canSign,
                canSignWithBiometrics = state.canSignWithBiometrics,
                signLabel = "Sign and deploy",
                signingLabel = "Signing and sending…",
                onPasswordChange = actions.onPasswordChange,
                onSign = actions.onDeploy,
                onSignWithBiometrics = actions.onDeployWithBiometrics,
                passwordTag = CREATE_SAFE_PASSWORD_TAG,
                signTag = CREATE_SAFE_DEPLOY_TAG,
            )
        }
    }
}

/** One owner: its blockie, its name, its address (in [full] where it is confirmed) and what it is. */
@Composable
private fun OwnerRow(owner: OwnerItem, full: Boolean) {
    ListItem(
        leadingContent = { Blockie(owner.address, size = 32.dp) },
        headlineContent = { Text(owner.label ?: shortAddress(owner.address)) },
        supportingContent = {
            Column {
                if (full) {
                    SelectionContainer { Text(owner.address, fontFamily = FontFamily.Monospace) }
                } else {
                    Text(shortAddress(owner.address), fontFamily = FontFamily.Monospace)
                }
                Text(owner.note)
            }
        },
    )
}

@Composable
private fun ContinueButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.testTag(CREATE_SAFE_CONTINUE_TAG)) {
        Text(label)
    }
}
