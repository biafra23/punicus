package eth.serotine.ui.dappproposal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.platform.protectFromScreenCapture
import kotlinx.coroutines.Dispatchers

/** Test tag of an account's row, followed by its address, for UI tests. */
const val DAPP_PROPOSAL_ACCOUNT_TAG = "dapp-proposal-account-"

/** Test tag of the new per-app address's row, for UI tests. */
const val DAPP_PROPOSAL_NEW_ADDRESS_TAG = "dapp-proposal-new-address"

/** Test tag of "Use an address from another site…", for UI tests. */
const val DAPP_PROPOSAL_ALIAS_TAG = "dapp-proposal-alias"

/** Test tag of another site's address in the alias list, followed by its key, for UI tests. */
const val DAPP_PROPOSAL_ALIAS_CHOICE_TAG = "dapp-proposal-alias-choice-"

/** Test tag of "Type the old site's host", for UI tests. */
const val DAPP_PROPOSAL_ALIAS_HOST_TAG = "dapp-proposal-alias-host"

/** Test tag of "Use that site's address", for UI tests. */
const val DAPP_PROPOSAL_ALIAS_TYPE_TAG = "dapp-proposal-alias-type"

/** Test tag of the alias question's password field, for UI tests. */
const val DAPP_PROPOSAL_ALIAS_PASSWORD_TAG = "dapp-proposal-alias-password"

/** Test tag of what the alias question says when "Use it" did not go through, for UI tests. */
const val DAPP_PROPOSAL_ALIAS_ERROR_TAG = "dapp-proposal-alias-error"

/** Test tag of the alias question's "Use it", for UI tests. */
const val DAPP_PROPOSAL_ALIAS_YES_TAG = "dapp-proposal-alias-yes"

/** Test tag of the wallet password field, for UI tests. */
const val DAPP_PROPOSAL_PASSWORD_TAG = "dapp-proposal-password"

/**
 * Binds [DappProposalScreen] to its ViewModel; an answer, or a proposal no longer waiting, ends it
 * through [onDone].
 */
@Composable
fun DappProposalRoute(
    viewModel: DappProposalViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: as on the other screens that take typing, an edit reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(state.done, state.gone) {
        if (state.done || state.gone) done()
    }
    // Leaving the screen, the app put in the background included, drops a typed password.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    DappProposalScreen(
        state,
        onToggle = viewModel::toggle,
        onApprove = viewModel::approve,
        onReject = viewModel::reject,
        onDismissExplanation = viewModel::dismissExplanation,
        onBack = onBack,
        onPasswordChange = viewModel::onPasswordChange,
        onChoosePhrase = viewModel::choosePhrase,
        onPickPhrase = viewModel::pickPhrase,
        modifier = modifier,
        aliasActions = AliasActions(
            viewModel::chooseAlias,
            viewModel::pickAlias,
            viewModel::answerAlias,
            viewModel::onAliasHostChange,
            viewModel::typeAlias,
        ),
    )
}

/**
 * What linking the dApp's site to another site's address does (docs/walletconnect.md, *Aliases*): [onChoose] opens the
 * list, [onPick] picks an address from it by its key (null closes it), and [onAnswer] answers the question asked.
 */
class AliasActions(
    val onChoose: () -> Unit = {},
    val onPick: (String?) -> Unit = {},
    val onAnswer: (Boolean) -> Unit = {},
    /** "Type the old site's host" changed. */
    val onHostChange: (String) -> Unit = {},
    /** The old site's host typed was submitted. */
    val onType: () -> Unit = {},
)

