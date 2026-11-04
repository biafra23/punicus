package eth.serotine.domain.usecase

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.RatePair
import eth.serotine.domain.model.RateSnapshot
import eth.serotine.domain.repository.CurrencyChoice
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.LastExchangeRate
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.PastReading
import eth.serotine.domain.repository.PriceHistory
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** [ReadExchangeRate], [RefreshExchangeRate] and [FollowExchangeRate] over fake ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExchangeRateTest {

    private val feeds = FakeFeeds(mapOf(ETH to PriceId.USD, LINK_ID to ETH, EUR to PriceId.USD))
    private val history = FakeHistory()
    private val read = ReadExchangeRate(feeds, history)
    private val setting = MemoryCurrencySetting()
    private val last = MemoryLastRate()
    private val currencies = ObserveDisplayCurrencies(feeds, setting, ListAccountRepository())
    private val refresh = RefreshExchangeRate(currencies, read, last, RatePair.native(Network.SEPOLIA))

    /** A day back that has no round, which is a lasting answer: read once, no retries. */
    private fun noDayBack() {
        history.answers[ETH] = PastReading.NoRoundThatOld
        history.answers[EUR] = PastReading.NoRoundThatOld
    }

    @Test
    fun theRateIsTheBaseInUnitsOfTheQuoteDatedByItsOldestFeedAndTheHeadsTime() = runTest {
        feeds.answer(ETH, 2_400.0, updatedAt = 1_100, ageSeconds = 100)
        feeds.answer(EUR, 1.2, updatedAt = 900, ageSeconds = 300)
        history.answers[ETH] = PastReading.Verified(2_200.0, updatedAt = 1, stale = false)
        history.answers[EUR] = PastReading.Verified(1.1, updatedAt = 1, stale = false)

        val snapshot = (read(Network.SEPOLIA, ETH, DisplayCurrency.EUR) as RateResult.Read).snapshot

        assertEquals(2_000.0, snapshot.rate, 1e-9)
        assertEquals(2_000.0, snapshot.dayAgoRate!!, 1e-9)
        assertEquals(900, snapshot.updatedAt)
        assertEquals(1_200, snapshot.readAt)
        assertEquals(0.0, snapshot.dayChangePercent!!, 1e-9)
        assertEquals(listOf(ETH to ReadExchangeRate.DAY_SECONDS, EUR to ReadExchangeRate.DAY_SECONDS), history.asked)
    }

    @Test
    fun aRateQuotedThroughAnotherFeedIsConvertedADayBackToo() = runTest {
        feeds.answer(LINK_ID, 0.005)
        feeds.answer(ETH, 2_400.0)
        history.answers[LINK_ID] = PastReading.Verified(0.004, updatedAt = 1, stale = false)
        history.answers[ETH] = PastReading.Verified(2_500.0, updatedAt = 1, stale = false)

        val snapshot = (read(Network.SEPOLIA, LINK_ID, DisplayCurrency.USD) as RateResult.Read).snapshot

        assertEquals(12.0, snapshot.rate, 1e-9)
        assertEquals(10.0, snapshot.dayAgoRate!!, 1e-9)
        assertEquals(20.0, snapshot.dayChangePercent!!, 1e-9)
    }

    @Test
    fun aRateWithAPoolsAverageOnItsPathSaysSo() = runTest {
        feeds.answer(LINK_ID, 0.005, pool = true)
        feeds.answer(ETH, 2_400.0)
        noDayBack()
        history.answers[LINK_ID] = PastReading.NoRoundThatOld

        val snapshot = (read(Network.SEPOLIA, LINK_ID, DisplayCurrency.USD) as RateResult.Read).snapshot
        assertTrue(snapshot.poolAverage)
        assertFalse((read(Network.SEPOLIA, ETH, DisplayCurrency.USD) as RateResult.Read).snapshot.poolAverage)
    }

    @Test
    fun noVerifiedFreshRoundADayBackOnThePathMeansNoChangeButStillARate() = runTest {
        feeds.answer(ETH, 2_400.0)
        feeds.answer(EUR, 1.2)
        history.answers[ETH] = PastReading.Verified(2_200.0, updatedAt = 1, stale = false)
        for (missing in listOf(
            PastReading.Verified(1.1, updatedAt = 1, stale = true),
            PastReading.NoRoundThatOld,
            PastReading.Unavailable(ReadUnavailable.UNPROVEN),
            PastReading.Syncing,
        )) {
            history.answers[EUR] = missing
            val snapshot = (read(Network.SEPOLIA, ETH, DisplayCurrency.EUR) as RateResult.Read).snapshot
            assertEquals(2_000.0, snapshot.rate, 1e-9)
            assertNull(snapshot.dayAgoRate, "$missing")
            assertNull(snapshot.dayChangePercent)
        }
    }

    @Test
    fun aReadUnprovenThisTimeIsWaitedPastAndADayBackTriedAgain() = runTest {
        feeds.of(ETH).value = FeedReading.Unavailable(ReadUnavailable.UNPROVEN)
        history.queued[ETH] = ArrayDeque(listOf(PastReading.Unavailable(ReadUnavailable.UNPROVEN), PastReading.Syncing))
        history.answers[ETH] = PastReading.Verified(2_000.0, updatedAt = 1, stale = false)
        val reading = async { read(Network.SEPOLIA, ETH, DisplayCurrency.USD) }
        runCurrent()
        assertFalse(reading.isCompleted)

        feeds.answer(ETH, 2_400.0)

        assertEquals(20.0, (reading.await() as RateResult.Read).snapshot.dayChangePercent!!, 1e-9)
        assertEquals(3, history.asked.size)
    }

    @Test
    fun aLastingAnswerEndsTheReadAtOnce() = runTest {
        feeds.of(ETH).value = FeedReading.Unavailable(ReadUnavailable.NO_ENGINE)

        assertEquals(
            RateResult.NotRead(Price.Unavailable(ReadUnavailable.NO_ENGINE)),
            read(Network.SEPOLIA, ETH, DisplayCurrency.USD),
        )
    }

    @Test
    fun noPriceNowIsNoRateAndNothingIsAskedOfTheHistory() = runTest {
        feeds.of(ETH).value = FeedReading.NoPrice(NoPriceReason.DECIMALS_CHANGED)
        feeds.answer(EUR, 1.2)

        assertEquals(
            RateResult.NotRead(Price.NoPrice(NoPriceReason.DECIMALS_CHANGED)),
            read(Network.SEPOLIA, ETH, DisplayCurrency.EUR),
        )
        assertTrue(history.asked.isEmpty())
    }

    @Test
    fun theNativeCurrencyIsShownInTheDisplayCurrencyOrInUsdWhereThatIsItself() = runTest {
        feeds.answer(ETH, 2_400.0)
        feeds.answer(EUR, 1.2)

        assertEquals(DisplayCurrency.USD, refresh()!!.quote) // none chosen
        setting.choose(DisplayCurrency.EUR)
        assertEquals(DisplayCurrency.EUR, refresh()!!.quote)
        setting.choose(DisplayCurrency.ETH)
        val inEth = refresh()!!
        assertEquals(DisplayCurrency.USD, inEth.quote)
        assertEquals(inEth, last.kept)
    }

    @Test
    fun aStoredCurrencyTheNetworkDoesNotOfferIsShownInUsdAsTheScreensDo() = runTest {
        feeds.answer(ETH, 2_400.0)
        setting.choice.value = CurrencyChoice.Chosen(DisplayCurrency.CHF) // no CHF feed here

        assertEquals(DisplayCurrency.USD, refresh()!!.quote)
        assertFalse(refresh.isDue(nowSeconds = 1_060, maxAgeSeconds = 600))
    }

    @Test
    fun aPairWithItsOwnCurrencyIsShownInItWhateverTheDisplayCurrency() = runTest {
        feeds.answer(LINK_ID, 0.005)
        feeds.answer(ETH, 2_400.0)
        feeds.answer(EUR, 1.2)
        val pair = RatePair(Network.SEPOLIA, LINK_ID, DisplayCurrency.EUR)
        val link = RefreshExchangeRate(currencies, read, last, pair)
        setting.choose(DisplayCurrency.USD)

        val snapshot = link()!!

        assertEquals(LINK_ID, snapshot.base)
        assertEquals(DisplayCurrency.EUR, snapshot.quote)
        assertEquals(10.0, snapshot.rate, 1e-9)
        assertEquals(pair, last.pair)
        setting.choose(DisplayCurrency.GBP)
        assertFalse(link.isDue(nowSeconds = 1_060, maxAgeSeconds = 600)) // the display currency is not its
    }

    @Test
    fun aTokenThatFollowsTheDisplayCurrencyIsShownInIt() = runTest {
        feeds.answer(LINK_ID, 0.005)
        feeds.answer(ETH, 2_400.0)
        feeds.answer(EUR, 1.2)
        setting.choose(DisplayCurrency.EUR)

        val snapshot = RefreshExchangeRate(currencies, read, last, RatePair(Network.SEPOLIA, LINK_ID, null))()!!

        assertEquals(DisplayCurrency.EUR, snapshot.quote)
        assertEquals(10.0, snapshot.rate, 1e-9)
    }

    @Test
    fun aReadWithoutARateKeepsTheLastOne() = runTest {
        feeds.answer(ETH, 2_400.0)
        val kept = refresh()!!

        feeds.of(ETH).value = FeedReading.NoPrice(NoPriceReason.NOT_POSITIVE)

        assertNull(refresh())
        assertEquals(kept, last.kept)
    }

    @Test
    fun aReadIsDueWithoutOneKeptInAnotherCurrencyOrOnceItIsOld() = runTest {
        assertTrue(refresh.isDue(nowSeconds = 0, maxAgeSeconds = 600))
        feeds.answer(ETH, 2_400.0, updatedAt = 1_000, ageSeconds = 0)
        refresh()

        assertFalse(refresh.isDue(nowSeconds = 1_600, maxAgeSeconds = 600))
        assertTrue(refresh.isDue(nowSeconds = 1_601, maxAgeSeconds = 600))
        setting.choose(DisplayCurrency.EUR)
        assertTrue(refresh.isDue(nowSeconds = 1_000, maxAgeSeconds = 600))
    }

    @Test
    fun followingReadsWhenDueAndAgainAtOnceWhenTheCurrencyChanges() = runTest {
        noDayBack()
        feeds.answer(ETH, 2_400.0, updatedAt = 1_000, ageSeconds = 0)
        feeds.answer(EUR, 1.2, updatedAt = 1_000, ageSeconds = 0)
        var now = 1_000L
        val shown = mutableListOf<RateSnapshot>()
        val follow = FollowExchangeRate(
            setting,
            refresh,
            maxAgeSeconds = 1_800,
            checkEvery = 5.minutes,
            readTimeout = 1.minutes,
            nowSeconds = { now },
        )
        val following = launch { follow { shown += it } }
        runCurrent()
        assertEquals(listOf(DisplayCurrency.USD), shown.map { it.quote })

        now += 600 // checked again, not due yet
        advanceTimeBy(5.minutes + 1.minutes)
        assertEquals(1, shown.size)

        setting.choose(DisplayCurrency.EUR)
        runCurrent()
        assertEquals(listOf(DisplayCurrency.USD, DisplayCurrency.EUR), shown.map { it.quote })
        following.cancel()
    }

    @Test
    fun followingGivesUpAReadThatOutwaitsItsTimeoutAndTriesAtTheNextCheck() = runTest {
        noDayBack()
        val shown = mutableListOf<RateSnapshot>()
        val follow = FollowExchangeRate(setting, refresh, 1_800, 5.minutes, 1.minutes) { 1_000L }
        val following = launch { follow { shown += it } }
        advanceTimeBy(2.minutes) // the feeds are still syncing
        assertTrue(shown.isEmpty())

        feeds.answer(ETH, 2_400.0, updatedAt = 1_000, ageSeconds = 0)
        advanceTimeBy(3.minutes)
        assertTrue(shown.isEmpty()) // not before the next check, 5 minutes after the first
        advanceTimeBy(2.minutes)
        assertEquals(1, shown.size)
        following.cancel()
    }
}

/** [PriceHistory] answering what a test sets per price id ([PastReading.Syncing] until then), recording each read. */
internal class FakeHistory : PriceHistory {
    val answers = mutableMapOf<PriceId, PastReading>()

    /** Answers given first, one per read, before [answers]. */
    val queued = mutableMapOf<PriceId, ArrayDeque<PastReading>>()
    val asked = mutableListOf<Pair<PriceId, Long>>()

    override suspend fun before(network: Network, priceId: PriceId, secondsBefore: Long): PastReading {
        asked += priceId to secondsBefore
        return queued[priceId]?.removeFirstOrNull() ?: answers[priceId] ?: PastReading.Syncing
    }
}

/** [LastExchangeRate] in memory: the last rate saved, of [pair], whatever pair is loaded. */
internal class MemoryLastRate : LastExchangeRate {
    var kept: RateSnapshot? = null
    var pair: RatePair? = null

    override suspend fun load(pair: RatePair): RateSnapshot? = kept

    override suspend fun save(pair: RatePair, snapshot: RateSnapshot): Boolean {
        kept = snapshot
        this.pair = pair
        return true
    }
}
