package eth.serotine.data.rates

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.RatePair
import eth.serotine.domain.model.RateSnapshot
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString

@OptIn(ExperimentalCoroutinesApi::class)
class FileLastExchangeRateTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-rate-${Random.nextLong().toULong()}")
    private val file = Path(directory, "last-exchange-rate.json")

    private fun TestScope.store() = FileLastExchangeRate(file.toString(), StandardTestDispatcher(testScheduler))

    private val snapshot = RateSnapshot(
        base = PriceId("ETH"),
        quote = DisplayCurrency.EUR,
        rate = 2_000.5,
        dayAgoRate = 1_950.25,
        updatedAt = 1_700_000_000,
        readAt = 1_700_000_600,
        stale = false,
    )

    private val sepolia = RatePair.native(Network.SEPOLIA)

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    private fun write(text: String) {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString(text) }
    }

    @Test
    fun noneIsKeptUntilOneIsSavedAndItSurvivesARestart() = runTest {
        assertNull(store().load(sepolia))

        assertTrue(store().save(sepolia, snapshot))
        assertTrue(store().save(sepolia, snapshot.copy(dayAgoRate = null, stale = true)))

        assertEquals(snapshot.copy(dayAgoRate = null, stale = true), store().load(sepolia))
    }

    @Test
    fun aRateAtAPoolsAverageSurvivesARestartAsOneAndAnOlderRateIsNone() = runTest {
        val pair = RatePair(Network.ETHEREUM, PriceId("COW"), DisplayCurrency.EUR)
        assertTrue(store().save(pair, snapshot.copy(base = PriceId("COW"), rate = 0.11, poolAverage = true)))
        assertTrue(store().save(sepolia, snapshot))

        assertEquals(true, store().load(pair)?.poolAverage)
        assertEquals(false, store().load(sepolia)?.poolAverage, "a rate saved without one, as every older file's")
    }

    @Test
    fun aCurrencyThisBuildDoesNotKnowOrNoRateIsNoneKept() = runTest {
        write(
            """{"version":1,"base":"ETH","quote":"XAU","rate":1.0,"dayAgoRate":null,""" +
                """"updatedAt":1,"readAt":2,"stale":false}""",
        )
        assertNull(store().load(sepolia))

        write("""{"version":1,"base":"ETH","quote":"EUR","rate":-1.0,"updatedAt":1,"readAt":2,"stale":false}""")
        assertNull(store().load(sepolia))
        assertTrue(SystemFileSystem.list(directory).none { it.name.contains("damaged") })
    }

    @Test
    fun aDamagedFileIsSetAsideAndANewerOneLeftAlone() = runTest {
        write("not json")
        assertNull(store().load(sepolia))
        assertTrue(SystemFileSystem.list(directory).any { it.name.contains("damaged") })

        cleanUp()
        write("""{"version":4,"base":"ETH"}""")
        assertNull(store().load(sepolia))
        assertFalse(store().save(sepolia, snapshot))
        assertTrue(SystemFileSystem.list(directory).size == 1)
    }

    @Test
    fun theRateOfAFileFromBeforeNetworksIsSepolias() = runTest {
        write(
            """{"version":1,"base":"ETH","quote":"EUR","rate":2000.5,"dayAgoRate":1950.25,""" +
                """"updatedAt":1700000000,"readAt":1700000600,"stale":false}""",
        )

        assertEquals(snapshot, store().load(sepolia))
    }

    @Test
    fun aRateOfANetworkThisBuildDoesNotKnowIsKeptAsideBeforeTheNextSave() = runTest {
        write(
            """{"version":2,"rates":[{"chain":424242,"base":"ETH","quote":"EUR","rate":1.0,"dayAgoRate":null,""" +
                """"updatedAt":1,"readAt":2,"stale":false}]}""",
        )

        assertNull(store().load(sepolia))
        assertTrue(store().save(sepolia, snapshot))
        assertEquals(snapshot, store().load(sepolia))
        assertTrue(
            SystemFileSystem.list(directory).any { it.name.contains("damaged") },
            "the other network's rate is kept",
        )
    }

    @Test
    fun eachPairKeepsItsOwnRateAndAFormerNetworksRateIsItsNativePairs() = runTest {
        write(
            """{"version":2,"rates":[{"chain":1,"base":"ETH","quote":"EUR","rate":2000.5,"dayAgoRate":1950.25,""" +
                """"updatedAt":1700000000,"readAt":1700000600,"stale":false}]}""",
        )
        val ethereum = RatePair.native(Network.ETHEREUM)
        val linkInEur = RatePair(Network.ETHEREUM, PriceId("LINK"), DisplayCurrency.EUR)
        val link = snapshot.copy(base = PriceId("LINK"), rate = 12.5)
        assertEquals(snapshot, store().load(ethereum))

        assertTrue(store().save(linkInEur, link))
        val ethInEur = RatePair(Network.ETHEREUM, PriceId("ETH"), DisplayCurrency.EUR)
        assertTrue(store().save(ethInEur, snapshot.copy(rate = 1.0)))

        assertEquals(link, store().load(linkInEur))
        assertEquals(snapshot, store().load(ethereum), "the pair that follows the display currency is apart")
        assertNull(store().load(RatePair(Network.GNOSIS, PriceId("LINK"), DisplayCurrency.EUR)))
    }

    @Test
    fun aRateThatIsNotThePairsIsRefused() = runTest {
        val linkInUsd = RatePair(Network.ETHEREUM, PriceId("LINK"), DisplayCurrency.USD)

        assertFalse(store().save(linkInUsd, snapshot)) // ETH, not LINK
        assertFalse(store().save(linkInUsd, snapshot.copy(base = PriceId("LINK")))) // in EUR, not USD
        assertNull(store().load(linkInUsd))
    }
}
