package eth.serotine.domain.model

import eth.serotine.domain.model.AbiWords.WORD
import eth.serotine.domain.model.AbiWords.dynamic
import eth.serotine.domain.model.AbiWords.selector
import eth.serotine.domain.model.AbiWords.word

/**
 * One owner's confirmation of a Safe transaction's hash, as the Safe's `checkSignatures` reads
 * it. The encoding format depends on the signature type.
 */
sealed interface SafeSignature {

    /** The owner this confirms for. */
    val owner: Address

    /** Its bytes as `execTransaction` takes them (dynamic length). */
    fun encode(): ByteArray

    /**
     * [owner]'s ECDSA [signature] of the raw Safe transaction hash — the EIP-712 digest itself,
     * with no `eth_sign` prefix — encoded `r ‖ s ‖ v` with `v` = 27 + its recovery id: what an
     * owner key held here makes, and what an EIP-712 signer elsewhere makes of the same hash.
     */
    data class Ecdsa(override val owner: Address, val signature: Signature) : SafeSignature {
        override fun encode(): ByteArray = signature.r.hexToBytes() + signature.s.hexToBytes() +
            byteArrayOf((ECDSA_V_OFFSET + signature.recoveryId).toByte())
    }

    /**
     * A WebAuthn-formatted P-256 signature for Safe transactions.
     *
     * Format: `authenticatorData ‖ clientDataFields ‖ r ‖ s`
     * This is used for hardware-bound P-256 owners stored in Secure Enclave/StrongBox.
     */
    data class WebAuthn(
        override val owner: Address,
        val authenticatorData: ByteArray,
        val clientDataFields: String,
        val r: String,
        val s: String,
    ) : SafeSignature {
        init {
            require(r.length == 64) { "r must be 64 hex characters (32 bytes)" }
            require(s.length == 64) { "s must be 64 hex characters (32 bytes)" }
            require(SCALAR.matches(r)) { "r must be lowercase hex" }
            require(SCALAR.matches(s)) { "s must be lowercase hex" }
            // s must be in lower half of curve order for non-malleability
            require(s <= HALF_ORDER) { "s must be in lower half of curve order" }
        }

        override fun encode(): ByteArray {
            val clientDataBytes = clientDataFields.encodeToByteArray()
            return authenticatorData + clientDataBytes + r.hexToBytes() + s.hexToBytes()
        }
    }

    /**
     * A pre-validated confirmation: `r` = [owner] as a word, `s` = 0, `v` = 1. The Safe accepts it
     * when [owner] is the account executing the transaction, or has approved its hash on chain
     * (`approveHash`, read back as `approvedHashes(owner, hash)`); it is never sent between
     * devices, since it proves nothing by itself.
     */
    data class ApprovedHash(override val owner: Address) : SafeSignature {
        override fun encode(): ByteArray = word(owner) + ByteArray(WORD) + byteArrayOf(APPROVED_HASH_V)
    }

    private companion object {
        const val ECDSA_V_OFFSET = 27
        const val APPROVED_HASH_V: Byte = 1

        private val SCALAR = Regex("^[0-9a-f]{64}$")
        private const val HALF_ORDER = "7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0"
    }
}

/**
 * The Safe's own functions this wallet calls from an owner account it holds, as call data: the
 * contract ABI of safe-smart-account 1.3.0 to 1.5.0, which these share.
 */
object SafeCalls {
    private val EXEC_TRANSACTION =
        selector("execTransaction(address,uint256,bytes,uint8,uint256,uint256,uint256,address,address,bytes)")
    private val APPROVE_HASH = selector("approveHash(bytes32)")

    /** The ten head words of `execTransaction`: where its two dynamic arguments start. */
    private const val EXEC_HEAD_WORDS = 10

