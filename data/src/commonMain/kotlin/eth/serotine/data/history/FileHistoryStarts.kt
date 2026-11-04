package eth.serotine.data.history

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.HistoryStartList
import eth.serotine.domain.repository.HistoryStarts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [HistoryStarts] kept in the file at [path] (a [VersionedJsonFile]), so a start survives a
 * restart: accounts (a network and an address) and blocks or times, public data only. A file
 * that cannot be used is handled as [VersionedJsonFile] says: a newer format or one that cannot
 * be read is [HistoryStartList.Unreadable] and nothing is written over it; a damaged one is set
 * aside, and the starts are noted again from what comes after. An entry that is neither a time
 * nor a block damages the file.
 *
 * Format 2 records each start's network by its chain id. Every entry of a format-1 file was
 * written when Serotine worked on Sepolia alone, so it reads as a Sepolia account's
 * (docs/networks.md, *Files*). An entry on a network this version does not know is passed over
 * and the file kept aside as it was, so it is never lost.
 */
class FileHistoryStarts(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HistoryStarts {

    private val file = VersionedJsonFile<Map<AccountId, HistoryStart>>(
        path,
        dispatcher,
        what = "history starts",
        formatVersion = FORMAT_VERSION,
        empty = emptyMap(),
        decode = { version, text ->
            JSON.decodeFromString(StoredStarts.serializer(), text).starts.mapNotNull { it.toStart(version) }.toMap()
        },
        encode = { starts ->
            val stored = StoredStarts(FORMAT_VERSION, starts.map { (id, start) -> StoredStart(id, start) })
            JSON.encodeToString(StoredStarts.serializer(), stored)
        },
        incomplete = {
            text,
                starts -> JSON.decodeFromString(StoredStarts.serializer(), text).starts.size > starts.size },
    )

    override fun observe(): Flow<HistoryStartList> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> HistoryStartList.Loaded(read.value)
            is VersionedJsonFile.Read.Unreadable -> HistoryStartList.Unreadable
        }
    }

    override suspend fun note(id: AccountId, atSeconds: Long): Boolean = file.change(notSaved = false) { starts ->
        Decision(true, starts + (id to HistoryStart.Noted(atSeconds)))
    }

    override suspend fun fix(id: AccountId, block: Long): Boolean = file.change(notSaved = false) { starts ->
        if (starts[id] is HistoryStart.Fixed) {
            Decision(false)
        } else {
            Decision(true, starts + (id to HistoryStart.Fixed(block)))
        }
    }

    override suspend fun forget(id: AccountId, start: HistoryStart): Boolean =
        file.change(notSaved = false) { starts ->
            if (starts[id] != start) Decision(true) else Decision(true, starts - id)
        }

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredStarts(val version: Int, val starts: List<StoredStart>)

    /**
     * One start: the account at [address] on the network of [chain] (absent before format 2: Sepolia),
     * with its [block] once fixed, or the time it was [addedAt] (unix seconds) before.
     */
    @Serializable
    private class StoredStart(
        val address: String,
        val block: Long? = null,
        val addedAt: Long? = null,
        val chain: Long? = null,
    ) {
        constructor(id: AccountId, start: HistoryStart) : this(
            id.address.hex,
            block = (start as? HistoryStart.Fixed)?.block,
            addedAt = (start as? HistoryStart.Noted)?.atSeconds,
            chain = id.network.chainId,
        )

        /**
         * Throws on anything but exactly one of the two: the whole file is then damaged. Null for
         * an entry on a network this version does not know.
         */
        fun toStart(version: Int): Pair<AccountId, HistoryStart>? {
            val network = when {
                version < 2 && chain == null -> Network.SEPOLIA
                chain == null -> error("a start without a network")
                else -> Network.ofChainId(chain) ?: return null
            }
            return AccountId(network, Address.of(address)) to when {
                block != null && addedAt == null -> HistoryStart.Fixed(block)
                addedAt != null && block == null -> HistoryStart.Noted(addedAt)
                else -> error("a start that is neither a block nor a time")
            }
        }
    }

    private companion object {
        /** 2 since accounts on more than one network: each start records its chain id. */
        const val FORMAT_VERSION = 2
        val JSON = Json
    }
}
