package eth.serotine.data.transfers

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.TransactionHistory
import eth.serotine.domain.repository.yieldsTo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * [TransactionHistory] kept in the file at [path] (a [VersionedJsonFile]): each transaction in
 * the form [FileSentTransfers] stores it ([StoredTransfer]: its signed bytes as hex, public once
 * broadcast, and what it does), with how it ended ([StoredOutcome]). Never pruned. An entry that
 * does not decode (a damaged one, or one of a kind or an outcome this build does not know) is
 * passed over and the rest read; the file it was in is then set aside once, whole, and what was
 * read written in its place, so that the entry is kept there rather than lost at the next write.
 * A file that is not one at all is set aside and the history starts empty (`FollowSentTransactions`
 * then adds back what [FileSentTransfers] still holds); one that cannot be read is
 * [HistoryList.Unreadable].
 *
 * Format 2 records each transaction's network ([StoredTransfer.chain]); a format-1 entry, without
 * one, was sent on Sepolia. A build that reads only format 1 leaves a format-2 file alone.
 */
class FileTransactionHistory(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TransactionHistory {

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "transaction history",
        formatVersion = FORMAT_VERSION,
        empty = emptyList<HistoryEntry>(),
        decode = { _, text -> JSON.decodeFromString(StoredFile.serializer(), text).entries.mapNotNull(::decoded) },
        incomplete = { text, entries ->
            JSON.decodeFromString(StoredFile.serializer(), text).entries.size != entries.size
        },
        encode = { entries ->
            val stored = entries.map { JSON.encodeToJsonElement(StoredEntry.serializer(), stored(it)) }
            JSON.encodeToString(StoredFile.serializer(), StoredFile(FORMAT_VERSION, stored))
        },
    )

    override fun observe(): Flow<HistoryList> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> HistoryList.Loaded(read.value)
            is VersionedJsonFile.Read.Unreadable -> HistoryList.Unreadable
        }
    }

    override suspend fun record(transfer: SentTransfer): Boolean = file.change(notSaved = false) { entries ->
        val index = entries.indexOfLast { it.transfer.hash == transfer.hash }
        val next = if (index < 0) {
            entries + HistoryEntry(transfer)
        } else {
            // In its place, with what was seen of it: one entry per hash, in the order sent.
            entries.toMutableList().apply { this[index] = this[index].copy(transfer = transfer) }
        }
        if (next == entries) Decision(true) else Decision(true, next = next)
    }

    override suspend fun settle(hash: String, outcome: SentOutcome): Boolean =
        file.change(notSaved = false) { entries ->
            val index = entries.indexOfLast { it.transfer.hash == hash }
            when {
                index < 0 -> Decision(false)
                entries[index].outcome == outcome -> Decision(true)
                // Under the file's lock: no outcome read meanwhile is replaced by one that says less.
                !entries[index].outcome.yieldsTo(outcome) -> Decision(false)
                else -> {
                    val next = entries.toMutableList().apply { this[index] = this[index].copy(outcome = outcome) }
                    Decision(true, next = next)
                }
            }
        }

    /**
     * The file's layout. [version] comes first and is always written; each of [entries] is a
     * [StoredEntry], decoded one by one so that one that does not decode is passed over.
     */
    @Serializable
    private class StoredFile(val version: Int, val entries: List<JsonElement>)

    /** One transaction ([transfer], a [StoredTransfer]) and how it ended ([outcome]). */
    @Serializable
    private class StoredEntry(val transfer: JsonElement, val outcome: StoredOutcome)

    /**
     * A [SentOutcome]: its [kind] ([NOT_SEEN], [INCLUDED], [FINALIZED] or [NONCE_USED]) and, for
     * one seen in a block, the [block] and whether it [succeeded].
     */
    @Serializable
    private class StoredOutcome(val kind: String, val block: Long? = null, val succeeded: Boolean? = null) {
        fun toOutcome(): SentOutcome = when (kind) {
            NOT_SEEN -> SentOutcome.NotSeen
            NONCE_USED -> SentOutcome.NonceUsed
            INCLUDED -> SentOutcome.Included(blockOf(), succeededOf())
            FINALIZED -> SentOutcome.Finalized(blockOf(), succeededOf())
            else -> throw IllegalArgumentException("an outcome of no kind")
        }

        private fun blockOf(): Long = requireNotNull(block) { "a block seen and not named" }.also {
            require(it >= 0) { "a block below zero" }
        }

        private fun succeededOf(): Boolean = requireNotNull(succeeded) { "a receipt without its result" }
    }

    private companion object {
        const val FORMAT_VERSION = 2
        const val NOT_SEEN = "not-seen"
        const val INCLUDED = "included"
        const val FINALIZED = "finalized"
        const val NONCE_USED = "nonce-used"
        val JSON = Json

        /** The entry [element] stores, or null when it does not decode: passed over, the rest read. */
        fun decoded(element: JsonElement): HistoryEntry? = try {
            val entry = JSON.decodeFromJsonElement(StoredEntry.serializer(), element)
            decodedTransfer(entry.transfer)?.let { HistoryEntry(it, entry.outcome.toOutcome()) }
        } catch (e: IllegalArgumentException) {
            // A SerializationException (a field missing or of another type), or a field out of
            // range as the stored types refuse it.
            null
        }

        fun stored(entry: HistoryEntry) = StoredEntry(
            JSON.encodeToJsonElement(StoredTransfer.serializer(), storedTransfer(entry.transfer)),
            when (val outcome = entry.outcome) {
                SentOutcome.NotSeen -> StoredOutcome(NOT_SEEN)
                SentOutcome.NonceUsed -> StoredOutcome(NONCE_USED)
                is SentOutcome.Included -> StoredOutcome(INCLUDED, outcome.blockNumber, outcome.succeeded)
                is SentOutcome.Finalized -> StoredOutcome(FINALIZED, outcome.blockNumber, outcome.succeeded)
            },
        )
    }
}
