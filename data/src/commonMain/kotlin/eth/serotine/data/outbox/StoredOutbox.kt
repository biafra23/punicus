package eth.serotine.data.outbox

import eth.serotine.data.keys.fromHex
import eth.serotine.data.keys.toHex
import eth.serotine.data.safe.WireRequest
import eth.serotine.data.safe.WireSignature
import eth.serotine.data.transfers.StoredTransfer
import eth.serotine.data.transfers.storedTransfer
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.DraftBasis
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.HeldConflict
import eth.serotine.domain.repository.HeldRefusal
import eth.serotine.domain.repository.HeldSafeSignature
import eth.serotine.domain.repository.QueueConflict
import eth.serotine.domain.repository.QueueRefusal
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.SafeDraftBasis
import eth.serotine.domain.repository.SentTransfer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The outbox's content as it is sealed (docs/lenient-mode.md, *The signed bytes kept*): what is
 * queued, oldest first, and the Safe signatures held until their check ([held], oldest first;
 * *Safe transactions in lenient mode*). [version] comes first and is always written; a newer one
 * does not open here. Version 1 held no signatures, and reads as holding none. Never logged: it
 * holds signed bytes that are not public yet.
 */
@Serializable
internal class StoredOutbox(
    val version: Int,
    val queued: List<StoredQueued>,
    val held: List<StoredHeld> = emptyList(),
) {
    companion object {
        const val VERSION = 2
        private val JSON = Json { ignoreUnknownKeys = false }
        private val VERSION_JSON = Json { ignoreUnknownKeys = true }

        /** [queued] and [held] as the bytes sealed. */
        fun encode(queued: List<QueuedTransaction>, held: List<HeldSafeSignature> = emptyList()): ByteArray =
            JSON.encodeToString(
                serializer(),
                StoredOutbox(VERSION, queued.map(::stored), held.map(StoredHeld::of)),
            ).encodeToByteArray()

        /** What [bytes] hold: what is queued and held, that a newer version wrote them, or that they are damaged. */
        fun decode(bytes: ByteArray): Decoding {
            val text = bytes.decodeToString()
            val version = try {
                VERSION_JSON.decodeFromString(VersionOnly.serializer(), text).version
            } catch (e: Exception) {
                return Decoding.Damaged
            }
            if (version > VERSION) return Decoding.Newer
            return try {
                val stored = JSON.decodeFromString(serializer(), text)
                if (stored.version !in 1..VERSION || stored.version == 1 && stored.held.isNotEmpty()) {
                    Decoding.Damaged
                } else {
                    Decoding.Read(stored.queued.map(StoredQueued::toQueued), stored.held.map(StoredHeld::toHeld))
                }
            } catch (e: Exception) {
                // A SerializationException, a field out of range as the domain's types refuse it,
                // or a name this version does not know though it wrote this version: damaged.
                Decoding.Damaged
            }
        }
    }

    /** What [decode] found. */
    sealed interface Decoding {
        /** The [queued] transactions and the [held] Safe signatures. */
        data class Read(val queued: List<QueuedTransaction>, val held: List<HeldSafeSignature> = emptyList()) : Decoding

        /** A newer version wrote it: it is left as it is. */
        data object Newer : Decoding

        /** It is not an outbox this version can read, though it says this version. */
        data object Damaged : Decoding
    }

    /** The version alone, read first, so a newer layout is told from a damaged one. */
    @Serializable
    private class VersionOnly(val version: Int)
}

/**
 * One queued transaction. Its account, nonce, signed bytes (empty once a conflict deleted them),
 * action, most cost and signing time travel in a [StoredTransfer], the form the record of sent
 * transactions keeps, so an action is written one way; [tx] holds the signed fields.
 */
