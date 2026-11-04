package eth.serotine.data.prices

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.PastReading
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.PriceHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [FeedsThenPools]: a pinned feed always wins, a pool only where none is, now and a day back alike. */
class PoolFallbackTest {

    private val eth = PriceId("ETH")
    private val cow = PriceId("COW")
    private val both = PriceId("BOTH")

    /** A source quoting [ids] in ETH, each answering [answer] with the id's own price. */
    private class Source(private val ids: Set<PriceId>, private val answer: (PriceId) -> FeedReading) :
        PriceFeeds, PriceHistory {
        override fun quotes(network: Network) = ids.associateWith { PriceId("ETH") }

        override fun observe(network: Network, priceId: PriceId): Flow<FeedReading> =
            flowOf(if (priceId in ids) answer(priceId) else FeedReading.NoPrice(NoPriceReason.NO_FEED))

        override suspend fun before(network: Network, priceId: PriceId, secondsBefore: Long): PastReading =
            (observe(network, priceId).first() as? FeedReading.Verified)
                ?.let { PastReading.Verified(it.price, it.updatedAt, it.stale) }
                ?: PastReading.NoPrice(NoPriceReason.NO_FEED)
    }

    private val staleFeed = FeedReading.Verified(1.0, updatedAt = 1, ageSeconds = 999_999, stale = true)
    private val feeds = Source(setOf(eth, both)) { staleFeed }
    private val pools = Source(setOf(cow, both)) {
        FeedReading.Verified(2.0, updatedAt = 5, ageSeconds = 0, stale = false, poolAverage = true)
    }

    @Test
    fun aPoolPricesOnlyWhatNoFeedQuotesAndAStaleFeedIsNeverReplaced() = runTest {
        val prices = FeedsThenPools(feeds, feeds, pools)

        assertEquals(setOf(eth, cow, both), prices.quotes(Network.ETHEREUM).keys)
        assertEquals(true, (prices.observe(Network.ETHEREUM, cow).first() as FeedReading.Verified).poolAverage)
        // The feed's answer, stale as it is: never the pool's (owner ruling, 2026-10-29, #205).
        assertEquals(staleFeed, prices.observe(Network.ETHEREUM, both).first())
        assertEquals(staleFeed, prices.observe(Network.ETHEREUM, eth).first())
        assertEquals(
            FeedReading.NoPrice(NoPriceReason.NO_FEED),
            prices.observe(Network.ETHEREUM, PriceId("GNO")).first(),
        )
    }

    @Test
    fun theDayBackIsReadFromTheSameSourceAsThePriceNow() = runTest {
        val history = FeedsThenPools(feeds, feeds, pools)

        assertEquals(2.0, (history.before(Network.ETHEREUM, cow, 86_400) as PastReading.Verified).price)
        assertEquals(1.0, (history.before(Network.ETHEREUM, both, 86_400) as PastReading.Verified).price)
    }
}
