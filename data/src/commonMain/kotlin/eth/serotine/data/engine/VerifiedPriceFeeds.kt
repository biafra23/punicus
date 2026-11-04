package eth.serotine.data.engine

import eth.serotine.data.prices.PinnedFeed
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.concurrent.Volatile
import kotlin.math.pow
import kotlin.time.Duration

/**
 * [PriceFeeds] over a verified light client: each [PinnedFeed]'s `decimals()` and
 * `latestRoundData()` as verified calls ([VerifiedChain.call]), through [ProvenReads], at
 * every verified head that [activity] cannot vouch for. A proof records the aggregator the
 * proxy pointed to (`aggregator()`, called with it), and stands, re-dated at each later head,
 * while no block since it was proven may have changed the answer: no log of that aggregator —
 * every new round emits one — and no change to the proxy's own state, which a switch to
 * another aggregator is (block access lists: until a chain has them, every price is proven
 * at every head, as without [activity], and `aggregator()` is not called). A proof stands only
 * when its `decimals()`, its round and its aggregator were all read at the same block — the
 * same proven hash, not only the same number — and only
 * for a price ([FeedReading.Verified]); every proof reads `decimals()`, since the proxy's
 * aggregator can be replaced; one that differs from the pin, a non-positive answer, or what
 * is no round, is no price. A round is stale once it is older than the feed's heartbeat and [STALE_GRACE_SECONDS]
 * (a heartbeat update lands a little after the heartbeat, not at it), measured against the
 * time of the verified head the read was made at, never the device's clock; a head whose time
 * is not known yet gives no reading (unproven) rather than a guess. A round dated more than
 * [FUTURE_SKEW_SECONDS] after that head is what no feed answers.
 */