@Serializable
internal class StoredQueued(
    val id: String,
    val transfer: StoredTransfer,
    val level: String,
    val tx: StoredTx,
    val builtOn: StoredBasis,
    val conflict: StoredConflict? = null,
    val confirmedAt: Long? = null,
    val sending: Boolean = false,
) {
    fun toQueued(): QueuedTransaction {
        val record = transfer.toTransfer()
        val raw = record.signed.raw
        return QueuedTransaction(
            network = record.network,
            account = record.account,
            id = id,
            action = record.action,
            level = FeeLevel.entries.first { it.code == level },
            transaction = tx.toTransaction(),
            signed = if (raw.isEmpty()) null else record.signed,
            maxCost = record.maxCost,
            signedAt = record.sentAt,
            builtOn = builtOn.toBasis(),
            conflict = conflict?.toConflict(),
            confirmedAt = confirmedAt,
            sending = sending,
        )
    }
}

private fun stored(queued: QueuedTransaction) = StoredQueued(
    id = queued.id,
    transfer = storedTransfer(
        SentTransfer(
            network = queued.network,
            account = queued.account,
            nonce = queued.transaction.nonce,
            signed = queued.signed ?: SignedTransfer(ByteArray(0)),
            action = queued.action,
            maxCost = queued.maxCost,
            sentAt = queued.signedAt,
        ),
    ),
    level = queued.level.code,
    tx = StoredTx.of(queued.transaction),
    builtOn = StoredBasis.of(queued.builtOn),
    conflict = queued.conflict?.let(StoredConflict::of),
    confirmedAt = queued.confirmedAt,
    sending = queued.sending,
)

/** The signed fields of an EIP-1559 transaction, amounts in decimal, the address and the data in hex. */
@Serializable
internal class StoredTx(
    val chainId: Long,
    val nonce: Long,
    val tip: String,
    val maxFee: String,
    val gasLimit: Long,
    val to: String,
    val value: String,
    val data: String,
) {
    fun toTransaction() = Eip1559Transaction(
        chainId = chainId,
        nonce = nonce,
        maxPriorityFeePerGas = UInt256.of(tip),
        maxFeePerGas = UInt256.of(maxFee),
        gasLimit = gasLimit,
        to = Address.of(to),
        value = UInt256.of(value),
        data = data.fromHex(),
    )

    companion object {
        fun of(transaction: Eip1559Transaction) = StoredTx(
            chainId = transaction.chainId,
            nonce = transaction.nonce,
            tip = transaction.maxPriorityFeePerGas.toDecimal(),
            maxFee = transaction.maxFeePerGas.toDecimal(),
            gasLimit = transaction.gasLimit,
            to = transaction.to.hex,
            value = transaction.value.toDecimal(),
            data = transaction.data.toHex(),
        )
    }
}

/** A [DraftBasis] as stored. */
@Serializable
internal class StoredBasis(
    val balanceBlock: Long? = null,
    val balanceAsOf: Long? = null,
    val tokenBalanceBlock: Long? = null,
    val tokenBalanceAsOf: Long? = null,
    val nonceBlock: Long,
    val nonceAsOf: Long? = null,
    val feeBlock: Long,
    val feeAsOf: Long? = null,
    val safeBlock: Long? = null,
    val safeAsOf: Long? = null,
) {
    fun toBasis() = DraftBasis(
        balanceBlock,
        balanceAsOf,
        tokenBalanceBlock,
        tokenBalanceAsOf,
        nonceBlock,
        nonceAsOf,
        feeBlock,
        feeAsOf,
        safeBlock,
        safeAsOf,
    )

    companion object {
        fun of(basis: DraftBasis) = StoredBasis(
            basis.balanceBlock,
            basis.balanceAsOf,
            basis.tokenBalanceBlock,
            basis.tokenBalanceAsOf,
            basis.nonceBlock,
            basis.nonceAsOf,
            basis.feeBlock,
            basis.feeAsOf,
            basis.safeBlock,
            basis.safeAsOf,
        )
    }
}

