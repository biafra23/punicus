package eth.serotine.domain.repository

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.UInt256
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The port for the outbox (docs/lenient-mode.md, *The outbox*; owner rulings, 2026-11-06): the
 * transactions signed in lenient mode, each waiting for the check at a fresh verified head that
 * alone sends it. At most one per account ([QueuedTransaction.accountId]); [put] replaces the
 * account's. Nothing here has left the device: a queued transaction's signed bytes are neither
 * public data nor key material, and whoever has them can broadcast them.
 *
 * :data keeps them in a file sealed with a hardware key of the outbox's own, out of backups and
 * device transfers, never logged; where there is no hardware key to seal with, in memory only, so
 * a restart asks for the signature again (docs/keys-and-signing.md, *The outbox's seal key*).
 * Never throws.
 */
interface Outbox {

    /** What is queued, re-emitted on each change, or that the outbox cannot be read. */
    fun observe(): Flow<OutboxList>

    /** Queues [queued] in place of anything its account had queued; false when that could not be saved. */
    suspend fun put(queued: QueuedTransaction): Boolean

    /** Forgets what [account] had queued, its signed bytes with it; false when that could not be saved. */
    suspend fun remove(account: AccountId): Boolean

    /** Clears [OutboxList.Loaded.lost], once it was said; false when that could not be saved. */
    suspend fun acknowledgeLost(): Boolean

    companion object {
        /**
         * The outbox of a wallet that queues nothing (a test, or a use case built without one):
         * always empty, and every change refused.
         */
        val None: Outbox = object : Outbox {
            override fun observe(): Flow<OutboxList> = flowOf(OutboxList.Loaded(emptyList()))

            override suspend fun put(queued: QueuedTransaction): Boolean = false

            override suspend fun remove(account: AccountId): Boolean = false

            override suspend fun acknowledgeLost(): Boolean = false
        }
    }
}

/** What [Outbox.observe] holds. */
sealed interface OutboxList {

    /**
     * The [queued] transactions, oldest first. [lost] when queued signed bytes could not be opened
     * since they were sealed (the seal key gone: the key store reset, or a copy restored on another
     * device): they are deleted, and the user is told once and asked to sign again.
     */
    data class Loaded(val queued: List<QueuedTransaction>, val lost: Boolean = false) : OutboxList

    /** The outbox cannot be read right now; nothing queued can be checked or sent until it can. */
    data object Unreadable : OutboxList
}

/**
 * A transaction from [account] on [network], signed in lenient mode on the state last verified
 * ([builtOn]) and waiting in the outbox. [transaction] is what was signed, [signed] its bytes
 * (null once a conflict deleted them: the user signs again or discards it), [action] what it does,
 * as its record says once it is sent, and [maxCost] the most it can take of the native balance
 * (its value and its gas limit times its fee cap). [signedAt] (unix seconds) starts its hour: past
 * it, it is sent only after the user's tap ([confirmedAt]).
 */
data class QueuedTransaction(
    val network: Network,
    val account: Address,
    /** What names it while it waits, its bytes deleted or not: the hash of the bytes first signed. */
    val id: String,
    val action: SentAction,
    val level: FeeLevel,
    val transaction: Eip1559Transaction,
    val signed: SignedTransfer?,
    val maxCost: UInt256,
    val signedAt: Long,
    val builtOn: DraftBasis,
    val conflict: QueueConflict? = null,
    val confirmedAt: Long? = null,
) {
    init {
        require(transaction.chainId == network.chainId) { "a queued transaction is signed for its own network" }
        require((signed == null) == (conflict != null)) { "the signed bytes are kept until a conflict, and only then deleted" }
    }

    /** The account it waits to be sent from. */
    val accountId: AccountId
        get() = AccountId(network, account)

    /** Its transaction hash once sent, while its bytes are kept. */
    val hash: String?
        get() = signed?.hash
}

/**
 * What a draft was built on, each value as the light client verified it last (docs/lenient-mode.md,
 * *What a draft is built on*): the native balance at [balanceBlock] (a head of [balanceAsOf]), the
 * token's at [tokenBalanceBlock] for a token, the mined count at [nonceBlock], and the fee quote up
 * to [feeBlock], each with the time of its head (unix seconds; null when unknown). The review shows
 * each value's age; the check reads every one again.
 */
data class DraftBasis(
    val balanceBlock: Long?,
    val balanceAsOf: Long?,
    val tokenBalanceBlock: Long?,
    val tokenBalanceAsOf: Long?,
    val nonceBlock: Long,
    val nonceAsOf: Long?,
    val feeBlock: Long,
    val feeAsOf: Long?,
) {
    /** The oldest time among these, what the review's "prepared on data from …" names; null when none is known. */
    val oldestAsOf: Long?
        get() = listOfNotNull(balanceAsOf, tokenBalanceAsOf, nonceAsOf, feeAsOf).minOrNull()
}

/**
 * Why the check found a queued transaction no longer stands (docs/lenient-mode.md, *The check
 * before sending*): its signed bytes are deleted, and the user signs again or discards it. Never
 * fixed by itself, since every field is in the signature.
 */
sealed interface QueueConflict {

    /** The fresh quote refused it, for [reason] ([detail]: the EVM's words, for [QueueRefusal.INFEASIBLE]). */
    data class Refused(val reason: QueueRefusal, val detail: String? = null) : QueueConflict

    /** Another transaction from the account took its nonce: the fresh one is [fresh], above the signed [signed]. */
    data class NonceUsed(val signed: Long, val fresh: Long) : QueueConflict

    /** The signed nonce is past the fresh one: it would wait behind a gap ([fresh] is the next one). */
    data class NonceGap(val signed: Long, val fresh: Long) : QueueConflict

    /** It needs more gas ([estimate]) than it was signed for ([limit]). */
    data class NeedsMoreGas(val limit: Long, val estimate: Long) : QueueConflict

    /** Fees rose since it was signed: the cap no longer leaves the headroom asked for; [nowAtMost] is the most it would cost now. */
    data class FeesRose(val nowAtMost: UInt256) : QueueConflict

    /** The fresh native balance does not cover what the signed bytes may take and what earlier transactions may still take. */
    data object InsufficientFunds : QueueConflict

    /** The network refused the broadcast ([detail], its words); nothing is recorded, so it holds no nonce. */
    data class Rejected(val detail: String) : QueueConflict
}

/**
 * Why the fresh quote refused a queued transaction ([QueueConflict.Refused]): the refusals of a
 * strict quote that the account's funds of the network's currency do not cover (those are
 * [QueueConflict.InsufficientFunds]) and that do not just wait for the light client.
 */
enum class QueueRefusal {
    /** The account holds less of the token than the amount, less what its earlier transactions still move. */
    INSUFFICIENT_TOKEN,

    /** The token's address answers as no token. */
    NOT_A_TOKEN,

    /** It reverts, run as a verified call from the account. */
    REVERTS,

    /** It cannot succeed for a reason other than the account's funds. */
    INFEASIBLE,

    /** The engine will not estimate or run it on this build. */
    ENGINE_REFUSED,
}
