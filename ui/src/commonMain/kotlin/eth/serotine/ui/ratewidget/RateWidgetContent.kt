package eth.serotine.ui.ratewidget

import eth.serotine.domain.model.RatePair
import eth.serotine.domain.model.RateSnapshot
import eth.serotine.ui.components.POOL_AVERAGE
import eth.serotine.ui.components.age
import eth.serotine.ui.components.formatFiat
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What a home-screen widget shows of the exchange rate last verified (docs/wallet-ux.md,
 * *Home-screen widget*), display-ready: the [pair] ("ETH · EUR"), the [rate] ("€2,000.50"), its
 * [change] over the 24 hours before it was read ("24h +2.31%", "24h −1.05%", "24h —" when none
 * was verified; null without a rate) in the direction [trend] says, and the [note] that dates it
 * and says where it comes from ("Verified 14:05 · may have changed since · testnet prices"). The host draws it in its own
 * widget toolkit, green for [Trend.UP] and red for [Trend.DOWN]. [icon] is the watched asset's
 * bundled icon key ([eth.serotine.ui.components.TokenIcons]'s keys; null: no icon shown), drawn
 * in the widget's top-left corner.
 */
data class RateWidgetContent(
    val pair: String,
    val rate: String,
    val change: String?,
    val trend: Trend,
    val note: String,
    val icon: String? = null,
)

/** Which way a rate moved in 24 hours, as far as its shown digits tell. */
enum class Trend { UP, DOWN, FLAT, UNKNOWN }

/**
 * [snapshot] as the widget of [pair] shows it, read at [readAt] (the host's own rendering of
 * [RateSnapshot.readAt] in the user's locale, "Oct 21, 14:05"); no rate kept yet says so, under
 * the pair as far as it is chosen ("LINK · EUR"; "ETH" where it follows the display currency).
 * The note names the widget's network first, each widget showing one (docs/networks.md, *The
 * widget*). Being a rate kept and shown later, it says it may have changed since it was verified,
 * or, while the network is not [on] in this wallet, that it is off (nothing reads it then); one
 * whose feeds were stale says how old its price is. On a testnet every rate says "testnet
 * prices" (CLAUDE.md, *Data sources*), and one with a pool's average on its path says so
 * (owner ruling, 2026-10-29, #205). [icon] is the bundled icon key of [pair]'s base
 * ([eth.serotine.domain.usecase.RateWidgetChoices.icon]; the host resolves it, since this
 * function stays a pure, display-ready mapping), passed straight through.
 */
fun rateWidgetContent(
    snapshot: RateSnapshot?,
    pair: RatePair,
    readAt: String,
    on: Boolean = true,
    icon: String? = null,
): RateWidgetContent {
    val network = pair.network
    val off = "${network.displayName} is off".takeUnless { on }
    if (snapshot == null) {
        return RateWidgetContent(
            pair = listOfNotNull(pair.base.id, pair.quote?.code).joinToString(" · "),
            rate = "—",
            change = null,
            trend = Trend.UNKNOWN,
            note = listOfNotNull(network.displayName, off ?: "No verified rate yet").joinToString(" · "),
            icon = icon,
        )
    }
    val percent = snapshot.dayChangePercent
    // In hundredths of a percent, the digits shown: what rounds to zero is no move.
    val hundredths = percent?.let { (it * 100).roundToLong() }
    return RateWidgetContent(
        pair = "${snapshot.base} · ${snapshot.quote.code}",
        rate = formatFiat(snapshot.rate, snapshot.quote),
        change = "24h " + (hundredths?.let(::signedPercent) ?: "—"),
        trend = when {
            hundredths == null -> Trend.UNKNOWN
            hundredths > 0 -> Trend.UP
            hundredths < 0 -> Trend.DOWN
            else -> Trend.FLAT
        },
        note = listOfNotNull(
            network.displayName,
            "Verified $readAt",
            POOL_AVERAGE.takeIf { snapshot.poolAverage },
            // Kept and shown later: an older answer, labelled as one (CLAUDE.md, *Trust*).
            off ?: "may have changed since",
            "price ${age(snapshot.readAt - snapshot.updatedAt)} old".takeIf { snapshot.stale },
            "testnet prices".takeIf { network.testnet },
        ).joinToString(" · "),
        icon = icon,
    )
}

/** 231 → "+2.31%", −105 → "−1.05%" (a minus sign, not a hyphen), 0 → "0.00%". */
private fun signedPercent(hundredths: Long): String {
    val sign = when {
        hundredths > 0 -> "+"
        hundredths < 0 -> "−"
        else -> ""
    }
    val magnitude = abs(hundredths)
    return "$sign${magnitude / 100}.${(magnitude % 100).toString().padStart(2, '0')}%"
}
