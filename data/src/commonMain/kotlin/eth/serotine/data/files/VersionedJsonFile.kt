package eth.serotine.data.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * One value kept in the file at [path] as a versioned JSON document: what the account list and
 * the token settings share. [decode] reads a document of a version up to [formatVersion] (and
 * throws on anything it would not accept: the file is then damaged); [encode] writes the
 * current one, `version` first.
 *
 * Writing: the whole document, into a temporary file of its own whose data is flushed to the
 * disk ([writeSynced]), then moved over the file in one step, and the directory flushed after
 * the move ([syncDirectoryOf]). A crash or a power loss during a write leaves the previous
 * document or the new one, each complete; where a directory cannot be flushed (Windows) a power
 * loss just after the move may still leave the previous one. Each change reads the file again
 * first. One process writes the file: Android runs one, and the desktop host holds a per-user
 * instance lock; were a second one to write it, it could lose a change only within that one
 * change.
 *
 * Reading, which never destroys or hides what is stored:
 * - no file: [empty];
 * - a newer format, a file whose [decode] finds content only a newer version writes
 *   ([NewerContent]), or a file that cannot be read: [Read.Unreadable], the file untouched, and
 *   nothing can be changed until it can be read. An unreadable file is read again by every
 *   new subscriber, and every [RETRY_INTERVAL] while one stays subscribed, so it recovers on
 *   its own once the file can be read (a lock released, a permission fixed);
 * - a damaged file: set aside under a name of its own (`<file>.damaged-<random>`, never
 *   replacing an earlier one), then [empty], saying so ([Read.Loaded.damagedSetAside]); if it
 *   cannot be set aside, Unreadable;
 * - a file [incomplete] says [decode] passed parts of over (entries it could not decode):
 *   copied aside under the same kind of name, once, and what was read written in its place, so
 *   that nothing passed over is lost at the next write; saying so too. The file is never left
 *   missing: if either step cannot be done it stays as it is, and what was read is used.
 *
 * File work runs on [dispatcher], never the caller's thread. Problems are reported on standard
 * output by type only, naming the file as [what]: an exception message could quote the
 * file's contents. [writeFile] is [writeSynced] but in tests.
 */
