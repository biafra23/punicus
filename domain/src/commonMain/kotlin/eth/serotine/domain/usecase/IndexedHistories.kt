package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.HistoryStartList
import eth.serotine.domain.repository.HistoryStarts
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest

/** One event of an account's history read from the log index: where it is on chain. */
interface IndexedEvent {
    /** The block it is in. */
    val blockNumber: Long

    /** The transaction that logged it (`0x` and 64 lower-case hex digits). */
    val transactionHash: String

    /** Its log's place in the block. */
    val logIndex: Int
}

/** An account's history as the log index holds it (issue #85): its events, or why there are none. */
sealed interface IndexedHistory<out E : IndexedEvent> {

    /** No block its history starts at is fixed yet: once the light client is synced, it is. */
    data object NotFollowed : IndexedHistory<Nothing>

    /** It is followed from block [start], and the index has no part of that span to read yet. */
    data class Checking(val start: Long) : IndexedHistory<Nothing>

    /**
     * Its [events] in blocks [checked], in block order: every one of them in that span. It is
     * followed from block [start]; the blocks from [start] to below [checked] are still being walked.
     */
    data class Read<out E : IndexedEvent>(val events: List<E>, val start: Long, val checked: LongRange) :
        IndexedHistory<E> {
        /** Whether every block from its start on is checked. */
        val complete: Boolean
            get() = checked.first <= start
    }

    /** It cannot be read right now (the engine asleep or not running, the starts unreadable). */
    data object Unavailable : IndexedHistory<Nothing>
}

/**
 * The history of [key]'s entry in [index] (issue #85): the events [read] makes of the logs in a
 * span of blocks, from where the account's history starts ([HistoryStarts], kept by
 * `FollowAccountLogs`) up to the top of what the index covers of the entry, and never below either
 * end of that span.
 *
 * Read again each time the covered span moves (about every slot while the index follows the
 * head), only for the blocks it did not cover before and the top [REORG_DEPTH] blocks read before:
 * coverage is polled, so a rewind and its re-walk between two polls looks like growth, and the
 * blocks a reorganisation can still replace are read again rather than kept. A span that shrank
 * is read all again. A read that fails, or a coverage that cannot be had for a moment, keeps the
 * events read before rather than dropping them: they are verified, and what they cover is said
 * with them. [IndexedHistory.Unavailable] until the first coverage answers, so no one waits on
 * the engine.
 */
internal class IndexedReader<E : IndexedEvent>(
    private val network: Network,
    private val index: LogIndex,
    private val starts: HistoryStarts,
    private val key: LogWatchKey,
    /** The events of the logs [queries] of one span select; each query is asked of [index]. */
    private val queries: (LongRange) -> List<LogQuery>,
    private val decode: (IndexedLog) -> E?,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<IndexedHistory<E>> = flow {
        var last: IndexedHistory.Read<E>? = null
        val coverage = index.observeCoverage(network).onStart { emit(LogCoverageReading.Unavailable) }
        emitAll(
            combine(starts.observe(), coverage, ::spanOf)
                .distinctUntilChanged()
                .transformLatest { span ->
                    val shown = when (span) {
                        is Span.Ready -> read(span, last.takeIf { it?.start == span.start })
                        is Span.Other -> when (val history = span.history) {
                            IndexedHistory.NotFollowed -> history
                            IndexedHistory.Unavailable -> last ?: history
                            is IndexedHistory.Checking -> last.takeIf { it?.start == history.start } ?: history
                            is IndexedHistory.Read -> history
                        }
                    }
                    @Suppress("UNCHECKED_CAST") // Only this reader's own reads are ever Read.
                    last = (shown as? IndexedHistory.Read<E>) ?: last.takeIf { shown !== IndexedHistory.NotFollowed }
                    emit(shown)
                },
        )
    }

    private fun spanOf(held: HistoryStartList, coverage: LogCoverageReading): Span {
        val start = when (held) {
            HistoryStartList.Unreadable -> return Span.Other(IndexedHistory.Unavailable)
            is HistoryStartList.Loaded -> (held.starts[AccountId(network, key.address)] as? HistoryStart.Fixed)?.block
        } ?: return Span.Other(IndexedHistory.NotFollowed)
        val entries = (coverage as? LogCoverageReading.Read)?.entries ?: return Span.Other(IndexedHistory.Unavailable)
        val covered = entries[key]?.covered ?: return Span.Other(IndexedHistory.Checking(start))
        val from = maxOf(covered.first, start)
        if (from > covered.last) return Span.Other(IndexedHistory.Checking(start))
        return Span.Ready(start, from..covered.last)
    }

    /**
     * The events of [span]: those of [before] (read for the same start) kept, and only the blocks
     * it did not cover read, with its top [REORG_DEPTH] blocks read again; all of [span] when there
     * is no [before] or the span shrank. A read that does not answer keeps [before], or says why
     * there is none.
     */
    private suspend fun read(span: Span.Ready, before: IndexedHistory.Read<E>?): IndexedHistory<E> {
        val checked = span.checked
        val grown = before != null && checked.first <= before.checked.first && checked.last >= before.checked.last
        val keptBelow = if (before == null || !grown) {
            checked.first
        } else {
            maxOf(before.checked.first, before.checked.last - REORG_DEPTH + 1)
        }
        val ranges = if (before == null || !grown) {
            listOf(checked)
        } else {
            listOf(checked.first until before.checked.first, keptBelow..checked.last).filterNot { it.isEmpty() }
        }
        val kept = if (before != null && grown) before.events.filter { it.blockNumber < keptBelow } else emptyList()
        val read = ranges.flatMap(queries).map { query ->
            when (val answer = index.logs(network, query)) {
                is LogsReading.Logs -> answer.logs.mapNotNull(decode)
                // The span moved between the coverage and the query: the next coverage reads it again.
                is LogsReading.NotCovered, is LogsReading.BeforeStart, LogsReading.NotWatched ->
                    return before ?: IndexedHistory.Checking(span.start)
                is LogsReading.Unavailable -> return before ?: IndexedHistory.Unavailable
            }
        }
        val events = (read.flatten() + kept)
            .distinctBy { it.transactionHash to it.logIndex }
            .sortedWith(compareBy({ it.blockNumber }, { it.logIndex }))
        return IndexedHistory.Read(events, span.start, checked)
    }

    /** What is to be read: a [Ready] span, or an [Other] answer that needs no read. */
    private sealed interface Span {
        data class Ready(val start: Long, val checked: LongRange) : Span
        data class Other(val history: IndexedHistory<Nothing>) : Span
    }

    private companion object {
        /** The top blocks read again at each growth: two epochs, as deep as one not finalized reaches. */
        const val REORG_DEPTH = 64L
    }
}
