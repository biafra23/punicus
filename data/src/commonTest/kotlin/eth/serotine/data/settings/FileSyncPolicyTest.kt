package eth.serotine.data.settings

import eth.serotine.domain.model.SyncPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileSyncPolicyTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-sync-policy-${Random.nextLong().toULong()}")
    private val file = Path(directory, "sync-policy.json")

    private fun TestScope.setting() = FileSyncPolicy(file.toString(), StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun itIsStrictUntilLenientIsChosenAndStaysSoAcrossARestart() = runTest {
        assertEquals(SyncPolicy.STRICT, setting().observe().first())

        assertTrue(setting().set(SyncPolicy.LENIENT))
        assertEquals(SyncPolicy.LENIENT, setting().observe().first())

        assertTrue(setting().set(SyncPolicy.STRICT))
        assertEquals(SyncPolicy.STRICT, setting().observe().first())
    }

    @Test
    fun aDamagedNewerOrUnknownFileIsStrict() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":99,"policy":"lenient"}""") }
        assertEquals(SyncPolicy.STRICT, setting().observe().first())

        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":1,"policy":"reckless"}""") }
        assertEquals(SyncPolicy.STRICT, setting().observe().first())

        SystemFileSystem.sink(file).buffered().use { it.writeString("not json") }
        assertEquals(SyncPolicy.STRICT, setting().observe().first())
    }
}
