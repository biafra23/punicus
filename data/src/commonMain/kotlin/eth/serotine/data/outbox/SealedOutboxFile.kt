package eth.serotine.data.outbox

import eth.serotine.data.files.syncDirectoryOf
import eth.serotine.data.keys.EnvelopeCrypto
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Digest
import eth.serotine.domain.repository.HeldSafeSignature
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.QueuedTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
 * outbox's seal key*): an app-private file of its own, written whole and durably, never logged,
 * what is queued and what is held in it together. An empty outbox has no file. The host keeps it
 * out of backups and device transfers, as it keeps the envelopes.
 *
 * A file that will never open here is deleted and said once as lost ([OutboxList.Loaded.lost], a
 * marker beside it, `<path>.lost`, which holds nothing, written before the file is deleted, until
 * [acknowledgeLost]): the user signs again. That is a seal key known to be gone (the key store
 * reset, a copy restored on another device), and a file sealed under another key or damaged (its tag
 * does not verify). A file that cannot be read right now (the key store would not say whether its
 * key exists, the hardware failed, a version of Serotine wrote what this one cannot read, an I/O
 * error) is [OutboxList.Unreadable], untouched,
 * read again by the next subscriber, and nothing changes until it can be read. File work runs on
 * [dispatcher].
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
        current.copy(queued = current.queued.filterNot { it.accountId == queued.accountId } + queued)
    }

    override suspend fun remove(account: AccountId): Boolean =
        change { current -> current.copy(queued = current.queued.filterNot { it.accountId == account }) }

    override suspend fun hold(held: HeldSafeSignature): Boolean = change { current ->
        current.copy(held = current.held.filterNot { it.safe == held.safe && it.nonce == held.nonce } + held)
    }

    override suspend fun release(safe: AccountId, hash: Digest): Boolean =
        change { current -> current.copy(held = current.held.filterNot { it.safe == safe && it.hash == hash }) }

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
            // Whether the key existed before anything could make one: asked of the first read only.
            if (!keyChecked) {
                when (seal.keyExistedAtStart) {
                    false -> return@withContext lostNow("its seal key is gone")
                    null -> return@withContext unreadable("the key store would not say whether its key exists")
                    true -> keyChecked = true
                }
            }
            val sealed = SystemFileSystem.source(file).buffered().use { it.readByteArray() }
            when (val opening = seal.open(sealed)) {
                OutboxSeal.Opening.WrongKey -> lostNow("it was sealed under another key, or is damaged")
                OutboxSeal.Opening.Newer -> unreadable("a newer version of Serotine wrote it")
                OutboxSeal.Opening.Failed -> unreadable("the hardware key store failed")
                is OutboxSeal.Opening.Opened -> {
                    val decoding = StoredOutbox.decode(opening.plaintext)
                    opening.plaintext.fill(0)
                    when (decoding) {
                        is StoredOutbox.Decoding.Read -> OutboxList.Loaded(decoding.queued, lost, decoding.held)
                        StoredOutbox.Decoding.Newer -> unreadable("a newer version of Serotine wrote it")
                        // The tag verified, so it is what a version of Serotine sealed: not damage, and kept.
                        StoredOutbox.Decoding.Damaged -> unreadable("this version cannot read what it holds")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unreadable(e::class.simpleName ?: "an error")
        }
    }

    private fun unreadable(why: String): OutboxList {
        println("Serotine: the outbox cannot be read now ($why); it is left as it is")
        return OutboxList.Unreadable
    }

    /**
     * Says what will never open as lost, the marker first so a crash between the two never hides
     * it, then deletes it.
     */
    private fun lostNow(why: String): OutboxList {
        if (!SystemFileSystem.exists(lostMarker)) {
            lostMarker.parent?.let { EnvelopeCrypto.createPrivateDirectory(it.toString()) }
            EnvelopeCrypto.writeDurably(lostMarker.toString(), ByteArray(0))
        }
        SystemFileSystem.delete(file, mustExist = false)
        keyChecked = true
        println("Serotine: the outbox could not be opened ($why); what it held is deleted")
        return OutboxList.Loaded(emptyList(), lost = true)
    }

    /** Stores what [next] makes of what is queued and held, under [lock]; false when it cannot be read or written. */
    private suspend fun change(next: (OutboxList.Loaded) -> OutboxList.Loaded): Boolean = lock.withLock {
        val loaded = loadedOrNull() ?: return@withLock false
        val changed = next(loaded)
        val queued = changed.queued
        val held = changed.held
        // Not cancellable: a write that completes must also be what everyone observes.
        withContext(NonCancellable + dispatcher) {
            try {
                if (queued.isEmpty() && held.isEmpty()) {
                    // Durably gone: a discarded transaction must not come back after a power loss.
                    SystemFileSystem.delete(file, mustExist = false)
                    syncDirectoryOf(path)
                } else {
                    val plain = StoredOutbox.encode(queued, held)
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
                state.value = loaded.copy(queued = queued, held = held)
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
 * signature again, a held Safe signature's too. Nothing is written anywhere. A sealed outbox lost
 * before it ([lostMarker], the sealed file's marker, there) is said as lost until it is acknowledged,
 * which deletes the marker, on [dispatcher].
 */
class MemoryOutbox(
    private val lostMarker: String? = null,
    private val dispatcher: CoroutineDispatcher? = null,
) : Outbox {
    private val state = MutableStateFlow(OutboxList.Loaded(emptyList(), lost = lostMarker?.let(::markerThere) == true))
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

    override suspend fun hold(held: HeldSafeSignature): Boolean = lock.withLock {
        val current = state.value
        val others = current.held.filterNot { it.safe == held.safe && it.nonce == held.nonce }
        state.value = current.copy(held = others + held)
        true
    }

    override suspend fun release(safe: AccountId, hash: Digest): Boolean = lock.withLock {
        state.value = state.value.copy(held = state.value.held.filterNot { it.safe == safe && it.hash == hash })
        true
    }

    override suspend fun acknowledgeLost(): Boolean = lock.withLock {
        val marker = lostMarker
        if (marker != null && state.value.lost) {
            val deleted = withContext(NonCancellable + (dispatcher ?: Dispatchers.Default)) {
                try {
                    SystemFileSystem.delete(Path(marker), mustExist = false)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (!deleted) return@withLock false
        }
        state.value = state.value.copy(lost = false)
        true
    }
}

/** Whether the lost marker at [path] is there; not when it cannot be told. */
private fun markerThere(path: String): Boolean = try {
    SystemFileSystem.exists(Path(path))
} catch (e: Exception) {
    false
}
