package eth.serotine.ui.createaccount

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.NetworkChecklist
import eth.serotine.ui.components.PasswordStep
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.platform.KeepScreenOn
import eth.serotine.ui.platform.OnStopUnlessConfigurationChange
import eth.serotine.ui.platform.protectFromScreenCapture
import kotlinx.coroutines.Dispatchers

/** Test tag of the phrase's words, for UI tests. */
const val CREATE_PHRASE_TAG = "create-phrase"

/** Test tag prefix of the checked words' fields (`create-check-0` …), for UI tests. */
const val CREATE_CHECK_TAG = "create-check"

/** Test tags of the password fields, for UI tests. */
const val CREATE_PASSWORD_TAG = "create-password"
const val CREATE_REPEAT_TAG = "create-password-repeat"

/**
 * Binds [CreateAccountScreen] to its ViewModel, reports a created account through [onCreated]
 * so the caller can close the screen, and tells the ViewModel when the screen is left, so it
 * drops the words: a stop that is not a configuration change (owner ruling, 2026-10-18, #39).
 */
@Composable
fun CreateAccountRoute(
    viewModel: CreateAccountViewModel,
    onBack: () -> Unit,
    onCreated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: as on the import screen, an edit reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    // Left, the words go; a rotation or another configuration change keeps them in the ViewModel's
    // memory, never in a saved state (owner ruling, 2026-10-18, #39).
    OnStopUnlessConfigurationChange(onStop = viewModel::onHidden)
    CreateAccountScreen(
        state = state,
        onWrittenDown = viewModel::onWrittenDown,
        onShowAgain = viewModel::onShowAgain,
        onCheckedWordChange = viewModel::onCheckedWordChange,
        onCheck = viewModel::onCheck,
        onPasswordChange = viewModel::onPasswordChange,
        onRepeatChange = viewModel::onRepeatChange,
        onCreate = viewModel::onCreate,
        onStartOver = viewModel::startOver,
        onBack = onBack,
        onDone = onCreated,
        modifier = modifier,
        onNetworkToggle = viewModel::onNetworkToggle,
        onTurnOnNetwork = viewModel::onTurnOnNetwork,
    )
    // A note to read first keeps the screen until the user is done with it.
    val created = state as? CreateAccountUiState.Created
    if (created != null && created.note == null) {
        LaunchedEffect(Unit) { onCreated() }
    }
}

