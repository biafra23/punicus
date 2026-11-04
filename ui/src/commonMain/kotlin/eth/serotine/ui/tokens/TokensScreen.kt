package eth.serotine.ui.tokens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.components.ActionDropdown
import eth.serotine.ui.components.HiddenDetails
import eth.serotine.ui.components.MenuAction
import eth.serotine.ui.components.NetworkMark
import eth.serotine.ui.components.Note
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.mode.LocalInterfaceMode
import eth.serotine.ui.platform.rememberCopyText
import kotlinx.coroutines.launch

/** Test tag of each token row, for UI tests. */
const val TOKENS_ROW_TAG = "tokens-row"

/** Test tag of the *Details* that keep the blocks in simple mode, for UI tests. */
const val TOKENS_DETAILS_TAG = "tokens-details"

/** Test tag prefix of a network's section header, followed by its chain id, for UI tests. */
const val TOKENS_SECTION_TAG = "tokens-section-"

/** Binds [TokensScreen] to its ViewModel; Copy is done here, on the platform's clipboard. */
@Composable
fun TokensRoute(
    viewModel: TokensViewModel,
    onBack: () -> Unit,
    onAddToken: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val copy = rememberCopyText()
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleTokensScreen(
            state,
            onBack = onBack,
            onAddToken = onAddToken,
            onCopy = copy,
            onSetHidden = viewModel::setHidden,
            onRemove = viewModel::remove,
            modifier = modifier,
        )
        InterfaceMode.DETAILED -> TokensScreen(
            state,
            onBack = onBack,
            onAddToken = onAddToken,
            onCopy = copy,
            onSetHidden = viewModel::setHidden,
            onRemove = viewModel::remove,
            modifier = modifier,
        )
    }
}

/**
 * Settings → Tokens as a pure function of [state]: every known token under its network's mark,
 * each with what its check says (which names the block it was checked at, so no light-client
 * strip: the tokens are several networks') and a menu (Copy contract, Hide or Show, Remove for an
 * added one) behind a visible button, and Add token. [onSetHidden] and [onRemove] get the token's
 * network's chain id and its address. Simple mode ([SimpleTokensScreen]) keeps the blocks behind
 * *Details* ([details]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokensScreen(
    state: TokensUiState,
    onBack: () -> Unit,
    onAddToken: () -> Unit,
    onCopy: (String) -> Unit,
    onSetHidden: (chainId: Long, address: String, hidden: Boolean) -> Unit,
    onRemove: (chainId: Long, address: String) -> Unit,
    modifier: Modifier = Modifier,
    details: List<String> = emptyList(),
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Tokens") },
            )
        },
        floatingActionButton = {
            // The content overload: the icon-and-text one leaves its label out of the
            // button's merged semantics, so a screen reader would say only "Button".
            // Nothing to add while the settings cannot be read: it would be refused.
            if (state.canChange) {
                ExtendedFloatingActionButton(onClick = onAddToken) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Add token")
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            state.notice?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            state.sections.forEach { section ->
                NetworkMark(
                    section.network,
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .semantics { heading() }
                        .testTag(TOKENS_SECTION_TAG + section.network.chainId),
                )
                val chainId = section.network.chainId
                section.items.forEach { item ->
                    TokenRow(
                        item,
                        canChange = state.canChange,
                        onCopy = {
                            onCopy(item.address)
                            scope.launch { snackbar.showSnackbar("Contract address copied") }
                        },
                        onSetHidden = { onSetHidden(chainId, item.address, it) },
                        onRemove = { onRemove(chainId, item.address) },
                    )
                }
            }
            if (details.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    HiddenDetails(TOKENS_DETAILS_TAG) { details.forEach { Note(it) } }
                }
            }
            Text(
                "A token is known by its contract address. Added tokens are described as their " +
                    "contracts describe themselves; nothing is fetched from a token list.",
                // Room under it for the floating button, which would cover the last row's menu.
                Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TokenRow(
    item: TokenItem,
    canChange: Boolean,
    onCopy: () -> Unit,
    onSetHidden: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.testTag(TOKENS_ROW_TAG),
        leadingContent = { TokenIcon(item.icon, item.symbol) },
        headlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.symbol)
                if (item.hidden) {
                    Text(
                        "Hidden",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        supportingContent = {
            Column {
                Text("${item.name} · ${shortAddress(item.address)}")
                Text(
                    item.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.problem) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                item.warning?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${item.symbol}")
                }
                ActionDropdown(
                    actions = listOfNotNull(
                        MenuAction("Copy contract", SerotineIcons.ContentCopy, onClick = onCopy),
                        when {
                            !canChange -> null
                            item.hidden -> MenuAction("Show token", SerotineIcons.Visibility) { onSetHidden(false) }
                            else -> MenuAction("Hide token", SerotineIcons.Visibility) { onSetHidden(true) }
                        },
                        if (item.builtIn || !canChange) {
                            null
                        } else {
                            MenuAction("Remove", Icons.Filled.Delete, destructive = true, onClick = onRemove)
                        },
                    ),
                    expanded = menu,
                    onDismiss = { menu = false },
                )
            }
        },
    )
}
