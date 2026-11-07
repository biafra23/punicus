package eth.serotine.ui.dapprequest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.UnlockFields
import eth.serotine.ui.components.unavailable

/** Test tags of D4's password field, its Sign button and its Refuse button, for UI tests. */
const val DAPP_REQUEST_PASSWORD_TAG = "dapp-request-password"
const val DAPP_REQUEST_SIGN_TAG = "dapp-request-sign"
const val DAPP_REQUEST_REFUSE_TAG = "dapp-request-refuse"

/** Test tag of D5's every-field box, for UI tests. */
const val DAPP_REQUEST_FIELDS_TAG = "dapp-request-fields"

/**
 * Binds [DappRequestScreen] to its ViewModel; an answer, or a request no longer waiting, ends it
 * through [onDone]. The password goes whenever the screen is not shown.
 */
@Composable
fun DappRequestRoute(
    viewModel: DappRequestViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(state.done, state.gone) {
        if (state.done || state.gone) done()
    }
    DappRequestScreen(
        state,
        onPasswordChange = viewModel::onPasswordChange,
        onSign = viewModel::sign,
        onSignWithBiometrics = viewModel::signWithBiometrics,
        onRefuse = viewModel::refuse,
        onDismissExplanation = viewModel::dismissExplanation,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * A dApp's request to sign (docs/walletconnect.md, screens D4 and D5) as a pure function of [state]:
 * who asks, marked as the dApp's claim; the account asked to sign, in full; its network; a message, field
 * by field when it is a sign-in, as text, or as hex marked "not text"; or typed data, what it lets
 * someone do when decoded, its domain and every field; the warnings;
 * and the unlock, with Refuse beside it. One that cannot be signed shows why, its Sign greyed out and
 * still tappable. Nothing on it is read from the chain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DappRequestScreen(
    state: DappRequestUiState,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
    onRefuse: () -> Unit,
    onDismissExplanation: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    state.explaining?.let { UnavailableDialog(UnavailableNotice(it.title, it.reason), onDismissExplanation) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(state.title.ifEmpty { "Request" }) },
            )
        },
    ) { padding ->
        if (state.loading || state.gone) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (state.ended) {
            Text(
                state.notice.orEmpty(),
                Modifier.padding(padding).padding(16.dp),
                color = MaterialTheme.colorScheme.error,
            )
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(state.name, style = MaterialTheme.typography.headlineSmall)
                Text(state.url, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Stated by the dApp, not verified: Punicus cannot check who is asking.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Heading("Asks this account to sign")
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Blockie(state.accountAddress)
                Column {
                    state.accountName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    SelectionContainer {
                        Text(
                            state.accountAddress,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Text(state.network, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

            if (state.typed) {
                TypedDataSections(state)
            } else if (state.signIn.isNotEmpty()) {
                Heading("The sign-in")
                state.signIn.forEach { field ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            field.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer { Text(field.value) }
                    }
                }
            } else {
                Heading("The message (${state.messageSize})")
                Surface(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    SelectionContainer {
                        Text(
                            state.messageText ?: state.messageHex.orEmpty(),
                            Modifier.padding(12.dp),
                            fontFamily = if (state.messageText == null) FontFamily.Monospace else null,
                        )
                    }
                }
                if (state.messageHex != null) {
                    Text(
                        "Not text: shown as hex, byte by byte.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "Signing reads nothing from the chain and sends no transaction.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.warnings.forEach {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
            }
            state.refusal?.let {
                Text(
                    "This request cannot be signed. $it Refuse answers the dApp.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                state.waits,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            state.notice?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.signUnavailable) {
                    // Greyed out while it cannot be done, and still tappable: the tap says why.
                    Button(
                        onClick = onSign,
                        colors = Unavailable.buttonColors(),
                        modifier = Modifier.fillMaxWidth().unavailable(true).testTag(DAPP_REQUEST_SIGN_TAG),
                    ) { Text(state.signLabel) }
                } else {
                    UnlockFields(
                        unlock = state.unlock,
                        signing = state.busy,
                        canSign = !state.busy && state.unlock.password.text.isNotEmpty(),
                        canSignWithBiometrics = !state.busy,
                        signLabel = state.signLabel,
                        signingLabel = "Signing…",
                        onPasswordChange = onPasswordChange,
                        onSign = onSign,
                        onSignWithBiometrics = onSignWithBiometrics,
                        passwordTag = DAPP_REQUEST_PASSWORD_TAG,
                        signTag = DAPP_REQUEST_SIGN_TAG,
                    )
                }
                OutlinedButton(
                    onClick = onRefuse,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag(DAPP_REQUEST_REFUSE_TAG),
                ) { Text("Refuse") }
            }
        }
    }
}

/** Screen D5's part: what typed data lets someone do, when decoded; its domain; and every field. */
@Composable
private fun TypedDataSections(state: DappRequestUiState) {
    state.meaning?.let { meaning ->
        Heading("What signing it lets someone do")
        Text(meaning, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        state.meaningFields.forEach { Field(it) }
    }
    Heading("Who checks the signature")
    Text(
        "As the data states it: the contract and network it is for.",
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.domainFields.forEach { Field(it) }
    Heading(if (state.meaning == null) "Every field, not decoded" else "Every field")
    Surface(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag(DAPP_REQUEST_FIELDS_TAG),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        SelectionContainer {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.typedRows.forEach { row ->
                    Row(
                        Modifier.padding(start = (row.depth * 16).dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            row.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        row.value?.let {
                            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(field: SignInField) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            field.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer { Text(field.value) }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
