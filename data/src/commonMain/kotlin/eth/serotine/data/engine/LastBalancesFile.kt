package eth.serotine.data.engine

import eth.serotine.domain.model.Network
import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The balances the light client last verified, kept in the file at [path] (a
 * [VersionedJsonFile]) so that after a restart a row shows them as last known until a head can
 * be read again (owner's request, 2026-10-19). Each entry is public chain data: an amount, the
 * block it was proven at and the time of the head it was proven at, under an address (or a holder
 * and a token); no key material. Shown only, never acted on: a quote and a signature read a fresh
 * proof (docs/wallet-ux.md).
 *
 * At most [maxKept] entries ([MAX_KEPT] but in tests), the least recently proven dropped first.
 * A balance is written again only when it changed or its stored proof is [REWRITE_AFTER_BLOCKS]
 * blocks old, not at every head, so the time a row shows may be up to that much older than the
 * last proof: an earlier time at which the same amount was verified. A file that cannot be read
 * keeps and shows nothing; a damaged one is set aside as every [VersionedJsonFile] is. File work
 * runs on [dispatcher], and every write is flushed to the disk (twice: the file, then its
 * directory).
 *
 * Each entry's id starts with its network's chain id (`11155111:native:0x…`): a balance is an
 * address's on one network. Format 1 wrote ids without one, when Serotine worked on Sepolia
 * alone, so they read as Sepolia's (docs/networks.md, *Files*).
 */
internal class LastBalancesFile(
    path: String,
    dispatcher: CoroutineDispatcher,
    private val maxKept: Int = MAX_KEPT,
) {
    init {
        require(maxKept > 0) { "maxKept must be positive" }
    }

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "last verified balances",
        formatVersion = FORMAT_VERSION,
        empty = Kept(emptyMap()),
        decode = { version, text ->
            val stored = JSON.decodeFromString(StoredBalances.serializer(), text).balances
            val migrated = if (version < 2) stored.map { it.copy(
                    id = "${Network.SEPOLIA.chainId}:${it.id}",
                ) } else stored
            Kept(migrated.associateBy { it.id })
        },
        encode = { kept ->
            JSON.encodeToString(StoredBalances.serializer(), StoredBalances(FORMAT_VERSION, kept.byId.values.toList()))
        },
    )

    /**
     * The proofs of one adapter's keys in this file: each key under [id] (unique across the
     * adapters that share the file), its value written as [units] (null: not a balance, so
     * nothing is kept for it) and read back with [parse] (null: the entry is not used).
     */
    fun <K, T> kept(id: (K) -> String, units: (T) -> String?, parse: (String) -> T?): KeptProofs<K, T> =
        object : KeptProofs<K, T> {
            override suspend fun load(key: K): EngineReading.LastKnown<T>? {
                val stored = entries()?.get(id(key)) ?: return null
                val value = parse(stored.units) ?: return null
                return EngineReading.LastKnown(value, stored.block, stored.asOf)
            }

            override suspend fun save(key: K, proof: EngineReading.LastKnown<T>) {
                val entryId = id(key)
                val amount = units(proof.value)
                val stored = entries()?.get(entryId)
                if (amount == null) {
                    if (stored != null) change { it - entryId }
                    return
                }
                // A proof of an older block (another subscriber's, late) never replaces a newer one.
                if (stored != null && proof.blockNumber < stored.block) return
                if (stored?.units == amount && proof.blockNumber - stored.block < REWRITE_AFTER_BLOCKS) return
                val entry = StoredBalance(entryId, amount, proof.blockNumber, proof.asOf)
                // Re-inserted last: the map's order is the order of proving.
                change { byId -> (byId - entryId + (entryId to entry)).toList().takeLast(maxKept).toMap() }
            }
        }

    /** What the file holds, or null when it cannot be read. */
    private suspend fun entries(): Map<String, StoredBalance>? =
        (file.observe().first() as? VersionedJsonFile.Read.Loaded)?.value?.byId

    private suspend fun change(next: (Map<String, StoredBalance>) -> Map<String, StoredBalance>) {
        try {
            file.change(notSaved = Unit) { kept -> Decision(Unit, Kept(next(kept.byId))) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only what is shown after a restart is lost; the file reports its own failures.
        }
    }

    /** What the file holds: the entries by id, in the order they were proven. */
    private class Kept(val byId: Map<String, StoredBalance>)

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredBalances(val version: Int, val balances: List<StoredBalance>)

    /** One balance: [units] in the smallest unit, proven at [block], at a head produced at [asOf] (unix seconds). */
    @Serializable
    private data class StoredBalance(val id: String, val units: String, val block: Long, val asOf: Long?)

    internal companion object {
        /** 2 since networks: each id starts with its chain id. */
        const val FORMAT_VERSION = 2

        /** Entries kept: far more than the accounts and tokens a wallet shows. */
        const val MAX_KEPT = 256

        /** About ten minutes of Sepolia blocks: how old an unchanged balance's stored proof may get. */
        const val REWRITE_AFTER_BLOCKS = 50L

        private val JSON = Json
    }
}
