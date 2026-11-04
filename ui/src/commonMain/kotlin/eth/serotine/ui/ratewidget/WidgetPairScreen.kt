package eth.serotine.ui.ratewidget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.usecase.RateBase
import eth.serotine.domain.usecase.RateQuote
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.NetworkMark
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.currency.nameOf

/** Test tag prefix of a network's row, followed by its chain id, for UI tests. */
const val WIDGET_NETWORK_TAG = "widget-network-"

/** Test tag prefix of a base's row, followed by its price id (a token's symbol where it has none), for UI tests. */
const val WIDGET_BASE_TAG = "widget-base-"

/** Test tag prefix of a currency's row, followed by its code ([WIDGET_FOLLOWS] for the display currency's). */
const val WIDGET_QUOTE_TAG = "widget-quote-"

/** Test tag of the prices' filter field. */
const val WIDGET_FILTER_TAG = "widget-filter"

/** Test tag of what the prices' step says when its filter matches none. */
const val WIDGET_NO_MATCH_TAG = "widget-no-match"

/** The tag suffix of the row that follows the display currency. */
const val WIDGET_FOLLOWS = "display"

/**
 * What the widget's configuration shows when a widget is placed (docs/wallet-ux.md, *Home-screen
 * widget*), one step at a time: the network, then what its rate is of, then the currency it is
 * shown in. A placed widget keeps its choice; another one means placing a new widget.
 */
sealed interface WidgetPairUiState {

    /**
     * The networks it may show a rate of ([choices]: those that are on and have accounts, or, when
     * none is, every one that is on), or why there is none ([message]).
     */
    data class Networks(val choices: List<NetworkLabel>, val message: String? = null) : WidgetPairUiState

    /**
     * What a rate on [network] may be of: its native currency first, then its tokens' prices, then,
     * greyed out, its tokens with none; [query], what the filter above them holds, narrows them to
     * [shown] (issue #207).
     */
    data class Bases(
        val network: NetworkLabel,
        val choices: List<WidgetBaseChoice>,
        val query: String = "",
    ) : WidgetPairUiState {

        /** [choices] that [query] matches, in their order; every one while it is blank. */
        val shown: List<WidgetBaseChoice>
            get() = choices.filter { it.matches(query) }

        /** What the step says when [query] matches none of [choices]; null while one is shown. */
        val noMatch: String?
            get() = "No price matches “${query.trim()}”"
                .takeIf { choices.isNotEmpty() && choices.none { it.matches(query) } }
    }

    /** The currencies the rate of [base] ("LINK") on [network] may be shown in. */
    data class Quotes(
        val network: NetworkLabel,
        val base: String,
        val choices: List<WidgetQuoteChoice>,
    ) : WidgetPairUiState
}

/**
 * A base: its price [id] ("ETH"; a token's symbol where it has no price), and what it is the
 * price of ([names]: "Ether · WETH"); [unavailable], the reason, where it has no rate, which
 * greys it out (CLAUDE.md, *Conventions*).
 */
data class WidgetBaseChoice(val id: String, val names: String, val unavailable: String? = null) {

    /**
     * Whether [query] is part of this choice, ignoring case and the spaces around it: of its price
     * id or of one of the names it prices ("usdc.e" in "USDC · USDC.e"), never of the "No price" a
     * greyed-out one says instead of names. A blank query matches every one.
     */
    fun matches(query: String): Boolean {
        val wanted = query.trim()
        if (wanted.isEmpty()) return true
        // A greyed-out one's id is its symbols; its names only say it has no price.
        val parts = if (unavailable == null) listOf(id) + names.split(" · ") else id.split(" · ")
        return parts.any { it.contains(wanted, ignoreCase = true) }
    }
}

/**
 * A currency: its [code] ("EUR"; null for the display currency, as chosen in Settings), its
 * [title] ("EUR", "Display currency") and what it is ([detail]: "Euro"); [unavailable], the
 * reason, where the network cannot price it, which greys it out.
 */
data class WidgetQuoteChoice(
    val code: String?,
    val title: String,
    val detail: String,
    val unavailable: String? = null,
)

/**
 * The widget's configuration as a pure function of [state]: a row per choice, [onNetwork] with
 * its chain id, [onBase] with its price id and [onQuote] with its code (null: the display
 * currency) when tapped; [onFilter] with the text typed into the prices' filter; [onBack] steps
 * back from a later step, and [onCancel] places no widget.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun WidgetPairScreen(
    state: WidgetPairUiState,
    onNetwork: (chainId: Long) -> Unit,
    onBase: (id: String) -> Unit,
    onQuote: (code: String?) -> Unit,
    onFilter: (query: String) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = state is WidgetPairUiState.Networks
    BackHandler(enabled = !first, onBack = onBack)
    var notice by remember { mutableStateOf<UnavailableNotice?>(null) }
    notice?.let { UnavailableDialog(it, onDismiss = { notice = null }) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (first) {
                        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                title = { Text("Exchange rate widget") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            when (state) {
                is WidgetPairUiState.Networks -> {
                    Question(
                        "Which network's rate should this widget show? It keeps what you choose; for another, " +
                            "place another widget.",
                    )
                    state.message?.let {
                        Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                    }
                    state.choices.forEach { network ->
                        NetworkMark(
                            network,
                            Modifier.fillMaxWidth()
                                .clickable(role = Role.Button) { onNetwork(network.chainId) }
                                .padding(horizontal = 16.dp, vertical = 18.dp)
                                .testTag(WIDGET_NETWORK_TAG + network.chainId),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        HorizontalDivider()
                    }
                }
                is WidgetPairUiState.Bases -> {
                    NetworkMark(state.network, Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
                    Question("Which price should it show?")
                    if (state.choices.isNotEmpty()) BaseFilter(state.query, onFilter)
                    val shown = state.shown
                    state.noMatch?.let {
                        Text(
                            it,
                            Modifier.padding(16.dp).testTag(WIDGET_NO_MATCH_TAG),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    shown.forEach { base ->
                        Choice(base.id, base.names, WIDGET_BASE_TAG + base.id, base.unavailable != null) {
                            val reason = base.unavailable
                            if (reason == null) {
                                onBase(base.id)
                            } else {
                                notice = UnavailableNotice("No rate of ${base.id}", reason)
                            }
                        }
                    }
                }
                is WidgetPairUiState.Quotes -> {
                    NetworkMark(state.network, Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
                    Question("Which currency should ${state.base} be shown in?")
                    state.choices.forEach { quote ->
                        val tag = WIDGET_QUOTE_TAG + (quote.code ?: WIDGET_FOLLOWS)
                        Choice(quote.title, quote.detail, tag, quote.unavailable != null) {
                            val reason = quote.unavailable
                            if (reason == null) {
                                onQuote(quote.code)
                            } else {
                                notice = UnavailableNotice("No rate in ${quote.title}", reason)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The field that filters the prices as it is typed into, as the Debug tab's filters its log, with
 * a button that empties it.
 */
