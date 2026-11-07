package eth.serotine.domain.model

import eth.serotine.domain.model.AbiWords.dynamic
import eth.serotine.domain.model.AbiWords.selector
import eth.serotine.domain.model.AbiWords.word
import kotlin.jvm.JvmInline

/**
 * CoW Protocol's contracts on one network (docs/swaps.md, *An order, step by step*): its
 * [settlement] contract, `GPv2Settlement`, which checks an order's signature and limit and
 * settles it, and its [vaultRelayer], `GPv2VaultRelayer`, the spender an order's sold token is
 * approved to. :data pins them per network with their code hashes, and the domain uses them only
 * once their code is proven (`CowChain.venue`).
 */
data class CowDeployment(val settlement: Address, val vaultRelayer: Address)

/**
 * A CoW Protocol sell order as Punicus signs one (docs/swaps.md, *An order, step by step*):
 * sell exactly [sellAmount] of [sellToken] for at least [buyAmount] (never zero: an order with no
 * minimum gives the sold token away) of [buyToken], paid to [receiver], until [validTo] (unix
 * seconds, a `uint32`).
 *
 * GPv2's other order fields are fixed here, not chosen: the app data [CowAppData.HASH] (nothing
 * beyond the required fields, owner ruling, 2026-10-22), no fee (`feeAmount` 0, the fee comes out
 * of the price), a sell order, fill-or-kill (`partiallyFillable` false), and both tokens moved as
 * plain ERC-20 balances. [buyToken] may be [NATIVE_BUY_TOKEN], CoW's marker for native ether
 * bought; native ether is never sold this way (it would need CoW's ETH-flow contract).
 */
data class CowOrder(
    val sellToken: Address,
    val buyToken: Address,
    val receiver: Address,
    val sellAmount: UInt256,
    val buyAmount: UInt256,
    val validTo: Long,
) {
    init {
        require(validTo in 0..UINT32_MAX) { "validTo is a uint32: $validTo" }
        require(!sellAmount.isZero) { "an order sells something" }
        require(!buyAmount.isZero) { "an order buys at least something" }
        require(sellToken != NATIVE_BUY_TOKEN) { "native ether is not sold by a signed order" }
        require(sellToken != buyToken) { "an order buys another token than it sells" }
    }

    /**
     * The EIP-712 digest this order's owner signs on the chain [chainId], for its [settlement]
     * contract: GPv2's `keccak256(0x19 ‖ 0x01 ‖ domainSeparator ‖ structHash)`, under the domain
     * `EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)` with
     * the name "Gnosis Protocol" and the version "v2".
     */
    fun digest(chainId: Long, settlement: Address): Digest = GPv2Signing.digest(
        GPv2Signing.domainSeparator(chainId, settlement),
        GPv2Signing.structHash(
            sellToken = sellToken,
            buyToken = buyToken,
            receiver = receiver,
            sellAmount = sellAmount,
            buyAmount = buyAmount,
            validTo = validTo,
            appData = CowAppData.HASH,
            feeAmount = UInt256.ZERO,
            partiallyFillable = false,
        ),
    )

    /** This order's UID once [owner] signs it on [chainId] for [settlement] ([CowOrderUid.of]). */
    fun uid(chainId: Long, settlement: Address, owner: Address): CowOrderUid =
        CowOrderUid.of(digest(chainId, settlement), owner, validTo)

    companion object {
        /** CoW's marker for native ether as the token an order buys. */
        val NATIVE_BUY_TOKEN: Address = Address.of("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")

        /** The largest `uint32`, the most [validTo] can be. */
        const val UINT32_MAX: Long = 0xFFFF_FFFFL
    }
}

/**
 * The app data every order Punicus signs carries: the document [DOCUMENT], the empty JSON
 * object, with nothing beyond the required fields (owner ruling, 2026-10-22; CLAUDE.md, *Data
 * sources*), and its [HASH], the `appData` an order signs (`keccak256` of the document's UTF-8
 * bytes, which CoW's API documents as `0xb48d38f9…739d`).
 */
object CowAppData {
    /** The app-data document itself, sent with the order. */
    const val DOCUMENT: String = "{}"

