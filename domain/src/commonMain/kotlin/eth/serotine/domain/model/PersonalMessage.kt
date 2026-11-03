package eth.serotine.domain.model

/**
 * A message a dApp asks an account to sign with `personal_sign` (docs/walletconnect.md, *Signing in*):
 * the bytes exactly as the dApp sent them, never repaired. What is signed is their EIP-191 digest
 * ([digest]), computed here: a digest the dApp chose is never signed (`eth_sign` stays refused).
 *
 * At most [MAX_BYTES] long: a longer one is refused before it is shown.
 */
class PersonalMessage private constructor(private val bytes: ByteArray) {

    /** How many bytes the message has. */
    val size: Int get() = bytes.size

    /** The message as lower-case hex, two digits per byte, no prefix. */
    val hex: String get() = bytes.toHex()

    /**
     * The message as text, or null when it is not text to show as such: bytes that are not UTF-8,
     * or that hold a control character other than a line break or a tab, a formatting character
     * (a right-to-left override, a zero-width space), a line or paragraph separator, or any other
     * character Unicode says draws nothing (its default-ignorable code points: the tag characters,
     * the variation selectors, the Hangul fillers among them), which could make the text read as
     * something it does not say. Such a message is shown as hex, marked "not text".
     */
    val text: String? by lazy {
        val decoded = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return@lazy null
        }
        decoded.takeIf(::shownAsText)
    }

    /**
     * The EIP-191 digest (version `0x45`) of this message: Keccak-256 of
     * `"\x19Ethereum Signed Message:\n"`, the message's length in bytes as decimal digits, and the
     * message. What `personal_sign` signs.
     */
    fun digest(): Digest {
        val prefix = "\u0019Ethereum Signed Message:\n${bytes.size}".encodeToByteArray()
        return Digest.of(keccak256(prefix + bytes).toHex())
    }

    override fun equals(other: Any?): Boolean = other is PersonalMessage && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "PersonalMessage(${bytes.size} bytes)"

    companion object {
        /** The longest message a dApp may ask to have signed, in bytes. */
        const val MAX_BYTES: Int = 16 * 1024

        /** [bytes] as a message (copied), or null when longer than [MAX_BYTES]. */
        fun of(bytes: ByteArray): PersonalMessage? =
            bytes.takeIf { it.size <= MAX_BYTES }?.let { PersonalMessage(it.copyOf()) }

        /** The bytes [hex] spells (either case, no prefix), or null when it is not even-length hex or too long. */
        fun ofHex(hex: String): PersonalMessage? {
            if (hex.length % 2 != 0 || hex.length > 2 * MAX_BYTES) return null
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            return PersonalMessage(hex.hexToBytes())
        }

        // Characters that draw nothing, or draw a line break that is not one: a control, a formatting
        // character (a right-to-left override, a zero-width space), a line or paragraph separator.
        private val HIDDEN = setOf(
            CharCategory.CONTROL,
            CharCategory.FORMAT,
            CharCategory.LINE_SEPARATOR,
            CharCategory.PARAGRAPH_SEPARATOR,
        )

        // Unicode's Default_Ignorable_Code_Point (DerivedCoreProperties.txt): drawn as nothing even by a
        // font that lacks them. Some are formatting characters, caught above already; the others are
        // marks or letters (U+034F, the variation selectors, the Hangul fillers), or outside the BMP,
        // where Char.category sees only a surrogate (the tag characters, U+E0000 to U+E0FFF).
        private val DEFAULT_IGNORABLE = listOf(
            0x00AD..0x00AD, 0x034F..0x034F, 0x061C..0x061C, 0x115F..0x1160, 0x17B4..0x17B5,
            0x180B..0x180F, 0x200B..0x200F, 0x202A..0x202E, 0x2060..0x206F, 0x3164..0x3164,
            0xFE00..0xFE0F, 0xFEFF..0xFEFF, 0xFFA0..0xFFA0, 0xFFF0..0xFFF8, 0x1BCA0..0x1BCA3,
            0x1D173..0x1D17A, 0xE0000..0xE0FFF,
        )

        /** Whether every code point of [text] draws as itself (line breaks and tabs among them). */
        private fun shownAsText(text: String): Boolean {
            var at = 0
            while (at < text.length) {
                val char = text[at]
                val low = text.getOrNull(at + 1)
                // Strict UTF-8 decoding leaves no lone surrogate: a high one is always followed by its low one.
                val codePoint = if (char.isHighSurrogate() && low != null && low.isLowSurrogate()) {
                    0x10000 + ((char.code - 0xD800) shl 10) + (low.code - 0xDC00)
                } else {
                    char.code
                }
                at += if (codePoint > 0xFFFF) 2 else 1
                if (char == '\n' || char == '\r' || char == '\t') continue
                if (codePoint <= 0xFFFF && char.category in HIDDEN) return false
                if (DEFAULT_IGNORABLE.any { codePoint in it }) return false
            }
            return true
        }
    }
}
