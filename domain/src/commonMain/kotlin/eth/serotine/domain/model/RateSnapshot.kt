package eth.serotine.domain.model

/**
 * An exchange rate the light client verified: one [base] cost [rate] in [quote], by feeds whose
 * oldest update was at [updatedAt], read at the verified head of [readAt] (both unix seconds,
 * the chain's own time); [stale] when a feed on its path was older than its heartbeat. The
 * same rate 24 hours before that head was [dayAgoRate], or null when no verified one could be
 * had; [poolAverage] when a pool's average is on its path, which the widget says. Kept as it
 * was read, to be shown dated and marked as possibly changed since, as the older answer
 * CLAUDE.md's *Trust* describes is.
 */
data class RateSnapshot(
    val base: PriceId,
    val quote: DisplayCurrency,
    val rate: Double,
    val dayAgoRate: Double?,
    val updatedAt: Long,
    val readAt: Long,
    val stale: Boolean,
    val poolAverage: Boolean = false,
) {
    init {
        require(rate.isFinite() && rate > 0) { "not a rate: $rate" }
        require(dayAgoRate == null || (dayAgoRate.isFinite() && dayAgoRate > 0)) { "not a rate: $dayAgoRate" }
    }

    /** How much [rate] moved in the 24 hours before [readAt], in percent; null without [dayAgoRate]. */
    val dayChangePercent: Double?
        get() = dayAgoRate?.let { (rate / it - 1) * 100 }
}