    /**
     * The call data of `execTransaction` for [tx] with [signatures] packed as the Safe reads them
     * ([packSignatures]): every field of [tx] in the contract's order, its data and the
     * signatures as the two dynamic `bytes` arguments.
     */
    fun execTransaction(tx: SafeTx, signatures: List<SafeSignature>): ByteArray {
        val data = tx.data
        val packed = packSignatures(signatures)
        val dataOffset = EXEC_HEAD_WORDS * WORD
        val signaturesOffset = dataOffset + WORD + data.size + AbiWords.padding(data.size)
        return EXEC_TRANSACTION +
            word(tx.to) +
            tx.value.toBytes32() +
            word(dataOffset.toLong()) +
            word(tx.operation.code.toLong()) +
            tx.safeTxGas.toBytes32() +
            tx.baseGas.toBytes32() +
            tx.gasPrice.toBytes32() +
            word(tx.gasToken) +
            word(tx.refundReceiver) +
            word(signaturesOffset.toLong()) +
            dynamic(data) +
            dynamic(packed)
    }

    /** The call data of `approveHash(hash)`: an owner's approval of a transaction's hash, on chain. */
    fun approveHash(hash: Digest): ByteArray = APPROVE_HASH + hash.hex.hexToBytes()

    /**
     * [signatures] as `checkSignatures` reads them: in ascending order of their owners,
     * which the Safe requires (it refuses an owner that does not sort above the one
     * before it). One per owner: two for one owner throw, as a caller's error.
     */
    fun packSignatures(signatures: List<SafeSignature>): ByteArray {
        require(signatures.map { it.owner }.toSet().size == signatures.size) { "one signature per owner" }
        // Equal-length lower-case hex: the strings order as the addresses do as numbers.
        return signatures.sortedBy { it.owner.hex }.fold(ByteArray(0)) { packed, it -> packed + it.encode() }
    }
}

/**
 * A request to sign a Safe transaction, as it moves between devices without any off-chain
 * service (CLAUDE.md, *Data sources*): the Safe at [safe] on [network], the raw transaction
 * [tx], and the signatures of its owners collected so far — one per owner, in ascending
 * order of owner. It carries no label, summary or hash for its receiver to trust: [hash] is
 * computed here from the fields, and what it does is decoded from them ([SafeTxAction]).
 *
 * Also what the queue of pending transactions holds ([eth.serotine.domain.repository.SafeTxQueue]).
 */
@ConsistentCopyVisibility
data class SigningRequest private constructor(
    val network: Network,
    val safe: Address,
    val tx: SafeTx,
    val signatures: List<SafeSignature>,
) {
    /** The hash its owners sign, recomputed from [tx] for [safe] on [network]. */
    val hash: Digest = tx.hash(network.chainId, safe)

    /** Its Safe nonce. */
    val nonce: Long
        get() = tx.nonce

    /** The owners it carries a signature of. */
    val signers: List<Address>
        get() = signatures.map { it.owner }

    /**
     * This request with [added] signatures too; an owner it already carries a signature of keeps
     * that one. Neither is checked here: who signed what is checked against the chain by the use
     * case that accepts a signature.
     */
    fun withSignatures(added: List<SafeSignature>): SigningRequest =
        of(network, safe, tx, signatures + added.filter { it.owner !in signers }.distinctBy { it.owner })

    override fun toString(): String = "SigningRequest(safe=$safe, nonce=${tx.nonce}, hash=$hash, signers=$signers)"

    companion object {
        /**
         * The request for [tx] on [safe] on [network] with [signatures], sorted by owner. Two
         * signatures of one owner throw, as a caller's error: a decoder refuses them first.
         */
        fun of(
            network: Network,
            safe: Address,
            tx: SafeTx,
            signatures: List<SafeSignature> = emptyList(),
        ): SigningRequest {
            require(signatures.map { it.owner }.toSet().size == signatures.size) { "one signature per owner" }
            return SigningRequest(network, safe, tx, signatures.sortedBy { it.owner.hex })
        }
    }
}
