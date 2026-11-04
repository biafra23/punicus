package eth.serotine.domain.model

/**
 * An unsigned 256-bit integer, the EVM's word: a fee per gas, a value in wei, an amount of a
 * token in its smallest unit. Immutable, equal and hashed by value, and exact: an operation
 * whose result would leave 0..2^256 - 1 is refused — null from the `…OrNull` forms, an
 * [ArithmeticException] from the operators — and never wrapped around, as the EVM's own
 * arithmetic would wrap it. [toString] is the decimal.
 */
class UInt256 private constructor(
    // Eight 32-bit limbs, least significant first. Never exposed and never written after
    // construction: that is what makes the type immutable.
    private val limbs: IntArray,
) : Comparable<UInt256> {

    /** Whether this is zero. */
    val isZero: Boolean
        get() = limbs.all { it == 0 }

    /** `this + other`, or null when the sum exceeds 2^256 - 1. */
    fun plusOrNull(other: UInt256): UInt256? {
        val sum = IntArray(LIMBS)
        var carry = 0L
        for (i in 0 until LIMBS) {
            val limb = limb(i) + other.limb(i) + carry
            sum[i] = limb.toInt()
            carry = limb ushr LIMB_BITS
        }
        return if (carry == 0L) UInt256(sum) else null
    }

    /** `this + other`; throws [ArithmeticException] when the sum exceeds 2^256 - 1. */
    operator fun plus(other: UInt256): UInt256 =
        plusOrNull(other) ?: throw ArithmeticException("uint256 overflow: $this + $other")

    /** `this - other`, or null when [other] is the larger (the difference would be negative). */
    fun minusOrNull(other: UInt256): UInt256? {
        val difference = IntArray(LIMBS)
        var borrow = 0L
        for (i in 0 until LIMBS) {
            val limb = limb(i) - other.limb(i) - borrow
            difference[i] = limb.toInt()
            borrow = if (limb < 0) 1 else 0
        }
        return if (borrow == 0L) UInt256(difference) else null
    }

    /** `this - other`; throws [ArithmeticException] when [other] is the larger. */
    operator fun minus(other: UInt256): UInt256 =
        minusOrNull(other) ?: throw ArithmeticException("uint256 underflow: $this - $other")

    /** `this × other`, or null when the product exceeds 2^256 - 1. */
    fun timesOrNull(other: UInt256): UInt256? {
        // The product fits when the upper eight of its sixteen limbs are zero.
        val product = wideProduct(other)
        if ((LIMBS until 2 * LIMBS).any { product[it] != 0L }) return null
        return UInt256(IntArray(LIMBS) { product[it].toInt() })
    }

    /** `this × other` exactly, as sixteen 32-bit limbs (least significant first): schoolbook multiplication. */
    private fun wideProduct(other: UInt256): LongArray {
        val product = LongArray(2 * LIMBS)
        for (i in 0 until LIMBS) {
            val a = limb(i).toULong()
            if (a == 0uL) continue
            var carry = 0uL
            for (j in 0 until LIMBS) {
                // At most (2^32 - 1)^2 + 2 × (2^32 - 1) = 2^64 - 1: it never leaves the 64 unsigned bits.
                val t = a * other.limb(j).toULong() + product[i + j].toULong() + carry
                product[i + j] = (t and LIMB_MASK.toULong()).toLong()
                carry = t shr LIMB_BITS
            }
            product[i + LIMBS] = carry.toLong()
        }
        return product
    }

    /** `this × other`; throws [ArithmeticException] when the product exceeds 2^256 - 1. */
    operator fun times(other: UInt256): UInt256 =
        timesOrNull(other) ?: throw ArithmeticException("uint256 overflow: $this × $other")

    override fun compareTo(other: UInt256): Int {
        for (i in LIMBS - 1 downTo 0) {
            val order = limb(i).compareTo(other.limb(i))
            if (order != 0) return order
        }
        return 0
    }

    /** The 32 big-endian bytes of this number, zero-padded on the left: an ABI word. */
    fun toBytes32(): ByteArray = ByteArray(BYTES) { i ->
        val fromEnd = BYTES - 1 - i
        (limbs[fromEnd / 4] ushr (8 * (fromEnd % 4))).toByte()
    }

    /**
     * The big-endian bytes of this number without leading zero bytes — empty for zero: the form
     * RLP encodes an integer in.
     */
    fun toBigEndian(): ByteArray {
        val word = toBytes32()
        val first = word.indexOfFirst { it != 0.toByte() }
        return if (first < 0) ByteArray(0) else word.copyOfRange(first, BYTES)
    }

    /** This number in decimal, without leading zeros ("0" for zero). */
    fun toDecimal(): String {
        if (isZero) return "0"
        // Nine digits at a time, least significant first.
        val chunks = mutableListOf<Int>()
        var rest = this
        while (!rest.isZero) {
            val (quotient, remainder) = rest.divRem(CHUNK)
            chunks += remainder
            rest = quotient
        }
        return buildString {
            append(chunks.last())
            for (i in chunks.size - 2 downTo 0) append(chunks[i].toString().padStart(CHUNK_DIGITS, '0'))
        }
    }

    /**
     * This amount of smallest units in whole units of a currency with [decimals] decimals, exact:
     * `1500000000000000000` with 18 is "1.5". The fraction has no trailing zeros, and no dot when
     * it is empty (`1000000` with 6 is "1"); zero is "0". [decimals] is not negative.
     */
    fun formatUnits(decimals: Int): String {
        require(decimals >= 0) { "decimals are not negative: $decimals" }
        val digits = toDecimal()
        if (decimals == 0) return digits
        val padded = digits.padStart(decimals + 1, '0')
        val whole = padded.substring(0, padded.length - decimals)
        val fraction = padded.substring(padded.length - decimals).trimEnd('0')
        return if (fraction.isEmpty()) whole else "$whole.$fraction"
    }

    /** This amount as [Wei]. */
    fun toWei(): Wei = Wei.of(toDecimal())

    /** This number as a [Long], or null when it is above [Long.MAX_VALUE] (2^63 - 1). */
    fun toLongOrNull(): Long? {
        if ((2 until LIMBS).any { limbs[it] != 0 } || limbs[1] < 0) return null
        return (limb(1) shl LIMB_BITS) or limb(0)
    }

    override fun equals(other: Any?): Boolean = other is UInt256 && limbs.contentEquals(other.limbs)

    override fun hashCode(): Int = limbs.contentHashCode()

    override fun toString(): String = toDecimal()

    /** Limb [i] as the unsigned value it holds. */
    private fun limb(i: Int): Long = limbs[i].toLong() and LIMB_MASK

    /**
     * `this × multiplier / divisor`, rounded down, with the product held exactly in 512 bits:
     * null when [divisor] is zero or the quotient exceeds 2^256 - 1.
     */
    internal fun mulDivOrNull(multiplier: UInt256, divisor: UInt256): UInt256? {
        if (divisor.isZero) return null
        val product = wideProduct(multiplier)
        // Long division, a bit at a time: the remainder stays below the divisor, and a bit
        // shifted out of it (overflow) means it was at least 2^256, above any divisor.
        val remainder = IntArray(LIMBS)
        val quotient = IntArray(2 * LIMBS)
        for (bit in 2 * LIMBS * LIMB_BITS - 1 downTo 0) {
            val overflow = shiftLeftOne(remainder, ((product[bit / LIMB_BITS] ushr (bit % LIMB_BITS)) and 1L).toInt())
            if (overflow || !lessThan(remainder, divisor.limbs)) {
                subtractInPlace(remainder, divisor.limbs)
                quotient[bit / LIMB_BITS] = quotient[bit / LIMB_BITS] or (1 shl (bit % LIMB_BITS))
            }
        }
        if ((LIMBS until 2 * LIMBS).any { quotient[it] != 0 }) return null
        return UInt256(quotient.copyOf(LIMBS))
    }

    /** `this / divisor` and `this % divisor`, for a [divisor] in 1..2^31 - 1. */
    internal fun divRem(divisor: Int): Pair<UInt256, Int> {
        require(divisor > 0) { "a divisor is positive: $divisor" }
        val quotient = IntArray(LIMBS)
        var remainder = 0L
        for (i in LIMBS - 1 downTo 0) {
            // remainder < divisor < 2^31, so this stays below 2^63.
            val current = (remainder shl LIMB_BITS) or limb(i)
            quotient[i] = (current / divisor).toInt()
            remainder = current % divisor
        }
        return UInt256(quotient) to remainder.toInt()
    }

    companion object {
        /** Shifts [limbs] left by one bit, [lowBit] in; whether a bit was shifted out at the top. */
        private fun shiftLeftOne(limbs: IntArray, lowBit: Int): Boolean {
            var carry = lowBit
            for (i in 0 until LIMBS) {
                val next = limbs[i] ushr (LIMB_BITS - 1)
                limbs[i] = (limbs[i] shl 1) or carry
                carry = next
            }
            return carry != 0
        }

        /** Whether [a] is below [b], both unsigned limbs. */
        private fun lessThan(a: IntArray, b: IntArray): Boolean {
            for (i in LIMBS - 1 downTo 0) {
                val order = (a[i].toLong() and LIMB_MASK).compareTo(b[i].toLong() and LIMB_MASK)
                if (order != 0) return order < 0
            }
            return false
        }

        /** `a -= b` modulo 2^256. */
        private fun subtractInPlace(a: IntArray, b: IntArray) {
            var borrow = 0L
            for (i in 0 until LIMBS) {
                val limb = (a[i].toLong() and LIMB_MASK) - (b[i].toLong() and LIMB_MASK) - borrow
                a[i] = limb.toInt()
                borrow = if (limb < 0) 1 else 0
            }
        }

        private const val LIMBS = 8
        private const val LIMB_BITS = 32
        private const val LIMB_MASK = 0xFFFF_FFFFL
        private const val BYTES = 32
        private const val CHUNK = 1_000_000_000
        private const val CHUNK_DIGITS = 9

        /** Zero. */
        val ZERO: UInt256 = UInt256(IntArray(LIMBS))

        /** One. */
        val ONE: UInt256 = of(1)

        /** 2^256 - 1, the largest. */
        val MAX: UInt256 = UInt256(IntArray(LIMBS) { -1 })

        private val TEN: UInt256 = of(10)

        /** [value], which is not negative. */
        fun of(value: Long): UInt256 {
            require(value >= 0) { "a uint256 is not negative: $value" }
            val limbs = IntArray(LIMBS)
            limbs[0] = value.toInt()
            limbs[1] = (value ushr LIMB_BITS).toInt()
            return UInt256(limbs)
        }

        /**
         * The number [decimal] spells, or null when it is not one: ASCII digits only, in the
         * canonical form [Wei] holds (no sign, no leading zeros, no separators, no whitespace),
         * and at most 2^256 - 1.
         */
        fun parse(decimal: String): UInt256? {
            if (decimal.length > 1 && decimal[0] == '0') return null
            return digitsOrNull(decimal)
        }

        /** [parse], throwing on bad input — for literals the caller knows are numbers. */
        fun of(decimal: String): UInt256 = requireNotNull(parse(decimal)) { "not a uint256: '$decimal'" }

        /**
         * The number [bytes] spell, big-endian: at most 32 of them, leading zeros allowed (an ABI
         * word has them, RLP's integers do not), none at all for zero.
         */
        fun fromBigEndian(bytes: ByteArray): UInt256 {
            require(bytes.size <= BYTES) { "a uint256 is at most $BYTES bytes, not ${bytes.size}" }
            val limbs = IntArray(LIMBS)
            for (i in bytes.indices) {
                val fromEnd = bytes.size - 1 - i
                limbs[fromEnd / 4] = limbs[fromEnd / 4] or ((bytes[i].toInt() and 0xff) shl (8 * (fromEnd % 4)))
            }
            return UInt256(limbs)
        }

        /**
         * The amount [text] spells in whole units of a currency with [decimals] decimals, in its
         * smallest units: "1.5" with 18 is 1500000000000000000. [text] is a whole part — "0", or
         * ASCII digits without a leading zero — optionally followed by a dot and one to
         * [decimals] fraction digits. Null for anything else: an empty string, a sign, an
         * exponent, separators or whitespace, a second dot, ".5" or "5." (no digits on one side
         * of the dot), more fraction digits than [decimals] (so a currency without decimals takes
         * whole numbers only, "5.0" included), or an amount above 2^256 - 1. [decimals] is not
         * negative.
         */
        fun parseUnits(text: String, decimals: Int): UInt256? {
            require(decimals >= 0) { "decimals are not negative: $decimals" }
            val dot = text.indexOf('.')
            val whole = if (dot < 0) text else text.substring(0, dot)
            val fraction = if (dot < 0) "" else text.substring(dot + 1)
            if (whole.isEmpty() || !whole.all(::isAsciiDigit) || (whole.length > 1 && whole[0] == '0')) return null
            if (dot >= 0 && (fraction.isEmpty() || !fraction.all(::isAsciiDigit))) return null
            if (fraction.length > decimals) return null
            var amount = digitsOrNull(whole + fraction) ?: return null
            // Scaled by ten per missing fraction digit; a nonzero amount overflows within 78 steps,
            // and zero stays zero, so even an absurd decimals count is a short loop.
            repeat(decimals - fraction.length) {
                if (amount.isZero) return amount
                amount = amount.timesOrNull(TEN) ?: return null
            }
            return amount
        }

        private fun isAsciiDigit(char: Char): Boolean = char in '0'..'9'

        /** The number a non-empty run of ASCII [digits] spells (leading zeros allowed), or null. */
        private fun digitsOrNull(digits: String): UInt256? {
            if (digits.isEmpty() || !digits.all(::isAsciiDigit)) return null
            val limbs = IntArray(LIMBS)
            for (digit in digits) {
                var carry = (digit - '0').toLong()
                for (i in 0 until LIMBS) {
                    val limb = (limbs[i].toLong() and LIMB_MASK) * 10 + carry
                    limbs[i] = limb.toInt()
                    carry = limb ushr LIMB_BITS
                }
                if (carry != 0L) return null
            }
            return UInt256(limbs)
        }
    }
}
