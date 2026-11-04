package eth.serotine.domain.model

import eth.serotine.domain.model.AbiWords.word

/**
 * How a Safe runs a [SafeTx]: a [CALL] of its target, or a [DELEGATECALL], which runs the
 * target's code as the Safe itself — with the Safe's storage, owners and funds — and so can do
 * anything to it. [code] is the `uint8 operation` the contract takes.
 */
enum class SafeOperation(val code: Int) {
    /** The Safe calls the target, as any account would. */
    CALL(0),

    /** The Safe runs the target's code in its own context: whatever that code does, the Safe does. */
    DELEGATECALL(1),
    ;

    companion object {
        /** The operation [code] names, or null for any other number. */
        fun of(code: Int): SafeOperation? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A Safe transaction, the raw fields its owners sign (safe-smart-account's `SafeTx`): the Safe
 * runs [data] against [to] with [value] wei, as [operation] says, at the Safe's transaction
 * number [nonce]. [safeTxGas], [baseGas], [gasPrice], [gasToken] and [refundReceiver] are its
 * refund terms: with a [gasPrice] of zero there is none, and the account that executes it pays
 * the gas; otherwise the Safe pays up to `(gas used + baseGas) × gasPrice` in [gasToken] (the
 * zero address: ether) to [refundReceiver] (the zero address: whoever executes it).
 *
 * Only the fields: what the transaction does is decoded from them ([SafeTxAction]), never
 * taken from a description. Equal by value, [data] included; not a data class, because a data
 * class compares an array by reference and hands out the array it holds.
 */
class SafeTx(
    val to: Address,
    val value: UInt256,
    data: ByteArray,
    val operation: SafeOperation,
    val safeTxGas: UInt256,
    val baseGas: UInt256,
    val gasPrice: UInt256,
    val gasToken: Address,
    val refundReceiver: Address,
    val nonce: Long,
) {
    // Copied in, and copied again on the way out: the transaction stays as it was built.
    private val callData: ByteArray = data.copyOf()

    init {
        require(nonce >= 0) { "a Safe nonce is not negative: $nonce" }
    }

    /** The call data: a copy, so changing it changes nothing here. */
    val data: ByteArray
        get() = callData.copyOf()

    /** How many bytes of call data it carries. */
    val dataSize: Int
        get() = callData.size

    /**
     * The hash the Safe at [safe] on the chain [chainId] checks its owners' signatures against
     * (its `getTransactionHash`): EIP-712's `keccak256(0x19 ‖ 0x01 ‖ domainSeparator ‖
     * structHash)`, with the domain of Safe 1.3.0 on — `EIP712Domain(uint256 chainId,address
     * verifyingContract)`, computed fresh for every call — and the `SafeTx` struct
     * ([SAFE_TX_TYPEHASH]), unchanged since 1.0.0 (its on-chain `SAFE_TX_TYPEHASH()` getter and
     * 1.3.0's hash alike).
     *
     * `SafeDeployments` also recognises, for display, three Safe releases that predate this
     * domain: 1.0.0, 1.1.1 and 1.2.0, each a `domainSeparator` computed once at setup and stored
     * (`keccak256(abi.encode(DOMAIN_SEPARATOR_TYPEHASH, this))`, no chain id in it anywhere) and
     * read as is, never recomputed. A Safe left on one of those — as opposed to one moved since
     * to a pinned ≥1.3.0 singleton by its owners' own `changeMasterCopy` — checks a different
     * hash than this one, so nothing here is signed, approved or executed against it
     * ([eth.serotine.domain.usecase.SafeTxRefusal.TooOldToSign], checked against its verified
     * [eth.serotine.domain.repository.SafeState.version] before this is ever called).
     */
    fun hash(chainId: Long, safe: Address): Digest {
        val domain = keccak256(DOMAIN_TYPEHASH + word(chainId) + word(safe))
        val struct = keccak256(
            SAFE_TX_TYPEHASH +
                word(to) +
                value.toBytes32() +
                keccak256(callData) +
                word(operation.code.toLong()) +
                safeTxGas.toBytes32() +
                baseGas.toBytes32() +
                gasPrice.toBytes32() +
                word(gasToken) +
                word(refundReceiver) +
                word(nonce),
        )
        return Digest.of(keccak256(EIP712_PREFIX + domain + struct).toHex())
    }

    override fun equals(other: Any?): Boolean =
        other is SafeTx &&
            to == other.to &&
            value == other.value &&
            callData.contentEquals(other.callData) &&
            operation == other.operation &&
            safeTxGas == other.safeTxGas &&
            baseGas == other.baseGas &&
            gasPrice == other.gasPrice &&
            gasToken == other.gasToken &&
            refundReceiver == other.refundReceiver &&
            nonce == other.nonce

    override fun hashCode(): Int {
        var result = to.hashCode()
        result = 31 * result + value.hashCode()
        result = 31 * result + callData.contentHashCode()
        result = 31 * result + operation.hashCode()
        result = 31 * result + safeTxGas.hashCode()
        result = 31 * result + baseGas.hashCode()
        result = 31 * result + gasPrice.hashCode()
        result = 31 * result + gasToken.hashCode()
        result = 31 * result + refundReceiver.hashCode()
        result = 31 * result + nonce.hashCode()
        return result
    }

    override fun toString(): String =
        "SafeTx(to=$to, value=$value, data=0x${callData.toHex()}, operation=$operation, safeTxGas=$safeTxGas, " +
            "baseGas=$baseGas, gasPrice=$gasPrice, gasToken=$gasToken, refundReceiver=$refundReceiver, nonce=$nonce)"

    companion object {
        /** keccak256 of the EIP-712 domain type of Safe 1.3.0 on. */
        internal val DOMAIN_TYPEHASH: ByteArray =
            keccak256("EIP712Domain(uint256 chainId,address verifyingContract)".encodeToByteArray())

        /** keccak256 of the `SafeTx` struct type of Safe 1.0.0 on. */
        internal val SAFE_TX_TYPEHASH: ByteArray = keccak256(
            (
                "SafeTx(address to,uint256 value,bytes data,uint8 operation,uint256 safeTxGas,uint256 baseGas," +
                    "uint256 gasPrice,address gasToken,address refundReceiver,uint256 nonce)"
                ).encodeToByteArray(),
        )

        private val EIP712_PREFIX = byteArrayOf(0x19, 0x01)

        /**
         * A plain call the Safe makes with no refund terms, at [nonce]: what this wallet proposes
         * (safeTxGas, baseGas and gasPrice zero, no gas token, no refund receiver), so the
         * account that executes it pays its gas and the Safe pays nothing but [value].
         */
        fun call(to: Address, value: UInt256, data: ByteArray, nonce: Long): SafeTx = SafeTx(
            to = to,
            value = value,
            data = data,
            operation = SafeOperation.CALL,
            safeTxGas = UInt256.ZERO,
            baseGas = UInt256.ZERO,
            gasPrice = UInt256.ZERO,
            gasToken = Address.ZERO,
            refundReceiver = Address.ZERO,
            nonce = nonce,
        )
    }
}
