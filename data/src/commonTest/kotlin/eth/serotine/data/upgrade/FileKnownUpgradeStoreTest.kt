package eth.serotine.data.upgrade

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.UpgradeNotice.Kind
import eth.serotine.domain.repository.KnownUpgrade
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString

@OptIn(ExperimentalCoroutinesApi::class)
class FileKnownUpgradeStoreTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-upgrade-${Random.nextLong().toULong()}")
    private val file = Path(directory, "upgrade.json")

    private val record = KnownUpgrade(
        NetworkUpgrade(NetworkUpgrade.Phase.SCHEDULED, 1_791_294_816, "0x6c1d9423", 5),
        setOf(Kind.ANNOUNCED, Kind.WEEK_AHEAD),
    )

    private fun TestScope.store(appVersion: String = "0.1.0") =
        FileKnownUpgradeStore(file.toString(), appVersion, StandardTestDispatcher(testScheduler))

    private fun put(text: String) {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString(text) }
    }

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun noFileIsNoRecord() = runTest {
        assertEquals(null, store().observe(Network.SEPOLIA).first())
    }

    @Test
    fun aSavedRecordIsObservedAtOnceAndSurvivesARestart() = runTest {
        val store = store()
        val seen = mutableListOf<KnownUpgrade?>()
        backgroundScope.launch { store.observe(Network.SEPOLIA).collect { seen += it } }
        runCurrent()

        assertTrue(store.save(Network.SEPOLIA, record))
        runCurrent()

        assertEquals(listOf(null, record), seen)
        assertEquals(record, store().observe(Network.SEPOLIA).first(), "read back by the next process")
    }

    @Test
    fun aSaveWhoseCallerIsCancelledDuringTheWriteFinishesItAndReturns() = runTest {
        val store = store()
        var saved: Boolean? = null
        // Run until the write waits for the store's dispatcher, then cancel the caller there.
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { saved = store.save(Network.SEPOLIA, record) }
        saving.cancel()
        runCurrent()

        // NonCancellable: the write finishes, the save returns normally, and the caller's
        // cancellation shows at its next suspension, not as the save returns.
        assertEquals(true, saved)
        assertTrue(saving.isCancelled)
        assertEquals(record, store.observe(Network.SEPOLIA).first())
        assertEquals(record, store().observe(Network.SEPOLIA).first(), "and it is in the file")
    }

    @Test
    fun aRecordAnotherAppVersionWroteIsNone() = runTest {
        store(appVersion = "0.1.0").save(Network.SEPOLIA, record)

        assertEquals(null, store(appVersion = "0.2.0").observe(Network.SEPOLIA).first())
        // Replaced by the next save, for the version that now runs.
        store(appVersion = "0.2.0").save(Network.SEPOLIA, record.copy(shown = emptySet()))
        assertEquals(record.copy(shown = emptySet()), store(appVersion = "0.2.0").observe(Network.SEPOLIA).first())
    }

    @Test
    fun aDamagedOrUnknownRecordIsNone() = runTest {
        for (text in listOf("{", """{"version":2,"appVersion":"0.1.0"}""", "[]")) {
            put(text)
            assertEquals(null, store().observe(Network.SEPOLIA).first(), text)
        }
        put(
            """{"version":1,"appVersion":"0.1.0","phase":"SOON","reportingNetworks":1,"shown":[]}""",
        )
        assertEquals(null, store().observe(Network.SEPOLIA).first(), "a phase the domain does not know")
    }

    @Test
    fun anUnknownTimeAndForkIdRoundTripAsNull() = runTest {
        val placed = KnownUpgrade(NetworkUpgrade(NetworkUpgrade.Phase.ACTIVE, null, null, 3), setOf(Kind.ACTIVATED))
        store().save(Network.SEPOLIA, placed)

        assertEquals(placed, store().observe(Network.SEPOLIA).first())
    }
}
