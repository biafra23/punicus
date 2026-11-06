package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.UInt256

/** The few pieces of the contract ABI the engine adapters speak: calldata in, words out. */
internal object Abi {

    /** `balanceOf(address)`: the selector and [holder] as one left-padded word. */
    fun balanceOf(holder: Address): ByteArray = ("70a08231" + "0".repeat(24) + holder.hex.removePrefix("0x")).fromHex()

    /** ERC-721's `ownerOf(uint256)`: the selector and [tokenId] as one word. */
    fun ownerOf(tokenId: UInt256): ByteArray = "6352211e".fromHex() + tokenId.toBytes32()

    /** ERC-1155's `balanceOf(address,uint256)`: the selector, [holder] as a left-padded word, and [id]. */
    fun balanceOf(holder: Address, id: UInt256): ByteArray =
        ("00fdd58e" + "0".repeat(24) + holder.hex.removePrefix("0x")).fromHex() + id.toBytes32()

    /**
     * A Safe's `approvedHashes(address,bytes32)`: the selector, [owner] as a left-padded word and
     * [hash] as the second.
     */
    fun approvedHashes(owner: Address, hash: Digest): ByteArray =
        ("7d832974" + "0".repeat(24) + owner.hex.removePrefix("0x") + hash.hex).fromHex()

    /**
     * The one uint256 word [returnedHex] (lower-case hex, no `0x`) holds, as a decimal integer;
     * null when it is not exactly one word, so a call that answered something else is not
     * taken for an amount.
     */
    fun uint256(returnedHex: String): String? {
        if (returnedHex.length != WORD_HEX_DIGITS || !returnedHex.all { it in HEX }) return null
        return toDecimal(returnedHex.fromHex())
    }

    /** A Chainlink proxy's `aggregator()`: the contract its rounds are read from. */
    val AGGREGATOR: ByteArray = "245a7bfc".fromHex()

    /**
     * The one address word [returnedHex] holds, or null when it is not exactly one word whose
     * upper 12 bytes are zero.
     */
    fun address(returnedHex: String): Address? {
        if (returnedHex.length != WORD_HEX_DIGITS || !returnedHex.all { it in HEX }) return null
        if (returnedHex.take(24).any { it != '0' }) return null
        return Address.parse("0x" + returnedHex.drop(24))
    }

    /** Chainlink's `latestRoundData()`: no arguments, the selector alone. */
    val LATEST_ROUND_DATA: ByteArray = "feaf968c".fromHex()

    /**
     * The round `latestRoundData()` answered in [returnedHex]: five words — roundId, answer
     * (an int256), startedAt, updatedAt, answeredInRound. Null when it is not five words, or
     * its update time is no time; [Round.answer] is null for a negative answer.
     */
    fun round(returnedHex: String): Round? {
        if (returnedHex.length != ROUND_WORDS * WORD_HEX_DIGITS || !returnedHex.all { it in HEX }) return null
        val words = returnedHex.chunked(WORD_HEX_DIGITS)
        // Two's complement: a set top bit is a negative answer.
        val answer = words[1].takeIf { it[0] < '8' }?.let(::uint256)
        val updatedAt = uint256(words[3])?.toLongOrNull() ?: return null
        return Round(answer, updatedAt)
    }

    /** A feed's [answer] (a non-negative decimal integer; null when negative) as of [updatedAt], in unix seconds. */
    data class Round(val answer: String?, val updatedAt: Long)

    /**
     * Chainlink's `getRoundData(uint80)` on a feed's proxy: the selector and [id] as the proxy's
     * round id, its [RoundId.phase] in the top 16 of the 80 bits and the phase aggregator's
     * [RoundId.round] in the low 64. It answers as `latestRoundData()` does ([round]).
     */
    fun getRoundData(id: RoundId): ByteArray =
        (
            "9a6fc8f5" + "0".repeat(WORD_HEX_DIGITS - ROUND_ID_HEX_DIGITS) +
                id.phase.toString(16).padStart(PHASE_HEX_DIGITS, '0') +
                id.round.toULong().toString(16).padStart(AGGREGATOR_ROUND_HEX_DIGITS, '0')
            ).fromHex()

    /**
     * The proxy round id a round answer in [returnedHex] (five words, as [round] reads them)
     * starts with, split into its phase and the phase aggregator's round; null when it is not
     * five words or its first word is no uint80 round id.
     */
    fun roundId(returnedHex: String): RoundId? {
        if (returnedHex.length != ROUND_WORDS * WORD_HEX_DIGITS || !returnedHex.all { it in HEX }) return null
        val word = returnedHex.substring(0, WORD_HEX_DIGITS)
        if (word.take(WORD_HEX_DIGITS - ROUND_ID_HEX_DIGITS).any { it != '0' }) return null
        val phase = word.substring(WORD_HEX_DIGITS - ROUND_ID_HEX_DIGITS, WORD_HEX_DIGITS - AGGREGATOR_ROUND_HEX_DIGITS)
            .toInt(16)
        // A round past what a Long holds is none any aggregator has written.
        val round = word.takeLast(AGGREGATOR_ROUND_HEX_DIGITS).toULong(16).takeIf { it <= Long.MAX_VALUE.toULong() }
            ?: return null
        return RoundId(phase, round.toLong())
    }

    /**
     * A round of a Chainlink proxy: the [phase] (which of its aggregators answered it, never
     * changed once set) and that aggregator's own [round] number, counted from 1.
     */
    data class RoundId(val phase: Int, val round: Long) {
        init {
            require(phase in 0..0xffff && round >= 0) { "not a round id: $phase/$round" }
        }
    }

