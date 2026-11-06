package eth.serotine.data.engine

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.KeptSafe
import eth.serotine.domain.repository.KeptSafes
import eth.serotine.domain.repository.SafeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Each Safe's state the light client last verified, kept in the file at [path] (a
 * [VersionedJsonFile]) for a lenient Safe draft (docs/lenient-mode.md, *Safe transactions in lenient
 * mode*; owner rulings, 2026-11-06, rule change 4): its singleton's version, its owners, threshold,
 * nonce, modules and guards, with the block they were proven at and the time of the head that proved
 * them. [VerifiedAddressKinds] keeps each Safe it proves here through [proofs], as part 2 keeps the
 * mined counts ([KeptInputsFile]); [KeptSafes] reads them back. Every entry is public chain data
 * under a network's chain id and an address; no key material.
 *
 * Kept, never acted on alone: a draft built on one is shown to another owner, approved or executed
 * only after the check at a fresh head. An older block's proof never replaces a newer one's, decided
 * under the file's lock. A Safe is written again only when its state changed or its stored block is
 * [REWRITE_AFTER_BLOCKS] blocks old, so its time may be a little older than the last proof, never
 * newer. An address proven to be no Safe (any more), at a block not before the stored one, is
 * forgotten. At most [maxKept] Safes are kept ([MAX_KEPT]; fewer in tests), the least recently
 * verified dropped first. A file that cannot be read keeps and gives nothing; a damaged one is set
 * aside as every [VersionedJsonFile] is. File work runs on [dispatcher].
 */
