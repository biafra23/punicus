package eth.serotine.desktop

import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceLockTest {

    private val directory: File = Files.createTempDirectory("serotine-instance").toFile()
    private val file = directory.resolve("data/serotine.lock")

    @AfterTest
    fun cleanUp() {
        directory.setWritable(true)
        directory.deleteRecursively()
    }

    @Test
    fun aSecondInstanceIsRefusedWhileTheFirstRuns() {
        val first = assertNotNull(InstanceLock.acquire(file), "the data directory is created too")

        assertEquals("held", probe(), "another process")
        first.close()
        assertEquals("acquired", probe(), "free once the first one ends")
    }

    @Test
    fun askingAgainInTheSameProcessKeepsTheLock() {
        val first = assertNotNull(InstanceLock.acquire(file))

        assertNull(InstanceLock.acquire(file), "this process holds it")
        // On macOS and Linux, closing a second descriptor of the file would have dropped the lock.
        assertEquals("held", probe(), "still held for everyone else")
        first.close()
    }

    @Test
    fun aCrashedInstanceLeavesNothingBehindThatBlocks() {
        // The other process takes the lock and dies without releasing it: the file stays,
        // and the OS drops the lock with the process.
        assertEquals("acquired", probe(crash = true))
        assertTrue(file.exists())

        // Windows releases a dead process's locks "depending on available system resources".
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var lock = InstanceLock.acquire(file)
        while (lock == null && System.nanoTime() < deadline) {
            Thread.sleep(100)
            lock = InstanceLock.acquire(file)
        }
        assertNotNull(lock).close()
    }

    @Test
    fun aDataDirectoryThatCannotBeCreatedIsAnErrorNotARefusal() {
        // A read-only directory needs POSIX permissions (Windows' read-only flag does not stop
        // creating files in it) and a user they bind (not root).
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        val readOnly = directory.resolve("read-only").apply { mkdirs() }
        if (!readOnly.setWritable(false) || readOnly.canWrite()) return

        try {
            assertFailsWith<IOException> { InstanceLock.acquire(readOnly.resolve("data/serotine.lock")) }
        } finally {
            readOnly.setWritable(true)
        }
    }

    /** Asks another JVM to take the lock: what a second Punicus would see. */
    private fun probe(crash: Boolean = false): String {
        val java = File(System.getProperty("java.home"), "bin/java").path
        // Through an argument file: a long class path overruns Windows' command-line limit.
        val arguments = File.createTempFile("probe", ".args", directory).apply {
            writeText("-cp \"${System.getProperty("java.class.path").replace("\\", "\\\\")}\"")
        }
        val output = File.createTempFile("probe", ".txt", directory)
        val probe = LockProbe::class.java.name
        val process = ProcessBuilder(java, "@${arguments.path}", probe, file.path, crash.toString())
            .redirectErrorStream(true)
            .redirectOutput(output)
            .start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the probe finishes")
            return output.readText().trim()
        } finally {
            process.destroyForcibly()
        }
    }
}

/**
 * The other process of [InstanceLockTest]: tries the lock once and says what happened; with
 * `true` as its second argument it then dies holding it, as a crash would.
 */
object LockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val lock = InstanceLock.acquire(File(args[0]))
        println(if (lock == null) "held" else "acquired")
        System.out.flush()
        if (args[1].toBoolean()) Runtime.getRuntime().halt(0) // no release, no shutdown hooks
        lock?.close()
    }
}
