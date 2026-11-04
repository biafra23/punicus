package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.SafeEvent
import eth.serotine.domain.model.SafeEvents
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LogCoverage
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [ObserveSafeHistory] over a fake log index. */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeHistoriesTest {

    private val vault = Address.of("0x" + "5a".repeat(20))
    private val alice = Address.of("0x" + "11".repeat(20))
    private val index = RecordingLogIndex()
    private val starts = MemoryHistoryStarts()
    private val seen = mutableListOf<IndexedHistory<SafeChainEvent>>()

    private val added = "0x9465fa0c962cc76958e6373a993326400c1c94f8be2fe3a952adfa7f60b2ea26"
    private val alicesTopic = "0x" + "00".repeat(12) + "11".repeat(20)

    private fun TestScope.observe() {
        backgroundScope.launch { ObserveSafeHistory(index, starts)(vault.onSepolia).collect { seen += it } }
        runCurrent()
    }

    private fun covered(range: LongRange?, fromBlock: Long = 9_000) {
        index.coverage.value =
            LogCoverageReading.Read(mapOf(LogWatchKey.Emitter(vault) to LogCoverage(fromBlock, range)))
    }

    private fun log(block: Long, topics: List<String>, index: Int = 0) =
        IndexedLog(ReceiptLog(vault, topics, "0x"), block, "0x" + "bb".repeat(32), "0x" + "cc".repeat(32), index)

    @Test
    fun aSafeWithoutAFixedStartIsNotFollowedYet() = runTest {
        starts.starts.value = mapOf(vault to HistoryStart.Noted(1))
        covered(9_000L..9_100L)
        observe()

        assertEquals(listOf<IndexedHistory<SafeChainEvent>>(IndexedHistory.NotFollowed), seen)
        assertEquals(emptyList(), index.queries)
    }

    @Test
    fun itsEventsAreReadFromItsStartOrTheCoveredLowUpToTheCoveredTop() = runTest {
        starts.starts.value = mapOf(vault to HistoryStart.Fixed(9_000))
        index.answer = { LogsReading.Logs(listOf(log(9_150, listOf(added, alicesTopic)), log(9_160, listOf(added)))) }
        covered(null)
        observe()
        covered(9_100L..9_200L)
        runCurrent()

        assertEquals(LogQuery(LogWatchKey.Emitter(vault), 9_100, 9_200, SafeEvents.TOPICS), index.queries.single())
        val read = seen.last() as IndexedHistory.Read
        assertEquals(
            listOf(SafeChainEvent(SafeEvent.OwnerAdded(alice), 9_150, "0x" + "cc".repeat(32), 0)),
            read.events,
            "a log that is no Safe event's shape is left out",
        )
        assertEquals(9_100L..9_200L, read.checked)
        assertEquals(false, read.complete)
        // Unavailable until the coverage first answers, then nothing covered to read yet.
        assertEquals(listOf(IndexedHistory.Unavailable, IndexedHistory.Checking(9_000)), seen.take(2))

        covered(8_900L..9_201L, fromBlock = 8_900)
        runCurrent()

        assertEquals(9_000L, index.queries.minOf { it.fromBlock }, "never below its start")
        assertEquals(true, (seen.last() as IndexedHistory.Read).complete)
    }

    @Test
    fun anAnswerTheSpanMovedUnderIsCheckingAndNoAnswerUnavailable() = runTest {
        starts.starts.value = mapOf(vault to HistoryStart.Fixed(9_000))
        index.answer = { LogsReading.NotCovered(null) }
        covered(9_000L..9_100L)
        observe()

        assertEquals(IndexedHistory.Checking(9_000), seen.last())

        index.coverage.value = LogCoverageReading.Unavailable
        runCurrent()

        assertEquals(IndexedHistory.Unavailable, seen.last())
    }

    @Test
    fun aMovedSpanIsReadOnlyWhereItGrewAndAFailedReadKeepsWhatWasRead() = runTest {
        starts.starts.value = mapOf(vault to HistoryStart.Fixed(9_000))
        val logs = listOf(log(9_150, listOf(added, alicesTopic)))
        index.answer = { query -> LogsReading.Logs(logs.filter { it.blockNumber in query.fromBlock..query.toBlock }) }
        covered(9_100L..9_200L)
        observe()
        covered(9_050L..9_210L)
        runCurrent()

        // The new blocks below, and the top 64 blocks read before again with the new ones above.
        assertEquals(
            listOf(9_100L..9_200L, 9_050L..9_099L, 9_137L..9_210L),
            index.queries.map { it.fromBlock..it.toBlock },
        )
        val grown = seen.last() as IndexedHistory.Read
        assertEquals(listOf(9_150L), grown.events.map { it.blockNumber })
        assertEquals(9_050L..9_210L, grown.checked)

        index.answer = { LogsReading.Unavailable(ReadUnavailable.UNPROVEN) }
        covered(9_050L..9_220L)
        runCurrent()

        assertEquals(grown, seen.last(), "kept")

        covered(9_050L..9_190L)
        runCurrent()

        assertEquals(9_050L..9_190L, index.queries.last().let { it.fromBlock..it.toBlock }, "shrank: read again")
    }

    @Test
    fun anEventABlockReorganisedAwayIsDroppedThoughTheSpanOnlyLooksGrown() = runTest {
        starts.starts.value = mapOf(vault to HistoryStart.Fixed(9_000))
        var logs = listOf(log(9_190, listOf(added, alicesTopic)))
        index.answer = { query -> LogsReading.Logs(logs.filter { it.blockNumber in query.fromBlock..query.toBlock }) }
        covered(9_000L..9_200L)
        observe()
        // Between two polls the index rewound past block 9,190 and walked a chain without it.
        logs = emptyList()
        covered(9_000L..9_205L)
        runCurrent()

        assertEquals(emptyList(), (seen.last() as IndexedHistory.Read).events)
    }
}