    /** `symbol()`, `name()`, `decimals()`: no arguments, the selector alone. */
    val SYMBOL: ByteArray = "95d89b41".fromHex()
    val NAME: ByteArray = "06fdde03".fromHex()
    val DECIMALS: ByteArray = "313ce567".fromHex()

    /**
     * The text [returnedHex] holds: an ABI `string` (its offset, its length, its bytes), or, as
     * some early tokens answer `symbol()` and `name()`, one `bytes32` word padded with zero
     * bytes. Null when it is neither, or not valid UTF-8. What renders as nothing or moves text
     * is dropped ([visible]): what a contract says can never move text on screen, nor hide a
     * character in a symbol, which would let "US\u200BDC" pass for USDC where symbols are
     * compared.
     */
    fun text(returnedHex: String): String? {
        if (returnedHex.length % 2 != 0 || !returnedHex.all { it in HEX }) return null
        val bytes = returnedHex.fromHex()
        val raw = when {
            bytes.size == WORD -> bytes.copyOf(bytes.indexOfFirst { it == 0.toByte() }.takeIf { it >= 0 } ?: WORD)
            bytes.size >= 2 * WORD -> {
                val offset = wordAsInt(bytes, 0) ?: return null
                val length = offset.takeIf { it + WORD <= bytes.size }?.let { wordAsInt(bytes, it) } ?: return null
                if (offset + WORD + length > bytes.size) return null
                bytes.copyOfRange(offset + WORD, offset + WORD + length)
            }
            else -> return null
        }
        val decoded = raw.decodeToString(throwOnInvalidSequence = false)
        if ('\uFFFD' in decoded) return null
        return visible(decoded)
    }

    /**
     * [text] without the code points that render as nothing or move text: controls, line and
     * paragraph separators, format characters of any plane (bidirectional overrides, zero-width
     * ones, the tag characters), the rest of Unicode's Default_Ignorable_Code_Point set (the
     * combining grapheme joiner, variation selectors, the Hangul fillers) and the blank
     * Braille cell. Read by code point, so a character beyond the BMP is judged whole.
     * Lookalikes from other scripts remain: a token is known by its address.
     */
    internal fun visible(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val pair = text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            val codePoint = if (pair) {
                0x10000 + ((text[i].code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
            } else {
                text[i].code
            }
            val width = if (pair) 2 else 1
            if (!invisible(codePoint)) append(text, i, i + width)
            i += width
        }
    }

    private fun invisible(codePoint: Int): Boolean {
        if (INVISIBLE_RANGES.any { codePoint in it }) return true
        if (codePoint > 0xFFFF) return false
        val char = codePoint.toChar()
        return char.isISOControl() || char.isSurrogate() || char.category in INVISIBLE_CATEGORIES
    }

    /** The word at [at] as a small non-negative Int, or null when it is larger than any offset or length can be. */
    private fun wordAsInt(bytes: ByteArray, at: Int): Int? {
        if (at + WORD > bytes.size) return null
        val high = bytes.copyOfRange(at, at + WORD - 4)
        if (high.any { it != 0.toByte() }) return null
        var value = 0L
        for (i in at + WORD - 4 until at + WORD) value = (value shl 8) or (bytes[i].toLong() and 0xff)
        return value.takeIf { it <= MAX_TEXT_BYTES }?.toInt()
    }

    /** The unsigned big-endian [bytes] as a decimal integer, by long division in base 10. */
    internal fun toDecimal(bytes: ByteArray): String {
        var number = bytes.map { it.toInt() and 0xff }.dropWhile { it == 0 }
        if (number.isEmpty()) return "0"
        val digits = StringBuilder()
        while (number.isNotEmpty()) {
            var remainder = 0
            val quotient = ArrayList<Int>(number.size)
            for (byte in number) {
                val value = remainder * 256 + byte
                quotient += value / 10
                remainder = value % 10
            }
            digits.append(('0' + remainder))
            number = quotient.dropWhile { it == 0 }
        }
        return digits.reverse().toString()
    }

    private val INVISIBLE_CATEGORIES =
        setOf(CharCategory.FORMAT, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR)

    /**
     * Default_Ignorable_Code_Point (Unicode's DerivedCoreProperties), the format characters
     * beyond the BMP, whose category a [Char] cannot tell, and U+2800 BRAILLE PATTERN BLANK.
     */
    private val INVISIBLE_RANGES = listOf(
        0x00AD..0x00AD, 0x034F..0x034F, 0x061C..0x061C, 0x115F..0x1160, 0x17B4..0x17B5,
        0x180B..0x180F, 0x200B..0x200F, 0x202A..0x202E, 0x2060..0x206F, 0x2800..0x2800,
        0x3164..0x3164, 0xFE00..0xFE0F, 0xFEFF..0xFEFF, 0xFFA0..0xFFA0, 0xFFF0..0xFFF8,
        0x110BD..0x110BD, 0x110CD..0x110CD, 0x13430..0x1343F, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A,
        0xE0000..0xE0FFF,
    )

    private const val WORD = 32
    private const val ROUND_WORDS = 5
    private const val WORD_HEX_DIGITS = 64

    /** A uint80 round id: a 16-bit phase over a 64-bit aggregator round. */
    private const val ROUND_ID_HEX_DIGITS = 20
    private const val PHASE_HEX_DIGITS = 4
    private const val AGGREGATOR_ROUND_HEX_DIGITS = 16

    /** Longer than any symbol or name a screen shows: what a contract says beyond this is refused. */
    private const val MAX_TEXT_BYTES = 1_024L
    private const val HEX = "0123456789abcdef"
}
