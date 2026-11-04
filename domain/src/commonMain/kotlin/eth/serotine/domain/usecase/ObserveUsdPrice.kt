package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The USD price of one unit of a price id (docs/wallet-ux.md, *Prices*: every price goes
 * through USD). A feed quoted in another currency is converted with that currency's own
 * price first (an X/ETH answer through ETH/USD). A converted price is only as good as its
 * worst feed: one on the path that has no price makes it none, and the oldest update on the
 * path is the one it is dated by and the one the stale rule applies to.
 */
class ObserveUsdPrice(private val feeds: PriceFeeds) {

    operator fun invoke(network: Network, priceId: PriceId): Flow<Price> = usd(network, priceId, path = emptySet())

    private fun usd(network: Network, id: PriceId, path: Set<PriceId>): Flow<Price> {
        if (id == PriceId.USD) return flowOf(Price.ONE)
        val quote = feeds.quotes(network)[id] ?: return flowOf(Price.NoPrice(NoPriceReason.NO_FEED))
        // A table whose quotes go round in a circle never reaches USD: no price, not no end.
        if (quote == id || quote in path) return flowOf(Price.NoPrice(NoPriceReason.NO_FEED))
        val own = feeds.observe(network, id).map { it.asPrice() }
        return if (quote == PriceId.USD) own else combine(
            own,
            usd(network, quote, path + id),
        ) { price, rate -> price * rate }
    }

    private fun FeedReading.asPrice(): Price = when (this) {
        FeedReading.Syncing -> Price.Checking
        is FeedReading.Verified -> Price.Priced(price, updatedAt, ageSeconds, stale, poolAverage)
        is FeedReading.NoPrice -> Price.NoPrice(reason)
        is FeedReading.Unavailable -> Price.Unavailable(reason)
    }
}

/** A price of one unit of something, in a currency the reader of it knows. */
sealed interface Price {

    /** Not read yet. */
    data object Checking : Price

    /**
     * [perUnit], as of [updatedAt] (unix seconds; the oldest update its feeds made),
     * [ageSeconds] before the verified head; [stale] when a feed on its path is older than its
     * heartbeat, in which case it is shown with its age and left out of every total;
     * [poolAverage] when a pool's average is on its path ([FeedReading.Verified.poolAverage]),
     * which every value it gives says.
     */
    data class Priced(
        val perUnit: Double,
        val updatedAt: Long,
        val ageSeconds: Long,
        val stale: Boolean,
        val poolAverage: Boolean = false,
    ) : Price

    /** There is no price, for [reason]. */
    data class NoPrice(val reason: NoPriceReason) : Price

    /** No verified price can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : Price

    /** This price in units of [rate], the price of one unit of another currency in the same one. */
    infix fun inUnitsOf(rate: Price): Price = merge(rate) { price, per -> price / per }

    /** This price times [rate]: from the feed's quote into the quote's own. */
    operator fun times(rate: Price): Price = merge(rate) { price, per -> price * per }

    private fun merge(other: Price, combine: (Double, Double) -> Double): Price = when {
        this is NoPrice -> this
        other is NoPrice -> other
        this is Unavailable -> this
        other is Unavailable -> other
        this !is Priced || other !is Priced -> Checking
        else -> Priced(
            combine(perUnit, other.perUnit),
            min(updatedAt, other.updatedAt),
            max(ageSeconds, other.ageSeconds),
            stale || other.stale,
            poolAverage || other.poolAverage,
        )
    }

    companion object {
        /** One USD in USD: exact, never stale, dated by nothing. */
        internal val ONE = Priced(1.0, updatedAt = Long.MAX_VALUE, ageSeconds = 0, stale = false)
    }
}
