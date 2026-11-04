package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptLog
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionAdaptersTest {

    private val chain = FakeTransactingChain()
    private val alice = Address.of("0x" + "aa".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))

    private fun TestScope.inputs() = EngineTransactionInputs(
        Network.SEPOLIA,
        chain,
        StandardTestDispatcher(testScheduler),
    )

    private fun synced(finalized: Long = 0) {
        chain.base.snapshot = running(head = 100).copy(finalizedBlock = finalized)
    }

    @Test
    fun aFeeHistoryBecomesLevelsFromTheMedianOfEachPercentile() {
        val json = """
            {"oldestBlock":"0x5a","baseFeePerGas":["0x10","0x12","0x14","0x16"],
             "gasUsedRatio":[0.5,0.6,0.7],
             "reward":[["0x1","0x5","0x9"],["0x3","0x2","0x20"],["0x2","0x7","0x8"]]}
        """.trimIndent()

        val quote = EngineJson.feeQuote(json)!!

        assertEquals(UInt256.of(0x16), quote.nextBaseFee, "the base fee after the newest block")
        assertEquals(0x5aL + 2, quote.blockNumber)
        // Medians 2, 5 and 9, each raised to the 0.001 gwei floor; a level never below the slower one.
        assertEquals(EngineJson.MIN_TIP, quote.tip(FeeLevel.SLOW))
        assertEquals(EngineJson.MIN_TIP, quote.tip(FeeLevel.NORMAL))
        assertEquals(EngineJson.MIN_TIP, quote.tip(FeeLevel.FAST))
        assertEquals(UInt256.of(0x16 * 2) + EngineJson.MIN_TIP, quote.maxFeePerGas(FeeLevel.FAST))
    }

    @Test
    fun eachLevelsTipIsTheMedianOfItsPercentileOverTheBlocks() {
        val gwei = 1_000_000_000L
        fun hex(n: Long) = "\"0x" + n.toString(16) + "\""
        val blocks = listOf(listOf(1, 4, 9), listOf(3, 2, 7), listOf(2, 6, 8)).joinToString(",") { row ->
            "[" + row.joinToString(",") { hex(it * gwei) } + "]"
        }
        val json = """{"oldestBlock":"0x1","baseFeePerGas":[${(1..4).joinToString(",") { hex(gwei) }}],
            "reward":[$blocks]}"""

        val quote = EngineJson.feeQuote(json)!!

        // Medians: 2 of (1, 3, 2), 4 of (4, 2, 6), 8 of (9, 7, 8): neither the least nor the most.
        assertEquals(UInt256.of(2 * gwei), quote.tip(FeeLevel.SLOW))
        assertEquals(UInt256.of(4 * gwei), quote.tip(FeeLevel.NORMAL))
        assertEquals(UInt256.of(8 * gwei), quote.tip(FeeLevel.FAST))
    }

    @Test
    fun aFeeHistoryWithoutItsRewardsIsNoAnswer() {
        assertNull(EngineJson.feeQuote("""{"oldestBlock":"0x1","baseFeePerGas":["0x1","0x1"]}"""))
        // Two blocks' base fees, one block's rewards: not the history that was asked for.
        val short = """{"oldestBlock":"0x1","baseFeePerGas":["0x1","0x1","0x1"],"reward":[["0x1","0x2","0x3"]]}"""
        assertNull(EngineJson.feeQuote(short))
    }

    @Test
    fun aFeeHistoryWithRealTipsKeepsThemInOrder() {
        val gwei = 1_000_000_000L
        fun hex(n: Long) = "\"0x" + n.toString(16) + "\""
        val json = """{"oldestBlock":"0x1","baseFeePerGas":[${hex(gwei)},${hex(gwei)}],
            "reward":[[${hex(3 * gwei)},${hex(2 * gwei)},${hex(5 * gwei)}]]}"""

        val quote = EngineJson.feeQuote(json)!!

        assertEquals(UInt256.of(3 * gwei), quote.tip(FeeLevel.SLOW))
        assertEquals(UInt256.of(3 * gwei), quote.tip(FeeLevel.NORMAL), "raised to the slower level's")
        assertEquals(UInt256.of(5 * gwei), quote.tip(FeeLevel.FAST))
    }

    @Test
    fun aMalformedFeeHistoryIsNoAnswer() {
        assertNull(EngineJson.feeQuote("""{"oldestBlock":"0x1","baseFeePerGas":["12"]}"""))
        assertNull(EngineJson.feeQuote("""{"baseFeePerGas":["0x1"]}"""))
        assertNull(EngineJson.feeQuote("not json"))
    }

    @Test
    fun aReceiptSaysItsBlockAndWhetherItSucceeded() {
        assertEquals(
            EngineJson.Receipt(0x1234, succeeded = true),
            EngineJson.receipt("""{"blockNumber":"0x1234","status":"0x1"}"""),
        )
        assertEquals(
            EngineJson.Receipt(7, succeeded = false),
            EngineJson.receipt("""{"blockNumber":"0x7","status":"0x0"}"""),
        )
        assertNull(EngineJson.receipt("null"))
    }

    @Test
    fun aReceiptsLogsAreReadWholeOrNotAtAll() {
        val topic = "0x" + "DD".repeat(32)
        val withLogs = EngineJson.receipt(
            """{"blockNumber":"0x7","status":"0x1","logs":[{"address":"0x7777777777777777777777777777777777777777",""" +
                """"topics":["$topic"],"data":"0x01"}]}""",
        )
        assertEquals(
            listOf(ReceiptLog(Address.of("0x" + "77".repeat(20)), listOf(topic.lowercase()), "0x01")),
            withLogs?.logs,
        )
        // A malformed log: the verdict stands, the logs are not read.
        val malformed = EngineJson.receipt(
            """{"blockNumber":"0x7","status":"0x1","logs":[{"address":"0x77","topics":[],"data":"0x"}]}""",
        )
        assertEquals(EngineJson.Receipt(7, succeeded = true, logs = null), malformed)
        assertEquals(emptyList(), EngineJson.receipt("""{"blockNumber":"0x7","status":"0x1","logs":[]}""")?.logs)
    }

    @Test
    fun aColdFeeHistoryIsAskedAgainUntilItIsBuilt() = runTest {
        synced()
        val history = """{"oldestBlock":"0x1","baseFeePerGas":["0x10","0x10"],"reward":[["0x1","0x2","0x3"]]}"""
        // Nothing twice (the engine still building its history), then a history.
        var answers = 0
        chain.feeAnswer = { if (++answers <= 2) null else history }
        val inputs = EngineTransactionInputs(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            timeSource = testScheduler.timeSource,
        )

        val reading = async { inputs.fees(Network.SEPOLIA) }
        advanceTimeBy(4_999)
        runCurrent()
        assertFalse(reading.isCompleted, "asked again every 2.5 s")
        advanceTimeBy(2)
        runCurrent()
        assertIs<FeeReading.Quoted>(reading.await())
        assertEquals(3, answers)
    }

    @Test
    fun aFeeHistoryStillColdAfterFifteenSecondsIsUnavailable() = runTest {
        synced()
        var answers = 0
        chain.feeAnswer = {
            answers++
            null
        }
        val inputs = EngineTransactionInputs(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            timeSource = testScheduler.timeSource,
        )

        assertEquals(FeeReading.Unavailable(ReadUnavailable.UNPROVEN), inputs.fees(Network.SEPOLIA))
        // At 0, 2.5, 5, 7.5 and 10 s: none at 12.5, which the engine's own 5 s wait could carry past 15.
        assertEquals(5, answers)
        assertTrue(testScheduler.currentTime <= 15_000)

        // An engine that cannot verify reads is said at once, not waited for.
        chain.base.snapshot = EngineSnapshot.Down
        answers = 0
        assertEquals(FeeReading.Unavailable(ReadUnavailable.NO_ENGINE), inputs.fees(Network.SEPOLIA))
        assertEquals(0, answers)
    }

    @Test
    fun inputsAreAskedOnlyWhileReadsCanBeVerified() = runTest {
        chain.base.snapshot = EngineSnapshot.Booting
        chain.nonce = 7
        assertEquals(NonceReading.Unavailable(ReadUnavailable.SYNCING), inputs().nonce(Network.SEPOLIA, alice))
        assertEquals(0, chain.asked, "not asked at all before reads can be verified")
        chain.base.snapshot = EngineSnapshot.Down
        assertEquals(FeeReading.Unavailable(ReadUnavailable.NO_ENGINE), inputs().fees(Network.SEPOLIA))
        assertEquals(
            NonceReading.Unavailable(ReadUnavailable.NO_ENGINE),
            EngineTransactionInputs(
                Network.SEPOLIA,
                null,
                StandardTestDispatcher(testScheduler),
            ).nonce(Network.SEPOLIA, alice),
        )

        synced()
        chain.nonce = 7
        assertEquals(NonceReading.Read(7), inputs().nonce(Network.SEPOLIA, alice))
    }

    @Test
    fun anEstimateAndASimulationSayWhatTheEngineFound() = runTest {
        synced()
        val value = UInt256.of(5)
        chain.estimate = EstimateAnswer.Gas(21_000)
        assertEquals(
            GasEstimate.Estimated(21_000),
            inputs().estimateGas(Network.SEPOLIA, alice, bob, value, ByteArray(0)),
        )
        chain.estimate = EstimateAnswer.Reverted("08c379a0")
        assertEquals(
            GasEstimate.Reverted("08c379a0"),
            inputs().estimateGas(Network.SEPOLIA, alice, bob, value, ByteArray(0)),
        )
        chain.estimate = EstimateAnswer.Infeasible("insufficient funds for transfer")
        assertEquals(
            GasEstimate.Infeasible("insufficient funds for transfer"),
            inputs().estimateGas(Network.SEPOLIA, alice, bob, value, ByteArray(0)),
        )

        chain.call = CallOutcome.Returned("01", 99)
        assertEquals(
            Simulation.Succeeded("01", 99),
            inputs().simulate(Network.SEPOLIA, alice, bob, value, ByteArray(0)),
        )
        chain.call = CallOutcome.Reverted(99)
        assertEquals(Simulation.Reverted(99), inputs().simulate(Network.SEPOLIA, alice, bob, value, ByteArray(0)))
        chain.call = null
        assertEquals(
            Simulation.Unavailable(ReadUnavailable.UNPROVEN),
            inputs().simulate(Network.SEPOLIA, alice, bob, value, ByteArray(0)),
        )
    }

    @Test
    fun aNonPositiveGasOrANegativeNonceIsNoAnswer() = runTest {
        synced()
        chain.estimate = EstimateAnswer.Gas(0)
        assertEquals(
            GasEstimate.Unavailable(ReadUnavailable.UNPROVEN),
            inputs().estimateGas(Network.SEPOLIA, alice, bob, UInt256.ONE, ByteArray(0)),
        )
        chain.nonce = -1
        assertEquals(NonceReading.Unavailable(ReadUnavailable.UNPROVEN), inputs().nonce(Network.SEPOLIA, alice))
    }

    @Test
    fun theMinedCountIsItsOwnVerifiedRead() = runTest {
        synced()
        chain.nonce = 9
        chain.mined = 7
        assertEquals(NonceReading.Read(7), inputs().minedNonce(Network.SEPOLIA, alice), "latest, not the pending count")
        chain.mined = null
        assertEquals(NonceReading.Unavailable(ReadUnavailable.UNPROVEN), inputs().minedNonce(Network.SEPOLIA, alice))
        chain.mined = -1
        assertEquals(NonceReading.Unavailable(ReadUnavailable.UNPROVEN), inputs().minedNonce(Network.SEPOLIA, alice))
        chain.base.snapshot = EngineSnapshot.Down
        assertEquals(NonceReading.Unavailable(ReadUnavailable.NO_ENGINE), inputs().minedNonce(Network.SEPOLIA, alice))
    }

    @Test
    fun anEngineThatFailsHasNoAnswerNotACrash() = runTest {
        synced()
        chain.failure = Error("native library broken")
        assertEquals(NonceReading.Unavailable(ReadUnavailable.UNPROVEN), inputs().nonce(Network.SEPOLIA, alice))
        // A CancellationException the engine's own code throws is a failure, not the end of the caller.
        chain.failure = CancellationException("thrown by the engine")
        assertEquals(FeeReading.Unavailable(ReadUnavailable.UNPROVEN), inputs().fees(Network.SEPOLIA))
    }

    @Test
    fun theBroadcastVerdictIsPassedOn() = runTest {
        val broadcaster = EngineBroadcaster(Network.SEPOLIA, chain, StandardTestDispatcher(testScheduler))
        chain.send = SendAnswer.Sent("0x" + "ab".repeat(32))
        assertEquals(Broadcast.Sent("0x" + "ab".repeat(32)), broadcaster.send(Network.SEPOLIA, byteArrayOf(2, 1)))
        chain.send = SendAnswer.Rejected("nonce too low: next nonce 8, tx nonce 7")
        assertEquals(
            Broadcast.Rejected("nonce too low: next nonce 8, tx nonce 7"),
            broadcaster.send(Network.SEPOLIA, byteArrayOf(2)),
        )
        chain.failure = Error("native library broken")
        assertEquals(
            Broadcast.Unavailable("the light client failed (Error)"),
            broadcaster.send(Network.SEPOLIA, byteArrayOf(2)),
        )
        assertEquals(
            Broadcast.Unavailable("no light client on this device"),
            EngineBroadcaster(
                Network.SEPOLIA,
                null,
                StandardTestDispatcher(testScheduler),
            ).send(Network.SEPOLIA, byteArrayOf(2)),
        )
    }

    @Test
    fun aReceiptIsWatchedFromPendingToIncludedToFinalizedAndThenNoMore() = runTest {
        synced(finalized = 50)
        val receipts = EngineReceipts(Network.SEPOLIA, chain, StandardTestDispatcher(testScheduler), 4.seconds)
        val seen = mutableListOf<TransactionProgress>()
        val watching = launch { receipts.observe(Network.SEPOLIA, "0x" + "cd".repeat(32)).toList(seen) }

        chain.receipt = "null"
        runCurrent()
        chain.receipt = """{"blockNumber":"0x60","status":"0x1"}"""
        advanceTimeBy(4_001)
        synced(finalized = 0x60)
        advanceTimeBy(4_001)
        advanceTimeBy(8_001)

        assertEquals(
            listOf(
                TransactionProgress.Pending,
                TransactionProgress.Included(0x60, succeeded = true),
                TransactionProgress.Finalized(0x60, succeeded = true),
            ),
            seen,
        )
        assertEquals(true, watching.isCompleted, "finalized: nothing more to watch")
    }

    @Test
    fun aReceiptThatCannotBeReadIsNotVerifiedForThatPoll() = runTest {
        synced()
        val receipts = EngineReceipts(Network.SEPOLIA, chain, StandardTestDispatcher(testScheduler), 4.seconds)
        val seen = mutableListOf<TransactionProgress>()
        val watching = launch { receipts.observe(Network.SEPOLIA, "0x" + "cd".repeat(32)).toList(seen) }

        chain.receipt = null
        runCurrent()
        chain.receipt = "null"
        advanceTimeBy(4_001)

        assertEquals(
            listOf(TransactionProgress.Unavailable(ReadUnavailable.UNPROVEN), TransactionProgress.Pending),
            seen,
        )
        watching.cancel()
    }

    @Test
    fun aReceiptErrorIsNotVerifiedAndADownEngineEndsTheWatch() = runTest {
        synced()
        val receipts = EngineReceipts(Network.SEPOLIA, chain, StandardTestDispatcher(testScheduler), 4.seconds)
        val seen = mutableListOf<TransactionProgress>()
        val watching = launch { receipts.observe(Network.SEPOLIA, "0x" + "cd".repeat(32)).toList(seen) }

        chain.receipt = """{"error":"receipts root mismatch"}"""
        runCurrent()
        chain.base.snapshot = EngineSnapshot.Down
        advanceTimeBy(4_001)

        assertEquals(
            listOf<TransactionProgress>(
                TransactionProgress.Unavailable(ReadUnavailable.UNPROVEN),
                TransactionProgress.Unavailable(ReadUnavailable.NO_ENGINE),
            ),
            seen,
        )
        assertEquals(true, watching.isCompleted, "a down engine is final")
    }

    @Test
    fun aMalformedHashIsRefusedOnceNotPolled() = runTest {
        synced()
        val receipts = EngineReceipts(Network.SEPOLIA, chain, StandardTestDispatcher(testScheduler), 4.seconds)

        assertEquals(
            listOf<TransactionProgress>(TransactionProgress.Unavailable(ReadUnavailable.REFUSED)),
            receipts.observe(Network.SEPOLIA, "cd".repeat(32)).toList(),
        )
    }

    @Test
    fun aNameIsTakenOnlyAsFinalizedStateVerifiesIt() = runTest {
        synced()
        val names = EngineNames(Network.SEPOLIA, chain, StandardTestDispatcher(testScheduler))
        chain.name = NameAnswer(bob.hex, 90, verified = true, error = null)
        assertEquals(NameResolution.Resolved("serotine.eth", bob, 90), names.resolve(Network.SEPOLIA, " Serotine.ETH "))
        assertEquals(listOf("serotine.eth"), chain.resolved, "lower-cased before it is asked")

        chain.name = NameAnswer(bob.hex, 90, verified = false, error = null)
        assertEquals(
            NameResolution.Unavailable(ReadUnavailable.UNPROVEN),
            names.resolve(Network.SEPOLIA, "serotine.eth"),
        )
        chain.name = NameAnswer(null, 90, verified = true, error = null)
        assertEquals(NameResolution.NoAddress, names.resolve(Network.SEPOLIA, "serotine.eth"))
        chain.name = NameAnswer(null, -1, verified = false, error = "no snap peer")
        assertEquals(
            NameResolution.Unavailable(ReadUnavailable.UNPROVEN),
            names.resolve(Network.SEPOLIA, "serotine.eth"),
        )

        assertEquals(NameResolution.NotAName, names.resolve(Network.SEPOLIA, "serotine"))
        assertEquals(NameResolution.NotAName, names.resolve(Network.SEPOLIA, "a..eth"))
        assertEquals(NameResolution.NotAName, names.resolve(Network.SEPOLIA, "my name.eth"))
    }

    @Test
    fun aLookAlikeNameIsNotTakenAndAnOffchainOneIsSaid() = runTest {
        synced()
        val names = EngineNames(Network.SEPOLIA, chain, StandardTestDispatcher(testScheduler))
        chain.name = NameAnswer(bob.hex, 90, verified = true, error = null)

        // A Cyrillic "а", and a zero-width joiner: they hash to other nodes than they look like.
        assertEquals(NameResolution.NotAName, names.resolve(Network.SEPOLIA, "vit\u0430lik.eth"))
        assertEquals(NameResolution.NotAName, names.resolve(Network.SEPOLIA, "vitalik\u200d.eth"))
        assertEquals(NameResolution.NotAName, names.resolve(Network.SEPOLIA, "-vitalik.eth"))
        assertEquals(emptyList<String>(), chain.resolved, "none of them asked")
        assertEquals(NameResolution.Resolved("my-name2.eth", bob, 90), names.resolve(Network.SEPOLIA, "my-name2.eth"))

        chain.name = NameAnswer(null, -1, verified = false, error = "resolves offchain (ERC-3668)", offchain = true)
        assertEquals(NameResolution.OffchainOnly, names.resolve(Network.SEPOLIA, "dark.eth"))
    }
}
