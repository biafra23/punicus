package eth.serotine.data.outbox

import eth.serotine.data.files.fileThere
import eth.serotine.data.keys.FakeHardware
import eth.serotine.domain.repository.OutboxList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A sealed file whose directory cannot be searched or listed (POSIX permissions; skipped where
 * they do not hold, as for root): the file system cannot say whether the file is there, so it is
 * taken as there. The outbox is unreadable, every send held back, and its key never forgotten.
 */
class ChosenOutboxPermissionsTest {

    private val directory: File = Files.createTempDirectory("serotine-outbox-permissions").toFile()
    private val file = File(directory, "outbox.json").apply { writeBytes(ByteArray(8)) }

    private fun permissions(mode: String) =
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString(mode))

    @AfterTest
    fun cleanUp() {
        permissions("rwx------")
        directory.deleteRecursively()
    }

    private fun assertHeldBack(mode: String) = runTest {
        permissions(mode)
        assumeTrue("permissions do not hold here", directory.list() == null || !file.exists())
        assertNull(fileThere(file.path), "the file system cannot say")
        var forgotten = 0
        val chosen = ChosenOutbox(
            file.path,
            { FakeHardware(claims = { error("does not answer") }) },
            StandardTestDispatcher(testScheduler),
            forgetKey = { forgotten++ },
        )

        assertEquals(OutboxList.Unreadable, chosen.observe().first())
        assertEquals(0, forgotten, "the key of a file that may be there is never forgotten")
        permissions("rwx------")
        assertTrue(file.exists(), "and the file is there still")
    }

    @Test
    fun aDirectoryThatCannotBeSearchedHoldsEverySendBack() = assertHeldBack("rw-------")

    @Test
    fun aDirectoryThatCannotBeReadOrSearchedHoldsEverySendBack() = assertHeldBack("---------")

    @Test
    fun theFileSystemSaysWhatItKnows() {
        assertEquals(true, fileThere(file.path))
        assertEquals(false, fileThere(File(directory, "none.json").path))
        assertEquals(false, fileThere(File(directory, "none/none.json").path))
    }
}
