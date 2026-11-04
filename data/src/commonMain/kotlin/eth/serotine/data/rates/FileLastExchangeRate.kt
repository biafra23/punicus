package eth.serotine.data.rates

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.RatePair
import eth.serotine.domain.model.RateSnapshot
import eth.serotine.domain.repository.LastExchangeRate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * [LastExchangeRate] kept in the file at [path] (a [VersionedJsonFile]), one rate per [RatePair]:
 * what a home-screen widget shows without starting the light client. Format 3 keeps each pair's
 * by its network's chain id, its base and whether it follows the display currency (its own
 * currency is the rate's); format 2 kept one per network, its native currency in the display
 * currency ([RatePair.native]), and the one rate of a format-1 file was Sepolia's, the only one a
 * widget could show then (docs/networks.md, *The widget*). A rate of a network or in a currency
 * this build does not know, or one that is no rate, reads as none kept, and the file is kept aside
 * before the next save rewrites it, so a newer build's rates are not lost; a damaged file is set
 * aside, and one that cannot be read is none kept too, the file left as it is. Public data only.
 */
class FileLastExchangeRate(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LastExchangeRate {

    // Wrapped, never null: the file reads a null value as damaged.
    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "last exchange rate",
        formatVersion = FORMAT_VERSION,
        empty = Kept(emptyMap()),
        decode = { version, text ->
            if (version < 2) {
                val one = JSON.decodeFromString(StoredRate.serializer(), text).toSnapshot()
                Kept(listOfNotNull(one?.let { RatePair.native(Network.SEPOLIA) to it }).toMap())
            } else {
                val stored = JSON.decodeFromString(StoredRates.serializer(), text)
                Kept(stored.rates.mapNotNull { it.toEntry(follows = if (version < 3) true else it.follows) }.toMap())
            }
        },
        // A rate this build cannot read (a network or a currency it does not know): the file is kept
        // aside before the next save rewrites it, as every file is that passes entries over.
        incomplete = { text, kept ->
            val stored = LENIENT.decodeFromString(StoredRateFile.serializer(), text)
            stored.version >= 2 && stored.rates.size > kept.rates.size
        },
        encode = { kept ->
            val stored = StoredRates(
                FORMAT_VERSION,
                kept.rates.map { (pair, snapshot) -> StoredRate.of(pair, snapshot) },
            )
            JSON.encodeToString(StoredRates.serializer(), stored)
        },
    )

    override suspend fun load(pair: RatePair): RateSnapshot? =
        (file.observe().first() as? VersionedJsonFile.Read.Loaded)?.value?.rates?.get(pair)

    override suspend fun save(pair: RatePair, snapshot: RateSnapshot): Boolean {
        // Another pair's rate kept as this one's would be shown as what it is not.
        if (snapshot.base != pair.base || (pair.quote != null && pair.quote != snapshot.quote)) return false
        return file.change(notSaved = false) { kept -> Decision(true, next = Kept(kept.rates + (pair to snapshot))) }
    }

    /** What the file holds: each pair's rate kept. */
    private data class Kept(val rates: Map<RatePair, RateSnapshot>)

    /**
     * The file's layout from format 2 (from format 3 each rate says whether it [StoredRate.follows]
     * the display currency). [version] comes first and is always written.
     */
    @Serializable
    private class StoredRates(val version: Int, val rates: List<StoredRate>)

    /** Any format's file, read leniently: its [version], and from format 2 its [rates]. */
    @Serializable
    private class StoredRateFile(val version: Int, val rates: List<JsonElement> = emptyList())

    /**
     * One rate: format 1's whole file, with its [version]; from format 2 a network's, the network
     * of [chain], and from format 3 a pair's, which [follows] the display currency or has its own,
     * [quote]. [poolAverage], when true, says a pool's average was on its path; a file written
     * before pool prices has none, and its rates had no pool on their path. No rate is all null.
     */
    @Serializable
    private class StoredRate(
        val version: Int? = null,
        val chain: Long? = null,
        val follows: Boolean? = null,
        val base: String? = null,
        val quote: String? = null,
        val rate: Double? = null,
        val dayAgoRate: Double? = null,
        val updatedAt: Long? = null,
        val readAt: Long? = null,
        val stale: Boolean? = null,
        val poolAverage: Boolean? = null,
    ) {
        /**
         * The pair of [chain] whose rate it holds, following the display currency where [follows];
         * null as [toSnapshot], and for a pair this build cannot read.
         */
        fun toEntry(follows: Boolean?): Pair<RatePair, RateSnapshot>? {
            val network = chain?.let(Network::ofChainId) ?: return null
            val snapshot = toSnapshot() ?: return null
            val quote = if (follows ?: return null) null else snapshot.quote
            // A currency in itself is no pair: none this build can read.
            val pair = runCatching { RatePair(network, snapshot.base, quote) }.getOrNull() ?: return null
            return pair to snapshot
        }

        /** The rate it holds, or null when it holds none this build can read. */
        fun toSnapshot(): RateSnapshot? {
            val currency = DisplayCurrency.entries.firstOrNull { it.code == quote } ?: return null
            return runCatching {
                RateSnapshot(
                    PriceId(base!!),
                    currency,
                    rate!!,
                    dayAgoRate,
                    updatedAt!!,
                    readAt!!,
                    stale!!,
                    poolAverage = poolAverage == true,
                )
            }.getOrNull()
        }

        companion object {
            fun of(pair: RatePair, snapshot: RateSnapshot?): StoredRate = StoredRate(
                null,
                pair.network.chainId,
                pair.quote == null,
                snapshot?.base?.id,
                snapshot?.quote?.code,
                snapshot?.rate,
                snapshot?.dayAgoRate,
                snapshot?.updatedAt,
                snapshot?.readAt,
                snapshot?.stale,
                snapshot?.poolAverage?.takeIf { it },
            )
        }
    }

    private companion object {
        /** 2 since networks: one rate per network, by its chain id; 3 since pairs: one per pair. */
        const val FORMAT_VERSION = 3
        val JSON = Json { encodeDefaults = true }
        val LENIENT = Json { ignoreUnknownKeys = true }
    }
}
