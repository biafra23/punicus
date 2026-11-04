package eth.serotine.data.swaps

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.WaitingSwap
import eth.serotine.domain.repository.WaitingSwaps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * [WaitingSwaps] kept in the file at [path] (a [VersionedJsonFile]): each swap's inputs, all public,
 * never a quote or a signature. One this build cannot read (a chain no [Network] is, a venue it does
 * not know, a field missing, of another type or out of its range) is left out and the others are
 * kept; the file holding it is first copied aside whole, once, before anything rewrites it, as the
 * other adapters do. A file that is no list of them is damaged, set aside, and the list starts empty.
 * One that cannot be read reads as empty: a waiting swap is a convenience, which Continue quotes
 * again from the chain.
 */
class FileWaitingSwaps(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WaitingSwaps {

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "waiting swaps",
        formatVersion = FORMAT_VERSION,
        empty = emptyList<WaitingSwap>(),
        decode = { _, text -> JSON.decodeFromString(StoredFile.serializer(), text).swaps.mapNotNull(::decoded) },
        // An entry passed over is copied aside with the whole file before the first rewrite.
        incomplete = { text, swaps -> JSON.decodeFromString(StoredFile.serializer(), text).swaps.size != swaps.size },
        encode = { swaps ->
            val entries = swaps.map { JSON.encodeToJsonElement(StoredSwap.serializer(), stored(it)) }
            JSON.encodeToString(StoredFile.serializer(), StoredFile(FORMAT_VERSION, entries))
        },
    )

    override fun observe(): Flow<List<WaitingSwap>> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> read.value
            is VersionedJsonFile.Read.Unreadable -> emptyList()
        }
    }.distinctUntilChanged()

    override suspend fun keep(swap: WaitingSwap): Boolean = file.change(notSaved = false) { swaps ->
        Decision(true, next = swaps.filterNot { it.account == swap.account && it.sell == swap.sell } + swap)
    }

    override suspend fun drop(account: AccountId, sell: Address): Boolean = file.change(notSaved = false) { swaps ->
        val kept = swaps.filterNot { it.account == account && it.sell == sell }
        if (kept.size == swaps.size) Decision(true) else Decision(true, next = kept)
    }

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredFile(val version: Int, val swaps: List<JsonElement>)

    /** One swap: [buy] null for the network's currency, [amount] in the sold token's units, as decimal text. */
    @Serializable
    private class StoredSwap(
        val chain: Long,
        val account: String,
        val sell: String,
        val buy: String?,
        val amount: String,
        val toleranceBasisPoints: Int,
        val venue: String,
        val approval: String,
        val step: Int,
        val steps: Int,
    )

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json

        /** How a venue is written: a name of its own, as the venue setting writes it. */
        val SwapVenue.code: String
            get() = when (this) {
                SwapVenue.UNISWAP -> "uniswap"
                SwapVenue.COW -> "cow"
            }

        fun stored(swap: WaitingSwap) = StoredSwap(
            chain = swap.account.network.chainId,
            account = swap.account.address.hex,
            sell = swap.sell.hex,
            buy = swap.buy?.hex,
            amount = swap.amount.toDecimal(),
            toleranceBasisPoints = swap.tolerance.basisPoints,
            venue = swap.venue.code,
            approval = swap.approval,
            step = swap.step,
            steps = swap.steps,
        )

        /** [entry] as the swap it keeps, or null for one this build cannot read: left out. */
        fun decoded(entry: JsonElement): WaitingSwap? = try {
            swap(JSON.decodeFromJsonElement(StoredSwap.serializer(), entry))
        } catch (e: IllegalArgumentException) {
            // A SerializationException: a field missing or of another type.
            null
        }

        /** [stored] as the swap it keeps, or null for one this build cannot read: left out. */
        fun swap(stored: StoredSwap): WaitingSwap? {
            val network = Network.ofChainId(stored.chain) ?: return null
            val buy = stored.buy?.let { Address.parse(it) ?: return null }
            if (stored.step !in 2..stored.steps) return null
            return WaitingSwap(
                account = AccountId(network, Address.parse(stored.account) ?: return null),
                sell = Address.parse(stored.sell) ?: return null,
                buy = buy,
                amount = UInt256.parse(stored.amount) ?: return null,
                tolerance = SlippageTolerance.ofBasisPoints(stored.toleranceBasisPoints) ?: return null,
                venue = SwapVenue.entries.firstOrNull { it.code == stored.venue } ?: return null,
                approval = stored.approval.takeIf(HASH::matches) ?: return null,
                step = stored.step,
                steps = stored.steps,
            )
        }

        val HASH = Regex("0x[0-9a-fA-F]{64}")
    }
}
