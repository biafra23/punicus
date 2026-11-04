package eth.serotine.data.engine

import eth.serotine.data.engine.Abi.RoundId
import eth.serotine.data.keys.toHex
import eth.serotine.data.prices.PinnedFeed
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.PastReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.kotlincrypto.hash.sha3.Keccak256

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedPriceHistoryTest {

    private val eth = PinnedFeed(PriceId("ETH"), Address.of("0x" + "69".repeat(20)), PriceId.USD, 8, 3_600)
    private val head = 1_000_000L
    private val chain = FakeChain().apply { snapshot = running(head = 9, headTimestamp = head) }

    /** The rounds' calls asked for, by round number, in order. */
    private val asked = mutableListOf<Long>()
    private val counting = object : VerifiedChain by chain {
        override fun call(to: Address, data: ByteArray): CallOutcome? {
            val hex = data.toHex()
            if (hex.startsWith(GET_ROUND_DATA)) asked += hex.takeLast(16).toLong(16)
            return chain.call(to, data)
        }
    }

    private fun TestScope.history() = VerifiedPriceHistory(
        Network.SEPOLIA,
        counting,
        StandardTestDispatcher(testScheduler),
        listOf(eth),
    )

    private fun word(n: Long) = n.toString(16).padStart(64, '0')

    private fun idWord(id: RoundId) =
        "0".repeat(44) + id.phase.toString(16).padStart(4, '0') + id.round.toString(16).padStart(16, '0')

    private fun roundHex(id: RoundId, answer: Long, updatedAt: Long) =
        idWord(id) + word(answer) + word(updatedAt) + word(updatedAt) + idWord(id)

    /** Rounds 1..[times].size of phase [phase], round n updated at times[n-1] and answering 100 × n (n µ$). */
    private fun rounds(times: List<Long>, phase: Int = 3) {
        chain.calls[eth.address to Abi.DECIMALS.toHex()] = CallOutcome.Returned(word(8), 9)
        times.forEachIndexed { index, time ->
            val id = RoundId(phase, index + 1L)
            val hex = roundHex(id, 100L * (index + 1), time)
            chain.calls[eth.address to Abi.getRoundData(id).toHex()] = CallOutcome.Returned(hex, 9)
            if (index == times.lastIndex) {
                chain.calls[eth.address to Abi.LATEST_ROUND_DATA.toHex()] = CallOutcome.Returned(hex, 9)
            }
        }
    }

    /** The round of each hour from 72 hours back, at five past: 72 rounds. */
    private val hourly = (0 until 72).map { head - (72 - it) * 3_600L + 300 }

    @Test
    fun theSelectorIsGetRoundDataOfAUint80() {
        assertEquals(GET_ROUND_DATA, Keccak256().digest("getRoundData(uint80)".encodeToByteArray()).toHex().take(8))
        assertEquals(
            GET_ROUND_DATA + "0".repeat(44) + "0003" + "0000000000000011",
            Abi.getRoundData(RoundId(3, 17)).toHex(),
        )
    }

    @Test
    fun aRoundIdSplitsIntoItsPhaseAndItsAggregatorsRound() {
        assertEquals(RoundId(3, 17), Abi.roundId(roundHex(RoundId(3, 17), 1, 1)))
        assertNull(Abi.roundId("1" + "0".repeat(319)), "more than 80 bits is no round id")
        assertNull(Abi.roundId(word(5)), "one word is no round")
    }

    @Test
    fun anHourlyFeedFindsTheRoundOfADayAgoInTwoCalls() = runTest {
        rounds(hourly)

        val reading = history().before(Network.SEPOLIA, eth.priceId, 86_400)

        // Round 48 was updated at 24 h 55 min before the head, round 49 at 23 h 55 min.
        assertEquals(PastReading.Verified(48e-6, updatedAt = hourly[47], stale = false), reading)
        assertEquals(listOf(48L, 49L), asked)
    }

    @Test
    fun aSecondSearchReadsTheRoundsItKeptNoMoreAgain() = runTest {
        rounds(hourly)
        val history = history()
        history.before(Network.SEPOLIA, eth.priceId, 86_400)
        val first = asked.toList()

        assertEquals(
            PastReading.Verified(48e-6, hourly[47], stale = false),
            history.before(Network.SEPOLIA, eth.priceId, 86_400),
        )
        assertEquals(first, asked)
    }

    @Test
    fun quickRoundsAreSearchedWithinTheBudget() = runTest {
        // A round every two minutes for three days, far quicker than the heartbeat suggests.
        val times = (0 until 2_160).map { head - (2_160 - it) * 120L + 30 }
        rounds(times)

        val reading = history().before(Network.SEPOLIA, eth.priceId, 86_400) as PastReading.Verified

        val expected = times.last { it <= head - 86_400 }
        assertEquals(expected, reading.updatedAt)
        assertTrue(asked.size <= VerifiedPriceHistory.MAX_ROUND_CALLS, "${asked.size} calls")
    }

    @Test
    fun burstsOfRoundsAreBisectedRatherThanCrawled() = runTest {
        // Two days hourly, then 1,000 rounds in the last hour.
        val times = (0 until 48).map { head - (49 - it) * 3_600L } + (0 until 1_000).map { head - 3_600L + it * 3 }
        rounds(times)

        val reading = history().before(Network.SEPOLIA, eth.priceId, 86_400) as PastReading.Verified

        assertEquals(times.last { it <= head - 86_400 }, reading.updatedAt)
        assertTrue(asked.size <= VerifiedPriceHistory.MAX_ROUND_CALLS, "${asked.size} calls")
    }

    @Test
    fun aFeedThatHasNotUpdatedSinceAnswersItsLatestRoundStaleOnceOlderThanItsHeartbeat() = runTest {
        rounds(listOf(head - 200_000, head - 90_000))

        assertEquals(
            PastReading.Verified(200e-8, head - 90_000, stale = false),
            history().before(Network.SEPOLIA, eth.priceId, 86_400),
        )
        assertTrue(asked.isEmpty())

        rounds(listOf(head - 200_000, head - 100_000))
        assertEquals(true, (history().before(Network.SEPOLIA, eth.priceId, 86_400) as PastReading.Verified).stale)
    }

    @Test
    fun aTimeBeforeThePhaseBeganHasNoRoundThatOld() = runTest {
        rounds((0 until 10).map { head - (10 - it) * 3_600L })

        assertEquals(PastReading.NoRoundThatOld, history().before(Network.SEPOLIA, eth.priceId, 86_400))
    }

    @Test
    fun aMissingRoundOrTimesGoingBackAreNoFeed() = runTest {
        rounds(hourly)
        chain.calls[eth.address to Abi.getRoundData(RoundId(3, 48)).toHex()] = CallOutcome.Reverted(9)

        assertEquals(
            PastReading.NoPrice(NoPriceReason.NOT_A_FEED),
            history().before(Network.SEPOLIA, eth.priceId, 86_400),
        )

        rounds(hourly)
        // Round 49 says it is older than round 48.
        chain.calls[eth.address to Abi.getRoundData(RoundId(3, 49)).toHex()] =
            CallOutcome.Returned(roundHex(RoundId(3, 49), 4_900, hourly[0]), 9)
        assertEquals(
            PastReading.NoPrice(NoPriceReason.NOT_A_FEED),
            history().before(Network.SEPOLIA, eth.priceId, 86_400),
        )
    }

    @Test
    fun aRoundAnsweringZerosIsMissingAsAnUnwrittenOneIs() = runTest {
        rounds(hourly)
        chain.calls[eth.address to Abi.getRoundData(RoundId(3, 48)).toHex()] =
            CallOutcome.Returned("0".repeat(320), 9)

        assertEquals(
            PastReading.NoPrice(NoPriceReason.NOT_A_FEED),
            history().before(Network.SEPOLIA, eth.priceId, 86_400),
        )
    }

    @Test
    fun changedDecimalsAreNoPriceAndAnUnprovenRoundUnavailable() = runTest {
        rounds(hourly)
        chain.calls[eth.address to Abi.DECIMALS.toHex()] = CallOutcome.Returned(word(18), 9)
        assertEquals(
            PastReading.NoPrice(NoPriceReason.DECIMALS_CHANGED),
            history().before(Network.SEPOLIA, eth.priceId, 86_400),
        )

        rounds(hourly)
        chain.calls.remove(eth.address to Abi.getRoundData(RoundId(3, 49)).toHex())
        assertEquals(
            PastReading.Unavailable(ReadUnavailable.UNPROVEN),
            history().before(Network.SEPOLIA, eth.priceId, 86_400),
        )
    }

    @Test
    fun withoutAReadableHeadItSaysWhy() = runTest {
        rounds(hourly)
        chain.snapshot = running(head = 9, headTimestamp = head, headAgeMs = 60_000)
        assertEquals(PastReading.Syncing, history().before(Network.SEPOLIA, eth.priceId, 86_400))

        chain.snapshot = running(head = 9, headTimestamp = null)
        assertEquals(PastReading.Syncing, history().before(Network.SEPOLIA, eth.priceId, 86_400))

        // Still starting, or catching up: checking, so the 24-hour change waits for it.
        chain.snapshot = EngineSnapshot.Booting
        assertEquals(PastReading.Syncing, history().before(Network.SEPOLIA, eth.priceId, 86_400))
        chain.snapshot = running(phase = EngineSnapshot.Phase.CATCHING_UP, head = 9, headTimestamp = head)
        assertEquals(PastReading.Syncing, history().before(Network.SEPOLIA, eth.priceId, 86_400))

        chain.snapshot = running(phase = EngineSnapshot.Phase.STALE_ANCHOR, head = 9, headTimestamp = head)
        assertEquals(
            PastReading.Unavailable(ReadUnavailable.STALE_ANCHOR),
            history().before(Network.SEPOLIA, eth.priceId, 86_400),
        )

        chain.snapshot = EngineSnapshot.Down
        assertEquals(
            PastReading.Unavailable(ReadUnavailable.NO_ENGINE),
            history().before(Network.SEPOLIA, eth.priceId, 86_400),
        )

        chain.snapshot = EngineSnapshot.Failed
        assertEquals(
            PastReading.Unavailable(ReadUnavailable.NO_ENGINE),
            history().before(Network.SEPOLIA, eth.priceId, 86_400),
        )

        // A network that is off is not "checking": it says so, as every other read does.
        chain.snapshot = EngineSnapshot.Stopped(off = true)
        assertEquals(
            PastReading.Unavailable(ReadUnavailable.NETWORK_OFF),
            history().before(Network.SEPOLIA, eth.priceId, 86_400),
        )

        chain.failure = IllegalStateException("engine")
        assertEquals(
            PastReading.Unavailable(ReadUnavailable.UNPROVEN),
            history().before(Network.SEPOLIA, eth.priceId, 86_400),
        )
    }

    @Test
    fun aPriceIdWithoutAFeedOrADeviceWithoutAnEngine() = runTest {
        assertEquals(
            PastReading.NoPrice(NoPriceReason.NO_FEED),
            history().before(Network.SEPOLIA, PriceId("UNI"), 86_400),
        )
        val none = VerifiedPriceHistory(Network.SEPOLIA, null, StandardTestDispatcher(testScheduler), listOf(eth))
        assertEquals(
            PastReading.Unavailable(ReadUnavailable.NO_ENGINE),
            none.before(Network.SEPOLIA, eth.priceId, 86_400),
        )
    }

    private companion object {
        const val GET_ROUND_DATA = "9a6fc8f5"
    }
}