/** A [QueueConflict] as stored: its [kind] and the fields that kind has. */
@Serializable
internal class StoredConflict(
    val kind: String,
    val signed: Long? = null,
    val fresh: Long? = null,
    val amount: String? = null,
    val refusal: String? = null,
    val detail: String? = null,
) {
    fun toConflict(): QueueConflict = when (kind) {
        REFUSED -> QueueConflict.Refused(QueueRefusal.entries.first { it.code == refusal }, detail)
        NONCE_USED -> QueueConflict.NonceUsed(requireNotNull(signed), requireNotNull(fresh))
        NONCE_GAP -> QueueConflict.NonceGap(requireNotNull(signed), requireNotNull(fresh))
        MORE_GAS -> QueueConflict.NeedsMoreGas(requireNotNull(signed), requireNotNull(fresh))
        FEES_ROSE -> QueueConflict.FeesRose(UInt256.of(requireNotNull(amount)))
        FUNDS -> QueueConflict.InsufficientFunds
        REJECTED -> QueueConflict.Rejected(requireNotNull(detail))
        else -> throw IllegalArgumentException("an unknown conflict")
    }

    companion object {
        private const val REFUSED = "refused"
        private const val NONCE_USED = "nonce-used"
        private const val NONCE_GAP = "nonce-gap"
        private const val MORE_GAS = "more-gas"
        private const val FEES_ROSE = "fees-rose"
        private const val FUNDS = "funds"
        private const val REJECTED = "rejected"

        fun of(conflict: QueueConflict): StoredConflict = when (conflict) {
            is QueueConflict.Refused -> StoredConflict(REFUSED, refusal = conflict.reason.code, detail = conflict.detail)
            is QueueConflict.NonceUsed -> StoredConflict(NONCE_USED, signed = conflict.signed, fresh = conflict.fresh)
            is QueueConflict.NonceGap -> StoredConflict(NONCE_GAP, signed = conflict.signed, fresh = conflict.fresh)
            is QueueConflict.NeedsMoreGas -> StoredConflict(MORE_GAS, signed = conflict.limit, fresh = conflict.estimate)
            is QueueConflict.FeesRose -> StoredConflict(FEES_ROSE, amount = conflict.nowAtMost.toDecimal())
            QueueConflict.InsufficientFunds -> StoredConflict(FUNDS)
            is QueueConflict.Rejected -> StoredConflict(REJECTED, detail = conflict.detail)
        }
    }
}

/** How a level is written: a name of its own, not the enum's, so a rename changes no file. */
private val FeeLevel.code: String
    get() = when (this) {
        FeeLevel.SLOW -> "slow"
        FeeLevel.NORMAL -> "normal"
        FeeLevel.FAST -> "fast"
    }

/** How a refusal is written. */
private val QueueRefusal.code: String
    get() = when (this) {
        QueueRefusal.INSUFFICIENT_TOKEN -> "insufficient-token"
        QueueRefusal.NOT_A_TOKEN -> "not-a-token"
        QueueRefusal.REVERTS -> "reverts"
        QueueRefusal.INFEASIBLE -> "infeasible"
        QueueRefusal.ENGINE_REFUSED -> "engine-refused"
        QueueRefusal.SAFE_NONCE_USED -> "safe-nonce-used"
        QueueRefusal.NOT_AN_OWNER -> "not-an-owner"
        QueueRefusal.ALREADY_APPROVED -> "already-approved"
        QueueRefusal.ALREADY_SENT -> "already-sent"
        QueueRefusal.NOT_A_SAFE -> "not-a-safe"
        QueueRefusal.SAFE_REFUSED -> "safe-refused"
    }

/**
 * One held Safe signature: its [request] with no signature (the Safe transaction, on its chain), the
 * [owner]'s [signature] of its hash (null once a conflict deleted it), whether this device [proposed]
 * it, when it was signed ([signedAt]), what it was signed on ([builtOn]), and its [conflict].
 */
