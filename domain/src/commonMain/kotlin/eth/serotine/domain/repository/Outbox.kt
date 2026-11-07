package eth.serotine.domain.repository

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
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
 * Beside them, the Safe signatures held in lenient mode ([HeldSafeSignature]; *Safe transactions in
 * lenient mode*): an owner's signature of a Safe transaction, made on the Safe last verified and
 * waiting for its check before it is saved to the Safe's queue and shown to another owner. At most
 * one per Safe and Safe nonce; [hold] replaces the one at its nonce. A held signature takes no
 * account's place among the queued transactions: it spends no nonce of the owner's.
 *
 * :data keeps both in one file sealed with a hardware key of the outbox's own, out of backups and
 * device transfers, never logged; where there is no hardware key to seal with, in memory only, so
 * a restart asks for the signature again (docs/keys-and-signing.md, *The outbox's seal key*).
 * Never throws.
 */
interface Outbox {

    /** What is queued and held, re-emitted on each change, or that the outbox cannot be read. */
    fun observe(): Flow<OutboxList>

    /** Queues [queued] in place of anything its account had queued; false when that could not be saved. */
    suspend fun put(queued: QueuedTransaction): Boolean

    /** Forgets what [account] had queued, its signed bytes with it; false when that could not be saved. */
    suspend fun remove(account: AccountId): Boolean

    /**
     * Holds [held] in place of anything held for its Safe at its Safe nonce; false when that could
     * not be saved.
     */
    suspend fun hold(held: HeldSafeSignature): Boolean

    /**
     * Forgets the signature held for the Safe [safe] of the Safe transaction [hash], its signature
     * with it; false when that could not be saved. Nothing held under that hash is no change.
     */
    suspend fun release(safe: AccountId, hash: Digest): Boolean

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

            override suspend fun hold(held: HeldSafeSignature): Boolean = false

            override suspend fun release(safe: AccountId, hash: Digest): Boolean = false

            override suspend fun acknowledgeLost(): Boolean = false
        }
    }
}

/** What [Outbox.observe] holds. */
sealed interface OutboxList {

    /**
     * The [queued] transactions, oldest first, and the [held] Safe signatures, oldest first. [lost]
     * when what was sealed could not be opened since (the seal key gone: the key store reset, or a
     * copy restored on another device): it is deleted, and the user is told once and asked to sign
     * again.
     */
    data class Loaded(
        val queued: List<QueuedTransaction>,
        val lost: Boolean = false,
        val held: List<HeldSafeSignature> = emptyList(),
    ) : OutboxList

    /** The outbox cannot be read right now; nothing queued can be checked or sent until it can. */
    data object Unreadable : OutboxList
}

/**
 * A transaction from [account] on [network], signed in lenient mode on the state last verified
 * ([builtOn]) and waiting in the outbox. [transaction] is what was signed, [signed] its bytes
 * (null once a conflict deleted them: the user signs again or discards it), [action] what it does,
 * as its record says once it is sent, and [maxCost] the most it can take of the native balance
 * (its value and its gas limit times its fee cap). [signedAt] (unix seconds) starts its hour: past
 * it, it is sent only after the user's tap ([confirmedAt]), which starts an hour of its own.
 *
 * [sending] from just before its broadcast until its outcome is recorded: one found so (the process
 * ended, or a record could not be saved, in between) may have reached the network, so it is
 * resolved as strict mode resolves a broadcast no peer answered: recorded as maybe sent, its status
 * offering to try the same bytes again, never checked or signed again.
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
    val sending: Boolean = false,
) {
    init {
        require(transaction.chainId == network.chainId) { "a queued transaction is signed for its own network" }
        require((signed == null) == (conflict != null)) { "the signed bytes are kept until a conflict deletes them" }
        require(!sending || signed != null) { "only signed bytes are sent" }
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
 * to [feeBlock], each with the time of its head (unix seconds; null when unknown). For a Safe's
 * approval or execution, also the Safe's nonce, owners and threshold at [safeBlock] (a head of
 * [safeAsOf]); null for a transfer. The review shows each value's age; the check reads every one
 * again.
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
    val safeBlock: Long? = null,
    val safeAsOf: Long? = null,
) {
    /** The oldest time among these, what the review's "prepared on data from …" names; null when none is known. */
    val oldestAsOf: Long?
        get() = listOfNotNull(balanceAsOf, tokenBalanceAsOf, nonceAsOf, feeAsOf, safeAsOf).minOrNull()
}

/**
 * An owner's signature of a Safe transaction, made in lenient mode on the Safe last verified and
 * held in the outbox until its check at a fresh verified head (docs/lenient-mode.md, *Safe
 * transactions in lenient mode*; owner rulings, 2026-11-06): the Safe verified as a Safe, the
 * transaction's Safe nonce not used yet, [owner] a current owner. Only a request that passes is
 * saved to the Safe's queue ([SafeTxQueue], which keeps only what was checked against the chain),
 * and only then are its QR code and text offered. Until then it is not shown to anyone: whoever has
 * it holds one owner's confirmation of [request].
 *
 * [request] is the Safe transaction with no signature; [signature] is [owner]'s of its hash, null
 * once a conflict deleted it ([conflict]: the owner signs again, or the request is discarded).
 * [proposed] when it is a transfer this device proposed (Sign again proposes it anew), not a
 * co-signature of a request in the Safe's queue. [signedAt] in unix seconds; [builtOn] what it was
 * signed on. Its [toString] names the request and the owner, never the signature.
 */
