package eth.serotine.android.widget

import android.content.Context
import android.content.SharedPreferences
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.RatePair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What each placed exchange-rate widget shows (docs/wallet-ux.md, *Home-screen widget*), by its
 * app-widget id: its network, base and currency, chosen when it is placed
 * ([RateWidgetConfigureActivity]) and kept until it is removed. A widget placed before networks has
 * none recorded and shows Sepolia's native rate, the only one it could have shown; one placed before
 * pairs has only its network, and shows that network's native rate in the display currency
 * ([RatePair.native]). Private preferences: the choice is no secret, but no other app needs it.
 */
class RateWidgetPairs(context: Context) {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The pair the widget [id] shows. */
    fun of(id: Int): RatePair {
        val network = preferences.getLong(networkKey(id), 0).takeIf { it != 0L }?.let(Network::ofChainId)
            ?: Network.SEPOLIA
        val base = preferences.getString(baseKey(id), null)?.let { runCatching { PriceId(it) }.getOrNull() }
            ?: return RatePair.native(network)
        val quote = preferences.getString(quoteKey(id), null)?.let { code ->
            DisplayCurrency.entries.firstOrNull { it.code == code } ?: return RatePair.native(network)
        }
        return runCatching { RatePair(network, base, quote) }.getOrElse { RatePair.native(network) }
    }

    /** Whether the widget [id] has its choice recorded: made once, when it was placed. */
    fun chosen(id: Int): Boolean = preferences.contains(networkKey(id))

    /** Records that the widget [id] shows [pair]; true once stored. Written off the main thread. */
    suspend fun set(id: Int, pair: RatePair): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().apply { put(id, pair) }.commit()
    }

    /** The widgets [oldIds] given the new ids [newIds] (a restore), each keeping its pair. */
    fun moved(oldIds: IntArray, newIds: IntArray) {
        val shown = oldIds.map(::of)
        preferences.edit().apply {
            oldIds.forEach { remove(it) }
            newIds.zip(shown).forEach { (id, pair) -> put(id, pair) }
        }.apply()
    }

    /** Forgets the widgets [ids], removed from the home screen. */
    fun forget(ids: IntArray) {
        preferences.edit().apply { ids.forEach { remove(it) } }.apply()
    }

    private fun SharedPreferences.Editor.put(id: Int, pair: RatePair) {
        putLong(networkKey(id), pair.network.chainId)
        putString(baseKey(id), pair.base.id)
        // None: the display currency, followed.
        val quote = pair.quote
        if (quote == null) remove(quoteKey(id)) else putString(quoteKey(id), quote.code)
    }

    private fun SharedPreferences.Editor.remove(id: Int) {
        remove(networkKey(id))
        remove(baseKey(id))
        remove(quoteKey(id))
    }

    private fun networkKey(id: Int) = "network-$id"

    private fun baseKey(id: Int) = "base-$id"

    private fun quoteKey(id: Int) = "quote-$id"

    private companion object {
        /** The file of the networks alone, before pairs: the widgets placed then keep theirs. */
        const val FILE = "rate-widget-networks"
    }
}
