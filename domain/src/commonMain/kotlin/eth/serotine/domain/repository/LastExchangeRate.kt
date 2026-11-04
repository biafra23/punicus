package eth.serotine.domain.repository

import eth.serotine.domain.model.RatePair
import eth.serotine.domain.model.RateSnapshot

/**
 * The port for the exchange rate last verified of each [RatePair] a widget shows, kept beyond the
 * process so a home-screen widget can show it without starting the light client. :data keeps it
 * in a file. Public data only: a price, its times, no account.
 */
interface LastExchangeRate {
    /** The rate last saved for [pair], or null when none is (or it cannot be read). Never throws. */
    suspend fun load(pair: RatePair): RateSnapshot?

    /**
     * Keeps [snapshot] as [pair]'s in place of its last one; false when it could not be saved, or
     * when [snapshot] is not [pair]'s (another base, or another currency than [pair]'s own), which
     * is refused. Never throws.
     */
    suspend fun save(pair: RatePair, snapshot: RateSnapshot): Boolean
}
