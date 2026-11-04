package eth.serotine.domain.usecase

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.FeedTable
import eth.serotine.domain.repository.TokenCatalog
import kotlinx.coroutines.flow.first

/**
 * What a home-screen widget may show the rate of on a network, chosen when it is placed
 * (docs/wallet-ux.md, *Home-screen widget*): a base, priced by a feed pinned there, and a
 * currency to show it in. Read from the pinned tables alone ([TokenCatalog], [FeedTable]), so
 * choosing starts no light client.
 */
class RateWidgetChoices(private val catalog: TokenCatalog, private val feeds: FeedTable) {

    /**
     * The bases on [network]: its native currency first, then each price id its built-in tokens are
     * priced by, in the list's order and each once, with the names of what it prices (the native
     * currency's, then the tokens' symbols); then each built-in token with no feed pinned there, by
     * its symbol and with no price id, which has no rate and is offered greyed out (CLAUDE.md,
     * *Conventions*), the native currency first among them where it has no feed either. The tokens
     * the user adds have no price and are none.
     */
    suspend fun bases(network: Network): List<RateBase> {
        val pinned = feeds.quotes(network)
        val names = LinkedHashMap<PriceId, MutableList<String>>()
        names.getOrPut(network.nativePriceId) { mutableListOf() } += network.nativeName
        // The native currency's row stays too where no feed prices it, greyed out as a token's.
        val unpriced = mutableListOf<RateBase>()
        if (network.nativePriceId !in pinned) unpriced += RateBase(null, listOf(network.nativeSymbol))
        for (token in catalog.observeTokens(network).first()) {
            val id = token.priceId?.takeIf { it in pinned }
            if (id == null) {
                unpriced += RateBase(null, listOf(token.symbol))
            } else {
                names.getOrPut(id) { mutableListOf() } += token.symbol
            }
        }
        return names.filterKeys { it in pinned }.map { (id, of) -> RateBase(id, of.toList()) } + unpriced
    }

    /**
     * The currencies [base] may be shown in on [network], in [DisplayCurrency] order but [base]
     * itself, each [RateQuote.priced] where it can be: USD always, every other one with a feed
     * pinned there.
     */
    fun quotes(network: Network, base: PriceId): List<RateQuote> {
        val pinned = feeds.quotes(network)
        return DisplayCurrency.entries.filter { it.priceId != base }
            .map { RateQuote(it, priced = it.priceId == PriceId.USD || it.priceId in pinned) }
    }

    /**
     * The bundled icon key of [base] on [network] (CLAUDE.md, *Data sources*: no icon fetched),
     * for the widget to draw in its corner: [network]'s own where [base] is its native
     * currency, else a built-in token priced by it; null where none is bundled, drawn as no
     * icon at all (every base [bases] offers is a native currency or a priced built-in token,
     * so this is null only for a base no longer offered).
     */
    suspend fun icon(network: Network, base: PriceId): String? {
        if (base == network.nativePriceId) return network.nativeIcon
        return catalog.observeTokens(network).first().firstOrNull { it.priceId == base }?.icon
    }
}

/**
 * A base a widget may show the rate of: [priceId], the price of each of [names] (a native
 * currency's name, tokens' symbols); none for a token with no feed pinned, named by its symbol.
 */
data class RateBase(val priceId: PriceId?, val names: List<String>)

/** A currency a rate may be shown in, and whether the network can price it ([priced]: a feed is pinned for it). */
data class RateQuote(val currency: DisplayCurrency, val priced: Boolean)
