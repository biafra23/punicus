package eth.serotine.data.settings

import eth.serotine.domain.repository.MeteredDownloadsChoice
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileMeteredDownloadsTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-metered-${Random.nextLong().toULong()}")
    private val file = Path(directory, "metered-downloads.json")

    private fun TestScope.setting() = FileMeteredDownloads(file.toString(), StandardTestDispatcher(testScheduler))

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
    fun notAllowedUntilTheUserAllowsItAndTheChoiceSurvivesARestart() = runTest {
        assertEquals(MeteredDownloadsChoice.Chosen(allowed = false), setting().observe().first())

        assertTrue(setting().allow(true))
        assertEquals(MeteredDownloadsChoice.Chosen(allowed = true), setting().observe().first())

        assertTrue(setting().allow(false))
        assertEquals(MeteredDownloadsChoice.Chosen(allowed = false), setting().observe().first())
    }

    @Test
    fun aNewerFormatIsUnreadableAndCannotBeOverwritten() = runTest {
        write("""{"version":2,"allowedOnMetered":true}""")
        val setting = setting()

        assertEquals(MeteredDownloadsChoice.Unreadable, setting.observe().first())
        assertFalse(setting.allow(false))
    }

    @Test
    fun aDamagedChoiceIsSetAsideAsNotAllowedAndSaidUntilAnotherIsMade() = runTest {
        write("""{"version":1,"allowedOnMetered":""")
        val setting = setting()

        assertEquals(MeteredDownloadsChoice.Chosen(allowed = false, setAside = true), setting.observe().first())
        setting.allow(false)
        assertEquals(MeteredDownloadsChoice.Chosen(allowed = false), setting.observe().first())
    }
}
