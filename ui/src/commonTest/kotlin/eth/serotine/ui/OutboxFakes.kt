package eth.serotine.ui

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SyncPolicy
import eth.serotine.domain.repository.KeptFees
import eth.serotine.domain.repository.KeptNonce
import eth.serotine.domain.repository.HeldSafeSignature
import eth.serotine.domain.repository.KeptSafe
import eth.serotine.domain.repository.KeptSafes
import eth.serotine.domain.repository.KeptTransactionInputs
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.SyncPolicySetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An [Outbox] in memory: [list] as a test sets it, changed by the use cases unless [failSaves];
 * [unreadable] makes it read as [OutboxList.Unreadable]. [removed] counts each account removed.
 */
internal class MemoryOutbox(vararg initial: QueuedTransaction) : Outbox {
    val list = MutableStateFlow<OutboxList>(OutboxList.Loaded(initial.toList()))
    var failSaves = false
    val removed = mutableListOf<AccountId>()

    /** What is queued now, an unreadable outbox's none. */
    val queued: List<QueuedTransaction>
        get() = (list.value as? OutboxList.Loaded)?.queued.orEmpty()

    override fun observe(): Flow<OutboxList> = list

    override suspend fun put(queued: QueuedTransaction): Boolean {
        if (failSaves) return false
        val loaded = list.value as? OutboxList.Loaded ?: return false
        list.value = loaded.copy(queued = loaded.queued.filterNot { it.accountId == queued.accountId } + queued)
        return true
    }

    override suspend fun remove(account: AccountId): Boolean {
        if (failSaves) return false
        val loaded = list.value as? OutboxList.Loaded ?: return false
        removed += account
        list.value = loaded.copy(queued = loaded.queued.filterNot { it.accountId == account })
        return true
    }

    /** What is held now, an unreadable outbox's none. */
    val held: List<HeldSafeSignature>
        get() = (list.value as? OutboxList.Loaded)?.held.orEmpty()

    override suspend fun hold(held: HeldSafeSignature): Boolean {
        if (failSaves) return false
        val loaded = list.value as? OutboxList.Loaded ?: return false
        list.value = loaded.copy(held = loaded.held.filterNot { it.safe == held.safe && it.nonce == held.nonce } + held)
        return true
    }

    override suspend fun release(safe: AccountId, hash: Digest): Boolean {
        if (failSaves) return false
        val loaded = list.value as? OutboxList.Loaded ?: return false
        list.value = loaded.copy(held = loaded.held.filterNot { it.safe == safe && it.hash == hash })
        return true
    }

    override suspend fun acknowledgeLost(): Boolean {
        if (failSaves) return false
        val loaded = list.value as? OutboxList.Loaded ?: return false
        list.value = loaded.copy(lost = false)
        return true
    }
}

/** [KeptSafes] a test sets: the Safe last verified, [kept], or none. */
internal class MemoryKeptSafes(kept: KeptSafe? = null) : KeptSafes {
    val kept = MutableStateFlow(kept)

    override fun observe(safe: AccountId): Flow<KeptSafe?> = this.kept
}

/** A [SyncPolicySetting] in memory, strict as in every build; [failSaves] makes a choice fail. */
internal class MemorySyncPolicy(policy: SyncPolicy = SyncPolicy.STRICT) : SyncPolicySetting {
    val policy = MutableStateFlow(policy)
    var failSaves = false

    override fun observe(): Flow<SyncPolicy> = this.policy

    override suspend fun set(policy: SyncPolicy): Boolean {
        if (failSaves) return false
        this.policy.value = policy
        return true
    }
}

/** [KeptTransactionInputs] a test sets: each account's [nonces] and each network's [fees]. */
internal class FakeKeptInputs : KeptTransactionInputs {
    val nonces = mutableMapOf<Pair<Network, Address>, KeptNonce>()
    val fees = mutableMapOf<Network, KeptFees>()

    override suspend fun minedNonce(network: Network, account: Address): KeptNonce? = nonces[network to account]

    override suspend fun fees(network: Network): KeptFees? = fees[network]
}
