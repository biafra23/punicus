package eth.serotine.data.prices

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.PastReading
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.PriceHistory
import kotlinx.coroutines.flow.Flow

/**
 * [PriceFeeds] and [PriceHistory] that ask [feeds] (and [feedHistory]) for every price id the feeds
 * quote, and [pools] only for one they do not (CLAUDE.md, *Data sources*: owner ruling, 2026-10-29,
 * #205). A pinned feed always wins, whatever it answers: a stale feed is shown as stale, never
 * replaced by a pool's average, so the choice is made by the tables alone and never by a reading.
 * The price now and the day back are routed by the one rule, so a change is always measured
 * against the source the price shown comes from.
 */
class FeedsThenPools<P>(
    private val feeds: PriceFeeds,
    private val feedHistory: PriceHistory,
    private val pools: P,
) : PriceFeeds, PriceHistory where P : PriceFeeds, P : PriceHistory {

    override fun quotes(network: Network): Map<PriceId, PriceId> = pools.quotes(network) + feeds.quotes(network)

    override fun observe(network: Network, priceId: PriceId): Flow<FeedReading> =
        if (byPool(network, priceId)) pools.observe(network, priceId) else feeds.observe(network, priceId)

    override suspend fun before(network: Network, priceId: PriceId, secondsBefore: Long): PastReading =
        if (byPool(network, priceId)) {
            pools.before(network, priceId, secondsBefore)
        } else {
            feedHistory.before(network, priceId, secondsBefore)
        }

    /** Whether a pool prices [priceId] on [network]: one is pinned for it, and no feed is. */
    private fun byPool(network: Network, priceId: PriceId): Boolean =
        priceId !in feeds.quotes(network) && priceId in pools.quotes(network)
}
