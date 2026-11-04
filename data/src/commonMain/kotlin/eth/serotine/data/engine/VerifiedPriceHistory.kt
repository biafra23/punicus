package eth.serotine.data.engine

import eth.serotine.data.engine.Abi.RoundId
import eth.serotine.data.prices.PinnedFeed
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.PastReading
import eth.serotine.domain.repository.PriceHistory
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.math.pow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [PriceHistory] over a verified light client: a [PinnedFeed]'s round of some time ago, found in
 * the feed's own round history with verified calls ([VerifiedChain.call]) at the verified head,
 * never with a call at a past block (no peer serves old state) and never from anything kept
 * outside the chain. `decimals()` and `latestRoundData()` are read and checked as
 * [VerifiedPriceFeeds] does; then the rounds of the latest one's phase are searched with
 * `getRoundData`, by interpolation on their update times (a feed updates about once per
 * heartbeat, so the first guess is the heartbeats back and the second is usually the neighbour
 * that brackets the time), with a bisection whenever two calls did not halve the span, and at
 * most [MAX_ROUND_CALLS] calls. A round, once written, never changes (the proxy's aggregator of
 * a phase is fixed, and an aggregator writes each round once), so the rounds read are kept in
 * memory ([MAX_KEPT_ROUNDS] per feed) and a later search on the same feed reads few or none
 * again: what keeps a widget's daily change to a handful of calls. The search stays in the
 * current phase: a time before the current aggregator took over is
 * [PastReading.NoRoundThatOld]. Rounds whose times go backwards, a round missing below the
 * latest one, and a non-positive answer are no price; an answer the engine could not verify
 * is [ReadUnavailable.UNPROVEN], as is a search that runs out of calls.
 */
