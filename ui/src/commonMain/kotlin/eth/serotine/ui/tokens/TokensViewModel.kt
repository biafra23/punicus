package eth.serotine.ui.tokens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenChange
import eth.serotine.domain.usecase.BuiltInCheck
import eth.serotine.domain.usecase.KnownToken
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.RemoveToken
import eth.serotine.domain.usecase.SetTokenHidden
import eth.serotine.domain.usecase.TokenOrigin
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.displayName
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.grouped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Tokens settings' ViewModel: every known token, grouped by network (docs/networks.md,
 * *Values*: a token's symbol is never the same token on another network), each hidden, shown
 * again or (if added) removed on its own network.
 */
class TokensViewModel(
    observeKnownTokens: ObserveKnownTokens,
    private val setTokenHidden: SetTokenHidden,
    private val removeToken: RemoveToken,
) : ViewModel() {

    private val lastChange = MutableStateFlow<String?>(null)

    private val known = combine(Network.entries.map { network -> observeKnownTokens(network).map { network to it } }) {
        it.toList()
    }

    val uiState: StateFlow<TokensUiState> = combine(known, lastChange) { known, change ->
        val unavailable = known.any { (_, tokens) -> tokens.settingsUnavailable }
        TokensUiState(
            sections = known.filter { (_, tokens) -> tokens.tokens.isNotEmpty() }
                .map { (network, tokens) -> TokenSection(NetworkLabel.of(network), tokens.tokens.map(::item)) },
            notice = when {
                unavailable -> SETTINGS_UNAVAILABLE
                change != null -> change
                known.any { (_, tokens) -> tokens.settingsSetAside } -> SETTINGS_SET_ASIDE
                else -> null
            },
            canChange = !unavailable,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TokensUiState())

    /** Hides the token at [address] on the network of [chainId] from every account there, or shows it again. */
    fun setHidden(chainId: Long, address: String, hidden: Boolean) =
        change(chainId, address) { network, token -> setTokenHidden(network, token, hidden) }

    /** Removes the added token at [address] on the network of [chainId]. */
    fun remove(chainId: Long, address: String) =
        change(chainId, address) { network, token -> removeToken(network, token) }

    private fun change(chainId: Long, address: String, action: suspend (Network, Address) -> TokenChange) {
        val network = Network.ofChainId(chainId) ?: return
        val token = Address.parse(address) ?: return
        viewModelScope.launch {
            lastChange.value = when (action(network, token)) {
                TokenChange.NotSaved -> "The change could not be saved. Nothing has changed."
                else -> null
            }
        }
    }

    private fun item(known: KnownToken) = TokenItem(
        symbol = displaySymbol(known.token.symbol),
        name = displayName(known.token.name),
        address = Eip55.format(known.token.address),
        icon = known.token.icon,
        builtIn = known.origin == TokenOrigin.BUILT_IN,
        status = status(known),
        simpleStatus = (known.check as? BuiltInCheck.Agrees)?.blockNumber
            ?.let { "Built in · checked against its contract" },
        problem = known.check is BuiltInCheck.Disagrees,
        hidden = known.hidden,
        warning = if (known.sharesBuiltInSymbol) {
            "Not the built-in ${displaySymbol(known.token.symbol)}: another contract"
        } else {
            null
        },
    )

    private fun status(known: KnownToken): String = when (val check = known.check) {
        null -> "Added: as its contract describes itself"
        BuiltInCheck.Checking -> "Built in · checking its contract…"
        is BuiltInCheck.Agrees ->
            "Built in · checked against its contract" + (check.blockNumber?.let { " at block ${grouped(it)}" } ?: "")
        is BuiltInCheck.Disagrees ->
            if (check.saidSymbol == null) {
                "Not shown: its contract does not answer as a token"
            } else {
                "Not shown: its contract says ${check.saidSymbol} with ${check.saidDecimals} decimals"
            }
        is BuiltInCheck.Unavailable ->
            if (check.reason == ReadUnavailable.REFUSED) {
                "Built in · its contract cannot be checked on this device"
            } else {
                "Built in · its contract cannot be checked right now"
            }
    }

    private companion object {
        const val SETTINGS_SET_ASIDE =
            "Your token settings were damaged and have been set aside: the tokens you added and hid start over."
        const val SETTINGS_UNAVAILABLE =
            "Your token settings cannot be read right now, so nothing can be added, hidden or removed. " +
                "Serotine keeps trying."
    }
}
