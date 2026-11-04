package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TokenTransfers
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LogCoverage
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import eth.serotine.domain.repository.ReceiptLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [ObserveTokenTransfers] over a fake log index. */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenTransferHistoriesTest {

    private val alice = Address.of("0x" + "11".repeat(20))
    private val bob = Address.of("0x" + "22".repeat(20))
    private val token = Address.of("0x" + "7c".repeat(20))
    private val index = RecordingLogIndex()
    private val starts = MemoryHistoryStarts(alice to HistoryStart.Fixed(9_000))

    private fun topic(address: Address) = "0x" + "00".repeat(12) + address.hex.removePrefix("0x")

    private fun transfer(from: Address, to: Address, logIndex: Int) = IndexedLog(
        ReceiptLog(token, listOf(TokenTransfers.TRANSFER, topic(from), topic(to)), "0x" + "1".padStart(64, '0')),
        9_100,
        "0x" + "bb".repeat(32),
        "0x" + "cc".repeat(32),
        logIndex,
    )

    @Test
    fun itsTransfersAreReadAtBothPositionsOnceEachAndToItselfOnce() = runTest {
        val sent = transfer(alice, bob, 0)
        val received = transfer(bob, alice, 1)
        val itself = transfer(alice, alice, 2)
        index.answer = { query ->
            LogsReading.Logs(if (query.position == 1) listOf(sent, itself) else listOf(received, itself))
        }
        index.coverage.value = LogCoverageReading.Read(
            mapOf(LogWatchKey.Mention(alice) to LogCoverage(9_000, 9_000L..9_200L)),
        )
        val seen = mutableListOf<IndexedHistory<TokenTransferEvent>>()
        backgroundScope.launch { ObserveTokenTransfers(index, starts)(alice.onSepolia).collect { seen += it } }
        runCurrent()

        val key = LogWatchKey.Mention(alice)
        assertEquals(
            listOf(
                LogQuery(key, 9_000, 9_200, listOf(TokenTransfers.TRANSFER), position = 1),
                LogQuery(key, 9_000, 9_200, listOf(TokenTransfers.TRANSFER), position = 2),
            ),
            index.queries,
        )
        val one = UInt256.of(1)
        assertEquals(
            listOf(
                TokenTransfer.Fungible(token, alice, bob, one),
                TokenTransfer.Fungible(token, bob, alice, one),
                TokenTransfer.Fungible(token, alice, alice, one),
            ),
            (seen.last() as IndexedHistory.Read).events.map { it.transfer },
        )
    }
}
