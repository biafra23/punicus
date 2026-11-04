package eth.serotine.ui.deriveaccount

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.platform.protectFromScreenCapture
import kotlinx.coroutines.Dispatchers

/** Test tag of the wallet password field, for UI tests. */
const val DERIVE_ACCOUNT_PASSWORD_TAG = "derive-account-password"

/** Test tag of the Add account button, for UI tests. */
const val DERIVE_ACCOUNT_ADD_TAG = "derive-account-add"

/**
 * Binds [DeriveAccountScreen] to its ViewModel, and tells it when the screen stops being shown, so
 * a typed password goes; an account that leaves the wallet ends the screen through [onBack].
 */
@Composable
fun DeriveAccountRoute(viewModel: DeriveAccountViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    // Main.immediate: as on the seed screens, an edit reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val back by rememberUpdatedState(onBack)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    LaunchedEffect(state is DeriveAccountUiState.Gone) {
        if (state is DeriveAccountUiState.Gone) back()
    }
    DeriveAccountScreen(state, viewModel::onPasswordChange, viewModel::derive, onBack, modifier)
}

/**
 * New account from the same phrase (docs/wallet-ux.md, screen 3) as a pure function of [state]:
 * the account it comes from, what is added, the wallet password, and Add account ([onDerive]);
 * then the account added, and Done. While the password field is shown, the window is kept out of
 * screen capture where the platform allows ([protectFromScreenCapture]: rule 8's protections of a
 * screen that takes the password), and said so where it does not. The top bar's Back waits while
 * it adds; leaving any other way does not cut the addition short (the use case runs to its end).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeriveAccountScreen(
    state: DeriveAccountUiState,
    onPasswordChange: (String) -> Unit,
    onDerive: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deriving = (state as? DeriveAccountUiState.Ask)?.deriving == true
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !deriving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("New account from the same phrase") },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                DeriveAccountUiState.Loading, DeriveAccountUiState.Gone ->
                    Box(Modifier.fillMaxWidth().padding(24.dp)) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                is DeriveAccountUiState.Unavailable -> Text(state.message, color = MaterialTheme.colorScheme.error)
                is DeriveAccountUiState.Ask -> AskContent(state, onPasswordChange, onDerive)
                is DeriveAccountUiState.Added -> AddedContent(state, onBack)
            }
        }
    }
}

@Composable
private fun AskContent(state: DeriveAccountUiState.Ask, onPasswordChange: (String) -> Unit, onDerive: () -> Unit) {
    // Only while the password field is shown: rule 8's protections of a screen that takes it.
    val captureBlocked = protectFromScreenCapture()
    AccountLine(state.label, state.address)
    Text(
        "Adds an account of the recovery phrase ${state.label} comes from, on ${state.network}: the first of the " +
            "phrase's accounts, in the order wallets list them, whose key this wallet does not hold there yet. An " +
            "account you already watch becomes one you hold. Its key comes from the phrase's seed stored on this " +
            "device, so the words are not needed.",
    )
    OutlinedTextField(
        value = state.password.text,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth().testTag(DERIVE_ACCOUNT_PASSWORD_TAG),
        enabled = !state.deriving,
        label = { Text("Wallet password") },
        supportingText = state.error?.let { error -> { Text(error) } },
        isError = state.error != null,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDerive() }),
    )
    Button(
        onClick = onDerive,
        enabled = state.canDerive,
        modifier = Modifier.fillMaxWidth().testTag(DERIVE_ACCOUNT_ADD_TAG),
    ) {
        if (state.deriving) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text("Adding…")
        } else {
            Text("Add account")
        }
    }
    if (!captureBlocked) {
        Text(
            "Screenshots and screen recordings of this window cannot be blocked here. Make sure nothing is " +
                "recording your screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun AddedContent(state: DeriveAccountUiState.Added, onDone: () -> Unit) {
    Text(
        "${state.label} is added on ${state.network}: account ${state.number} of the recovery phrase.",
        style = MaterialTheme.typography.titleMedium,
    )
    AccountLine(state.label, state.address)
    state.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

/** An account's blockie, name and full address: labels can repeat, the address cannot. */
@Composable
private fun AccountLine(label: String, address: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Blockie(address, size = 32.dp)
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
