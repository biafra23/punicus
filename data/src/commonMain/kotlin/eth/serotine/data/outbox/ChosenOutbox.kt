package eth.serotine.data.outbox

import eth.serotine.data.keys.HardwareSecret
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.QueuedTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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
 * there, the outbox is unreadable for this run (said, and tried again at the next start), so
 * nothing in it is lost, sent unchecked or forgotten; with none, memory only for this run.
 */
internal class ChosenOutbox(
    private val path: String,
    private val hardware: (() -> HardwareSecret)?,
    private val dispatcher: CoroutineDispatcher,
) : Outbox {
    private val lock = Mutex()
    private var chosen: Outbox? = null

    private suspend fun outbox(): Outbox = lock.withLock {
        chosen ?: withContext(dispatcher) { choose() }.also { chosen = it }
    }

    private fun choose(): Outbox {
        val make = hardware ?: return MemoryOutbox()
        val sealedThere = try {
            SystemFileSystem.exists(Path(path))
        } catch (e: Exception) {
            true
        }
        val failed = if (sealedThere) UnreadableOutbox else MemoryOutbox()
        val secret = try {
            make()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Serotine: the outbox's key store is not available (${e::class.simpleName})")
            return failed
        }
        val seal = OutboxSeal(secret)
        val protection = try {
            secret.protection
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Serotine: the outbox's key store does not answer (${e::class.simpleName})")
            return failed
        }
        return if (protection == KeyProtection.HARDWARE) SealedOutboxFile(path, seal, dispatcher) else MemoryOutbox()
    }

    override fun observe(): Flow<OutboxList> = flow { emitAll(outbox().observe()) }

    override suspend fun put(queued: QueuedTransaction): Boolean = outbox().put(queued)

    override suspend fun remove(account: AccountId): Boolean = outbox().remove(account)

    override suspend fun acknowledgeLost(): Boolean = outbox().acknowledgeLost()
}

/** The outbox of a run whose key store failed with a sealed file there: unreadable, and nothing changes. */
private object UnreadableOutbox : Outbox {
    override fun observe(): Flow<OutboxList> = flowOf(OutboxList.Unreadable)

    override suspend fun put(queued: QueuedTransaction): Boolean = false

    override suspend fun remove(account: AccountId): Boolean = false

    override suspend fun acknowledgeLost(): Boolean = false
}
