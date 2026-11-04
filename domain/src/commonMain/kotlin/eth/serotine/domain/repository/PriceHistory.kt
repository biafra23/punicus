package eth.serotine.domain.repository

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId

/**
 * The port for what a price feed answered some time ago (CLAUDE.md, *Data sources*: Chainlink
 * aggregators read through the light client): the round a pinned feed had in effect then, read
 * from the feed's own round history as verified calls at the verified head. :data implements it
 * over the Myotis engine with the same feed table as [PriceFeeds]. Like any price, it is shown
 * and never signed.
 */
interface PriceHistory {
    /**
     * The round of [priceId]'s feed on [network] in effect [secondsBefore] seconds before the verified head, by the
     * head's own time, never the device's clock: the last round updated at or before then. [PastReading.NoPrice] with
     * [NoPriceReason.NO_FEED] for a price id with no feed in [PriceFeeds.quotes] of [network]. A [network] no adapter
     * serves here is refused, never answered from another network ([ReadUnavailable.NO_ENGINE]). One read, when called;
     * never fails.
     */
    suspend fun before(network: Network, priceId: PriceId, secondsBefore: Long): PastReading
}

/** One answer of [PriceHistory.before]. */
sealed interface PastReading {

    /** The light client has no verified head to read at. */
    data object Syncing : PastReading

    /**
     * One unit of the priced asset cost [price] in the feed's quote, by the round updated at
     * [updatedAt] (unix seconds), the one in effect at the time asked for; [stale] when it was
     * older than the feed's heartbeat even then.
     */
    data class Verified(val price: Double, val updatedAt: Long, val stale: Boolean) : PastReading

    /** The feed has no round that old: its current aggregator took over since. */
    data object NoRoundThatOld : PastReading

    /** The feed answered, verified, with something that is no price, for [reason]. */
    data class NoPrice(val reason: NoPriceReason) : PastReading

    /** No verified answer could be had right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : PastReading
}
