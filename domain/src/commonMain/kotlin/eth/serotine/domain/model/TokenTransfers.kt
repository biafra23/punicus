package eth.serotine.domain.model

/** A token's move between two addresses, as its contract logged it (issue #85). */
sealed interface TokenTransfer {

    /** The contract that logged it: the token. */
    val token: Address

    /** Whom it left. The zero address for a mint. */
    val from: Address

    /** Whom it reached. The zero address for a burn. */
    val to: Address

    /**
     * Whether this is no token's transfer but the protocol's log of an ETH transfer (EIP-7708,
     * [TokenTransfers.ETH]): [Fungible], its amount in wei.
     */
    val isEth: Boolean
        get() = this is Fungible && token == TokenTransfers.ETH

    /** [amount] of an ERC-20 token, in its smallest units. */
    data class Fungible(
        override val token: Address,
        override val from: Address,
        override val to: Address,
        val amount: UInt256,
    ) : TokenTransfer

    /** The ERC-721 token [tokenId] of the collection [token]. */
    data class Nft(
        override val token: Address,
        override val from: Address,
        override val to: Address,
        val tokenId: UInt256,
    ) : TokenTransfer
}

/**
 * The `Transfer(address indexed from, address indexed to, uint256)` event ERC-20 and ERC-721 share:
 * ERC-20 logs the amount in the data, ERC-721 indexes the token id. Each is read in its own shape
 * and in no other.
 */
object TokenTransfers {

    /** `keccak256("Transfer(address,address,uint256)")`. */
    const val TRANSFER: String = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

    /**
     * The address the protocol logs every ETH transfer from (EIP-7708, on Sepolia's chain: seen in
     * block 11,958,655's receipts): a `Transfer` in the ERC-20 shape, the amount in wei, for each
     * transfer of a nonzero value to another account (a transaction's value, a call's, a `CREATE`'s
     * endowment, a `SELFDESTRUCT`'s balance). It is EIP-4788's system address, which holds no code
     * and whose key no one has, so no contract can log from it: such a log is the chain's own word.
     */
    val ETH: Address = Address.of("0xfffffffffffffffffffffffffffffffffffffffe")

    /**
     * What a log the contract [token] emitted with [topics] (each `0x` and 64 lower-case hex digits,
     * the signature hash first) and [data] (`0x` and lower-case hex) says, when it is a `Transfer` in
     * the ERC-20 or ERC-721 shape; null for anything else.
     */
    fun decode(token: Address, topics: List<String>, data: String): TokenTransfer? {
        if (topics.firstOrNull() != TRANSFER) return null
        val words = topics.drop(1).map { topic ->
            topic.takeIf { it.startsWith("0x") }?.let { AbiWords.returnedWord(it.removePrefix("0x")) } ?: return null
        }
        val bytes = data.takeIf { it.startsWith("0x") }?.let { AbiWords.returned(it.removePrefix("0x")) } ?: return null
        val from = words.getOrNull(0)?.let(AbiWords::address) ?: return null
        val to = words.getOrNull(1)?.let(AbiWords::address) ?: return null
        return when (words.size) {
            2 -> AbiWords.words(bytes, 1)?.single()?.let { TokenTransfer.Fungible(token, from, to, AbiWords.uint(it)) }
            3 -> if (bytes.isEmpty()) TokenTransfer.Nft(token, from, to, AbiWords.uint(words[2])) else null
            else -> null
        }
    }
}