internal class VersionedJsonFile<T>(
    path: String,
    private val dispatcher: CoroutineDispatcher,
    private val what: String,
    private val formatVersion: Int,
    private val empty: T,
    private val decode: (version: Int, text: String) -> T,
    private val encode: (T) -> String,
    private val incomplete: (text: String, value: T) -> Boolean = { _, _ -> false },
    private val writeFile: (path: String, bytes: ByteArray) -> Unit = ::writeSynced,
) {
    /** What the file holds, or why it cannot be read. */
    sealed interface Read<out T> {
        /** [value]; [damagedSetAside] once a damaged file was set aside and this is what came after. */
        data class Loaded<T>(val value: T, val damagedSetAside: Boolean = false) : Read<T>

        /** It cannot be used: written by a newer version ([newerFormat]), or not readable at all. */
        data class Unreadable(val newerFormat: Boolean) : Read<Nothing>
    }

    /** What a change makes of the value: the [answer], and the value to store, or none to store nothing. */
    class Decision<T, R>(val answer: R, val next: T? = null)

    private val file = Path(path)

    /** Null until the file has been read. */
    private val state = MutableStateFlow<Read<T>?>(null)
    private val lock = Mutex()

    /** The value, re-emitted on each change, or why it cannot be read. Never throws. */
    fun observe(): Flow<Read<T>> = channelFlow {
        refreshIfNeeded()
        launch {
            while (true) {
                delay(RETRY_INTERVAL)
                refreshIfNeeded()
            }
        }
        state.filterNotNull().collect { send(it) }
    }

    /** Reads the file when it has not been read yet, or when the last read found it unreadable. */
    private suspend fun refreshIfNeeded() {
        fun stale() = state.value.let { it == null || it is Read.Unreadable }
        if (stale()) lock.withLock { if (stale()) state.value = load() }
    }

    /**
     * Reads the file again under the lock and lets [decide] make the change on what it holds,
     * then stores the result; [notSaved] when the file cannot be read or written.
     */
    suspend fun <R> change(notSaved: R, decide: (T) -> Decision<T, R>): R = lock.withLock {
        val current = when (val read = load()) {
            is Read.Unreadable -> {
                state.value = read
                return notSaved
            }
            // A notice that the stored value was set aside outlives later reads of the new file.
            is Read.Loaded -> read.copy(damagedSetAside = read.damagedSetAside || setAsideBefore())
        }
        state.value = current
        val decision = decide(current.value)
        val next = decision.next ?: return decision.answer
        // Not cancellable: a write that completes must also be what everyone observes, or a
        // caller cancelled mid-move would leave the file and the observed value apart.
        withContext(NonCancellable) {
            if (write(next)) {
                state.value = current.copy(value = next)
                decision.answer
            } else {
                notSaved
            }
        }
    }

    private fun setAsideBefore(): Boolean = (state.value as? Read.Loaded)?.damagedSetAside == true

    private suspend fun load(): Read<T> = withContext(dispatcher) {
        if (!SystemFileSystem.exists(file)) return@withContext Read.Loaded(empty)
        val text = try {
            SystemFileSystem.source(file).buffered().use { it.readString() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("could not read the $what at $file (${e::class.simpleName})")
            return@withContext Read.Unreadable(newerFormat = false)
        }
        val version = try {
            LENIENT.decodeFromString(StoredVersion.serializer(), text).version
        } catch (e: Exception) {
            null // not even a JSON object: damaged
        }
        if (version != null && version > formatVersion) return@withContext Read.Unreadable(newerFormat = true)
        val value = try {
            version?.let { decode(it, text) }
        } catch (e: NewerContent) {
            return@withContext Read.Unreadable(newerFormat = true)
        } catch (e: Exception) {
            null
        }
        if (value == null) return@withContext setAside()
        val partly = try {
            incomplete(text, value)
        } catch (e: Exception) {
            false
        }
        if (partly) keepAside(text, value) else Read.Loaded(value)
    }

    /**
     * Keeps the file [decode] read only part of, [text], under a name of its own, then writes
     * [value], what was read, in its place: the parts passed over stay in the copy. The file
     * itself is never moved: the copy is written and flushed to the disk first, its name with it
     * where the directory can be flushed ([syncDirectoryOf]), and only then is the file replaced,
     * by [write]'s own flushed, atomic move; so a crash or a power loss leaves the file as it was,
     * or a complete copy and the rewritten file. One step a cancellation cannot cut in half
     * ([NonCancellable]). If the copy cannot be written, what it got of it is deleted and nothing
     * changes; if the file cannot be rewritten, the copy is removed again and the file stays as it
     * was, to be set aside at a later read. Either way what was read is used.
     */
    private suspend fun keepAside(text: String, value: T): Read<T> = withContext(NonCancellable) {
        var aside: Path
        do {
            aside = Path("$file.damaged-${Random.nextLong().toULong().toString(16)}")
        } while (SystemFileSystem.exists(aside))
        val copied = withContext(dispatcher) {
            try {
                writeFile(aside.toString(), text.encodeToByteArray())
                syncDirectoryOf(aside.toString())
                true
            } catch (e: Exception) {
                report("the $what at $file has entries that could not be read; not set aside (${e::class.simpleName})")
                deleteQuietly(aside)
                false
            }
        }
        if (!copied) return@withContext Read.Loaded(value)
        if (!write(value)) {
            // A stray copy would be harmless: the file still holds every entry.
            deleteQuietly(aside)
            report("the $what at $file has entries that could not be read; left as it is for now")
            return@withContext Read.Loaded(value)
        }
        report("the $what at $file had entries that could not be read; kept as $aside")
        Read.Loaded(value, damagedSetAside = true)
    }

    /** Deletes [path] if it is there; a file that cannot be deleted is left, harmless. */
    private fun deleteQuietly(path: Path) {
        try {
            SystemFileSystem.delete(path, mustExist = false)
        } catch (e: Exception) {
            // Nothing more to do.
        }
    }

    /** Moves a damaged file out of the way, keeping it; Unreadable when that fails. */
    private fun setAside(): Read<T> {
        var aside: Path
        do {
            aside = Path("$file.damaged-${Random.nextLong().toULong().toString(16)}")
        } while (SystemFileSystem.exists(aside))
        return try {
            SystemFileSystem.atomicMove(file, aside)
            report("the $what at $file was damaged; set aside as $aside")
            Read.Loaded(empty, damagedSetAside = true)
        } catch (e: Exception) {
            report("the $what at $file is damaged and could not be set aside (${e::class.simpleName})")
            Read.Unreadable(newerFormat = false)
        }
    }

    private suspend fun write(value: T): Boolean = withContext(dispatcher) {
        val temporary = Path("$file.${Random.nextLong().toULong().toString(16)}.tmp")
        try {
            file.parent?.let { SystemFileSystem.createDirectories(it) }
            val text = encode(value)
            // The data on the disk before the name points at it, and the name after the move.
            writeFile(temporary.toString(), text.encodeToByteArray())
            SystemFileSystem.atomicMove(temporary, file)
            syncDirectoryOf(file.toString())
            true
        } catch (e: Exception) {
            report("could not save the $what to $file (${e::class.simpleName})")
            try {
                if (SystemFileSystem.exists(temporary)) SystemFileSystem.delete(temporary)
            } catch (cleanup: Exception) {
                // Nothing more to do: a stray temporary file is harmless.
            }
            false
        }
    }

    /** Just the version, read leniently, to tell a newer format from a damaged file; absent, damaged. */
    @Serializable
    private class StoredVersion(val version: Int)

    private companion object {
        val RETRY_INTERVAL = 5.seconds
        val LENIENT = Json { ignoreUnknownKeys = true }

        fun report(what: String) = println("Serotine: $what")
    }
}

/**
 * What a [VersionedJsonFile]'s decode throws for content of its own format that only a newer
 * version of the app writes (an account on a network this version does not know): the file is
 * then a newer one's, [VersionedJsonFile.Read.Unreadable] and left untouched, never damaged.
 */
internal class NewerContent(what: String) : Exception(what)