class VerifiedPriceFeeds internal constructor(
    private val served: Network,
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
    feeds: List<PinnedFeed>,
    /** What vouches for an unchanged feed between heads; none: its calls at every head. */
    private val activity: AddressActivity? = null,
    /** When its prices are proven again: at every head when null; on demand in simple mode ([ProofSchedule]). */
    schedule: ProofSchedule? = null,
) : PriceFeeds {

    private val pinned = feeds.associateBy { it.priceId }

    /**
     * Per feed, the aggregator its proxy pointed to and the block (number and hash) of the proof
     * that read it: the one [unchangedSince] watches. Replaced whole on each write, so a reader
     * never sees half of one; a write lost to a race only costs that feed one more proof.
     */
    @Volatile
    private var aggregators: Map<PriceId, Noted> = emptyMap()

    private class Noted(val aggregator: Address, val block: Long, val hash: String)

    private val servedQuotes: Map<PriceId, PriceId> = feeds.associate { it.priceId to it.quote }

    /** [served]'s feeds' quotes; none for a network this adapter does not serve: no price crosses networks. */
    override fun quotes(network: Network): Map<PriceId, PriceId> = if (network == served) servedQuotes else emptyMap()

    private val reads = ProvenReads<PriceId, FeedReading>(
        chain,
        dispatcher,
        pollInterval,
        unchangedSince = { id, provenAt, provenHash, head -> unchangedSince(id, provenAt, provenHash, head) },
        atHead = { id, value, headTime -> atHead(id, value, headTime) },
        schedule = schedule,
    ) { verified, id ->
        val feed = pinned.getValue(id)
        // The head's time before the calls: the calls block, and the head may move meanwhile.
        val headTime = (verified.snapshot() as? EngineSnapshot.Running)?.headTimestamp
        val decimals = verified.call(feed.address, Abi.DECIMALS) ?: return@ProvenReads null
        if (decimals == CallOutcome.Refused) return@ProvenReads REFUSED
        val round = verified.call(feed.address, Abi.LATEST_ROUND_DATA) ?: return@ProvenReads null
        if (round == CallOutcome.Refused) return@ProvenReads REFUSED
        // A refusal is said whatever the head's time; an answer cannot be dated without it.
        if (headTime == null) return@ProvenReads null
        val block = (round as? CallOutcome.Returned)?.blockNumber
        val hash = (round as? CallOutcome.Returned)?.blockHash
        // Only where a kept price could stand: a chain whose blocks show account activity, and
        // a round whose block is proven, read at the same one as its decimals.
        val pairedDecimals = (decimals as? CallOutcome.Returned)?.let { it.blockNumber == block && it.blockHash == hash }
        if (block != null && hash != null && pairedDecimals == true && activity?.hasAccountCoverage(block) == true) {
            noteAggregator(id, verified, block, hash)
        } else {
            aggregators = aggregators - id
        }
        EngineReading.Proven(reading(feed, decimals, round, headTime), block ?: UNKNOWN_BLOCK, hash)
    }

    /**
     * Read the proxy's aggregator for the proof at [block] of hash [hash]. Kept only when the
     * call ran at that same block: an aggregator read at another one — a later block, or a
     * sibling of the same number — could be the proxy's next, not this proof's. Anything else
     * forgets the feed's, and its proof is not vouched for.
     */
    private fun noteAggregator(id: PriceId, verified: VerifiedChain, block: Long, hash: String) {
        val outcome = verified.call(pinned.getValue(id).address, Abi.AGGREGATOR) as? CallOutcome.Returned
        val aggregator = outcome?.takeIf { it.blockNumber == block && it.blockHash == hash }?.let { Abi.address(it.hex) }
        aggregators = if (aggregator != null) aggregators + (id to Noted(aggregator, block, hash)) else aggregators - id
    }

    /**
     * Whether the feed's answer proven at block [provenAt] still stands at [head]: its proof's
     * aggregator emitted no log since, and its proxy's own state did not change — both checked,
     * from the proof's block through the head, by [activity].
     */
    private fun unchangedSince(id: PriceId, provenAt: Long, provenHash: String?, head: Long): Boolean {
        val activity = activity ?: return false
        val noted = aggregators[id] ?: return false
        if (noted.block != provenAt || noted.hash != provenHash) return false
        val proxy = pinned.getValue(id).address
        // Both from one report: a reorg between two reads could hide the aggregator's new round.
        return activity.unchangedSince(
            listOf(noted.aggregator to AddressActivity.Kind.LOG, proxy to AddressActivity.Kind.ACCOUNT),
            provenAt,
            provenHash,
            head,
        )
    }

    /**
     * A kept reading as of a head produced at [headTime]: a price's age and staleness move with
     * the head even while its round stands. Null — prove again — when the head's time is not
     * known yet, since an answer cannot be dated without it, and for anything but a price: a
     * "no price" may itself depend on the head's time (a round dated after it).
     */
    private fun atHead(id: PriceId, value: FeedReading, headTime: Long?): FeedReading? {
        if (value !is FeedReading.Verified) return null
        headTime ?: return null
        val age = (headTime - value.updatedAt).coerceAtLeast(0)
        return value.copy(ageSeconds = age, stale = age > pinned.getValue(id).heartbeatSeconds + STALE_GRACE_SECONDS)
    }

    override fun observe(network: Network, priceId: PriceId): Flow<FeedReading> {
        if (network != served) return flowOf(FeedReading.Unavailable(ReadUnavailable.NO_ENGINE))
        if (priceId !in pinned) return flowOf(FeedReading.NoPrice(NoPriceReason.NO_FEED))
        return reads.observe(priceId).map { reading ->
            when (reading) {
                EngineReading.Syncing, is EngineReading.LastKnown -> FeedReading.Syncing
                is EngineReading.Proven -> reading.value
                is EngineReading.Unavailable -> FeedReading.Unavailable(reading.reason)
            }
        }.distinctUntilChanged()
    }

    private fun reading(feed: PinnedFeed, decimals: CallOutcome, round: CallOutcome, headTime: Long): FeedReading {
        val places = (decimals as? CallOutcome.Returned)?.let { Abi.uint256(it.hex) }?.toIntOrNull()
            ?: return FeedReading.NoPrice(NoPriceReason.NOT_A_FEED)
        if (places != feed.decimals) return FeedReading.NoPrice(NoPriceReason.DECIMALS_CHANGED)
        val data = (round as? CallOutcome.Returned)?.let { Abi.round(it.hex) }
            // A few slots past the head the time was taken at, a call can run at a later one.
            ?.takeIf { it.updatedAt > 0 && it.updatedAt <= headTime + FUTURE_SKEW_SECONDS }
            ?: return FeedReading.NoPrice(NoPriceReason.NOT_A_FEED)
        val answer = data.answer?.takeIf { it != "0" } ?: return FeedReading.NoPrice(NoPriceReason.NOT_POSITIVE)
        // A round dated after the head's time (a call run at a later head) is no older than it.
        val age = (headTime - data.updatedAt).coerceAtLeast(0)
        return FeedReading.Verified(
            price = answer.toDouble() / 10.0.pow(places),
            updatedAt = data.updatedAt,
            ageSeconds = age,
            stale = age > feed.heartbeatSeconds + STALE_GRACE_SECONDS,
        )
    }

    internal companion object {
        /** How long after its heartbeat a round may still land: its own consensus and inclusion. */
        const val STALE_GRACE_SECONDS = 600L

        /** How far after the head's time a round may be dated: a call answered a few heads later. */
        const val FUTURE_SKEW_SECONDS = 120L

        private val REFUSED = EngineReading.Unavailable(ReadUnavailable.REFUSED)

        /** What [ProvenReads] is given for a call whose block the engine did not name. */
        private const val UNKNOWN_BLOCK = -1L
    }
}
