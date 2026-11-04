package eth.serotine.ui

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.CurrencyChoice
import eth.serotine.domain.repository.DisplayCurrencySetting
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.PriceFeeds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** USD feeds for ETH, USDC, EUR and JPY: what the screens' tests price with. */
internal val TEST_QUOTES = listOf("ETH", "USDC", "EUR", "JPY").associate { PriceId(it) to PriceId.USD }

/** [PriceFeeds] with the feeds of [quotes], each answering what a test sets; Syncing until then. */
internal class FakeFeeds(val quotesTable: Map<PriceId, PriceId> = TEST_QUOTES) : PriceFeeds {
    override fun quotes(network: Network): Map<PriceId, PriceId> = quotesTable

    private val readings = mutableMapOf<PriceId, MutableStateFlow<FeedReading>>()

    fun of(id: String) = readings.getOrPut(PriceId(id)) { MutableStateFlow(FeedReading.Syncing) }

    /** Sets the feed of [id] to [price], [ageSeconds] old at the head; fresh unless [stale]. */
    fun answer(id: String, price: Double, ageSeconds: Long = 60, stale: Boolean = false) {
        of(id).value = FeedReading.Verified(price, updatedAt = 1_000, ageSeconds, stale)
    }

    override fun observe(network: Network, priceId: PriceId): Flow<FeedReading> =
        if (priceId in quotesTable) of(priceId.id) else flowOf(FeedReading.NoPrice(NoPriceReason.NO_FEED))
}

/** A [DisplayCurrencySetting] in memory; [failSaves] makes a choice fail. */
internal class MemoryCurrencySetting : DisplayCurrencySetting {
    val choice = MutableStateFlow<CurrencyChoice>(CurrencyChoice.Chosen(null))
    var failSaves = false

    override fun observe(): Flow<CurrencyChoice> = choice

    override suspend fun choose(currency: DisplayCurrency): Boolean {
        if (failSaves || choice.value is CurrencyChoice.Unreadable) return false
        choice.value = CurrencyChoice.Chosen(currency)
        return true
    }
}