@Serializable
internal class StoredHeld(
    val request: WireRequest,
    val owner: String,
    val signature: WireSignature? = null,
    val proposed: Boolean,
    val signedAt: Long,
    val builtOn: StoredSafeBasis,
    val conflict: StoredHeldConflict? = null,
) {
    fun toHeld(): HeldSafeSignature {
        require(request.v == WireRequest.VERSION) { "a request of another version" }
        val network = requireNotNull(Network.ofChainId(request.chainId)) { "a chain" }
        val decoded = requireNotNull(request.toRequest(network)) { "a request out of range" }
        val owner = Address.of(owner)
        val ecdsa = signature?.let { requireNotNull(it.toSignature()) { "a signature out of range" } }
        require(ecdsa == null || ecdsa.owner == owner) { "the owner's own signature" }
        return HeldSafeSignature(
            request = decoded,
            owner = owner,
            signature = ecdsa?.signature,
            proposed = proposed,
            signedAt = signedAt,
            builtOn = builtOn.toBasis(),
            conflict = conflict?.toConflict(),
        )
    }

    companion object {
        fun of(held: HeldSafeSignature) = StoredHeld(
            request = WireRequest.of(held.request),
            owner = held.owner.hex,
            signature = held.signature?.let { WireSignature.of(SafeSignature.Ecdsa(held.owner, it)) },
            proposed = held.proposed,
            signedAt = held.signedAt,
            builtOn = StoredSafeBasis.of(held.builtOn),
            conflict = held.conflict?.let(StoredHeldConflict::of),
        )
    }
}

/** A [SafeDraftBasis] as stored. */
@Serializable
internal class StoredSafeBasis(
    val safeBlock: Long,
    val safeAsOf: Long? = null,
    val balanceBlock: Long? = null,
    val balanceAsOf: Long? = null,
) {
    fun toBasis() = SafeDraftBasis(safeBlock, safeAsOf, balanceBlock, balanceAsOf)

    companion object {
        fun of(basis: SafeDraftBasis) =
            StoredSafeBasis(basis.safeBlock, basis.safeAsOf, basis.balanceBlock, basis.balanceAsOf)
    }
}

/**
 * A [HeldConflict] as stored: its [kind], and the Safe's nonce (for [HeldConflict.NonceGap], the
 * nonce nothing takes) or the refusal that kind has.
 */
@Serializable
internal class StoredHeldConflict(val kind: String, val safeNonce: Long? = null, val refusal: String? = null) {
    fun toConflict(): HeldConflict = when (kind) {
        NONCE_USED -> HeldConflict.NonceUsed(requireNotNull(safeNonce))
        NONCE_TAKEN -> HeldConflict.NonceTaken
        NONCE_GAP -> HeldConflict.NonceGap(requireNotNull(safeNonce))
        NOT_AN_OWNER -> HeldConflict.NotAnOwner
        REFUSED -> HeldConflict.Refused(HeldRefusal.entries.first { it.code == refusal })
        else -> throw IllegalArgumentException("an unknown conflict")
    }

    companion object {
        private const val NONCE_USED = "nonce-used"
        private const val NONCE_TAKEN = "nonce-taken"
        private const val NONCE_GAP = "nonce-gap"
        private const val NOT_AN_OWNER = "not-an-owner"
        private const val REFUSED = "refused"

        fun of(conflict: HeldConflict): StoredHeldConflict = when (conflict) {
            is HeldConflict.NonceUsed -> StoredHeldConflict(NONCE_USED, safeNonce = conflict.safeNonce)
            HeldConflict.NonceTaken -> StoredHeldConflict(NONCE_TAKEN)
            is HeldConflict.NonceGap -> StoredHeldConflict(NONCE_GAP, safeNonce = conflict.next)
            HeldConflict.NotAnOwner -> StoredHeldConflict(NOT_AN_OWNER)
            is HeldConflict.Refused -> StoredHeldConflict(REFUSED, refusal = conflict.reason.code)
        }
    }
}

/** How a held signature's refusal is written. */
private val HeldRefusal.code: String
    get() = when (this) {
        HeldRefusal.NOT_A_SAFE -> "not-a-safe"
        HeldRefusal.TOO_OLD_TO_SIGN -> "too-old-to-sign"
        HeldRefusal.NEEDS_RECOVERY_MODULE -> "needs-recovery-module"
        HeldRefusal.BAD_SIGNATURE -> "bad-signature"
        HeldRefusal.UNSIGNABLE -> "unsignable"
    }
