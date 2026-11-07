package eth.serotine.ui.pairdapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * A `wc:` link the platform opened the app with (docs/walletconnect.md, part 1.2a), directly or
 * wrapped in Punicus's own deep link `serotine://wc?uri=<the wc: link, percent-encoded>`, waiting for
 * Connect a dApp to take it: the app opens that screen with the link filled in, and pairs only on
 * the user's Connect. Held in memory alone, never in a navigation argument, which Android keeps in
 * the saved state: the link carries its pairing's key (ruling 8). The host offers it; one link waits
 * at a time, the latest.
 */
class IncomingDappLink {

    private val link = MutableStateFlow<String?>(null)

    /** The link waiting, or null. */
    val pending: StateFlow<String?> = link.asStateFlow()

    /**
     * Offers [uri] when it is a `wc:` link no longer than a pairing URI can be, or Punicus's deep
     * link carrying one in its `uri` parameter; false for anything else, which is dropped. A deep link
     * with no `uri` (a dApp bringing the wallet to the front for a request) offers nothing.
     */
    fun offer(uri: String): Boolean {
        val wc = if (uri.startsWith(DEEP_LINK)) unwrap(uri.substring(DEEP_LINK.length)) else uri
        if (wc == null || !wc.startsWith("wc:") || wc.length > MAX_CHARS) return false
        link.value = wc
        return true
    }

    /** The link waiting, taken: no one else gets it. */
    fun take(): String? = link.getAndUpdate { null }

    private companion object {
        /** As the pairing URI parser's own limit. */
        const val MAX_CHARS = 2048

        /**
         * Punicus's deep link, the form a wallet picker opens: `serotine://wc?uri=` and the `wc:` link
         * percent-encoded, as WalletConnect's modals encode it (encodeURIComponent).
         */
        const val DEEP_LINK = "serotine://wc?"

        /**
         * The one `uri` parameter of a deep link's [query], percent-decoded; null when there is none,
         * more than one, or it is not well encoded. An unencoded `wc:` link is refused: its own `&`
         * cannot be told from the deep link's.
         */
        fun unwrap(query: String): String? {
            if (query.length > MAX_CHARS * 3) return null
            val values = query.split('&').filter { it.startsWith("uri=") }.map { it.removePrefix("uri=") }
            val encoded = values.singleOrNull() ?: return null
            if (encoded.startsWith("wc:")) return null
            return percentDecode(encoded)
        }

        /** Percent-decodes [text] as UTF-8; null on a malformed escape or malformed UTF-8. */
        fun percentDecode(text: String): String? {
            val bytes = ArrayList<Byte>(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    if (i + 2 >= text.length) return null
                    val hi = text[i + 1].digitToIntOrNull(16) ?: return null
                    val lo = text[i + 2].digitToIntOrNull(16) ?: return null
                    bytes.add((hi * 16 + lo).toByte())
                    i += 3
                } else {
                    if (c.code > 0x7F) return null
                    bytes.add(c.code.toByte())
                    i++
                }
            }
            return try {
                bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                null
            }
        }
    }
}
