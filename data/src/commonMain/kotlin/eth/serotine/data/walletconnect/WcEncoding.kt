package eth.serotine.data.walletconnect

import kotlin.io.encoding.Base64

/**
 * The text encodings WalletConnect uses: lower-case hex for keys and topics, base64 for envelopes,
 * unpadded base64url for the relay's auth token, and base58btc for a `did:key`. Decoding is strict:
 * anything that is not exactly the encoding is null, never repaired.
 */
internal object WcEncoding {

    private const val HEX_DIGITS = "0123456789abcdef"
    private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** Envelopes are written padded and read with or without the padding, which the JS clients' encodings differ on. */
    private val base64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    private val base64Padded = Base64.Default
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            append(HEX_DIGITS[(byte.toInt() shr 4) and 0x0f])
            append(HEX_DIGITS[byte.toInt() and 0x0f])
        }
    }

    /** The bytes [text] spells in hex (either case), or null; with [length], only that many bytes. */
    fun hexOrNull(text: String, length: Int? = null): ByteArray? {
        if (text.length % 2 != 0) return null
        if (length != null && text.length != length * 2) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val high = text[2 * i].digitToIntOrNull(16) ?: return null
            val low = text[2 * i + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    fun base64(bytes: ByteArray): String = base64Padded.encode(bytes)

    fun base64OrNull(text: String): ByteArray? = try {
        base64.decode(text)
    } catch (e: IllegalArgumentException) {
        null
    }

    fun base64Url(bytes: ByteArray): String = base64Url.encode(bytes)

    /** Base58 in Bitcoin's alphabet, each leading zero byte as a leading '1'. */
    fun base58(bytes: ByteArray): String {
        val zeros = bytes.takeWhile { it == 0.toByte() }.size
        // Repeated division of the big-endian number by 58, least significant digit first.
        val number = bytes.copyOfRange(zeros, bytes.size).map { it.toInt() and 0xff }.toMutableList()
        val digits = StringBuilder()
        while (number.isNotEmpty()) {
            var remainder = 0
            val quotient = ArrayList<Int>(number.size)
            for (byte in number) {
                val accumulator = remainder * 256 + byte
                val q = accumulator / 58
                remainder = accumulator % 58
                if (quotient.isNotEmpty() || q != 0) quotient.add(q)
            }
            digits.append(BASE58_ALPHABET[remainder])
            number.clear()
            number.addAll(quotient)
        }
        repeat(zeros) { digits.append('1') }
        return digits.reverse().toString()
    }
}
