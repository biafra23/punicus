package eth.serotine.data.history

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.HistoryStartList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString

/** [FileHistoryStarts] against the [eth.serotine.domain.repository.HistoryStarts] contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileHistoryStartsTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-starts-${Random.nextLong().toULong()}")
    private val file = Path(directory, "history-starts.json")
    private val alice = AccountId(Network.SEPOLIA, Address.of("0x" + "11".repeat(20)))
    private val vault = AccountId(Network.SEPOLIA, Address.of("0x" + "5a".repeat(20)))

    private fun TestScope.starts() = FileHistoryStarts(file.toString(), StandardTestDispatcher(testScheduler))

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

    private suspend fun FileHistoryStarts.held() = (observe().first() as HistoryStartList.Loaded).starts

    @Test
    fun aNoteIsFixedOnceAndBothSurviveARestart() = runTest {
        val starts = starts()
        assertEquals(emptyMap(), starts.held())

        assertTrue(starts.note(alice, 1_700_000_000))
        assertTrue(starts.note(vault, 1_800_000_100))
        assertTrue(starts.note(alice, 1_800_000_000), "added anew")
        assertTrue(starts.fix(vault, 9_400_000))
        assertFalse(starts.fix(vault, 9_000_000), "a fixed start is kept")

        assertEquals(
            mapOf(alice to HistoryStart.Noted(1_800_000_000), vault to HistoryStart.Fixed(9_400_000)),
            starts().held(),
        )
    }

    @Test
    fun aStartIsForgottenOnlyWhileItIsTheOneSeen() = runTest {
        val starts = starts()
        starts.note(alice, 1_800_000_000)
        starts.fix(alice, 9_400_000)
        starts.note(vault, 1_800_000_000)

        assertTrue(starts.forget(alice, HistoryStart.Fixed(9_400_000)))
        assertTrue(starts.forget(alice, HistoryStart.Fixed(9_400_000)), "none held")
        assertTrue(starts.forget(vault, HistoryStart.Fixed(9_400_000)), "replaced meanwhile: kept")

        assertEquals(mapOf(vault to HistoryStart.Noted(1_800_000_000)), starts().held())
    }

    @Test
    fun theFileHoldsAddressesAndBlocksOrTimesOnly() = runTest {
        val starts = starts()
        starts.note(alice, 1_800_000_000)
        starts.fix(vault, 9_400_000)

        val text = SystemFileSystem.source(file).buffered().use { it.readString() }
        assertEquals(
            """{"version":2,"starts":[""" +
                """{"address":"${alice.address.hex}","addedAt":1800000000,"chain":11155111},""" +
                """{"address":"${vault.address.hex}","block":9400000,"chain":11155111}]}""",
            text,
        )
    }

    @Test
    fun aStartThatIsBothOrNeitherDamagesTheFileWhichIsSetAside() = runTest {
        write("""{"version":1,"starts":[{"address":"${alice.address.hex}","block":1,"addedAt":2}]}""")

        assertEquals(emptyMap(), starts().held())
        assertTrue(SystemFileSystem.list(directory).any { it.name.contains("damaged") })
    }

    @Test
    fun aNewerFormatIsUnreadableAndNotWrittenOver() = runTest {
        write("""{"version":3,"starts":[]}""")
        val starts = starts()

        assertEquals(HistoryStartList.Unreadable, starts.observe().first())
        assertFalse(starts.note(alice, 1_800_000_000))
    }

    @Test
    fun aStartOfAFileFromBeforeNetworksIsSepolias() = runTest {
        write("""{"version":1,"starts":[{"address":"${alice.address.hex}","block":9400000}]}""")

        assertEquals(mapOf(alice to HistoryStart.Fixed(9_400_000)), starts().held())
    }

    @Test
    fun aStartOnANetworkThisVersionDoesNotKnowIsPassedOverAndKeptAside() = runTest {
        write(
            """{"version":2,"starts":[""" +
                """{"address":"${alice.address.hex}","block":9400000,"chain":11155111},""" +
                """{"address":"${vault.address.hex}","block":5,"chain":424242}]}""",
        )

        assertEquals(mapOf(alice to HistoryStart.Fixed(9_400_000)), starts().held())
        assertTrue(SystemFileSystem.list(directory).any { it.name.contains("damaged") })
    }
}
