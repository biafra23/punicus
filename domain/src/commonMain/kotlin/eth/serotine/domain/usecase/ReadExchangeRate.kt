package eth.serotine.domain.usecase

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.RateSnapshot
import eth.serotine.domain.repository.PastReading
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.PriceHistory
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * One verified reading of what one [PriceId] costs in a [DisplayCurrency], and what it cost 24
 * hours before (docs/wallet-ux.md, *Home-screen widget*). The rate now is the base's and the
 * quote's USD prices ([ObserveUsdPrice], every price going through USD), each waited for until
 * it is priced or has a lasting answer: no price, no engine, a refusal, a stale anchor. A read
 * that was not proven this time is waited past, for as long as the caller lets it. It is dated
 * by the later of the two heads its legs were read at. The rate a day before is each feed on
 * the same paths read from its own round history ([PriceHistory]), the two legs at once, each
 * read tried again a few times while unproven or syncing, and converted the same way; none
 * when any feed has no verified, fresh round of then: a change is only shown against a rate
 * the light client proved.
 */
class ReadExchangeRate(private val feeds: PriceFeeds, private val history: PriceHistory) {

    private val usdPrice = ObserveUsdPrice(feeds)

    suspend operator fun invoke(network: Network, base: PriceId, quote: DisplayCurrency): RateResult = coroutineScope {
        val (basePrice, quotePrice) =
            combine(usdPrice(network, base), usdPrice(network, quote.priceId)) { b, q -> b to q }
            .first { (b, q) -> b.settled() && q.settled() }
        val now = basePrice inUnitsOf quotePrice
        if (now !is Price.Priced || basePrice !is Price.Priced || quotePrice !is Price.Priced) {
            return@coroutineScope RateResult.NotRead(now)
        }
        val baseDayAgo = async { pastUsd(network, base, emptySet()) }
        val quoteDayAgo = async { pastUsd(network, quote.priceId, emptySet()) }
        val dayAgo = baseDayAgo.await()?.let { price -> quoteDayAgo.await()?.let { price / it } }
        RateResult.Read(
            RateSnapshot(
                base = base,
                quote = quote,
                rate = now.perUnit,
                dayAgoRate = dayAgo?.takeIf { it.isFinite() && it > 0 },
                updatedAt = now.updatedAt,
                // A leg's oldest update and its age add up to the time of the head it was read at.
                readAt = max(basePrice.headTime(), quotePrice.headTime()),
                stale = now.stale,
                poolAverage = now.poolAverage,
            ),
        )
    }

    /** Priced, or an answer that waiting does not change; not a read that was merely unproven. */
    private fun Price.settled(): Boolean = when (this) {
        Price.Checking -> false
        is Price.Unavailable -> reason != ReadUnavailable.UNPROVEN
        is Price.Priced, is Price.NoPrice -> true
    }

    /** The head this price was read at; none for USD itself, which no head dates. */
    private fun Price.Priced.headTime(): Long =
        if (updatedAt == Long.MAX_VALUE) Long.MIN_VALUE else updatedAt + ageSeconds

    /** The USD price of [id] a day before the head, or null when there is no verified, fresh one. */
    private suspend fun pastUsd(network: Network, id: PriceId, path: Set<PriceId>): Double? {
        if (id == PriceId.USD) return 1.0
        val quote = feeds.quotes(network)[id] ?: return null
        // As ObserveUsdPrice: a table whose quotes go round in a circle never reaches USD.
        if (quote == id || quote in path) return null
        val own = (past(network, id) as? PastReading.Verified)?.takeIf { !it.stale } ?: return null
        return if (quote == PriceId.USD) own.price else pastUsd(network, quote, path + id)?.let { own.price * it }
    }

    /** [id]'s round a day back on [network], read again a few times while it is unproven or syncing. */
    private suspend fun past(network: Network, id: PriceId): PastReading {
        var reading = history.before(network, id, DAY_SECONDS)
        repeat(PAST_RETRIES) {
            val passing = reading == PastReading.Syncing ||
                reading == PastReading.Unavailable(ReadUnavailable.UNPROVEN)
            if (!passing) return reading
            delay(PAST_RETRY_PAUSE)
            reading = history.before(network, id, DAY_SECONDS)
        }
        return reading
    }

    companion object {
        /** How far back the change is measured: 24 hours, by the chain's own time. */
        const val DAY_SECONDS = 86_400L

        /** How often a day back's read is tried again while unproven, and the pause before each. */
        private const val PAST_RETRIES = 2
        private val PAST_RETRY_PAUSE = 5.seconds
    }
}

/** What [ReadExchangeRate] read. */
sealed interface RateResult {

    /** The rate, verified, in [snapshot]. */
    data class Read(val snapshot: RateSnapshot) : RateResult

    /** No rate could be had: [price] says why (no price, or none verified). */
    data class NotRead(val price: Price) : RateResult
}