/**
 * A dApp's session proposal (docs/walletconnect.md, screen D3) as a pure function of [state]: what
 * the dApp says of itself, marked as its claim, the networks the connection would hold, each with its kind
 * of funds, what it asks to do, and the accounts to choose, each with its network, those this dApp may not
 * be offered greyed out, a tap saying why. At their head, the new per-app address for the dApp's site (issue #252),
 * ticked while the site has none and nothing it remembers is preselected, greyed out when it cannot be made, with
 * the phrase it comes from when there are several; chosen, it asks for the wallet password. For a site with no
 * address yet, "Use an address from another site…" links it to another site's per-app address after asking
 * ([aliasActions]). Approve and Reject; a proposal that asks for too much can only be rejected, and says why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DappProposalScreen(
    state: DappProposalUiState,
    onToggle: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDismissExplanation: () -> Unit,
    onBack: () -> Unit,
    onPasswordChange: (String) -> Unit = {},
    onChoosePhrase: () -> Unit = {},
    onPickPhrase: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
    aliasActions: AliasActions = AliasActions(),
) {
    state.phrases?.let { PhraseDialog(it, onPickPhrase) }
    state.aliasChoices?.let { AliasDialog(it, state.aliasHost, aliasActions) }
    state.confirmingAlias?.let { AliasQuestionDialog(it, state, aliasActions.onAnswer, onPasswordChange) }
    // Last, so that it shows over the list a greyed-out choice of which it explains.
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
                title = { Text("Connection request") },
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
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(16.dp)) {
                Text(state.name, style = MaterialTheme.typography.headlineSmall)
                Text(state.url, style = MaterialTheme.typography.bodyMedium)
                if (state.description.isNotEmpty()) Text(state.description, Modifier.padding(top = 4.dp))
                Text(
                    "Stated by the dApp, not verified: Punicus cannot check who is asking.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.networks.isNotEmpty()) {
                Heading(if (state.networks.size == 1) "Network" else "Networks")
                state.networks.forEach { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
                state.networksNote?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.asks.isNotEmpty()) {
                Heading("It may")
                state.asks.forEach { Text("· $it", Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
            }
            state.refusal?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            Heading("Accounts it sees")
            state.newAddress?.let { NewAddressRow(it, state.busy, onToggle, onChoosePhrase) }
            if (state.newAddress?.chosen == true) PasswordField(state, onPasswordChange, onApprove)
            state.aliasOffer?.let { AliasOfferRow(it, state.busy, aliasActions.onChoose) }
            state.accounts.forEach { account ->
                val unavailable = account.unavailable != null
                val color = if (unavailable) Unavailable.contentColor() else MaterialTheme.colorScheme.onSurface
                ListItem(
                    modifier = Modifier
                        .toggleable(
                            value = account.chosen,
                            role = Role.Checkbox,
                            onValueChange = { onToggle(account.key) },
                        )
                        .unavailable(unavailable)
                        .testTag(DAPP_PROPOSAL_ACCOUNT_TAG + account.key),
                    leadingContent = {
                        Checkbox(checked = account.chosen, onCheckedChange = null, enabled = !unavailable)
                    },
                    headlineContent = { Text(account.name, color = color) },
                    supportingContent = {
                        val where = when {
                            account.network.isEmpty() -> account.address
                            else -> "${account.network} · ${account.address}"
                        }
                        val line = when {
                            account.perAppSite == null -> where
                            account.linked -> "Per-app address for ${account.perAppSite}, linked by you · $where"
                            else -> "Per-app address for ${account.perAppSite} · $where"
                        }
                        Text(account.unavailable ?: line, color = color)
                    },
                    colors = ListItemDefaults.colors(),
                )
            }
            state.notice?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            // What Approve waits for, said beside it as a tap on it would.
            if (state.refusal == null && state.accounts.any { it.chosen }) {
                state.missingRequired?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReject, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text("Reject")
                }
                // Greyed out while it cannot be done, and still tappable: the tap says why.
                Button(
                    onClick = onApprove,
                    enabled = !state.busy,
                    colors = if (state.approveUnavailable) {
                        Unavailable.buttonColors()
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.weight(1f).unavailable(state.approveUnavailable),
                ) { Text("Approve") }
            }
        }
    }
}

/** The new per-app address's row: ticked or not, greyed out with a tap saying why when it cannot be made. */
@Composable
private fun NewAddressRow(
    option: NewAddressOption,
    busy: Boolean,
    onToggle: (String) -> Unit,
    onChoosePhrase: () -> Unit,
) {
    val unavailable = option.unavailable != null
    val color = if (unavailable) Unavailable.contentColor() else MaterialTheme.colorScheme.onSurface
    ListItem(
        modifier = Modifier
            .toggleable(
                value = option.chosen,
                enabled = !busy,
                role = Role.Checkbox,
                onValueChange = { onToggle(DappProposalViewModel.NEW_KEY) },
            )
            .unavailable(unavailable)
            .testTag(DAPP_PROPOSAL_NEW_ADDRESS_TAG),
        leadingContent = { Checkbox(checked = option.chosen, onCheckedChange = null, enabled = !unavailable) },
        headlineContent = { Text(option.title, color = color) },
        supportingContent = {
            Column {
                Text(option.unavailable ?: option.where, color = color)
                if (!unavailable) {
                    Text(
                        "An address of its own, which no other site sees.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                option.phrase?.let { phrase ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(phrase, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = color)
                        if (option.changeable) TextButton(onClick = onChoosePhrase, enabled = !busy) { Text("Change") }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(),
    )
}

/** The wallet password, which making the new address takes. The window is kept out of capture while it shows. */
@Composable
private fun PasswordField(state: DappProposalUiState, onPasswordChange: (String) -> Unit, onApprove: () -> Unit) {
    val captureBlocked = protectFromScreenCapture()
    OutlinedTextField(
        value = state.password.text,
        onValueChange = onPasswordChange,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .testTag(DAPP_PROPOSAL_PASSWORD_TAG),
        enabled = !state.busy,
        label = { Text("Wallet password") },
        supportingText = { Text("Making the new address takes it.") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onApprove() }),
    )
    if (!captureBlocked) {
        Text(
            "Screenshots and screen recordings of this window cannot be blocked here. Make sure nothing is " +
                "recording your screen.",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * "No address for `<site>` yet" and the way to another site's address instead, greyed out, still tappable, where there
 * is none to use (owner ruling, 2026-10-18, #55).
 */
@Composable
private fun AliasOfferRow(offer: AliasOffer, busy: Boolean, onChoose: () -> Unit) {
    val unavailable = offer.unavailable != null
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(offer.line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(
            onClick = onChoose,
            enabled = !busy,
            colors = if (unavailable) {
                ButtonDefaults.textButtonColors(contentColor = Unavailable.contentColor())
            } else {
                ButtonDefaults.textButtonColors()
            },
            modifier = Modifier.unavailable(unavailable).testTag(DAPP_PROPOSAL_ALIAS_TAG),
        ) { Text("Use an address from another site…") }
    }
}

/**
 * The other sites' per-app addresses, each with its site and the sites already linked to it. It suggests none: no
 * "looks like" a site, the cue a look-alike phishing domain wants (docs/walletconnect.md, *Aliases*).
 */
@Composable
private fun AliasDialog(choices: List<AliasChoice>, host: String, actions: AliasActions) {
    val onPick = actions.onPick
    AlertDialog(
        onDismissRequest = { onPick(null) },
        title = { Text("Use an address from another site") },
        text = {
            // It grows with every site connected: it scrolls, so that each can be reached.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "The site that connects then sees that site's address, so the two can link your activity.",
                    style = MaterialTheme.typography.bodySmall,
                )
                choices.forEach { choice ->
                    val greyed = choice.unavailable != null
                    val color = if (greyed) Unavailable.contentColor() else MaterialTheme.colorScheme.onSurface
                    Column(
                        Modifier.fillMaxWidth().clickable { onPick(choice.key) }.unavailable(greyed)
                            .padding(vertical = 6.dp)
                            .testTag(DAPP_PROPOSAL_ALIAS_CHOICE_TAG + choice.key),
                    ) {
                        Text(choice.site, color = color)
                        Text(
                            "${choice.networks} · ${choice.address}",
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                        )
                        choice.linked?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
                    }
                }
                // Not listed (after a restore of the phrase, say): its address is made again from the phrase.
                OutlinedTextField(
                    value = host,
                    onValueChange = actions.onHostChange,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().testTag(DAPP_PROPOSAL_ALIAS_HOST_TAG),
                    label = { Text("Type the old site's host") },
                    placeholder = { Text("app.uniswap.org") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { actions.onType() }),
                )
                // Always tappable: with nothing typed, the tap says what is missing (owner ruling, 2026-10-18, #55).
                TextButton(
                    onClick = actions.onType,
                    modifier = Modifier.testTag(DAPP_PROPOSAL_ALIAS_TYPE_TAG),
                ) { Text("Use that site's address") }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(null) }) { Text("Cancel") } },
    )
}

/**
 * What is asked before the dApp's site is linked to another site's address; with the wallet password where that
 * address is made again first, the window then kept out of capture.
 */
@Composable
private fun AliasQuestionDialog(
    question: AliasQuestion,
    state: DappProposalUiState,
    onAnswer: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(question.title) },
        text = {
            Column {
                Text(question.text)
                question.error?.let {
                    Text(
                        it,
                        Modifier.padding(top = 8.dp).testTag(DAPP_PROPOSAL_ALIAS_ERROR_TAG),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (question.needsPassword) {
                    val captureBlocked = protectFromScreenCapture()
                    OutlinedTextField(
                        value = state.password.text,
                        onValueChange = onPasswordChange,
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                            .testTag(DAPP_PROPOSAL_ALIAS_PASSWORD_TAG),
                        enabled = !state.busy,
                        label = { Text("Wallet password") },
                        singleLine = true,
                        isError = question.error != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { onAnswer(true) }),
                    )
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
        },
        // While the address is made, neither answer is taken: what "Use it" started goes on.
        confirmButton = {
            TextButton(
                onClick = { onAnswer(true) },
                enabled = !state.busy,
                modifier = Modifier.testTag(DAPP_PROPOSAL_ALIAS_YES_TAG),
            ) { Text(if (state.busy) "Making…" else "Use it") }
        },
        dismissButton = { TextButton(onClick = { onAnswer(false) }, enabled = !state.busy) { Text("Cancel") } },
    )
}

/** The phrases a new address can come from, each named by an account of it; picking one closes the list. */
@Composable
private fun PhraseDialog(phrases: List<PhraseOption>, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onPick(null) },
        title = { Text("Which recovery phrase") },
        text = {
            Column {
                Text(
                    "The new address comes from one of the recovery phrases on this device, each named here by an " +
                        "account of it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                phrases.forEach { phrase ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(phrase.key) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = phrase.chosen, onClick = { onPick(phrase.key) })
                        Column {
                            Text(phrase.name)
                            Text(phrase.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(null) }) { Text("Cancel") } },
    )
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