    /** keccak256 of [DOCUMENT], `0x` and 64 lower-case hex digits. */
    val HASH: String = "0x" + keccak256(DOCUMENT.encodeToByteArray()).toHex()
}

/**
 * A CoW order's UID (GPv2Order's `packOrderUidParams`): its 32-byte [digest], its [owner]'s
 * address and its 4-byte [validTo], packed into 56 bytes. [hex] is `0x` and 112 lower-case hex
 * digits. The UID is what the settlement contract keys an order's fill by, and what its `Trade`
 * and `OrderInvalidated` logs carry.
 */
@JvmInline
value class CowOrderUid private constructor(val hex: String) {

    /** The order's EIP-712 digest. */
    val digest: Digest
        get() = Digest.of(hex.substring(2, 2 + DIGEST_DIGITS))

    /** The account that signed the order. */
    val owner: Address
        get() = Address.of("0x" + hex.substring(2 + DIGEST_DIGITS, 2 + DIGEST_DIGITS + ADDRESS_DIGITS))

    /** The order's `validTo`, unix seconds. */
    val validTo: Long
        get() = hex.substring(2 + DIGEST_DIGITS + ADDRESS_DIGITS).toLong(16)

    /** The 56 packed bytes. */
    fun bytes(): ByteArray = hex.removePrefix("0x").hexToBytes()

    override fun toString(): String = "CowOrderUid($hex)"

    companion object {
        private const val DIGEST_DIGITS = 64
        private const val ADDRESS_DIGITS = 40
        private const val VALID_TO_DIGITS = 8
        private val SHAPE = Regex("^0x[0-9a-f]{112}$")

        /** The UID of [digest] signed by [owner], valid until [validTo] (a `uint32`). */
        fun of(digest: Digest, owner: Address, validTo: Long): CowOrderUid {
            require(validTo in 0..CowOrder.UINT32_MAX) { "validTo is a uint32: $validTo" }
            val validToHex = validTo.toString(16).padStart(VALID_TO_DIGITS, '0')
            return CowOrderUid("0x" + digest.hex + owner.hex.removePrefix("0x").lowercase() + validToHex)
        }

        /** The UID [text] spells (`0x` optional, any case), or null when it is not 56 bytes. */
        fun parse(text: String): CowOrderUid? {
            // Lower-cased first, so one prefix is stripped and a doubled one fails the shape.
            val hex = "0x" + text.trim().lowercase().removePrefix("0x")
            return hex.takeIf(SHAPE::matches)?.let(::CowOrderUid)
        }

        /** The UID [hex] spells; throws when it is not one. */
        fun of(hex: String): CowOrderUid = requireNotNull(parse(hex)) { "not a CoW order UID: '$hex'" }
    }
}

/**
 * GPv2's EIP-712 encoding (`GPv2Order.hash`, `GPv2Signing`), for every order field: the orders
 * Punicus signs fix most of them ([CowOrder]), but a test vector is any order.
 */
internal object GPv2Signing {

    /** keccak256 of `EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)`. */
    val DOMAIN_TYPEHASH: ByteArray = keccak256(
        "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)".encodeToByteArray(),
    )

    /** keccak256 of GPv2's `Order` type: `GPv2Order.TYPE_HASH`, `0xd5a25ba2…e489`. */
    val ORDER_TYPEHASH: ByteArray = keccak256(ORDER_TYPE.encodeToByteArray())

    /** GPv2's `Order` type, as EIP-712's `encodeType` spells it. */
    const val ORDER_TYPE: String =
        "Order(address sellToken,address buyToken,address receiver,uint256 sellAmount,uint256 buyAmount," +
            "uint32 validTo,bytes32 appData,uint256 feeAmount,string kind,bool partiallyFillable," +
            "string sellTokenBalance,string buyTokenBalance)"

    /** keccak256("sell"): `GPv2Order.KIND_SELL`. */
    val KIND_SELL: ByteArray = keccak256("sell".encodeToByteArray())

    /** keccak256("erc20"): `GPv2Order.BALANCE_ERC20`. */
    val BALANCE_ERC20: ByteArray = keccak256("erc20".encodeToByteArray())

