package eth.serotine.domain.model

/**
 * What one exchange rate a home-screen widget shows is of (docs/wallet-ux.md, *Home-screen
 * widget*): [base] on [network] in [quote], or, where [quote] is null, in the display currency as
 * the screens have it (USD where that is [base] itself or [network] cannot price it). A widget
 * placed before pairs could be chosen shows its network's [native] pair.
 */
data class RatePair(val network: Network, val base: PriceId, val quote: DisplayCurrency?) {
    init {
        require(quote?.priceId != base) { "$base in itself is no rate" }
    }

    companion object {
        /** [network]'s native currency in the display currency: what every widget showed before pairs. */
        fun native(network: Network): RatePair = RatePair(network, network.nativePriceId, quote = null)
    }
}
