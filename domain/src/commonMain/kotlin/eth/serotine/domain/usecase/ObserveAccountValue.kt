package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenBalanceReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.math.pow

/**
 * What an account holds and what it is worth in the display currency (docs/wallet-ux.md,
 * screens 1 and 4, and *Prices*): its verified native balance and token holdings, each valued
 * at its feed's price, and the [AccountValue.total] of the priced ones. The prices are read
 * one per price id ([readEach]), so a token added or the currency changed starts only the
 * reading it needs. A price is display-only: nothing here is ever part of an amount signed.
 */
class ObserveAccountValue(
    private val observeNativeBalance: ObserveNativeBalance,
    private val observeTokenHoldings: ObserveTokenHoldings,
    private val observeUsdPrice: ObserveUsdPrice,
    private val observeDisplayCurrencies: ObserveDisplayCurrencies,
) {
    operator fun invoke(holder: AccountId): Flow<AccountValue> {
        val network = holder.network
        val address = holder.address
        val native = observeNativeBalance(network, address).map<BalanceReading, BalanceReading?> { it }
            .onStart { emit(null) }
        // Like the native balance, the holdings never hold the account back: none until they
        // come, which is not the same as none held.
        val holdings = observeTokenHoldings(network, address).map<TokenHoldings, TokenHoldings?> { it }
            .onStart { emit(null) }
        val currency = observeDisplayCurrencies().map { it.chosen }.distinctUntilChanged()
        return combine(native, holdings, currency) { balance, held, chosen -> Inputs(balance, held, chosen) }
            .readEach(keys = { priceIds(network, it) }, read = { observeUsdPrice(network, it) })
            .map { (inputs, usd) -> valued(network, inputs, usd) }
    }

    private class Inputs(val native: BalanceReading?, val holdings: TokenHoldings?, val currency: DisplayCurrency)

    private fun priceIds(network: Network, inputs: Inputs): Set<PriceId> = buildSet {
        add(inputs.currency.priceId)
        add(network.nativePriceId)
        inputs.holdings?.holdings?.mapNotNullTo(this) { it.token.priceId }
    }

    private fun valued(network: Network, inputs: Inputs, usd: Map<PriceId, Price>): AccountValue {
        val rate = usd[inputs.currency.priceId] ?: Price.Checking
        // The currency's own rate missing is no fault of the assets': said once, as such.
        val noRate = rate is Price.NoPrice || rate is Price.Unavailable
        fun value(units: String, decimals: Int, id: PriceId?): Valuation {
            // An amount of the display currency itself is its own value: no feed is asked.
            if (id == inputs.currency.priceId) return valuation(units, decimals, Price.ONE)
            val own = id?.let { usd[it] ?: Price.Checking } ?: Price.NoPrice(NoPriceReason.NO_FEED)
            // An asset without a feed has no price whatever the rate; any other has no value
            // in a currency without one.
            if (noRate && own !is Price.NoPrice) return Valuation.NoRate
            return valuation(units, decimals, own inUnitsOf rate)
        }
        val native = inputs.native
        return AccountValue(
            currency = inputs.currency,
            native = native,
            nativeValue = (native as? BalanceReading.Verified)
                ?.let { value(it.wei.decimal, NATIVE_DECIMALS, network.nativePriceId) },
            tokens = inputs.holdings?.holdings.orEmpty().map { holding ->
                val units = (holding.reading as? TokenBalanceReading.Verified)?.units
                TokenValue(holding, units?.let { value(it.decimal, holding.token.decimals, holding.token.priceId) })
            },
            tokensRead = inputs.holdings != null,
            settingsUnavailable = inputs.holdings?.settingsUnavailable == true,
        )
    }

    private companion object {
        /** Decimal places of ether and of every EVM chain's native currency. */
        const val NATIVE_DECIMALS = 18
    }
}

/**
 * An account's assets valued in [currency]: its [native] balance (null before the first
 * reading) with its value ([nativeValue], null while the balance is not verified), and each
 * shown token's holding with its value ([tokens], none before they are read: [tokensRead]
 * false); [settingsUnavailable] when the user's token settings cannot be read, so the tokens
 * ignore them.
 */