    private val NAME_HASH = keccak256("Gnosis Protocol".encodeToByteArray())
    private val VERSION_HASH = keccak256("v2".encodeToByteArray())
    private val EIP712_PREFIX = byteArrayOf(0x19, 0x01)

    /** The settlement contract's domain separator on [chainId] (its `domainSeparator()`). */
    fun domainSeparator(chainId: Long, settlement: Address): ByteArray =
        keccak256(DOMAIN_TYPEHASH + NAME_HASH + VERSION_HASH + word(chainId) + word(settlement))

    /**
     * The struct hash of a sell order with ERC-20 balances, whatever its other fields; [appData] is
     * one 32-byte word (`0x` optional), and anything else is refused rather than shifting the
     * fields after it.
     */
    fun structHash(
        sellToken: Address,
        buyToken: Address,
        receiver: Address,
        sellAmount: UInt256,
        buyAmount: UInt256,
        validTo: Long,
        appData: String,
        feeAmount: UInt256,
        partiallyFillable: Boolean,
    ): ByteArray {
        val appDataWord = appData.removePrefix("0x").hexToBytes()
        require(appDataWord.size == 32) { "appData is one 32-byte word" }
        return keccak256(
            ORDER_TYPEHASH +
                word(sellToken) +
                word(buyToken) +
                word(receiver) +
                sellAmount.toBytes32() +
                buyAmount.toBytes32() +
                word(validTo) +
                appDataWord +
                feeAmount.toBytes32() +
                KIND_SELL +
                word(if (partiallyFillable) 1L else 0L) +
                BALANCE_ERC20 +
                BALANCE_ERC20,
        )
    }

    /** EIP-712's `keccak256(0x19 ‖ 0x01 ‖ domain ‖ struct)`. */
    fun digest(domainSeparator: ByteArray, structHash: ByteArray): Digest =
        Digest.of(keccak256(EIP712_PREFIX + domainSeparator + structHash).toHex())
}

/**
 * One fill of an order, as the settlement contract's `Trade` log says it (`GPv2Settlement`):
 * [owner] sold [sellAmount] of [sellToken], fees included, for [buyAmount] of [buyToken];
 * [feeAmount] of the sold amount went to fees; the order is [uid].
 */
data class CowTrade(
    val owner: Address,
    val sellToken: Address,
    val buyToken: Address,
    val sellAmount: UInt256,
    val buyAmount: UInt256,
    val feeAmount: UInt256,
    val uid: CowOrderUid,
)

/**
 * The calls and logs of CoW's settlement contract, `GPv2Settlement`, that Punicus uses
 * (docs/swaps.md, *Proving an order's end*): its domain separator, an order's filled amount, the
 * owner's cancellation on chain, and the `Trade` and `OrderInvalidated` logs. Each decoder is
 * strict: an answer or a log of any other shape is none.
 */
object GPv2Settlement {

    /** The `Trade(address indexed owner, …, bytes orderUid)` log's topic 0. */
    const val TRADE_TOPIC: String = "0xa07a543ab8a018198e99ca0184c93fe9050a79400a0a723441f84de1d972cc17"

    /** The `OrderInvalidated(address indexed owner, bytes orderUid)` log's topic 0. */
    const val ORDER_INVALIDATED_TOPIC: String = "0x875b6cb035bbd4ac6500fabc6d1e4ca5bdc58a3e2b424ccb5c24cdbebeb009a9"

    private val DOMAIN_SEPARATOR = selector("domainSeparator()")
    private val FILLED_AMOUNT = selector("filledAmount(bytes)")
    private val INVALIDATE_ORDER = selector("invalidateOrder(bytes)")
    private const val WORD = 32
    private const val UID_BYTES = 56
    private const val TRADE_HEAD_WORDS = 6
    private const val TRADE_UID_OFFSET = TRADE_HEAD_WORDS * WORD

    /** `domainSeparator()`'s call data. */
    fun domainSeparator(): ByteArray = DOMAIN_SEPARATOR.copyOf()

