package eth.serotine.data.outbox

import eth.serotine.data.keys.EnvelopeCrypto
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.QueuedTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * The [Outbox] kept in the file at [path], sealed with [seal] (docs/keys-and-signing.md, *The
 * outbox's seal key*): an app-private file of its own, written whole and durably, never logged.
 * An empty outbox has no file. The host keeps it out of backups and device transfers, as it keeps
 * the envelopes.
 *
 * A file that does not open (its seal key gone, a copy restored on another device, or damage that
 * fails the tag) is deleted and said once as lost ([OutboxList.Loaded.lost], a marker beside it,
 * `<path>.lost`, which holds nothing, until [acknowledgeLost]): the user signs again. A file that
 * cannot be read at all right now is [OutboxList.Unreadable], untouched, and nothing changes until
 * it can be read. File work runs on [dispatcher].
 */
internal class SealedOutboxFile(
    private val path: String,
    private val seal: OutboxSeal,
    private val dispatcher: CoroutineDispatcher,
) : Outbox {

    private val file = Path(path)
    private val lostMarker = Path("$path.lost")
    private val state = MutableStateFlow<OutboxList?>(null)
    private val lock = Mutex()

    /** Whether the seal key's absence was already looked at: only the first read can find it lost. */
    private var keyChecked = false

    override fun observe(): Flow<OutboxList> = state.onStart { refreshIfNeeded() }.filterNotNull()

    override suspend fun put(queued: QueuedTransaction): Boolean = change { current ->
        current.filterNot { it.accountId == queued.accountId } + queued
    }

    override suspend fun remove(account: AccountId): Boolean = change { current -> current.filterNot { it.accountId == account } }

    override suspend fun acknowledgeLost(): Boolean = lock.withLock {
        val loaded = loadedOrNull() ?: return@withLock false
        withContext(NonCancellable + dispatcher) {
            try {
                if (SystemFileSystem.exists(lostMarker)) SystemFileSystem.delete(lostMarker)
                state.value = loaded.copy(lost = false)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private suspend fun refreshIfNeeded() {
        if (state.value.let { it == null || it is OutboxList.Unreadable }) lock.withLock { loadedOrNull() }
    }

    /** What is stored, read once (again while unreadable); null when it cannot be read. Under [lock]. */
    private suspend fun loadedOrNull(): OutboxList.Loaded? {
        (state.value as? OutboxList.Loaded)?.let { return it }
        val read = load()
        state.value = read
        return read as? OutboxList.Loaded
    }

    private suspend fun load(): OutboxList = withContext(dispatcher) {
        try {
            val lost = SystemFileSystem.exists(lostMarker)
            if (!SystemFileSystem.exists(file)) return@withContext OutboxList.Loaded(emptyList(), lost)
            // A sealed file whose key did not exist before anything could make one cannot be opened.
            val keyLost = !keyChecked && !seal.keyExistedAtStart
            keyChecked = true
            val opened = if (keyLost) null else seal.open(SystemFileSystem.source(file).buffered().use { it.readByteArray() })
            val queued = opened?.let { bytes -> StoredOutbox.decode(bytes).also { bytes.fill(0) } }
            if (queued != null) return@withContext OutboxList.Loaded(queued, lost)
            markLost()
            OutboxList.Loaded(emptyList(), lost = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Serotine: the outbox cannot be read (${e::class.simpleName})")
            OutboxList.Unreadable
        }
    }

    /** Deletes what does not open and leaves the marker that says so. */
    private fun markLost() {
        SystemFileSystem.delete(file, mustExist = false)
        if (!SystemFileSystem.exists(lostMarker)) {
            lostMarker.parent?.let { EnvelopeCrypto.createPrivateDirectory(it.toString()) }
            EnvelopeCrypto.writeDurably(lostMarker.toString(), ByteArray(0))
        }
        println("Serotine: the outbox could not be opened; what it held is deleted")
    }

    /** Stores what [next] makes of what is queued, under [lock]; false when it cannot be read or written. */
    private suspend fun change(next: (List<QueuedTransaction>) -> List<QueuedTransaction>): Boolean = lock.withLock {
        val loaded = loadedOrNull() ?: return@withLock false
        val queued = next(loaded.queued)
        // Not cancellable: a write that completes must also be what everyone observes.
        withContext(NonCancellable + dispatcher) {
            try {
                if (queued.isEmpty()) {
                    SystemFileSystem.delete(file, mustExist = false)
                } else {
                    val plain = StoredOutbox.encode(queued)
                    val sealed = try {
                        seal.seal(plain)
                    } finally {
                        plain.fill(0)
                    }
                    sealed ?: return@withContext false
                    file.parent?.let { EnvelopeCrypto.createPrivateDirectory(it.toString()) }
                    EnvelopeCrypto.writeDurably(path, sealed)
                }
                keyChecked = true
                state.value = loaded.copy(queued = queued)
                true
            } catch (e: Exception) {
                println("Serotine: the outbox could not be saved (${e::class.simpleName})")
                false
            }
        }
    }
}

/**
 * The [Outbox] where there is no hardware key to seal with (docs/keys-and-signing.md, *The
 * outbox's seal key*): in memory only, for the life of the process, so a restart asks for the
 * signature again. Nothing is written anywhere.
 */
class MemoryOutbox : Outbox {
    private val state = MutableStateFlow<OutboxList.Loaded>(OutboxList.Loaded(emptyList()))
    private val lock = Mutex()

    override fun observe(): Flow<OutboxList> = state

    override suspend fun put(queued: QueuedTransaction): Boolean = lock.withLock {
        state.value = state.value.copy(queued = state.value.queued.filterNot { it.accountId == queued.accountId } + queued)
        true
    }

    override suspend fun remove(account: AccountId): Boolean = lock.withLock {
        state.value = state.value.copy(queued = state.value.queued.filterNot { it.accountId == account })
        true
    }

    override suspend fun acknowledgeLost(): Boolean = true
}