@Composable
private fun BaseFilter(query: String, onFilter: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onFilter,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)
            .testTag(WIDGET_FILTER_TAG),
        label = { Text("Filter") },
        placeholder = { Text("Symbol or name") },
        singleLine = true,
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { onFilter("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear the filter")
                }
            }
        },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
    )
}

/** The step's question. */
@Composable
private fun Question(text: String) {
    Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
}

/**
 * One choice's row: its [title] over its [detail], [onClick] when tapped; greyed out where it is
 * [unavailable], still tappable, to say why.
 */
@Composable
private fun Choice(title: String, detail: String, tag: String, unavailable: Boolean, onClick: () -> Unit) {
    val greyed = Unavailable.contentColor()
    Column(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag)
            .unavailable(unavailable),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (unavailable) greyed else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = if (unavailable) greyed else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

/**
 * The first step's state from the networks that are [on] and those [withAccounts] (null: the
 * account list cannot be read), in `Network` order: the networks on with accounts; every one on
 * when none of those is (the wallet has no account, its list cannot be read, or its accounts'
 * networks are all off), since a network that is on can show its rate all the same; with none
 * on, the way to one.
 */
fun widgetNetworkUiState(
    networks: List<NetworkLabel>,
    on: Set<Long>,
    withAccounts: Set<Long>?,
): WidgetPairUiState.Networks {
    val onNetworks = networks.filter { it.chainId in on }
    val held = withAccounts?.let { accounts -> onNetworks.filter { it.chainId in accounts } }.orEmpty()
    val choices = held.ifEmpty { onNetworks }
    return WidgetPairUiState.Networks(
        choices,
        message = NO_NETWORK_ON.takeIf { choices.isEmpty() },
    )
}

/**
 * The second step's state: the [bases] on [network], each with what it is the price of; one with
 * no price, greyed out, saying so.
 */
fun widgetBaseUiState(network: Network, bases: List<RateBase>): WidgetPairUiState.Bases =
    WidgetPairUiState.Bases(
        NetworkLabel.of(network),
        bases.map { base ->
            val id = base.priceId?.id
            if (id != null) {
                WidgetBaseChoice(id, base.names.joinToString(" · "))
            } else {
                val symbol = base.names.joinToString(" · ")
                WidgetBaseChoice(symbol, "No price", noPriceReason(symbol, network.displayName))
            }
        },
    )

/**
 * The last step's state: the display currency first, which the widget then follows as Settings
 * has it (USD where [network] has no rate in it, or where it is [base] itself), then each of
 * [quotes] by its code and name, one [network] cannot price greyed out, saying so.
 */
fun widgetQuoteUiState(network: Network, base: String, quotes: List<RateQuote>): WidgetPairUiState.Quotes =
    WidgetPairUiState.Quotes(
        NetworkLabel.of(network),
        base,
        listOf(WidgetQuoteChoice(null, "Display currency", followsDetail(network.displayName, base))) +
            quotes.map { (currency, priced) ->
                WidgetQuoteChoice(
                    currency.code,
                    currency.code,
                    nameOf(currency),
                    unavailable = noQuoteReason(currency.code, network.displayName).takeUnless { priced },
                )
            },
    )

/**
 * What the configuration shows for [network] and [base] chosen so far while that step is being
 * read: its step, with nothing to choose yet (a rotation reads it again).
 */
fun widgetPairLoading(network: Network?, base: PriceId?): WidgetPairUiState = when {
    network == null -> WidgetPairUiState.Networks(emptyList())
    base == null -> WidgetPairUiState.Bases(NetworkLabel.of(network), emptyList())
    else -> WidgetPairUiState.Quotes(NetworkLabel.of(network), base.id, emptyList())
}

/** What the row of the display currency says it does on [network] for [base]. */
internal fun followsDetail(network: String, base: String) =
    "As chosen in Settings, changing with it; USD where $network has no rate in it, or where it is $base"

/** Why the token [symbol] has no rate on [network]. */
internal fun noPriceReason(symbol: String, network: String) =
    "No price feed for $symbol is pinned on $network, so the widget has no verified rate of it to show."

/** Why no rate on [network] can be shown in the currency [code]. */
internal fun noQuoteReason(code: String, network: String) =
    "No $code price feed is pinned on $network, so no verified rate can be shown in $code there."

/** What the configuration says with every network off. */
internal const val NO_NETWORK_ON =
    "No network is on in this wallet, so no rate can be read. Turn one on in Settings → Networks first."
