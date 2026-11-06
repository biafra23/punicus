package eth.serotine.data.engine

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeQuote
import eth.serotine.domain.repository.KeptFees
import eth.serotine.domain.repository.KeptNonce
import eth.serotine.domain.repository.KeptTransactionInputs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The transaction inputs the light client last verified, kept in the file at [path] (a
 * [VersionedJsonFile]) for a lenient draft (docs/lenient-mode.md, *What a draft is built on*):
 * each account's count of mined transactions, and each network's fee quote, each with the block
 * and the time of the head it was verified at. [EngineTransactionInputs] saves each verified
 * answer here ([saveNonce], [saveFees]); [KeptTransactionInputs] reads them back. Every entry is
 * public chain data under a network's chain id and, for a count, an address; no key material.
 *
 * Kept, never acted on alone: a draft built on them is sent only after the check at a fresh head
 * (owner rulings, 2026-11-06). An older block's answer never replaces a newer one's, decided under
 * the file's lock. A count is written again only when it changed or its stored block is
 * [REWRITE_AFTER_BLOCKS] blocks old, and a fee quote only when the stored one is that old, so a
 * value may be a little older than the last answer, never newer. At most [maxKept] counts ([MAX_KEPT] but in tests), the least
 * recently verified dropped first. A file that cannot be read keeps and gives nothing; a damaged
 * one is set aside as every [VersionedJsonFile] is. File work runs on [dispatcher].
 */
internal class KeptInputsFile(
    path: String,
    dispatcher: CoroutineDispatcher,
    private val maxKept: Int = MAX_KEPT,
) : KeptTransactionInputs {
    init {
        require(maxKept > 0) { "maxKept must be positive" }
    }

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "last verified transaction inputs",
        formatVersion = FORMAT_VERSION,
        empty = Kept(emptyMap(), emptyMap()),
        decode = { _, text ->
            val stored = JSON.decodeFromString(StoredInputs.serializer(), text)
            Kept(
                nonces = stored.nonces.associateBy { it.id },
                fees = stored.fees.associateBy { it.chainId },
            )
        },
        encode = { kept ->
            JSON.encodeToString(
                StoredInputs.serializer(),
                StoredInputs(FORMAT_VERSION, kept.nonces.values.toList(), kept.fees.values.toList()),
            )
        },
    )

    override suspend fun minedNonce(network: Network, account: Address): KeptNonce? {
        val stored = kept()?.nonces?.get(nonceId(network, account)) ?: return null
        return stored.takeIf { it.count >= 0 }?.let { KeptNonce(it.count, it.block, it.asOf) }
    }

    override suspend fun fees(network: Network): KeptFees? {
        val stored = kept()?.fees?.get(network.chainId) ?: return null
        val tips = FeeLevel.entries.associateWith { level ->
            stored.tips[level.code]?.let(UInt256::parse) ?: return null
        }
        val nextBaseFee = UInt256.parse(stored.nextBaseFee) ?: return null
        return KeptFees(FeeQuote(nextBaseFee, tips, stored.block), stored.asOf)
    }

    /** Keeps [count], [account]'s mined count on [network] verified at [block] (a head of [asOf]). Never throws. */
    suspend fun saveNonce(network: Network, account: Address, count: Long, block: Long, asOf: Long?) {
        if (count < 0 || block <= 0) return
        val id = nonceId(network, account)
        fun wanted(stored: StoredNonce?): Boolean = when {
            stored == null -> true
            block < stored.block -> false
            else -> stored.count != count || block - stored.block >= REWRITE_AFTER_BLOCKS
        }
        // Asked of the value read last, so most answers write nothing; asked again under the
        // file's lock, so an older answer that lost a race never replaces a newer one.
        if (!wanted(kept()?.nonces?.get(id))) return
        val entry = StoredNonce(id, count, block, asOf)
        change { kept ->
            if (!wanted(kept.nonces[id])) return@change null
            // Re-inserted last: the map's order is the order of verifying.
            val nonces = (kept.nonces - id + (id to entry)).toList().takeLast(maxKept).toMap()
            Kept(nonces, kept.fees)
        }
    }

    /** Keeps [quote], [network]'s fee quote, read at a head of [asOf]. Never throws. */
    suspend fun saveFees(network: Network, quote: FeeQuote, asOf: Long?) {
        fun wanted(stored: StoredFees?): Boolean =
            stored == null || quote.blockNumber - stored.block >= REWRITE_AFTER_BLOCKS
        if (!wanted(kept()?.fees?.get(network.chainId))) return
        val entry = StoredFees(
            chainId = network.chainId,
            nextBaseFee = quote.nextBaseFee.toDecimal(),
            tips = FeeLevel.entries.associate { it.code to quote.tip(it).toDecimal() },
            block = quote.blockNumber,
            asOf = asOf,
        )
        change { kept ->
            if (!wanted(kept.fees[network.chainId])) return@change null
            Kept(kept.nonces, kept.fees + (network.chainId to entry))
        }
    }

    /** What the file holds, or null when it cannot be read. */
    private suspend fun kept(): Kept? = (file.observe().first() as? VersionedJsonFile.Read.Loaded)?.value

    /** Stores what [next] makes of the file's value, read again under its lock; null stores nothing. */
    private suspend fun change(next: (Kept) -> Kept?) {
        try {
            file.change(notSaved = Unit) { kept -> Decision(Unit, next(kept)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only what a lenient draft could start from is lost; the file reports its own failures.
        }
    }

    /** What the file holds: the counts by id, in the order they were verified, and the fee quotes by chain id. */
    private class Kept(val nonces: Map<String, StoredNonce>, val fees: Map<Long, StoredFees>)

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredInputs(val version: Int, val nonces: List<StoredNonce>, val fees: List<StoredFees>)

    /** One mined count: [count] under [id] (`<chain id>:<address>`), verified at [block], a head of [asOf]. */
    @Serializable
    private data class StoredNonce(val id: String, val count: Long, val block: Long, val asOf: Long?)

    /** One network's fee quote, its fees in decimal wei per gas, its tips by level code. */
    @Serializable
    private data class StoredFees(
        val chainId: Long,
        val nextBaseFee: String,
        val tips: Map<String, String>,
        val block: Long,
        val asOf: Long?,
    )

    internal companion object {
        const val FORMAT_VERSION = 1

        /** Counts kept: far more than the accounts a wallet sends from. */
        const val MAX_KEPT = 256

        /**
         * About five minutes of Ethereum's blocks: how old a stored value may get before it is
         * written again. Well under `KeepTransactionInputs`' ten minutes, so each of its rounds
         * writes a fresh fee quote, missed slots and all.
         */
        const val REWRITE_AFTER_BLOCKS = 25L

        private val JSON = Json

        private fun nonceId(network: Network, account: Address) = "${network.chainId}:${account.hex}"

        /** How a level is written: a name of its own, not the enum's, so a rename changes no file. */
        private val FeeLevel.code: String
            get() = when (this) {
                FeeLevel.SLOW -> "slow"
                FeeLevel.NORMAL -> "normal"
                FeeLevel.FAST -> "fast"
            }
    }
}

/** [KeptTransactionInputs] of a host that keeps none: nothing is ever kept, so a lenient draft waits. */
internal object NoKeptTransactionInputs : KeptTransactionInputs {
    override suspend fun minedNonce(network: Network, account: Address): KeptNonce? = null

    override suspend fun fees(network: Network): KeptFees? = null
}
