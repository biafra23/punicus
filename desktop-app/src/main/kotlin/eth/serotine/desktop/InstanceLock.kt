package eth.serotine.desktop

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

/**
 * This user's one running Punicus: an exclusive lock on a file in the app's data directory,
 * held until the process ends, when the OS releases it even after a crash. Two instances on
 * one data directory would both write the account list and the key store, and, with the
 * engine, open one database twice; the second one is refused instead.
 *
 * Nothing else in the process may open the lock file: on macOS and Linux the lock belongs to
 * the process, and closing ANY descriptor of the file drops it. That is why [acquire] refuses
 * a second acquisition within the process without opening the file again. A user who deletes
 * the file while Punicus runs defeats the guard: the next instance locks a new file.
 */
internal class InstanceLock private constructor(
    private val path: String,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    override fun close() {
        synchronized(held) {
            lock.release()
            channel.close()
            held -= path
        }
    }

    companion object {
        /** The canonical paths this process holds, so a second [acquire] never opens them again. */
        private val held = mutableSetOf<String>()

        /**
         * The lock on [file], or null when another Punicus, or this process already, holds it.
         * Throws [IOException] when the directory cannot be created or the file cannot be
         * opened or locked (a read-only disk, a network home without locking).
         */
        fun acquire(file: File): InstanceLock? = synchronized(held) {
            val directory = file.absoluteFile.parentFile
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $directory")
            val path = file.canonicalPath
            if (path in held) return null
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (e: IOException) {
                channel.close()
                throw e
            }
            if (lock == null) {
                channel.close()
                return null
            }
            held += path
            InstanceLock(path, channel, lock)
        }
    }
}
