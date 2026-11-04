package eth.serotine.data.walletconnect

/**
 * A WalletConnect v2 pairing URI, as a dApp shows it in a QR code or a link:
 * `wc:<topic>@2?relay-protocol=irn&symKey=<hex>[&expiryTimestamp=<unix seconds>][&methods=...]`.
 *
 * [parse] is strict (docs/walletconnect.md, *Pairing and the session proposal*): what arrives this
 * way is untrusted input. Version 2 only, the `irn` relay only, a 32-byte topic and key in hex, and
 * no parameter this build does not apply: `relay-data` (a custom relay) and any unknown parameter
 * are refused, never ignored.
 */
internal class WcPairingUri private constructor(
    /** The pairing's topic, 64 lower-case hex digits. */
    val topic: String,
    /** The pairing's symmetric key: never logged, never in a `toString()`. */
    val symKey: ByteArray,
    /** When the dApp stops listening for an answer on this pairing, in unix seconds, if it says. */
    val expirySeconds: Long?,
    /**
     * The methods the dApp says it will send on this pairing (`wc_sessionPropose`,
     * `wc_sessionAuthenticate`, ...), as given; empty when it does not say.
     */
    val methods: List<String>,
) {
    override fun toString(): String = "WcPairingUri(topic=$topic, symKey=redacted)"

    /** What [parse] made of a text. */
    sealed interface Parse {
        /** [uri], read. */
        class Parsed(val uri: WcPairingUri) : Parse

        /** Not a WalletConnect pairing URI, or one with a field out of shape. */
        data object Malformed : Parse

        /** A pairing URI of [version], which this build does not read (v1's bridge URIs among them). */
        data class UnsupportedVersion(val version: String) : Parse

        /** A pairing URI with [parameter], which this build cannot apply: a custom relay, or one it does not know. */
        data class UnsupportedParameter(val parameter: String) : Parse
    }

    companion object {
        /** Longer than any pairing URI: a 64-digit topic, a 64-digit key and a few short parameters. */
        const val MAX_CHARS = 2048

        private val KNOWN = setOf("relay-protocol", "symKey", "expiryTimestamp", "methods")

        fun parse(text: String): Parse {
            val trimmed = text.trim()
            if (trimmed.length > MAX_CHARS || !trimmed.startsWith("wc:")) return Parse.Malformed
            val rest = trimmed.removePrefix("wc:").removePrefix("//")
            val at = rest.indexOf('@')
            if (at < 0) return Parse.Malformed
            val topic = rest.substring(0, at)
            val afterAt = rest.substring(at + 1)
            val question = afterAt.indexOf('?')
            val version = if (question < 0) afterAt else afterAt.substring(0, question)
            if (version != "2") return Parse.UnsupportedVersion(version)
            if (question < 0) return Parse.Malformed
            val parameters = LinkedHashMap<String, String>()
            for (pair in afterAt.substring(question + 1).split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq <= 0) return Parse.Malformed
                val key = percentDecode(pair.substring(0, eq)) ?: return Parse.Malformed
                val value = percentDecode(pair.substring(eq + 1)) ?: return Parse.Malformed
                if (key in parameters) return Parse.Malformed
                parameters[key] = value
            }
            parameters.keys.firstOrNull { it !in KNOWN }?.let { return Parse.UnsupportedParameter(it) }
            if (parameters["relay-protocol"] != "irn") return Parse.Malformed
            val topicBytes = WcEncoding.hexOrNull(topic, WC_KEY_BYTES) ?: return Parse.Malformed
            val symKey = WcEncoding.hexOrNull(parameters["symKey"] ?: return Parse.Malformed, WC_KEY_BYTES)
                ?: return Parse.Malformed
            val expiry = parameters["expiryTimestamp"]?.let { value ->
                if (value.isEmpty() || value.length > 12 || !value.all(Char::isDigit)) return Parse.Malformed
                value.toLong()
            }
            val methods = parameters["methods"]?.let { parseMethods(it) ?: return Parse.Malformed }.orEmpty()
            return Parse.Parsed(WcPairingUri(WcEncoding.hex(topicBytes), symKey, expiry, methods))
        }

        /** `[wc_sessionPropose],[wc_authRequest,wc_authBatchRequest]`: the names, in order, or null. */
        private fun parseMethods(value: String): List<String>? {
            val names = value.replace("[", "").replace("]", "").split(',').filter { it.isNotEmpty() }
            return names.takeIf { list -> list.all { name -> name.all { it.isLetterOrDigit() || it == '_' } } }
        }

        /** [text] with its `%XX` escapes decoded as UTF-8, or null when one is broken. */
        private fun percentDecode(text: String): String? {
            if ('%' !in text) return text
            val bytes = ArrayList<Byte>(text.length)
            var i = 0
            while (i < text.length) {
                if (text[i] == '%') {
                    if (i + 2 >= text.length) return null
                    val byte = WcEncoding.hexOrNull(text.substring(i + 1, i + 3), 1) ?: return null
                    bytes.add(byte[0])
                    i += 3
                } else {
                    bytes.addAll(text[i].toString().encodeToByteArray().toList())
                    i++
                }
            }
            return try {
                bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
            } catch (e: CharacterCodingException) {
                null
            }
        }
    }
}
