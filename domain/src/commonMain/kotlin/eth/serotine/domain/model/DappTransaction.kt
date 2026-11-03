package eth.serotine.domain.model

/**
 * What a dApp's `eth_sendTransaction` asks (docs/walletconnect.md, *Transactions*): [value] wei and
 * [data] from [from] to [to], and, when the dApp gives one, the [gas] limit it asks for. Every field of
 * the request is applied or refused, never accepted and ignored (Myotis #314): these are the ones
 * applied; the adapter refuses the others (a nonce, fee fields, another chain or type, an access list).
 * At most [MAX_DATA_BYTES] of data.
 */
class DappTransactionRequest(
    val from: Address,
    val to: Address,
    val value: UInt256,
    data: ByteArray,
    val gas: Long?,
) {
    private val callData: ByteArray = data.copyOf()

    init {
        require(callData.size <= MAX_DATA_BYTES) { "call data of more than $MAX_DATA_BYTES bytes" }
        require(gas == null || gas > 0) { "a gas limit is positive" }
    }

    /** The call data, empty for a plain transfer of ether: a copy. */
    val data: ByteArray get() = callData.copyOf()

    /** The call data as lower-case hex, no prefix. */
    val dataHex: String get() = callData.toHex()

    /** How many bytes of call data it has. */
    val dataSize: Int get() = callData.size

    /** What its call does, for the calls this app knows. */
    val meaning: DappCallMeaning
        get() = when {
            callData.isEmpty() -> DappCallMeaning.Payment
            else -> Erc20.transferOf(callData)?.let { DappCallMeaning.TokenTransfer(to, it.to, it.amount) }
                ?: Erc20.approveOf(callData)?.let { DappCallMeaning.TokenApproval(to, it.spender, it.amount) }
                ?: DappCallMeaning.NotDecoded(callData.copyOf(4).toHex().takeIf { callData.size >= 4 })
        }

    override fun equals(other: Any?): Boolean = other is DappTransactionRequest && from == other.from &&
        to == other.to && value == other.value && callData.contentEquals(other.callData) && gas == other.gas

    override fun hashCode(): Int =
        listOf(from, to, value, callData.contentHashCode(), gas).hashCode()

    override fun toString(): String = "DappTransactionRequest(${from.hex} → ${to.hex}, $value wei, ${callData.size} bytes)"

    companion object {
        /** The most call data a dApp's transaction may carry. */
        const val MAX_DATA_BYTES = 128 * 1024
    }
}

/** What a dApp's transaction does, for the calls this app knows; anything else is not decoded. */
sealed interface DappCallMeaning {
    /** No call data: a payment of the transaction's value to its recipient. */
    data object Payment : DappCallMeaning

    /** An ERC-20 `transfer` of [amount] of [token] to [recipient]. */
    data class TokenTransfer(val token: Address, val recipient: Address, val amount: UInt256) : DappCallMeaning

    /** An ERC-20 `approve`: [spender] may take up to [amount] of [token]. It moves nothing itself. */
    data class TokenApproval(val token: Address, val spender: Address, val amount: UInt256) : DappCallMeaning

    /** A call this app does not decode, [selector] its first four bytes as hex (null with fewer). */
    data class NotDecoded(val selector: String?) : DappCallMeaning
}