data class HeldSafeSignature(
    val request: SigningRequest,
    val owner: Address,
    val signature: Signature?,
    val proposed: Boolean,
    val signedAt: Long,
    val builtOn: SafeDraftBasis,
    val conflict: HeldConflict? = null,
) {
    init {
        require(request.signatures.isEmpty()) { "a held request carries no signature but the one held beside it" }
        require((signature == null) == (conflict != null)) { "the signature is kept until a conflict deletes it" }
    }

    /** The Safe it is of, on its network. */
    val safe: AccountId
        get() = AccountId(request.network, request.safe)

    /** Its network. */
    val network: Network
        get() = request.network

    /** Its Safe nonce: one held signature per Safe and nonce. */
    val nonce: Long
        get() = request.nonce

    /** The Safe transaction's hash, computed here: what names it. */
    val hash: Digest
        get() = request.hash

    /** [request] with the held signature, as the Safe's queue is to keep it once checked; null once deleted. */
    fun signed(): SigningRequest? =
        signature?.let { request.withSignatures(listOf(SafeSignature.Ecdsa(owner, it))) }

    override fun toString(): String =
        "HeldSafeSignature(request=$request, owner=$owner, signed=${signature != null}, conflict=$conflict)"
}

/**
 * What a held Safe signature was signed on (docs/lenient-mode.md, *Safe transactions in lenient
 * mode*): the Safe's nonce, owners and threshold as verified at [safeBlock], a head of [safeAsOf];
 * for a proposal, the Safe's balance of what it sends at [balanceBlock], a head of [balanceAsOf].
 * Times in unix seconds, null when unknown.
 */
data class SafeDraftBasis(
    val safeBlock: Long,
    val safeAsOf: Long?,
    val balanceBlock: Long? = null,
    val balanceAsOf: Long? = null,
) {
    /** The oldest time among these, what a review's "prepared on data from …" names; null when none is known. */
    val oldestAsOf: Long?
        get() = listOfNotNull(safeAsOf, balanceAsOf).minOrNull()
}

/**
 * Why the check found a held Safe signature no longer stands (docs/lenient-mode.md, *Safe
 * transactions in lenient mode*): its signature is deleted, and it is never saved or shown.
 */
sealed interface HeldConflict {

    /**
     * Another Safe transaction used its nonce: the Safe is at [safeNonce] now. A transaction at a new
     * nonce has another hash, so the owner signs again; a request of the Safe's queue at a used
     * nonce can never run.
     */
    data class NonceUsed(val safeNonce: Long) : HeldConflict

    /**
     * A transfer this device proposed found another transaction at its nonce in the Safe's queue, put
     * there meanwhile (a proposal made strictly, an import): two at one nonce, of which only one can
     * ever run, so it is proposed again, at the next nonce, and signed anew.
     */
    data object NonceTaken : HeldConflict

    /**
     * A transfer this device proposed found the Safe's queue short of it: nothing takes the nonce
     * [next], which comes first (the transaction held before it was discarded or did not check out).
     * It could not run until another filled [next], so it is proposed again, at the next nonce, and
     * signed anew, as strict mode proposes.
     */
    data class NonceGap(val next: Long) : HeldConflict

    /** The signer is no longer an owner of the Safe: it cannot sign it at all, so it is discarded. */
    data object NotAnOwner : HeldConflict

    /** The Safe as it stands now refuses it, for [reason]. */
    data class Refused(val reason: HeldRefusal) : HeldConflict
}

/** Why the Safe as it stands at the check refuses a held signature ([HeldConflict.Refused]). */
enum class HeldRefusal {
    /** The address holds no Safe this app recognises any more. */
    NOT_A_SAFE,

    /** The Safe is on a singleton from before 1.3.0's EIP-712 domain: nothing is signed against it. */
    TOO_OLD_TO_SIGN,

    /** It would now leave the Safe an n-of-n above one with no recovery module (owner ruling, 2026-10-18, #54). */
    NEEDS_RECOVERY_MODULE,

    /** The signature does not recover to its owner over the hash computed here: never shown. */
    BAD_SIGNATURE,

    /** It is refused for what it does (a Safe refusal decided by the transaction itself). */
    UNSIGNABLE,
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

    /** Fees rose since it was signed, past the headroom asked: [nowAtMost] is the most it would cost now. */
    data class FeesRose(val nowAtMost: UInt256) : QueueConflict

    /** The fresh native balance does not cover what the signed bytes and the earlier transactions may still take. */
    data object InsufficientFunds : QueueConflict

    /** The network refused the broadcast ([detail], its words); nothing is recorded, so it holds no nonce. */
    data class Rejected(val detail: String) : QueueConflict
}

/**
 * Why the fresh quote refused a queued transaction ([QueueConflict.Refused]): the refusals of a
 * strict quote that the account's funds of the network's currency do not cover (those are
 * [QueueConflict.InsufficientFunds]) and that do not just wait for the light client. The last ones
 * are a Safe's, for its approval on chain or its execution, as the Safe stands at the check.
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

    /** The Safe's nonce has passed the Safe transaction: it ran, or another transaction used its nonce. */
    SAFE_NONCE_USED,

    /** The account approving is no longer an owner of the Safe. */
    NOT_AN_OWNER,

    /** The owner has approved the Safe transaction on chain already. */
    ALREADY_APPROVED,

    /** An approval or execution of the Safe transaction was sent from here already, and no block has it yet. */
    ALREADY_SENT,

    /** The address holds no Safe this app recognises any more. */
    NOT_A_SAFE,

    /** The Safe as it stands now refuses it for another reason (its version, its owners and threshold). */
    SAFE_REFUSED,
}
