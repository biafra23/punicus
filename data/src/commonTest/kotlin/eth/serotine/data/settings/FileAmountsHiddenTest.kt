package eth.serotine.data.settings

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileAmountsHiddenTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-hidden-${Random.nextLong().toULong()}")
    private val file = Path(directory, "amounts-hidden.json")

    private fun TestScope.setting() = FileAmountsHidden(file.toString(), StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun amountsShowUntilHiddenAndStaySoAcrossARestart() = runTest {
        assertFalse(setting().observe().first())

        assertTrue(setting().set(true))
        assertTrue(setting().observe().first())

        assertTrue(setting().set(false))
        assertFalse(setting().observe().first())
    }

    @Test
    fun aDamagedOrNewerFileShowsAmounts() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":99,"hidden":true}""") }
        assertFalse(setting().observe().first())

        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":1,"hidden":"yes"}""") }
        assertFalse(setting().observe().first())

        SystemFileSystem.sink(file).buffered().use { it.writeString("not json") }
        assertFalse(setting().observe().first())
    }
}
