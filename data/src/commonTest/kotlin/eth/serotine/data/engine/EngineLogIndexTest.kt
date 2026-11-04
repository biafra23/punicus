package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LogCoverage
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatch
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/** [EngineLogIndex] against the engine's log-index JSON (Myotis `myotis-engine/src/host.rs`). */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineLogIndexTest {

    private val alice = Address.of("0x" + "11".repeat(20))
    private val vault = Address.of("0x" + "5a".repeat(20))
    private val token = Address.of("0x" + "7c".repeat(20))
    private val alicesTopic = "0x" + "00".repeat(12) + "11".repeat(20)
    private val transfer = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

    /** An engine following Alice's mentions from block 100 and the vault's events from block 16. */
    private val chain = FakeLogIndexChain().apply {
        base.snapshot = running()
        status = """{"enabled":true,"logCount":0,"entries":[""" +
            """{"topic":"$alicesTopic","positions":[1,2,3],"fromBlock":100},""" +
            """{"address":"${vault.hex}","fromBlock":16}]}"""
    }

    private fun TestScope.index(chain: LogIndexChain? = this@EngineLogIndexTest.chain) =
        EngineLogIndex(Network.SEPOLIA, chain, StandardTestDispatcher(testScheduler), 4.seconds)

    @Test
    fun aChangeIsOneConfigWithAnAddressEntryAndATopicEntryAtAllThreePositions() = runTest {
        val taken = index().configure(
            Network.SEPOLIA,
            listOf(
                LogWatch(LogWatchKey.Mention(alice), 9_400_000),
                LogWatch(LogWatchKey.Emitter(vault), 9_500_000),
                LogWatch(LogWatchKey.Emitter(vault), 9_450_000),
            ),
            unwatch = listOf(LogWatchKey.Mention(token), LogWatchKey.Emitter(token)),
        )

        assertTrue(taken)
        assertEquals(
            Json.parseToJsonElement(
                """{"enabled":true,"watch":[""" +
                    """{"topic":"$alicesTopic","positions":[1,2,3],"fromBlock":9400000},""" +
                    """{"address":"${vault.hex}","fromBlock":9450000}],""" +
                    """"unwatch":["0x${"00".repeat(12)}${"7c".repeat(20)}","${token.hex}"]}""",
            ),
            Json.parseToJsonElement(chain.configs.single()),
        )
    }

    @Test
    fun aChangeIsNotAskedWhileReadsCannotBeVerifiedAndIsNotTakenWhenTheEngineRefusesIt() = runTest {
        val watch = listOf(LogWatch(LogWatchKey.Mention(alice), 1))
        chain.base.snapshot = running(phase = EngineSnapshot.Phase.CATCHING_UP)
        assertFalse(index().configure(Network.SEPOLIA, watch, emptyList()))
        assertEquals(emptyList(), chain.configs)

        chain.base.snapshot = running()
        chain.takes = false
        assertFalse(index().configure(Network.SEPOLIA, watch, emptyList()))
        assertFalse(index(chain = null).configure(Network.SEPOLIA, watch, emptyList()))
        assertTrue(index().configure(Network.SEPOLIA, emptyList(), emptyList()), "nothing to ask")
    }

    @Test
    fun anEntryBothWatchedAndUnwatchedIsTheCallersError() = runTest {
        assertFailsWith<IllegalArgumentException> {
            index().configure(
                Network.SEPOLIA,
                listOf(LogWatch(LogWatchKey.Mention(alice), 1)),
                listOf(LogWatchKey.Mention(alice)),
            )
        }
    }

    @Test
    fun theCoverageOfTheEntriesItModelsAndNothingElse() = runTest {
        chain.status = """{"enabled":true,"logCount":3,"maxSpeed":false,"backfillPaused":false,"entries":[""" +
            """{"address":"${vault.hex}","fromBlock":9400000,"coveredLow":9450000,"coveredHigh":9500000},""" +
            """{"topic":"$alicesTopic","positions":[1,2,3],"fromBlock":9400000},""" +
            """{"topic":"$alicesTopic","positions":[1,2],"fromBlock":9400000},""" +
            """{"topic":"$transfer","positions":[1,2,3],"fromBlock":1},""" +
            """{"address":"${token.hex}","fromBlock":1,"coveredLow":1,"coveredHigh":2,"restricted":true}]}"""

        assertEquals(
            LogCoverageReading.Read(
                mapOf(
                    LogWatchKey.Emitter(vault) to LogCoverage(9_400_000, 9_450_000L..9_500_000L),
                    LogWatchKey.Mention(alice) to LogCoverage(9_400_000, null),
                ),
            ),
            index().observeCoverage(Network.SEPOLIA).first(),
        )

        chain.status = """{"error":"handle is paused"}"""
        assertEquals(LogCoverageReading.Unavailable, index().observeCoverage(Network.SEPOLIA).first())
        assertEquals(LogCoverageReading.Unavailable, index(chain = null).observeCoverage(Network.SEPOLIA).first())
    }

    @Test
    fun aMentionIsQueriedAtItsPositionAndAnEmitterByItsAddress() = runTest {
        chain.answer = "[]"
        index().logs(
            Network.SEPOLIA,
            LogQuery(LogWatchKey.Mention(alice), 9_400_000, 9_400_255, listOf(transfer), position = 2),
        )
        index().logs(Network.SEPOLIA, LogQuery(LogWatchKey.Emitter(vault), 16, 32))

        assertEquals(
            listOf(
                Json.parseToJsonElement(
                    """{"fromBlock":"0x8f6ec0","toBlock":"0x8f6fbf","topics":[["$transfer"],null,"$alicesTopic"]}""",
                ),
                Json.parseToJsonElement("""{"fromBlock":"0x10","toBlock":"0x20","address":"${vault.hex}"}"""),
            ),
            chain.filters.map(Json::parseToJsonElement),
        )
    }

    @Test
    fun servedLogsAreTakenOnlyWhenEachIsOneTheQuerySelects() = runTest {
        val query = LogQuery(LogWatchKey.Mention(alice), 100, 200, listOf(transfer), position = 2)
        fun log(block: Long, topic2: String = alicesTopic) =
            """{"address":"${token.hex}","topics":["$transfer","0x${"22".repeat(32)}","$topic2"],""" +
                """"data":"0x01","blockNumber":"0x${block.toString(16)}","blockHash":"0x${"ab".repeat(32)}",""" +
                """"transactionHash":"0x${"cd".repeat(32)}","transactionIndex":"0x0","logIndex":"0x3",""" +
                """"removed":false}"""

        chain.answer = "[${log(150)}]"
        val logs = assertIs<LogsReading.Logs>(index().logs(Network.SEPOLIA, query)).logs
        assertEquals(150, logs.single().blockNumber)
        assertEquals(3, logs.single().logIndex)
        assertEquals(token, logs.single().log.address)

        chain.answer = "[${log(150)},${log(201)}]"
        assertEquals(
            LogsReading.Unavailable(ReadUnavailable.UNPROVEN),
            index().logs(Network.SEPOLIA, query),
            "outside the range",
        )
        chain.answer = "[${log(150, topic2 = transfer)}]"
        assertEquals(
            LogsReading.Unavailable(ReadUnavailable.UNPROVEN),
            index().logs(Network.SEPOLIA, query),
            "not naming Alice",
        )
        chain.answer = "[{\"address\":\"${token.hex}\"}]"
        assertEquals(
            LogsReading.Unavailable(ReadUnavailable.UNPROVEN),
            index().logs(Network.SEPOLIA, query),
            "not a log",
        )
    }

    @Test
    fun aRangeBelowTheEntrysBlockOrOfAnEntryNotFollowedIsNotAskedOfTheEngine() = runTest {
        val belowAlices = LogQuery(LogWatchKey.Mention(alice), 99, 200, position = 1)
        assertEquals(LogsReading.BeforeStart(100), index().logs(Network.SEPOLIA, belowAlices))
        assertEquals(
            LogsReading.NotWatched,
            index().logs(Network.SEPOLIA, LogQuery(LogWatchKey.Emitter(alice), 100, 200)),
        )
        chain.status = """{"error":"handle is paused"}"""
        assertEquals(
            LogsReading.Unavailable(ReadUnavailable.UNPROVEN),
            index().logs(Network.SEPOLIA, LogQuery(LogWatchKey.Mention(alice), 100, 200, position = 1)),
        )

        assertEquals(emptyList(), chain.filters)
    }

    @Test
    fun theEnginesRefusalsAreReadForWhatTheySay() = runTest {
        val query = LogQuery(LogWatchKey.Emitter(vault), 100, 200)
        suspend fun answer(json: String): LogsReading {
            chain.answer = json
            return index().logs(Network.SEPOLIA, query)
        }

        assertEquals(
            LogsReading.NotCovered(150L..300L),
            answer("""{"error":"requested range is not indexed yet (covered: 150-300); retry as it catches up"}"""),
        )
        assertEquals(
            LogsReading.NotCovered(null),
            answer("""{"error":"log index has not indexed any blocks yet; retry"}"""),
        )
        assertEquals(LogsReading.NotWatched, answer("""{"error":"address is not on this node's log watch-list"}"""))
        assertEquals(
            LogsReading.Unavailable(ReadUnavailable.REFUSED),
            answer("""{"error":"malformed filter","code":-32602}"""),
        )
        assertEquals(LogsReading.Unavailable(ReadUnavailable.UNPROVEN), answer("""{"error":"node is not running"}"""))
        assertEquals(LogsReading.Unavailable(ReadUnavailable.UNPROVEN), answer("not json"))

        chain.answer = null
        assertEquals(LogsReading.Unavailable(ReadUnavailable.UNPROVEN), index().logs(Network.SEPOLIA, query))
        chain.base.snapshot = EngineSnapshot.Down
        assertEquals(LogsReading.Unavailable(ReadUnavailable.NO_ENGINE), index().logs(Network.SEPOLIA, query))
        assertEquals(
            LogsReading.Unavailable(ReadUnavailable.NO_ENGINE),
            index(chain = null).logs(Network.SEPOLIA, query),
        )
    }
}

/** A [LogIndexChain] over [base] whose log index a test sets, recording the configs and filters asked of it. */
internal class FakeLogIndexChain(val base: FakeChain = FakeChain()) : LogIndexChain, VerifiedChain by base {
    val configs = mutableListOf<String>()
    val filters = mutableListOf<String>()
    var takes = true
    var status: String? = """{"enabled":false,"logCount":0,"entries":[]}"""
    var answer: String? = "[]"

    override fun setLogIndexConfig(configJson: String): Boolean {
        configs += configJson
        return takes
    }

    override fun logIndexStatus(): String? = status

    override fun logs(filterJson: String): String? {
        filters += filterJson
        return answer
    }
}
