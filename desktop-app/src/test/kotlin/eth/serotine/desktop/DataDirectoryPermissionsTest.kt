package eth.serotine.desktop

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Acceptance test of finding S5 of the security audit of 2026-11-01: the desktop's data directory
 * (the account list, the histories with their signed transactions, the Safe queue, the CoW orders)
 * is created by [InstanceLock.acquire] with the process's umask, so on a traversable home directory
 * every other local user reads it; only `keys/` is made owner-only. Ignored while the finding is
 * open; remove the @Ignore with the fix.
 */
class DataDirectoryPermissionsTest {

    private val directory: File = Files.createTempDirectory("serotine-instance").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Ignore // Audit 2026-11-01, S5: open.
    @Test
    fun theDataDirectoryIsThisUsersAlone() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        val lock = assertNotNull(InstanceLock.acquire(directory.resolve("data/serotine.lock")))
        try {
            val mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("data").toPath()))
            assertEquals("rwx------", mode, "the account list and the histories live here")
        } finally {
            lock.close()
        }
    }
}