data class AccountValue(
    val currency: DisplayCurrency,
    val native: BalanceReading?,
    val nativeValue: Valuation?,
    val tokens: List<TokenValue>,
    val tokensRead: Boolean = true,
    val settingsUnavailable: Boolean = false,
) {
    /**
     * The value of the priced holdings (docs/wallet-ux.md, *Prices*: totals add only priced
     * holdings, and say how many assets they left out): a held asset without a price, or with
     * a stale one, is left out and counted; a balance or price that cannot be verified right
     * now is counted as not verified; one still being read makes the total incomplete.
     */
    val total: Total
        get() {
            var total = Total.of(native?.let(::heldNative) ?: Holding.Pending, nativeValue)
            if (!tokensRead) total += Total.PENDING
            tokens.forEach { token -> total += Total.of(heldToken(token.holding.reading), token.value) }
            return total
        }

    private fun heldNative(reading: BalanceReading): Holding = when (reading) {
        is BalanceReading.Verified -> if (reading.wei.decimal == "0") Holding.None else Holding.Some
        // Last known: it may have changed, so the total waits for a fresh proof.
        BalanceReading.Syncing, is BalanceReading.LastKnown -> Holding.Pending
        is BalanceReading.Unavailable -> Holding.Unverified
    }

    private fun heldToken(reading: TokenBalanceReading?): Holding = when (reading) {
        is TokenBalanceReading.Verified -> if (reading.units.isZero) Holding.None else Holding.Some
        TokenBalanceReading.NotAToken -> Holding.None
        null, TokenBalanceReading.Syncing, is TokenBalanceReading.LastKnown -> Holding.Pending
        is TokenBalanceReading.Unavailable -> Holding.Unverified
    }
}

/** A shown token's [holding] and its [value] in the display currency, null while its balance is not verified. */
data class TokenValue(val holding: TokenHolding, val value: Valuation?)

/** What an amount is worth in the display currency. */
sealed interface Valuation {

    /** Its price is not read yet. */
    data object Checking : Valuation

    /**
     * [amount] at a price [ageSeconds] old at the verified head; [stale] when it is older than
     * its feed's heartbeat; [poolAverage] when the price is a pool's average
     * ([Price.Priced.poolAverage]), which the value says wherever it is shown.
     */
    data class Valued(
        val amount: Double,
        val ageSeconds: Long,
        val stale: Boolean,
        val poolAverage: Boolean = false,
    ) : Valuation

    /** It has no price, for [reason]. */
    data class NoPrice(val reason: NoPriceReason) : Valuation

    /** No verified price can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : Valuation

    /** It has a price, but the display currency's own rate has none right now. */
    data object NoRate : Valuation
}

/**
 * A total in the display currency: [amount], the sum of the [valued] holdings, [poolAveraged] of
 * them valued at a pool's average (counted in, and said under the total: owner ruling,
 * 2026-10-29, #205); the held assets [leftOut] for having no fresh price; the balances and prices
 * that cannot be verified right now ([unverified]); [noRate] when the display currency's own
 * rate has no price, so nothing can be valued in it; and whether every balance and price it
 * needs has been read ([complete]).
 */
data class Total(
    val amount: Double,
    val valued: Int = 0,
    val leftOut: Int = 0,
    val unverified: Int = 0,
    val complete: Boolean = true,
    val noRate: Boolean = false,
    val poolAveraged: Int = 0,
) {

    operator fun plus(other: Total): Total = Total(
        amount + other.amount,
        valued + other.valued,
        leftOut + other.leftOut,
        unverified + other.unverified,
        complete && other.complete,
        noRate || other.noRate,
        poolAveraged + other.poolAveraged,
    )

    companion object {
        /** Nothing, nothing left out, nothing missing. */
        val NONE = Total(0.0)

        /** Something still being read. */
        val PENDING = Total(0.0, complete = false)

        internal fun of(held: Holding, value: Valuation?): Total = when (held) {
            Holding.None -> NONE
            Holding.Pending -> PENDING
            Holding.Unverified -> Total(0.0, unverified = 1)
            Holding.Some -> when (value) {
                null, Valuation.Checking -> PENDING
                is Valuation.Valued -> when {
                    value.stale -> Total(0.0, leftOut = 1)
                    else -> Total(value.amount, valued = 1, poolAveraged = if (value.poolAverage) 1 else 0)
                }
                is Valuation.NoPrice -> Total(0.0, leftOut = 1)
                is Valuation.Unavailable -> Total(0.0, unverified = 1)
                Valuation.NoRate -> Total(0.0, noRate = true)
            }
        }
    }
}

/** Whether an asset is held, as its balance says: not yet read ([Pending]), or not verifiable now ([Unverified]). */
internal enum class Holding { None, Some, Pending, Unverified }

/** [units] of an asset with [decimals] at [price]: display-only, so a double is precise enough. */
internal fun valuation(units: String, decimals: Int, price: Price): Valuation = when (price) {
    Price.Checking -> Valuation.Checking
    is Price.Priced ->
        Valuation.Valued(
            units.toDouble() / 10.0.pow(decimals) * price.perUnit,
            price.ageSeconds,
            price.stale,
            price.poolAverage,
        )
    is Price.NoPrice -> Valuation.NoPrice(price.reason)
    is Price.Unavailable -> Valuation.Unavailable(price.reason)
}
