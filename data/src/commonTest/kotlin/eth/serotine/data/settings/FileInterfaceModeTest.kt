package eth.serotine.data.settings

import eth.serotine.domain.model.InterfaceMode
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
class FileInterfaceModeTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-ui-mode-${Random.nextLong().toULong()}")
    private val file = Path(directory, "ui-mode.json")

    private fun TestScope.setting() = FileInterfaceMode(file.toString(), StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun itIsSimpleUntilDetailedIsChosenAndStaysSoAcrossARestart() = runTest {
        assertEquals(InterfaceMode.SIMPLE, setting().observe().first())

        assertTrue(setting().set(InterfaceMode.DETAILED))
        assertEquals(InterfaceMode.DETAILED, setting().observe().first())

        assertTrue(setting().set(InterfaceMode.SIMPLE))
        assertEquals(InterfaceMode.SIMPLE, setting().observe().first())
    }

    @Test
    fun aDamagedNewerOrUnknownFileIsSimple() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":99,"mode":"detailed"}""") }
        assertEquals(InterfaceMode.SIMPLE, setting().observe().first())

        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":1,"mode":"expert"}""") }
        assertEquals(InterfaceMode.SIMPLE, setting().observe().first())

        SystemFileSystem.sink(file).buffered().use { it.writeString("not json") }
        assertEquals(InterfaceMode.SIMPLE, setting().observe().first())
    }
}
