package eth.serotine.domain.model

/**
 * The two calls of the pinned WETH9 a wrap is (docs/swaps.md, *Wrapping ETH*): `deposit()`, which
 * takes the call's value and credits the caller as much WETH, and `withdraw(wad)`, which takes
 * `wad` of the caller's WETH and pays it as much ETH. One for one, in wei: no pool, no price, no
 * minimum and no allowance, only the network's fee.
 */
object Weth9 {
    private val DEPOSIT = AbiWords.selector("deposit()")
    private val WITHDRAW = AbiWords.selector("withdraw(uint256)")

    /** ETH into WETH: `deposit()` at [weth9], carrying [amount] wei (not zero). */
    fun wrap(weth9: Address, amount: UInt256): TransactionCall {
        require(!amount.isZero) { "a wrap moves something" }
        return TransactionCall(weth9, amount, DEPOSIT)
    }

    /** WETH back into ETH: `withdraw(amount)` at [weth9] (not zero), carrying no value. */
    fun unwrap(weth9: Address, amount: UInt256): TransactionCall {
        require(!amount.isZero) { "an unwrap moves something" }
        return TransactionCall(weth9, UInt256.ZERO, WITHDRAW + amount.toBytes32())
    }

    /** Whether [returnHex], what a run of [wrap] or [unwrap] returned, is what WETH9 returns: nothing. */
    fun returnsNothing(returnHex: String): Boolean = returnHex.removePrefix("0x").isEmpty()
}

/** Which way a wrap goes. */
enum class WrapDirection {
    /** ETH into WETH (`deposit`). */
    WRAP,

    /** WETH back into ETH (`withdraw`). */
    UNWRAP,
}

/**
 * Which way selling [sell] for [buy] wraps through the WETH9 at [weth9]: ETH for its token is a
 * wrap, its token for ETH an unwrap; null for any other pair, which is no wrap.
 */
fun wrapDirection(sell: TransferAsset, buy: TransferAsset, weth9: Address): WrapDirection? = when {
    sell == TransferAsset.Native && (buy as? TransferAsset.Erc20Token)?.token?.address == weth9 -> WrapDirection.WRAP
    buy == TransferAsset.Native && (sell as? TransferAsset.Erc20Token)?.token?.address == weth9 -> WrapDirection.UNWRAP
    else -> null
}
