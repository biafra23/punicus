package eth.serotine.ui.components

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.usecase.Total
import eth.serotine.domain.usecase.Valuation
import kotlin.math.pow
import kotlin.math.roundToLong

/** The largest value shown in full; beyond it a value is shown as at least that much. */
private const val LARGEST_SHOWN = 1e15

/** The most a value's smallest digits may count to: well within a Long. */
private const val LARGEST_SCALED = 1e18

/**
 * [amount] in [currency] as the screens show it ("$3,250.00", "€2,400.00", "¥632",
 * "1.2345 ETH"), rounded to the currency's digits. A value too small for them shows as
 * "< $0.01", never as nothing.
 */
internal fun formatFiat(amount: Double, currency: DisplayCurrency): String {
    if (!amount.isFinite() || amount < 0) return "—"
    val digits = currency.fractionDigits
    val scale = 10.0.pow(digits)
    if (amount > 0 && amount * scale < 0.5) {
        // The smallest amount the currency's digits can show: 0.01, or 1 without digits.
        return "< " + withUnit(currency, if (digits > 0) "0." + "0".repeat(digits - 1) + "1" else "1")
    }
    val largest = minOf(LARGEST_SHOWN, LARGEST_SCALED / scale)
    if (amount >= largest) return withUnit(currency, grouped(largest.toLong()) + "+")
    val scaled = (amount * scale).roundToLong()
    val whole = grouped(scaled / scale.toLong())
    val fraction = if (digits > 0) "." + (scaled % scale.toLong()).toString().padStart(digits, '0') else ""
    return withUnit(currency, whole + fraction)
}

private fun withUnit(currency: DisplayCurrency, number: String): String = when (currency) {
    DisplayCurrency.USD -> "$$number"
    DisplayCurrency.EUR -> "€$number"
    DisplayCurrency.GBP -> "£$number"
    DisplayCurrency.JPY -> "¥$number"
    DisplayCurrency.CHF -> "CHF $number"
    DisplayCurrency.ETH, DisplayCurrency.BTC -> "$number ${currency.code}"
}

/**
 * What a row says of an asset's [value] in [currency]: its value, or why there is none; null
 * while there is no balance to value. On a [network] whose prices are a testnet's, every value
 * says so (CLAUDE.md, *Data sources*), and so does every value at a pool's average (owner ruling,
 * 2026-10-29, #205).
 */
internal fun valuationText(value: Valuation?, currency: DisplayCurrency, network: Network): String? = when (value) {
    null -> null
    Valuation.Checking -> "Checking price…"
    is Valuation.Valued -> listOfNotNull(
        // An average is an estimate, and reads as one (docs/wallet-ux.md, *Prices*).
        (if (value.poolAverage) "≈ " else "") + formatFiat(value.amount, currency),
        POOL_AVERAGE.takeIf { value.poolAverage },
        "price ${age(value.ageSeconds)} old".takeIf { value.stale },
        "testnet prices".takeIf { network.testnet },
    ).joinToString(" · ")
    is Valuation.NoPrice -> when (value.reason) {
        NoPriceReason.NO_FEED -> "No price"
        NoPriceReason.DECIMALS_CHANGED -> "No price: its feed changed"
        NoPriceReason.NOT_POSITIVE -> "No price: its feed answered none"
        NoPriceReason.NOT_A_FEED -> "No price: its feed does not answer as one"
        NoPriceReason.NOT_A_POOL -> "No price: its pool does not answer as one"
        NoPriceReason.POOL_HISTORY_SHORT -> "No price: its pool keeps too little history"
        NoPriceReason.POOL_TOO_THIN -> "No price: its pool holds too little"
        NoPriceReason.POOL_MOVED -> "No price: its pool just moved"
    }
    is Valuation.Unavailable -> "Price not verified"
    Valuation.NoRate -> "No ${currency.code} rate"
}

/**
 * A [total]'s value in [currency], or why nothing of it can be shown: still being read, no
 * rate for the currency, or nothing verifiable right now.
 */
internal fun totalText(total: Total, currency: DisplayCurrency): String = when {
    total.valued > 0 -> formatFiat(total.amount, currency)
    !total.complete -> "Checking…"
    total.noRate -> "No ${currency.code} rate"
    total.unverified > 0 && total.leftOut == 0 -> "Not verified"
    else -> formatFiat(0.0, currency)
}

/**
 * What a total leaves out, display-ready: that its prices are a testnet's on [network], that it
 * includes values at a pool's average, how many held assets it left out for having no fresh
 * price, how many balances or prices cannot be verified right now, that [currency] has no rate,
 * and that it is not complete yet.
 */
internal fun totalNote(total: Total, network: Network, currency: DisplayCurrency): String = listOfNotNull(
    "Testnet prices".takeIf { network.testnet },
    "includes Uniswap averages".takeIf { total.poolAveraged > 0 },
    when (total.leftOut) {
        0 -> null
        1 -> "1 asset without a price"
        else -> "${total.leftOut} assets without a price"
    },
    total.unverified.takeIf { it > 0 }?.let { "$it not verified" },
    "no verified ${currency.code} rate".takeIf { total.noRate },
    "still checking".takeIf { !total.complete },
).joinToString(" · ").replaceFirstChar { it.uppercase() }

/**
 * What a value at a pool's average says beside it (owner ruling, 2026-10-29, #205), its spaces
 * no-break ones and a word joiner after its hyphen, so a value too wide for its place wraps
 * before it, never inside it.
 */
internal const val POOL_AVERAGE = "Uniswap\u00a030-\u2060min\u00a0average"

/** 90 → "2 min", 7_200 → "2 h", 172_800 → "2 d": how old a price is. */
internal fun age(seconds: Long): String = when {
    seconds < 3_600 -> "${(seconds + 59) / 60} min"
    seconds < 172_800 -> "${seconds / 3_600} h"
    else -> "${seconds / 86_400} d"
}