internal class KeptSafesFile(
    path: String,
    dispatcher: CoroutineDispatcher,
    private val maxKept: Int = MAX_KEPT,
) : KeptSafes {
    init {
        require(maxKept > 0) { "maxKept must be positive" }
    }

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "last verified Safes",
        formatVersion = FORMAT_VERSION,
        empty = emptyMap(),
        decode = { _, text -> JSON.decodeFromString(StoredSafes.serializer(), text).safes.associateBy { it.id } },
        encode = { kept ->
            JSON.encodeToString(StoredSafes.serializer(), StoredSafes(FORMAT_VERSION, kept.values.toList()))
        },
    )

    override fun observe(safe: AccountId): Flow<KeptSafe?> {
        val id = idOf(safe.network, safe.address)
        return file.observe()
            .map { read -> (read as? VersionedJsonFile.Read.Loaded)?.value?.get(id)?.toKept() }
            .distinctUntilChanged()
    }

    /**
     * Where the address kinds of [network] keep the last proof of each address: a Safe's is kept, and
     * any other kind's forgets the Safe kept for that address. Never throws.
     */
    fun proofs(network: Network): KeptProofs<Address, AddressKind> = object : KeptProofs<Address, AddressKind> {
        override suspend fun load(key: Address): EngineReading.LastKnown<AddressKind>? {
            val kept = kept()?.get(idOf(network, key))?.toKept() ?: return null
            return EngineReading.LastKnown(AddressKind.Safe(kept.state), kept.blockNumber, kept.asOf)
        }

        override suspend fun save(key: Address, proof: EngineReading.LastKnown<AddressKind>) {
            when (val kind = proof.value) {
                is AddressKind.Safe -> keep(network, key, kind.state, proof.blockNumber, proof.asOf)
                else -> forget(network, key, proof.blockNumber)
            }
        }
    }

    /** Keeps [state], the Safe at [safe] on [network] proven at [block] (a head of [asOf]). Never throws. */
    suspend fun keep(network: Network, safe: Address, state: SafeState, block: Long, asOf: Long?) {
        if (block <= 0) return
        val id = idOf(network, safe)
        val entry = StoredSafe.of(id, state, block, asOf)
        fun wanted(stored: StoredSafe?): Boolean = when {
            stored == null -> true
            block < stored.block -> false
            else -> stored.withoutProof() != entry.withoutProof() || block - stored.block >= REWRITE_AFTER_BLOCKS
        }
        // Asked of the value read last, so most proofs write nothing; asked again under the file's
        // lock, so an older proof that lost a race never replaces a newer one.
        if (!wanted(kept()?.get(id))) return
        change { kept ->
            if (!wanted(kept[id])) return@change null
            // Re-inserted last: the map's order is the order of verifying.
            (kept - id + (id to entry)).toList().takeLast(maxKept).toMap()
        }
    }

    /** Forgets the Safe kept for [address] on [network], proven to be none at [block]. Never throws. */
    private suspend fun forget(network: Network, address: Address, block: Long) {
        val id = idOf(network, address)
        fun wanted(stored: StoredSafe?): Boolean = stored != null && block >= stored.block
        if (!wanted(kept()?.get(id))) return
        change { kept -> if (wanted(kept[id])) kept - id else null }
    }

    /** What the file holds, or null when it cannot be read. */
    private suspend fun kept(): Map<String, StoredSafe>? =
        (file.observe().first() as? VersionedJsonFile.Read.Loaded)?.value

    /** Stores what [next] makes of the file's value, read again under its lock; null stores nothing. */
    private suspend fun change(next: (Map<String, StoredSafe>) -> Map<String, StoredSafe>?) {
        try {
            file.change(notSaved = Unit) { kept -> Decision(Unit, next(kept)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only what a lenient draft could start from is lost; the file reports its own failures.
        }
    }

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredSafes(val version: Int, val safes: List<StoredSafe>)

    /**
     * One Safe under [id] (`<chain id>:<address>`): its state, the addresses in lower-case hex, proven
     * at [block], a head of [asOf].
     */
    @Serializable
    private data class StoredSafe(
        val id: String,
        val version: String,
        val l2: Boolean,
        val owners: List<String>,
        val threshold: Int,
        val nonce: Long,
        val modules: List<String>,
        val guard: String? = null,
        val moduleGuard: String? = null,
        val block: Long,
        val asOf: Long? = null,
    ) {
        /** The same Safe with no proof's block and time: what tells a changed state from the same one proven again. */
        fun withoutProof() = copy(block = 0, asOf = null)

        /** The kept Safe, or null when an entry is not one a Safe can be (written by hand, or damaged). */
        fun toKept(): KeptSafe? = try {
            if (owners.isEmpty() || threshold !in 1..owners.size || nonce < 0) {
                null
            } else {
                KeptSafe(
                    SafeState(
                        version = version,
                        l2 = l2,
                        owners = owners.map(Address::of),
                        threshold = threshold,
                        nonce = nonce,
                        modules = modules.map(Address::of),
                        guard = guard?.let(Address::of),
                        moduleGuard = moduleGuard?.let(Address::of),
                    ),
                    block,
                    asOf,
                )
            }
        } catch (e: IllegalArgumentException) {
            null
        }

        companion object {
            fun of(id: String, state: SafeState, block: Long, asOf: Long?) = StoredSafe(
                id = id,
                version = state.version,
                l2 = state.l2,
                owners = state.owners.map { it.hex },
                threshold = state.threshold,
                nonce = state.nonce,
                modules = state.modules.map { it.hex },
                guard = state.guard?.hex,
                moduleGuard = state.moduleGuard?.hex,
                block = block,
                asOf = asOf,
            )
        }
    }

    internal companion object {
        const val FORMAT_VERSION = 1

        /** Safes kept: far more than a wallet holds. */
        const val MAX_KEPT = 64

        /** As [KeptInputsFile.REWRITE_AFTER_BLOCKS]: about five minutes of Ethereum's blocks. */
        const val REWRITE_AFTER_BLOCKS = KeptInputsFile.REWRITE_AFTER_BLOCKS

        private val JSON = Json

        private fun idOf(network: Network, address: Address) = "${network.chainId}:${address.hex}"
    }
}
