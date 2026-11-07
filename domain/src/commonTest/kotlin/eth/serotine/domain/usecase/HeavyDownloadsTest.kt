package eth.serotine.domain.usecase

import eth.serotine.domain.repository.ConnectionCost
import eth.serotine.domain.repository.MeteredDownloadsChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [HeavyDownloads], [ObserveHeavyDownloads] and [AllowHeavyDownloadsOnMetered] over fake ports. */
class HeavyDownloadsTest {

    private val allowed = MeteredDownloadsChoice.Chosen(allowed = true)
    private val notAllowed = MeteredDownloadsChoice.Chosen(allowed = false)

    @Test
    fun onAMeteredConnectionTheyRunOnlyWhenTheUserAllowedThem() {
        assertFalse(HeavyDownloads(notAllowed, ConnectionCost.METERED).mayRun)
        assertTrue(HeavyDownloads(allowed, ConnectionCost.METERED).mayRun)
        assertFalse(HeavyDownloads(MeteredDownloadsChoice.Unreadable, ConnectionCost.METERED).mayRun)
        val setAside = MeteredDownloadsChoice.Chosen(allowed = false, setAside = true)
        assertFalse(HeavyDownloads(setAside, ConnectionCost.METERED).mayRun)
    }

    @Test
    fun onAnUnmeteredConnectionOrOneThePlatformDoesNotReportTheyAlwaysRun() {
        listOf(allowed, notAllowed, MeteredDownloadsChoice.Unreadable).forEach { choice ->
            assertTrue(HeavyDownloads(choice, ConnectionCost.UNMETERED).mayRun)
            assertTrue(HeavyDownloads(choice, ConnectionCost.NOT_REPORTED).mayRun)
        }
    }

    @Test
    fun theyAreNotAllowedOnAMeteredConnectionUntilTheUserSaysSo() = runTest {
        val meter = FakeConnectionMeter(ConnectionCost.METERED)
        val setting = MemoryMeteredDownloads()
        val observe = ObserveHeavyDownloads(meter, setting)

        assertEquals(HeavyDownloads(notAllowed, ConnectionCost.METERED), observe().first())

        assertTrue(AllowHeavyDownloadsOnMetered(setting)(true))
        assertTrue(observe().first().allowedOnMetered)

        setting.failSaves = true
        assertFalse(AllowHeavyDownloadsOnMetered(setting)(false))
        assertTrue(observe().first().allowedOnMetered)
    }
}
