package eth.serotine.domain.model

import org.kotlincrypto.hash.sha3.Keccak256

private const val HEX_DIGITS = "0123456789abcdef"

/** These bytes as lower-case hex, two digits per byte, no prefix. */
internal fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        append(HEX_DIGITS[(byte.toInt() shr 4) and 0x0f])
        append(HEX_DIGITS[byte.toInt() and 0x0f])
    }
}

/** The bytes this string of hex digits (either case, no prefix, even length) spells; throws on anything else. */
internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return ByteArray(length / 2) { i -> ((nibble(this[2 * i]) shl 4) or nibble(this[2 * i + 1])).toByte() }
}

private fun nibble(digit: Char): Int = when (digit) {
    in '0'..'9' -> digit - '0'
    in 'a'..'f' -> digit - 'a' + 10
    in 'A'..'F' -> digit - 'A' + 10
    else -> throw IllegalArgumentException("not a hex digit: '$digit'")
}

/** The 20 bytes of this address. */
internal fun Address.toBytes(): ByteArray = hex.removePrefix("0x").hexToBytes()

/** Keccak-256 of [bytes]: Ethereum's hash, the original Keccak padding rather than NIST SHA3-256's. */
internal fun keccak256(bytes: ByteArray): ByteArray = Keccak256().digest(bytes)

/**
 * Whether [text], an address as typed, carries no checksum (one case throughout) or a correct
 * EIP-55 one: mixed case is a checksum, and a wrong one is a typo.
 */
internal fun hasValidChecksum(text: String): Boolean {
    val digits = text.trim().let { if (it.startsWith("0x") || it.startsWith("0X")) it.drop(2) else it }
    if (digits == digits.lowercase() || digits == digits.uppercase()) return true
    val address = Address.parse("0x$digits") ?: return false
    return checksummed(address).drop(2) == digits
}

/**
 * [address] in its EIP-55 form: `0x` and 40 hex digits whose case carries the checksum. What a
 * payment link (`PaymentLinks`) names an address as, so that a wallet that checks the checksum
 * accepts it.
 */
internal fun checksummed(address: Address): String {
    val lower = address.hex.removePrefix("0x")
    val hash = keccak256(lower.encodeToByteArray())
    return buildString(2 + lower.length) {
        append("0x")
        lower.forEachIndexed { i, digit ->
            val nibble = (hash[i / 2].toInt() shr (if (i % 2 == 0) 4 else 0)) and 0x0f
            append(if (digit in 'a'..'f' && nibble >= 8) digit.uppercaseChar() else digit)
        }
    }
}
