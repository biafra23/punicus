package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Digest
import eth.serotine.domain.repository.HeldConflict
import eth.serotine.domain.repository.HeldSafeSignature
import eth.serotine.domain.repository.KeptSafe
import eth.serotine.domain.repository.KeptSafes
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.OutboxNotifier
import eth.serotine.domain.repository.QueueConflict
import eth.serotine.domain.repository.QueuedTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.test.assertNull

/**
 * An [Outbox] in memory: [queued] and [held] as the use cases change them; [unreadable] makes it read
 * as [OutboxList.Unreadable], and [failSaves] refuses every change.
 */
internal class ListOutbox : Outbox {
    val queued = MutableStateFlow<List<QueuedTransaction>>(emptyList())
    val held = MutableStateFlow<List<HeldSafeSignature>>(emptyList())
    var unreadable = false
    var failSaves = false

    override fun observe(): Flow<OutboxList> = combine(queued, held) { queued, held ->
        if (unreadable) OutboxList.Unreadable else OutboxList.Loaded(queued, held = held)
    }

    override suspend fun put(queued: QueuedTransaction): Boolean {
        if (failSaves) return false
        this.queued.value = this.queued.value.filterNot { it.accountId == queued.accountId } + queued
        return true
    }

    override suspend fun remove(account: AccountId): Boolean {
        if (failSaves) return false
        queued.value = queued.value.filterNot { it.accountId == account }
        return true
    }

    override suspend fun hold(held: HeldSafeSignature): Boolean {
        if (failSaves) return false
        this.held.value = this.held.value.filterNot { it.safe == held.safe && it.nonce == held.nonce } + held
        return true
    }

    override suspend fun release(safe: AccountId, hash: Digest): Boolean {
        if (failSaves) return false
        held.value = held.value.filterNot { it.safe == safe && it.hash == hash }
        return true
    }

    override suspend fun acknowledgeLost(): Boolean = true
}

/** [KeptSafes] a test sets: one Safe's last verified state, or none. */
internal class FakeKeptSafes(kept: KeptSafe? = null) : KeptSafes {
    val kept = MutableStateFlow(kept)

    override fun observe(safe: AccountId): Flow<KeptSafe?> = this.kept.map { it }
}

/** An [OutboxNotifier] that writes down what it is told, and holds it to never being told a signature not sent. */
internal class RecordingNotifier : OutboxNotifier {
    val told = mutableListOf<String>()

    override fun sent(queued: QueuedTransaction, hash: String) {
        told += "sent $hash"
    }

    override fun conflict(queued: QueuedTransaction, conflict: QueueConflict) {
        // Never the bytes of a transaction not sent.
        assertNull(queued.signed)
        told += "conflict"
    }

    override fun heldChecked(held: HeldSafeSignature) {
        told += "checked ${held.hash.hex}"
    }

    override fun heldConflict(held: HeldSafeSignature, conflict: HeldConflict) {
        // Never a signature not checked.
        assertNull(held.signature)
        told += "held conflict $conflict"
    }
}
