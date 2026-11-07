package eth.serotine.data.outbox

import eth.serotine.data.keys.EnvelopeCrypto
import eth.serotine.data.keys.HardwareSecret
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Digest
import eth.serotine.domain.repository.HeldSafeSignature
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.QueuedTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration.Companion.seconds

/**
 * The outbox of a device, chosen at its first use, on [dispatcher], never on the caller's thread:
 * asking a key store whether its key is in hardware can make the key and take a while. Sealed in
 * the file at [path] when [hardware] is hardware-backed ([KeyProtection.HARDWARE]); in memory only
 * otherwise, a software key store included, since a software key is in the same copy of the
 * device's storage as the file and would seal nothing (docs/keys-and-signing.md, *The outbox's seal
 * key*). Null [hardware]: no hardware key store here, memory only. The seal asks whether its key
 * exists before anything else touches it, so a lost one is told apart from a new one.
 *
 * A key store that fails while it is asked never hides a sealed file behind memory: with the file
 * there, the outbox is unreadable, nothing in it lost, sent unchecked or forgotten, and the choice
 * is made again at the next use. So it is when the key store will not say whether its key exists.
 * With no file there, nothing is sealed with the key yet: where the platform can forget it
 * ([forgetKey]; a Mac), it is forgotten once, and only once the file's directory is listed without
 * it, so a new one can be made; then, or where it cannot be forgotten, memory only for this run. A
 * screen that observes it while unreadable is told again once it opens: the key store is asked
 * again every [UNREADABLE_RETRY].
 *
 * A key known never to open the file here ([OutboxKeyGone], [hardware] says so: a Mac's enclave
 * state made on another Mac) is a lost key, said as lost as [SealedOutboxFile] says one: the
 * marker first, the file deleted, the key forgotten ([forgetKey]), and the outbox chosen again,
 * with no file now. A memory-only outbox says a loss found that way too, until it is
 * acknowledged. Only a key known gone is: a file that may hold a transaction already on the
 * network is never deleted on a failure that may pass.
 */
internal class ChosenOutbox(
    private val path: String,
    private val hardware: (() -> HardwareSecret)?,
    private val dispatcher: CoroutineDispatcher,
    private val forgetKey: () -> Unit = {},
) : Outbox {
    private val lock = Mutex()
    private var chosen: Outbox? = null
    private val lostMarker = "$path.lost"

    private suspend fun outbox(): Outbox = lock.withLock {
        // A key store that failed is asked again next time: only a choice made is kept.
        chosen ?: withContext(dispatcher) { choose() }.also { if (it !== UnreadableOutbox) chosen = it }
    }

    /** The outbox to use; [again] once a key was forgotten, so it is never forgotten twice in one choice. */
    private fun choose(again: Boolean = false): Outbox {
        val make = hardware ?: return MemoryOutbox(lostMarker, dispatcher)
        val sealedThere = try {
            SystemFileSystem.exists(Path(path))
        } catch (e: Exception) {
            true
        }
        val failed: () -> Outbox = {
            when {
                sealedThere -> UnreadableOutbox
                again -> MemoryOutbox(lostMarker, dispatcher)
                // Nothing is sealed with it: a key that fails is forgotten, and a new one made, once. Only
                // when the file is known to be absent: on the JVM, a file that cannot be read says it is not there.
                !knownAbsent() -> MemoryOutbox(lostMarker, dispatcher)
                else -> try {
                    forgetKey()
                    choose(again = true)
                } catch (e: Exception) {
                    MemoryOutbox(lostMarker, dispatcher)
                }
            }
        }
        val secret = try {
            make()
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutboxKeyGone) {
            return if (again) failed() else lose(e, sealedThere)
        } catch (e: Exception) {
            println("Serotine: the outbox's key store is not available (${e::class.simpleName})")
            return failed()
        }
        val seal = OutboxSeal(secret)
        // It would not say whether its key exists: nothing is concluded, and it is asked again next time.
        if (sealedThere && seal.keyExistedAtStart == null) return UnreadableOutbox
        val protection = try {
            secret.protection
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Serotine: the outbox's key store does not answer (${e::class.simpleName})")
            return failed()
        }
        return if (protection == KeyProtection.HARDWARE) {
            SealedOutboxFile(path, seal, dispatcher)
        } else {
            MemoryOutbox(lostMarker, dispatcher)
        }
    }

    /** Whether the file is absent, its directory listed without it; false when that cannot be told. */
    private fun knownAbsent(): Boolean = try {
        val file = Path(path)
        val directory = file.parent ?: return false
        SystemFileSystem.list(directory).none { it.name == file.name }
    } catch (e: Exception) {
        false
    }

    /**
     * The key [gone] says never opens a sealed file here: with one there ([sealedThere]), it is said
     * as lost, the marker first so a crash between the two never hides it, and deleted. The key is
     * forgotten either way, so a new one can be made; then the outbox is chosen again.
     */
    private fun lose(gone: OutboxKeyGone, sealedThere: Boolean): Outbox {
        try {
            if (sealedThere) {
                val marker = Path(lostMarker)
                if (!SystemFileSystem.exists(marker)) {
                    marker.parent?.let { EnvelopeCrypto.createPrivateDirectory(it.toString()) }
                    EnvelopeCrypto.writeDurably(lostMarker, ByteArray(0))
                }
                SystemFileSystem.delete(Path(path), mustExist = false)
                println("Serotine: the outbox could not be opened (${gone.message}); what it held is deleted")
            }
            forgetKey()
        } catch (e: Exception) {
            println("Serotine: the outbox's lost key could not be said (${e::class.simpleName}); it is left as it is")
            return if (sealedThere) UnreadableOutbox else MemoryOutbox(lostMarker, dispatcher)
        }
        return choose(again = true)
    }

    override fun observe(): Flow<OutboxList> = flow {
        while (true) {
            val outbox = outbox()
            if (outbox !== UnreadableOutbox) return@flow emitAll(outbox.observe())
            emit(OutboxList.Unreadable)
            delay(UNREADABLE_RETRY)
        }
    }

    override suspend fun put(queued: QueuedTransaction): Boolean = outbox().put(queued)

    override suspend fun remove(account: AccountId): Boolean = outbox().remove(account)

    override suspend fun hold(held: HeldSafeSignature): Boolean = outbox().hold(held)

    override suspend fun release(safe: AccountId, hash: Digest): Boolean = outbox().release(safe, hash)

    override suspend fun acknowledgeLost(): Boolean = outbox().acknowledgeLost()
}

/** How long an unreadable outbox's observer waits before the key store is asked again. */
internal val UNREADABLE_RETRY = 60.seconds

/**
 * What a platform's [ChosenOutbox] hardware throws when the outbox's own key is known never to open
 * its sealed file here (a Mac's enclave state made on another Mac), as opposed to a key store that
 * fails this time, a damaged state included: the file is then lost, and said so. Never thrown on a
 * failure that may pass.
 */
internal class OutboxKeyGone(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The outbox of a run whose key store failed with a sealed file there: unreadable, and nothing changes. */
private object UnreadableOutbox : Outbox {
    override fun observe(): Flow<OutboxList> = flowOf(OutboxList.Unreadable)

    override suspend fun put(queued: QueuedTransaction): Boolean = false

    override suspend fun remove(account: AccountId): Boolean = false

    override suspend fun hold(held: HeldSafeSignature): Boolean = false

    override suspend fun release(safe: AccountId, hash: Digest): Boolean = false

    override suspend fun acknowledgeLost(): Boolean = false
}
