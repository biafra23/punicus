package eth.serotine.data.transfers

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.prunable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * [SentTransfers] kept in the file at [path] (a [VersionedJsonFile]): the signed transactions,
 * each with its signed bytes as hex, which are public once broadcast, and what it does — a
 * transfer's recipient, token and amount, the Safe transaction an approval or execution was
 * for, in the form a signing request travels in, the Safe a deployment created (its address,
 * owners, threshold, version and the name it is listed under), a token approval's token, spender
 * and amount, a swap's router, the asset and amount it sells and the asset and minimum it buys,
 * a CoW order's cancellation on chain (its settlement contract and UID), or a dApp's transaction (its
 * recipient, value and the dApp's stated name), and whether it is only maybe sent.
 * An entry that does not decode (a damaged one, or one of a kind this build does not know) is
 * passed over, as [eth.serotine.data.safe.FileSafeTxQueue] passes one over, and the rest are read;
 * the file it was in is then set aside once, whole, and what was read written in its place, so
 * that the entry is kept there rather than lost at the next write;
 * a file that is not one at all is set aside and the record starts empty; one that cannot be read
 * is [SentTransferList.Unreadable] (a quote then goes by the engine's nonce alone).
 *
 * Format 2 adds the Safe transactions, format 3 the Safe deployments, format 4 the transactions
 * maybe sent, format 5 the token approvals and the swaps, format 6 either each one's network (its
 * chain id; one without was sent on Sepolia, the only network there was) or the CoW orders'
 * cancellations on chain (two builds took 6 apart; a record reads alike either way), format 7
 * both, and format 8 the dApps' transactions. A file of an earlier format reads as it is and is
 * written as 8 at its next change, which a build that reads only an earlier one then leaves alone.
 */
class FileSentTransfers(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SentTransfers {

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "sent transfers",
        formatVersion = FORMAT_VERSION,
        empty = emptyList<SentTransfer>(),
        decode = { _, text ->
            JSON.decodeFromString(StoredFile.serializer(), text).transfers.mapNotNull(::decodedTransfer)
        },
        incomplete = { text, transfers ->
            JSON.decodeFromString(StoredFile.serializer(), text).transfers.size != transfers.size
        },
        encode = { transfers ->
            val entries = transfers.map { JSON.encodeToJsonElement(StoredTransfer.serializer(), storedTransfer(it)) }
            JSON.encodeToString(StoredFile.serializer(), StoredFile(FORMAT_VERSION, entries))
        },
    )

    override fun observe(): Flow<SentTransferList> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> SentTransferList.Loaded(read.value)
            is VersionedJsonFile.Read.Unreadable -> SentTransferList.Unreadable
        }
    }

    override suspend fun record(transfer: SentTransfer): Boolean = file.change(notSaved = false) { transfers ->
        // One record per hash: signing the same quote twice is one transaction.
        Decision(true, next = transfers.filterNot { it.hash == transfer.hash } + transfer)
    }

    override suspend fun prune(account: AccountId, minedCount: Long, unlistedBefore: Long): Boolean =
        file.change(notSaved = false) { transfers ->
            val kept = transfers.filterNot { it.prunable(account, minedCount, unlistedBefore) }
            if (kept.size == transfers.size) Decision(true) else Decision(true, next = kept)
        }

    /**
     * The file's layout. [version] comes first and is always written; each of [transfers] is a
     * [StoredTransfer], decoded one by one so that one that does not decode is passed over.
     */
    @Serializable
    private class StoredFile(val version: Int, val transfers: List<JsonElement>)

    private companion object {
        const val FORMAT_VERSION = 8
        val JSON = Json
    }
}
