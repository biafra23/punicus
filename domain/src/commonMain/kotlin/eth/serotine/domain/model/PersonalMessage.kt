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
        decoded.takeIf(ShownText::isPlain)
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
    }
}