    /**
     * The domain separator a `domainSeparator()` call returned ([returnHex]: lower-case hex, no
     * `0x`, as a verified call returns it), or null for any other answer.
     */
    fun domainSeparatorOf(returnHex: String): ByteArray? = AbiWords.returnedWord(returnHex)

    /** `filledAmount(uid)`'s call data. */
    fun filledAmount(uid: CowOrderUid): ByteArray = FILLED_AMOUNT + word(WORD.toLong()) + dynamic(uid.bytes())

    /** The amount a `filledAmount` call returned ([returnHex] as above), or null for any other answer. */
    fun filledAmountOf(returnHex: String): UInt256? = AbiWords.returnedWord(returnHex)?.let(AbiWords::uint)

    /** `invalidateOrder(uid)`'s call data: the owner's cancellation of the order on chain. */
    fun invalidateOrder(uid: CowOrderUid): ByteArray = INVALIDATE_ORDER + word(WORD.toLong()) + dynamic(uid.bytes())

    /**
     * The fill a log reports when [settlement] emitted it ([emitter]) as a `Trade` with [topics]
     * and [data] (`0x` and lower-case hex), its UID naming the owner the log names; null for any
     * other log: another contract's (a topic entry matches logs from any contract), another
     * event, or data of another shape.
     */
    fun tradeOf(settlement: Address, emitter: Address, topics: List<String>, data: String): CowTrade? {
        if (emitter != settlement || topics.size != 2 || topics[0] != TRADE_TOPIC) return null
        val owner = AbiWords.address(topics[1].removePrefix("0x").hexToBytesOrNull() ?: return null) ?: return null
        val bytes = data.removePrefix("0x").hexToBytesOrNull() ?: return null
        if (bytes.size < TRADE_UID_OFFSET) return null
        val head = AbiWords.words(bytes.copyOf(TRADE_UID_OFFSET), TRADE_HEAD_WORDS) ?: return null
        if (AbiWords.long(head[5]) != TRADE_UID_OFFSET.toLong()) return null
        val uid = uidAt(bytes, TRADE_UID_OFFSET)?.takeIf { it.owner == owner } ?: return null
        return CowTrade(
            owner = owner,
            sellToken = AbiWords.address(head[0]) ?: return null,
            buyToken = AbiWords.address(head[1]) ?: return null,
            sellAmount = AbiWords.uint(head[2]),
            buyAmount = AbiWords.uint(head[3]),
            feeAmount = AbiWords.uint(head[4]),
            uid = uid,
        )
    }

    /**
     * The order a log reports cancelled when [settlement] emitted it ([emitter]) as an
     * `OrderInvalidated` with [topics] and [data], its UID naming the owner the log names; null
     * for any other log.
     */
    fun invalidationOf(settlement: Address, emitter: Address, topics: List<String>, data: String): CowOrderUid? {
        if (emitter != settlement || topics.size != 2 || topics[0] != ORDER_INVALIDATED_TOPIC) return null
        val owner = AbiWords.address(topics[1].removePrefix("0x").hexToBytesOrNull() ?: return null) ?: return null
        val bytes = data.removePrefix("0x").hexToBytesOrNull() ?: return null
        if (bytes.size < WORD) return null
        if (AbiWords.long(bytes.copyOf(WORD)) != WORD.toLong()) return null
        return uidAt(bytes, WORD)?.takeIf { it.owner == owner }
    }

    /** The 56-byte `bytes` at [offset] of [data], the last thing in it, zero-padded: a UID. */
    private fun uidAt(data: ByteArray, offset: Int): CowOrderUid? {
        val padded = UID_BYTES + AbiWords.padding(UID_BYTES)
        if (data.size != offset + WORD + padded) return null
        if (AbiWords.long(data.copyOfRange(offset, offset + WORD)) != UID_BYTES.toLong()) return null
        val start = offset + WORD
        val tail = data.copyOfRange(start + UID_BYTES, start + padded)
        if (tail.any { it != 0.toByte() }) return null
        return CowOrderUid.of(data.copyOfRange(start, start + UID_BYTES).toHex())
    }

    private fun String.hexToBytesOrNull(): ByteArray? =
        takeIf { it.length % 2 == 0 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
            ?.lowercase()?.hexToBytes()
}