class VerifiedPriceHistory internal constructor(
    private val served: Network,
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    feeds: List<PinnedFeed>,
) : PriceHistory {

    private val pinned = feeds.associateBy { it.priceId }

    /** The rounds read so far per feed, oldest read first; under [lock]. */
    private val kept = mutableMapOf<PriceId, LinkedHashMap<RoundId, Abi.Round>>()
    private val lock = Mutex()

    override suspend fun before(network: Network, priceId: PriceId, secondsBefore: Long): PastReading {
        if (network != served) return PastReading.Unavailable(ReadUnavailable.NO_ENGINE)
        val feed = pinned[priceId] ?: return PastReading.NoPrice(NoPriceReason.NO_FEED)
        val chain = chain ?: return PastReading.Unavailable(ReadUnavailable.NO_ENGINE)
        return withContext(dispatcher) {
            try {
                search(chain, feed, secondsBefore)
            } catch (e: Throwable) {
                // A cancellation of this read ends it; anything else (an engine call across JNA
                // fails with an Error too) is a read that could not be verified.
                currentCoroutineContext().ensureActive()
                UNPROVEN
            }
        }
    }

    private suspend fun search(chain: VerifiedChain, feed: PinnedFeed, secondsBefore: Long): PastReading {
        val snapshot = chain.snapshot()
        // No engine, a failed start or a network that is off say so; one still starting is checking.
        if (snapshot !is EngineSnapshot.Running) {
            val why = whyUnreadable(snapshot)
            return if (why == null || why == ReadUnavailable.UNPROVEN || why == ReadUnavailable.SYNCING) {
                PastReading.Syncing
            } else {
                PastReading.Unavailable(why)
            }
        }
        if (snapshot.phase == EngineSnapshot.Phase.STALE_ANCHOR) {
            return PastReading.Unavailable(ReadUnavailable.STALE_ANCHOR)
        }
        // The head's time before the calls, as for the price now: the calls block, the head moves.
        if (snapshot.readableHead == null) return PastReading.Syncing
        val headTime = snapshot.headTimestamp ?: return PastReading.Syncing
        val target = headTime - secondsBefore

        val places = when (val decimals = chain.call(feed.address, Abi.DECIMALS)) {
            null -> return UNPROVEN
            CallOutcome.Refused -> return REFUSED
            is CallOutcome.Reverted -> return NOT_A_FEED
            is CallOutcome.Returned -> Abi.uint256(decimals.hex)?.toIntOrNull() ?: return NOT_A_FEED
        }
        if (places != feed.decimals) return PastReading.NoPrice(NoPriceReason.DECIMALS_CHANGED)
        val latestHex = when (val latest = chain.call(feed.address, Abi.LATEST_ROUND_DATA)) {
            null -> return UNPROVEN
            CallOutcome.Refused -> return REFUSED
            is CallOutcome.Reverted -> return NOT_A_FEED
            is CallOutcome.Returned -> latest.hex
        }
        val latest = Abi.round(latestHex)
            ?.takeIf { it.updatedAt > 0 && it.updatedAt <= headTime + VerifiedPriceFeeds.FUTURE_SKEW_SECONDS }
            ?: return NOT_A_FEED
        val latestId = Abi.roundId(latestHex)?.takeIf { it.round >= 1 } ?: return NOT_A_FEED
        // The feed has not updated since then: its latest round is the one in effect at the time.
        if (latest.updatedAt <= target) return reading(feed, places, latest, target)

        // The phase's first round is after the time: its aggregator took over since.
        if (latestId.round == 1L) return PastReading.NoRoundThatOld

        // Rounds of the latest one's phase, by number: [lo] at or before the time, [hi] after it.
        var hi = latestId.round
        var hiTime = latest.updatedAt
        var lo = 0L // none known yet
        var loRound: Abi.Round? = null
        var step = 1L
        // The first guess: as many rounds back as heartbeats fit into the time to go back.
        var guess = hi - ceilDiv(hiTime - target, feed.heartbeatSeconds.coerceAtLeast(1))
        // The bracket's span after each call: interpolation that has not halved it in two calls bisects.
        val spans = ArrayList<Long>(MAX_ROUND_CALLS)
        for (call in 0 until MAX_ROUND_CALLS) {
            guess = guess.coerceIn(lo + 1, hi - 1)
            val id = RoundId(latestId.phase, guess)
            val round = when (val read = roundAt(chain, feed, id)) {
                is RoundRead.Found -> read.round
                RoundRead.Unproven -> return UNPROVEN
                RoundRead.Refused -> return REFUSED
                RoundRead.Missing -> return NOT_A_FEED
            }
            // Within the bracket a round's time lies between its ends', or the history is no feed's.
            val below = loRound
            if (round.updatedAt > hiTime || (below != null && round.updatedAt < below.updatedAt)) return NOT_A_FEED
            if (round.updatedAt <= target) {
                lo = guess
                loRound = round
            } else {
                hi = guess
                hiTime = round.updatedAt
            }
            spans += hi - lo
            val found = loRound
            if (found == null) {
                // Still going back: the first round of the phase is after the time.
                if (hi == 1L) return PastReading.NoRoundThatOld
                // From the rate the rounds came at so far, a little further than they suggest,
                // and at least twice the last step, so a run of quick rounds is passed in a few.
                val rounds = latestId.round - hi
                val seconds = latest.updatedAt - hiTime
                val estimate = if (rounds > 0 && seconds > 0) {
                    ceilDiv((hiTime - target) * rounds * 5, seconds * 4)
                } else {
                    1
                }
                step = maxOf(estimate, step * 2)
                guess = hi - step
                continue
            }
            if (hi - lo <= 1) return reading(feed, places, found, target)
            val twoCallsBack = spans.getOrNull(spans.size - 3)
            val bisect = twoCallsBack != null && (hi - lo) * 2 > twoCallsBack
            guess = if (bisect || hiTime <= found.updatedAt) {
                lo + (hi - lo) / 2
            } else {
                lo + (hi - lo) * (target - found.updatedAt) / (hiTime - found.updatedAt)
            }
        }
        return UNPROVEN
    }

    /** What the round [id] of [feed] answered: kept from an earlier read, or read now. */
    private suspend fun roundAt(chain: VerifiedChain, feed: PinnedFeed, id: RoundId): RoundRead {
        lock.withLock { kept[feed.priceId]?.get(id) }?.let { return RoundRead.Found(it) }
        val hex = when (val answer = chain.call(feed.address, Abi.getRoundData(id))) {
            null -> return RoundRead.Unproven
            CallOutcome.Refused -> return RoundRead.Refused
            is CallOutcome.Reverted -> return RoundRead.Missing
            is CallOutcome.Returned -> answer.hex
        }
        // A round never written answers zeros (an OCR aggregator) rather than reverting.
        val round = Abi.round(hex)?.takeIf { it.updatedAt > 0 } ?: return RoundRead.Missing
        if (Abi.roundId(hex) != id) return RoundRead.Missing
        lock.withLock {
            val rounds = kept.getOrPut(feed.priceId) { LinkedHashMap() }
            rounds[id] = round
            if (rounds.size > MAX_KEPT_ROUNDS) rounds.remove(rounds.keys.first())
        }
        return RoundRead.Found(round)
    }

    private fun reading(feed: PinnedFeed, places: Int, round: Abi.Round, target: Long): PastReading {
        val answer = round.answer?.takeIf { it != "0" } ?: return PastReading.NoPrice(NoPriceReason.NOT_POSITIVE)
        return PastReading.Verified(
            price = answer.toDouble() / 10.0.pow(places),
            updatedAt = round.updatedAt,
            stale = target - round.updatedAt > feed.heartbeatSeconds + VerifiedPriceFeeds.STALE_GRACE_SECONDS,
        )
    }

    private sealed interface RoundRead {
        data class Found(val round: Abi.Round) : RoundRead
        data object Missing : RoundRead
        data object Unproven : RoundRead
        data object Refused : RoundRead
    }

    internal companion object {
        /** The most `getRoundData` calls one search makes: far more than a feed updating once a heartbeat needs. */
        const val MAX_ROUND_CALLS = 12

        /** Rounds kept per feed: a day's worth of an hourly feed, and some. */
        const val MAX_KEPT_ROUNDS = 48

        private val UNPROVEN = PastReading.Unavailable(ReadUnavailable.UNPROVEN)
        private val REFUSED = PastReading.Unavailable(ReadUnavailable.REFUSED)
        private val NOT_A_FEED = PastReading.NoPrice(NoPriceReason.NOT_A_FEED)

        private fun ceilDiv(a: Long, b: Long): Long = if (a <= 0) 0 else (a + b - 1) / b
    }
}
