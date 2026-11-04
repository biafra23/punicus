package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.GPv2Settlement
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.CowVenueReading
import eth.serotine.domain.repository.LightClientRun
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatch
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Part 0's spike again, on the real engine and the real Sepolia (docs/swaps.md, *Proving an order's
 * end*): the pinned CoW contracts check against the chain's code, the finalized block comes with
 * its header's timestamp, and the log index serves a real CoW `Trade` of an owner it follows, which
 * decodes to the order's UID, while a range from before the entry's start is refused. Needs the
 * network and the engine's host library, so it is skipped unless run with
 * `-PmyotisLibraryDir=<dir with libmyotis_engine>` (data/build.gradle.kts), e.g.
 * `./gradlew :data:desktopTest --tests '*CowOrderEndSepoliaTest*' -PmyotisLibraryDir=…`. It
 * backfills the index some 10 blocks deep, so give it minutes.
 */
class CowOrderEndSepoliaTest {

    // A real CoW trade on Sepolia: its owner, the block its Trade landed in, its UID.
    private val owner = Address.of("0x9fa3c00a92ec5f96b1ad2527ab41b3932efeda58")
    private val tradeBlock = 11_968_860L
    private val uid = CowOrderUid.of(
        "0xacb9d41182bc7e7aba0f333a517d842ec8298235fdbcb9290d519a2c63457a4a" +
            "9fa3c00a92ec5f96b1ad2527ab41b3932efeda58" + "6ad9e7f4",
    )

    @Test
    fun theIndexProvesARealTradeAndTheFinalizedBlockCarriesItsTime(): Unit = runBlocking {
        assumeTrue("needs -PmyotisLibraryDir", System.getProperty("serotine.myotisLibraryDir") != null)
        val node = MyotisHost.start(Files.createTempDirectory("myotis-cow").toFile())
        node.set(Network.SEPOLIA, LightClientRun.RUN)
        val started = System.nanoTime()
        fun at() = "${(System.nanoTime() - started) / 1_000_000_000}s"
        val sepolia = Network.SEPOLIA
        val progress = launch {
            node.lightClient.observeStatus(sepolia).collect { println("[${at()}] light client: $it") }
        }
        try {
            val synced = withTimeout(10.minutes) {
                node.lightClient.observeStatus(sepolia).first { (it as? LightClientStatus.Synced)?.finalized != null }
            }
            synced as LightClientStatus.Synced
            val finalized = assertNotNull(synced.finalized)
            println("[${at()}] finalized block ${finalized.number} at ${finalized.timestamp}, head ${synced.headBlock}")
            assertTrue(finalized.timestamp > uid.validTo, "Sepolia's finalized block is past the order's validTo")
            // Its own header's time, not the head's: finality trails the head by two epochs or so.
            assertTrue(finalized.number < synced.headBlock, "a finalized block below the head")
            assertTrue(finalized.timestamp < assertNotNull(synced.headTimestamp), "the finalized block's own time")

            val venue = withTimeout(5.minutes) {
                var reading: CowVenueReading
                do {
                    reading = node.cow.venue(Network.SEPOLIA)
                    if (reading == CowVenueReading.Unavailable(ReadUnavailable.UNPROVEN)) delay(2.seconds)
                } while (reading == CowVenueReading.Unavailable(ReadUnavailable.UNPROVEN))
                reading
            }
            val deployment = assertIs<CowVenueReading.Verified>(venue, "the pins check against the chain").deployment

            val key = LogWatchKey.Mention(owner)
            val from = tradeBlock - 10
            withTimeout(5.minutes) {
                while (!node.logIndex.configure(sepolia, listOf(LogWatch(key, from)), emptyList())) delay(2.seconds)
            }
            withTimeout(30.minutes) {
                node.logIndex.observeCoverage(sepolia).first { reading ->
                    val covered = (reading as? LogCoverageReading.Read)?.entries?.get(key)?.covered
                    println("[${at()}] coverage: $covered")
                    covered != null && covered.first <= from && covered.last >= tradeBlock
                }
            }
            val topics = listOf(GPv2Settlement.TRADE_TOPIC, GPv2Settlement.ORDER_INVALIDATED_TOPIC)
            val reading = node.logIndex.logs(sepolia, LogQuery(key, from, tradeBlock, topics, position = 1))
            println("[${at()}] logs: $reading")
            val trades = assertIs<LogsReading.Logs>(reading).logs.mapNotNull {
                GPv2Settlement.tradeOf(deployment.settlement, it.log.address, it.log.topics, it.log.data)
                    ?.let { trade -> trade to it.blockNumber }
            }
            val (trade, block) = trades.single { it.first.uid == uid }
            assertEquals(owner, trade.owner)
            assertEquals(tradeBlock, block)

            val before = node.logIndex.logs(sepolia, LogQuery(key, from - 5, tradeBlock, topics, position = 1))
            assertEquals(LogsReading.BeforeStart(from), before, "never an empty list for blocks it never read")
        } finally {
            progress.cancel()
            node.shutdown()
        }
    }
}