/**
 * Create account (docs/wallet-ux.md, screen 12) as a function of [state]: the new phrase shown
 * here alone, three of its words asked back, then the wallet password. While it is shown the
 * window is kept out of screen capture where the platform allows ([protectFromScreenCapture]),
 * where it does not the screen says so, and the display is kept on ([KeepScreenOn]). Nothing
 * typed is saved: it lives in the ViewModel's state only. [onDone] once a creation's note is read.
 * With the password, the networks to add the account to (docs/networks.md, N8), ticked through
 * [onNetworkToggle], one that is off turned on through [onTurnOnNetwork].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAccountScreen(
    state: CreateAccountUiState,
    onWrittenDown: () -> Unit,
    onShowAgain: () -> Unit,
    onCheckedWordChange: (Int, String) -> Unit,
    onCheck: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onRepeatChange: (String) -> Unit,
    onCreate: () -> Unit,
    onStartOver: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
    onNetworkToggle: (chainId: Long) -> Unit = {},
    onTurnOnNetwork: (chainId: Long) -> Unit = {},
) {
    val captureBlocked = protectFromScreenCapture()
    if (state is CreateAccountUiState.Showing || state is CreateAccountUiState.Checking) KeepScreenOn()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Create account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                CreateAccountUiState.Preparing, CreateAccountUiState.Finishing ->
                    Box(Modifier.fillMaxWidth()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
                is CreateAccountUiState.Showing -> ShowingContent(state, onWrittenDown)
                is CreateAccountUiState.Checking -> CheckingContent(state, onCheckedWordChange, onCheck, onShowAgain)
                is CreateAccountUiState.Securing ->
                    SecuringContent(state, onPasswordChange, onRepeatChange, onCreate, onNetworkToggle, onTurnOnNetwork)
                is CreateAccountUiState.Created -> {
                    Text("${state.label} created")
                    state.note?.let { note ->
                        Text(note, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    }
                }
                CreateAccountUiState.NotListed -> Text(
                    "The key is stored, but the account list could not be saved, and the phrase is gone from " +
                        "this screen. To finish, import the phrase you wrote down (Import recovery phrase) with " +
                        "the wallet password.",
                    color = MaterialTheme.colorScheme.error,
                )
                is CreateAccountUiState.Restart -> {
                    Text(state.message)
                    Button(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) { Text("New phrase") }
                }
                CreateAccountUiState.Unavailable -> Text(
                    "The key store does not work on this device, so no account can be created here.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!captureBlocked) {
                Text(
                    "Screenshots and screen recordings of this window cannot be blocked here. Make sure " +
                        "nothing is recording your screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ShowingContent(state: CreateAccountUiState.Showing, onWrittenDown: () -> Unit) {
    Text(
        "Write these ${state.words.size} words down, in order, on paper. They are the only way to restore " +
            "this account: whoever has them controls it. Serotine shows them only while this account " +
            "is being created.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Column(Modifier.testTag(CREATE_PHRASE_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.words.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEachIndexed { column, word ->
                    Text(
                        "${row * 2 + column + 1}. $word",
                        Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
    Button(onClick = onWrittenDown, modifier = Modifier.fillMaxWidth()) { Text("I have written them down") }
}

@Composable
private fun CheckingContent(
    state: CreateAccountUiState.Checking,
    onCheckedWordChange: (Int, String) -> Unit,
    onCheck: () -> Unit,
    onShowAgain: () -> Unit,
) {
    Text("Type these words from what you wrote down.", style = MaterialTheme.typography.bodyMedium)
    state.positions.forEachIndexed { index, position ->
        OutlinedTextField(
            value = state.typed[index].text,
            onValueChange = { onCheckedWordChange(index, it) },
            modifier = Modifier.fillMaxWidth().testTag("$CREATE_CHECK_TAG-$index"),
            label = { Text("Word $position") },
            singleLine = true,
            isError = state.wrong,
            // No suggestions and nothing learned into the keyboard's dictionary, as for the phrase.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = if (index == state.positions.lastIndex) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onDone = { onCheck() }),
        )
    }
    if (state.wrong) {
        Text(
            "At least one word does not match. Check what you wrote down, or look at the phrase again.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onShowAgain) { Text("Show the phrase") }
        Button(onClick = onCheck, enabled = state.canCheck) { Text("Check") }
    }
}

@Composable
private fun SecuringContent(
    state: CreateAccountUiState.Securing,
    onPasswordChange: (String) -> Unit,
    onRepeatChange: (String) -> Unit,
    onCreate: () -> Unit,
    onNetworkToggle: (Long) -> Unit,
    onTurnOnNetwork: (Long) -> Unit,
) {
    val setting = state.step is PasswordStep.New
    when (val step = state.step) {
        PasswordStep.Checking -> Unit
        PasswordStep.Unavailable -> Text(
            "The key store does not work on this device, so no account can be created here.",
            color = MaterialTheme.colorScheme.error,
        )
        is PasswordStep.New, is PasswordStep.Existing -> {
            OutlinedTextField(
                value = state.password.text,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth().testTag(CREATE_PASSWORD_TAG),
                enabled = !state.creating,
                label = { Text(if (setting) "New wallet password" else "Wallet password") },
                supportingText = (state.passwordHint ?: if (setting) "Protects every key this wallet stores" else null)
                    ?.let { hint -> { Text(hint) } },
                isError = state.passwordHint != null,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = if (setting) ImeAction.Next else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onCreate() }),
            )
            if (setting) {
                OutlinedTextField(
                    value = state.repeat.text,
                    onValueChange = onRepeatChange,
                    modifier = Modifier.fillMaxWidth().testTag(CREATE_REPEAT_TAG),
                    enabled = !state.creating,
                    label = { Text("Repeat the password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onCreate() }),
                )
            }
            Text(
                when (step) {
                    is PasswordStep.New -> step.protection
                    is PasswordStep.Existing -> step.protection
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    // The question is asked, not pre-answered: none ticked (docs/networks.md, N8).
    if (state.step is PasswordStep.New || state.step is PasswordStep.Existing) {
        Text("Add to which networks?", style = MaterialTheme.typography.titleSmall)
        NetworkChecklist(state.networks, enabled = !state.creating, onNetworkToggle, onTurnOnNetwork)
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    // Greyed out while no network is ticked, a tap saying so (owner ruling, 2026-10-18, #55).
    var unavailable by remember { mutableStateOf<UnavailableNotice?>(null) }
    unavailable?.let { notice -> UnavailableDialog(notice, onDismiss = { unavailable = null }) }
    val waitsForNetwork = state.passwordReady && state.networkReason != null
    Button(
        onClick = {
            val reason = state.networkReason
            if (reason == null) onCreate() else unavailable = UnavailableNotice("Create account", reason)
        },
        enabled = state.passwordReady,
        modifier = Modifier.fillMaxWidth().unavailable(waitsForNetwork),
        colors = if (waitsForNetwork) Unavailable.buttonColors() else ButtonDefaults.buttonColors(),
    ) {
        if (state.creating) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text("Create account")
        }
    }
}
