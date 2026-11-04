package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.data.prices.ChainlinkFeeds
import eth.serotine.data.prices.PinnedFeed
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedPriceFeedsTest {

    private val eth = PinnedFeed(PriceId("ETH"), Address.of("0x" + "69".repeat(20)), PriceId.USD, 8, 3_600)
    private val chain = FakeChain().apply { snapshot = running(head = 9, headTimestamp = 10_000) }

    private fun TestScope.feeds() =
        VerifiedPriceFeeds(Network.SEPOLIA, chain, StandardTestDispatcher(testScheduler), 4.seconds, listOf(eth))

    private fun word(n: Long) = n.toString(16).padStart(64, '0')

    /** `latestRoundData()`'s five words for [answer] updated at [updatedAt]. */
    private fun round(answer: String, updatedAt: Long) = word(7) + answer + word(0) + word(updatedAt) + word(7)

    private fun answer(decimals: CallOutcome, round: CallOutcome) {
        chain.calls[eth.address to Abi.DECIMALS.toHex()] = decimals
        chain.calls[eth.address to Abi.LATEST_ROUND_DATA.toHex()] = round
    }

    @Test
    fun aFreshRoundIsAPriceDatedByTheHeadsOwnTime() = runTest {
        answer(CallOutcome.Returned(word(8), 9), CallOutcome.Returned(round(word(242_930_491_033), 9_400), 9))

        assertEquals(
            FeedReading.Verified(2_429.30491033, updatedAt = 9_400, ageSeconds = 600, stale = false),
            feeds().observe(Network.SEPOLIA, eth.priceId).first { it !is FeedReading.Syncing },
        )
    }

    @Test
    fun aRoundOlderThanTheHeartbeatAndItsGraceIsStale() = runTest {
        answer(CallOutcome.Returned(word(8), 9), CallOutcome.Returned(round(word(100_000_000), 5_000), 9))

        val reading = feeds().observe(
            Network.SEPOLIA,
            eth.priceId,
        ).first { it !is FeedReading.Syncing } as FeedReading.Verified
        assertEquals(5_000, reading.ageSeconds)
        assertEquals(true, reading.stale)
    }

    @Test
    fun theSameRoundTurnsStaleAsTheVerifiedHeadMovesOn() = runTest {
        answer(CallOutcome.Returned(word(8), 9), CallOutcome.Returned(round(word(100_000_000), 9_400), 9))
        val feeds = feeds()
        suspend fun reading() = feeds.observe(
            Network.SEPOLIA,
            eth.priceId,
        ).first { it !is FeedReading.Syncing } as FeedReading.Verified
        assertEquals(false, reading().stale)

        // Past the heartbeat, but within the grace a heartbeat update takes to land: not stale.
        chain.snapshot = running(head = 310, headTimestamp = 13_100)
        val late = reading()
        assertEquals(3_700, late.ageSeconds)
        assertEquals(false, late.stale)

        // Past the grace too, and still no new round: stale.
        chain.snapshot = running(head = 400, headTimestamp = 13_100 + VerifiedPriceFeeds.STALE_GRACE_SECONDS)
        assertEquals(true, reading().stale)
    }


    private val aggregator = Address.of("0x" + "a9".repeat(20))

    /** A report through [to], covering both kinds unless told, with [touched] (address to block). */
    private fun report(to: Long, accountGaps: String = "[]", touched: List<Pair<Address, Long>> = emptyList()) =
        """{"from":1,"to":$to,"base":${AddressActivityTest.FakeActivity.BASE},"gaps":{"account":$accountGaps,"log":[]},""" +
            """"touched":[${touched.joinToString(",") { (a, b) ->
                """{"address":"${a.hex}","block":$b,"blockHash":"0x${"11".repeat(32)}","via":["log"]}"""
            }}],"watching":["${aggregator.hex}","${eth.address.hex}"]}"""

    /** The hash the fake follower holds for [block]: what a call at the canonical block names. */
    private fun hash(block: Long) = AddressActivityTest.FakeActivity().hashOf(block)

    /** The feed answers [price] (8 decimals) updated at [updatedAt], with every call at [block]. */
    private fun feedAt(block: Long, price: Long, updatedAt: Long) {
        answer(
            CallOutcome.Returned(word(8), block, hash(block)),
            CallOutcome.Returned(round(word(price), updatedAt), block, hash(block)),
        )
        chain.calls[eth.address to Abi.AGGREGATOR.toHex()] =
            CallOutcome.Returned("0".repeat(24) + aggregator.hex.removePrefix("0x"), block, hash(block))
    }

    @Test
    fun aKeptPriceIsReDatedAtEachHeadAndProvenAgainOnlyWhenItsFeedMayHaveChanged() = runTest {
        val activity = AddressActivityTest.FakeActivity().apply { report = report(to = 9) }
        val feeds = VerifiedPriceFeeds(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            4.seconds,
            listOf(eth),
            AddressActivity(activity),
        )
        suspend fun reading() =
            feeds.observe(Network.SEPOLIA, eth.priceId).first { it !is FeedReading.Syncing } as FeedReading.Verified
        feedAt(9, 100_000_000, 9_400)
        assertEquals(FeedReading.Verified(1.0, 9_400, ageSeconds = 600, stale = false), reading())

        // Head 10, nothing touched the feed: the proof at block 9 stands, re-dated at the new
        // head's time, though a call would now answer 2.
        feedAt(10, 200_000_000, 10_010)
        chain.snapshot = running(head = 10, headTimestamp = 10_012)
        activity.report = report(to = 10)
        assertEquals(FeedReading.Verified(1.0, 9_400, ageSeconds = 612, stale = false), reading())

        // Its aggregator emitted a log (a new round): proven again.
        chain.snapshot = running(head = 11, headTimestamp = 10_024)
        activity.report = report(to = 11, touched = listOf(aggregator to 11))
        assertEquals(FeedReading.Verified(2.0, 10_010, ageSeconds = 14, stale = false), reading())
    }

    @Test
    fun theProxysOwnStateChangingIsAChangeAndDecimalsMustBeReadAtTheRoundsBlock() = runTest {
        val activity = AddressActivityTest.FakeActivity().apply { report = report(to = 9) }
        val feeds = VerifiedPriceFeeds(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            4.seconds,
            listOf(eth),
            AddressActivity(activity),
        )
        suspend fun reading() =
            feeds.observe(Network.SEPOLIA, eth.priceId).first { it !is FeedReading.Syncing } as FeedReading.Verified
        feedAt(9, 100_000_000, 9_400)
        reading()
        feedAt(10, 200_000_000, 10_010)
        chain.snapshot = running(head = 10, headTimestamp = 10_012)
        activity.report = report(to = 10)
        assertEquals(1.0, reading().price, "kept")

        // A switch to another aggregator writes the proxy's storage: an account touch of it.
        feedAt(11, 300_000_000, 10_020)
        chain.snapshot = running(head = 11, headTimestamp = 10_024)
        activity.report = report(to = 11, touched = listOf(eth.address to 11))
        assertEquals(3.0, reading().price)

        // A new round at block 12, proven with decimals() answered at block 11, not the round's:
        // that proof is not kept, so the next head proves the feed again.
        answer(
            CallOutcome.Returned(word(8), 11, hash(11)),
            CallOutcome.Returned(round(word(400_000_000), 10_030), 12, hash(12)),
        )
        chain.calls[eth.address to Abi.AGGREGATOR.toHex()] =
            CallOutcome.Returned("0".repeat(24) + aggregator.hex.removePrefix("0x"), 12, hash(12))
        chain.snapshot = running(head = 12, headTimestamp = 10_036)
        activity.report = report(to = 12, touched = listOf(aggregator to 12))
        assertEquals(4.0, reading().price)
        feedAt(13, 500_000_000, 10_040)
        chain.snapshot = running(head = 13, headTimestamp = 10_048)
        activity.report = report(to = 13)
        assertEquals(5.0, reading().price)

        // decimals() at a block 14 the chain left, the round at the canonical block 14: the same
        // number, another block, so that proof is not kept either.
        val sibling = "0x" + "ee".repeat(32)
        answer(
            CallOutcome.Returned(word(8), 14, sibling),
            CallOutcome.Returned(round(word(600_000_000), 10_050), 14, hash(14)),
        )
        chain.calls[eth.address to Abi.AGGREGATOR.toHex()] =
            CallOutcome.Returned("0".repeat(24) + aggregator.hex.removePrefix("0x"), 14, hash(14))
        chain.snapshot = running(head = 14, headTimestamp = 10_060)
        activity.report = report(to = 14, touched = listOf(aggregator to 14))
        assertEquals(6.0, reading().price)
        feedAt(15, 700_000_000, 10_070)
        chain.snapshot = running(head = 15, headTimestamp = 10_072)
        activity.report = report(to = 15)
        assertEquals(7.0, reading().price)
    }

    @Test
    fun aPriceWhoseRoundNamesNoBlockHashIsProvenAgain() = runTest {
        val activity = AddressActivityTest.FakeActivity().apply { report = report(to = 9) }
        val feeds = VerifiedPriceFeeds(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            4.seconds,
            listOf(eth),
            AddressActivity(activity),
        )
        suspend fun reading() =
            feeds.observe(Network.SEPOLIA, eth.priceId).first { it !is FeedReading.Syncing } as FeedReading.Verified
        // An engine before ABI 52 names no hash: nothing can tell its block from a sibling.
        answer(CallOutcome.Returned(word(8), 9), CallOutcome.Returned(round(word(100_000_000), 9_400), 9))
        chain.calls[eth.address to Abi.AGGREGATOR.toHex()] =
            CallOutcome.Returned("0".repeat(24) + aggregator.hex.removePrefix("0x"), 9)
        reading()
        answer(CallOutcome.Returned(word(8), 10), CallOutcome.Returned(round(word(200_000_000), 10_010), 10))
        chain.snapshot = running(head = 10, headTimestamp = 10_012)
        activity.report = report(to = 10)
        assertEquals(2.0, reading().price)
    }

    @Test
    fun aPriceIsNotKeptWithoutTheProxysAccountCoverageOrAKnownAggregator() = runTest {
        val activity = AddressActivityTest.FakeActivity().apply { report = report(to = 9) }
        val feeds = VerifiedPriceFeeds(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            4.seconds,
            listOf(eth),
            AddressActivity(activity),
        )
        suspend fun reading() =
            feeds.observe(Network.SEPOLIA, eth.priceId).first { it !is FeedReading.Syncing } as FeedReading.Verified
        // No aggregator() answer: nothing to watch, so no proof is vouched for.
        answer(CallOutcome.Returned(word(8), 9), CallOutcome.Returned(round(word(100_000_000), 9_400), 9))
        reading()
        answer(CallOutcome.Returned(word(8), 10), CallOutcome.Returned(round(word(200_000_000), 10_010), 10))
        chain.snapshot = running(head = 10, headTimestamp = 10_012)
        activity.report = report(to = 10)
        assertEquals(2.0, reading().price)

        // With the aggregator known, an account gap on the proxy (no block access list: a
        // switch to another aggregator would not show) is no vouching either.
        chain.calls[eth.address to Abi.AGGREGATOR.toHex()] =
            CallOutcome.Returned("0".repeat(24) + aggregator.hex.removePrefix("0x"), 11, hash(11))
        answer(
            CallOutcome.Returned(word(8), 11, hash(11)),
            CallOutcome.Returned(round(word(300_000_000), 10_020), 11, hash(11)),
        )
        chain.snapshot = running(head = 11, headTimestamp = 10_024)
        activity.report = report(to = 11)
        assertEquals(3.0, reading().price)
        answer(
            CallOutcome.Returned(word(8), 12, hash(12)),
            CallOutcome.Returned(round(word(400_000_000), 10_030), 12, hash(12)),
        )
        chain.snapshot = running(head = 12, headTimestamp = 10_036)
        activity.report = report(to = 12, accountGaps = "[[12,12]]")
        assertEquals(4.0, reading().price)
    }

    @Test
    fun anAggregatorWordIsAnAddressOnlyWithItsUpperBytesZero() {
        assertEquals(aggregator, Abi.address("0".repeat(24) + aggregator.hex.removePrefix("0x")))
        assertEquals(null, Abi.address("1" + "0".repeat(23) + aggregator.hex.removePrefix("0x")))
        assertEquals(null, Abi.address("00"))
    }

    @Test
    fun aRoundDatedWellAfterTheHeadIsNoFeedsAnswer() = runTest {
        answer(CallOutcome.Returned(word(8), 9), CallOutcome.Returned(round(word(1), 10_000 + 3_600), 9))

        assertEquals(
            FeedReading.NoPrice(NoPriceReason.NOT_A_FEED),
            feeds().observe(Network.SEPOLIA, eth.priceId).first { it !is FeedReading.Syncing },
        )
    }

    @Test
    fun whatIsNoPriceSaysWhy() = runTest {
        val feeds = feeds()
        suspend fun reading() = feeds.observe(Network.SEPOLIA, eth.priceId).first { it !is FeedReading.Syncing }

        answer(CallOutcome.Returned(word(18), 9), CallOutcome.Returned(round(word(1), 9_400), 9))
        assertEquals(FeedReading.NoPrice(NoPriceReason.DECIMALS_CHANGED), reading())

        chain.snapshot = running(head = 10, headTimestamp = 10_012)
        answer(CallOutcome.Returned(word(8), 10), CallOutcome.Returned(round("f".repeat(64), 9_400), 10))
        assertEquals(FeedReading.NoPrice(NoPriceReason.NOT_POSITIVE), reading(), "a negative answer")

        chain.snapshot = running(head = 11, headTimestamp = 10_024)
        answer(CallOutcome.Returned(word(8), 11), CallOutcome.Reverted(11))
        assertEquals(FeedReading.NoPrice(NoPriceReason.NOT_A_FEED), reading())

        assertEquals(
            listOf<FeedReading>(FeedReading.NoPrice(NoPriceReason.NO_FEED)),
            feeds.observe(Network.SEPOLIA, PriceId("UNI")).toList(),
        )
    }

    @Test
    fun aRefusalEndsTheReadingAndAnUnknownHeadTimeIsUnproven() = runTest {
        chain.snapshot = running(head = 9, headTimestamp = null)
        answer(CallOutcome.Returned(word(8), 9), CallOutcome.Returned(round(word(1), 9_400), 9))
        assertEquals(
            FeedReading.Unavailable(ReadUnavailable.UNPROVEN),
            feeds().observe(Network.SEPOLIA, eth.priceId).first { it !is FeedReading.Syncing },
        )

        answer(CallOutcome.Refused, CallOutcome.Refused)
        assertEquals(
            listOf<FeedReading>(FeedReading.Unavailable(ReadUnavailable.REFUSED)),
            feeds().observe(Network.SEPOLIA, eth.priceId).toList(),
        )
    }

    @Test
    fun aRoundIsFiveWordsWithAnUpdateTime() {
        assertEquals(Abi.Round("5", 3), Abi.round(round(word(5), 3)))
        assertNull(Abi.round(word(5)), "one word is no round")
        assertEquals(Abi.Round(null, 3), Abi.round(round("8" + "0".repeat(63), 3)), "a negative answer")
    }

    @Test
    fun sepoliaPinsAFeedForEveryDisplayCurrencyItOffersAndEachPricedToken() {
        val ids = ChainlinkFeeds.of(Network.SEPOLIA).map { it.priceId.id }.toSet()

        assertEquals(setOf("ETH", "BTC", "LINK", "USDC", "EUR", "GBP", "JPY"), ids)
    }
}
