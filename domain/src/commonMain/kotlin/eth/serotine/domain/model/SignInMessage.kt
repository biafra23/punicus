package eth.serotine.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A sign-in message of EIP-4361 ("Sign-In with Ethereum"), read field by field from a message's text
 * (docs/walletconnect.md, *Signing in*). Every field is the dApp's own claim: [parse] only reads them,
 * and the use case checks the ones that can be checked ([address], [chainId], the times) and warns of
 * a [domain] that is not the host the dApp stated for the session.
 *
 * Times are unix seconds.
 */
data class SignInMessage(
    /** The scheme before the domain, when the message names one (`https`). */
    val scheme: String?,
    /** The site asking for the sign-in, as `host` or `host:port`. */
    val domain: String,
    val address: Address,
    /** What the user is said to agree to, or null when the message has none. */
    val statement: String?,
    val uri: String,
    val version: String,
    val chainId: Long,
    val nonce: String,
    val issuedAtSeconds: Long,
    val expiresAtSeconds: Long?,
    val notBeforeSeconds: Long?,
    val requestId: String?,
    val resources: List<String>,
) {
    companion object {
        private const val HEADER = " wants you to sign in with your Ethereum account:"
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")
        // A host and an optional port, in ASCII alone: no user part (`app.example.org@evil.test` would read as
        // one site and be another), and no character that only looks like a letter or a space.
        private val AUTHORITY = Regex("^([A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*|\\[[0-9A-Fa-f:.]+])(:[0-9]{1,5})?$")
        private val URI = Regex("^[A-Za-z][A-Za-z0-9+.-]*:\\S+$")
        private val NONCE = Regex("^[A-Za-z0-9]{8,}$")
        private val CHAIN_ID = Regex("^[1-9][0-9]{0,17}$")
        private val REQUEST_ID = Regex("^\\S*$")

        // RFC 3339 names its parts; ISO 8601's other forms (week dates, no offset) are not it.
        private val DATE_TIME = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})$")

        /**
         * [text] read as an EIP-4361 message, or null when it is not exactly one: every line in the
         * order the EIP gives, a domain that is a host (ASCII letters, digits, dots and hyphens, or an
         * IPv6 address in brackets) with an optional port and no user part, version 1, an EIP-55 address (one case, or mixed case with a correct
         * checksum), a nonce of at least eight letters and digits, and RFC 3339 times. Line breaks are
         * `\n` alone. Nothing is repaired: a message that is not one is shown as it is, with the warning
         * that it is not a sign-in.
         */
        fun parse(text: String): SignInMessage? {
            val lines = text.split('\n')
            var at = 0
            fun next(): String? = lines.getOrNull(at)?.also { at++ }

            val header = next() ?: return null
            if (!header.endsWith(HEADER)) return null
            val origin = header.removeSuffix(HEADER)
            val scheme = origin.substringBefore("://", missingDelimiterValue = "").takeIf { "://" in origin }
            if (scheme != null && !SCHEME.matches(scheme)) return null
            val domain = if (scheme == null) origin else origin.substringAfter("://")
            if (!AUTHORITY.matches(domain)) return null

            val addressText = next() ?: return null
            if (!hasValidChecksum(addressText)) return null
            val address = Address.parse(addressText) ?: return null

            if (next() != "") return null
            // The statement, when there is one, sits between two empty lines. Without one, the EIP's
            // grammar has two empty lines and its reference library one: both are read.
            var statement: String? = null
            when (val line = next() ?: return null) {
                "" -> Unit
                else -> if (line.startsWith("URI: ")) {
                    at--
                } else {
                    statement = line
                    if (next() != "") return null
                }
            }

            fun field(name: String): String? = next()?.takeIf { it.startsWith("$name: ") }?.removePrefix("$name: ")

            val uri = field("URI")?.takeIf(URI::matches) ?: return null
            val version = field("Version")?.takeIf { it == "1" } ?: return null
            val chainId = field("Chain ID")?.takeIf(CHAIN_ID::matches)?.toLong() ?: return null
            val nonce = field("Nonce")?.takeIf(NONCE::matches) ?: return null
            val issuedAt = field("Issued At")?.let(::seconds) ?: return null

            fun optional(name: String): String? {
                val line = lines.getOrNull(at) ?: return null
                if (!line.startsWith("$name: ")) return null
                at++
                return line.removePrefix("$name: ")
            }

            val expiresAt = optional("Expiration Time")?.let { seconds(it) ?: return null }
            val notBefore = optional("Not Before")?.let { seconds(it) ?: return null }
            val requestId = optional("Request ID")?.let { it.takeIf(REQUEST_ID::matches) ?: return null }
            val resources = mutableListOf<String>()
            if (lines.getOrNull(at) == "Resources:") {
                at++
                while (at < lines.size) {
                    val resource = lines[at].takeIf { it.startsWith("- ") }?.removePrefix("- ") ?: return null
                    if (!URI.matches(resource)) return null
                    resources += resource
                    at++
                }
            }
            if (at != lines.size) return null
            return SignInMessage(
                scheme = scheme,
                domain = domain,
                address = address,
                statement = statement,
                uri = uri,
                version = version,
                chainId = chainId,
                nonce = nonce,
                issuedAtSeconds = issuedAt,
                expiresAtSeconds = expiresAt,
                notBeforeSeconds = notBefore,
                requestId = requestId,
                resources = resources,
            )
        }

        /** An RFC 3339 date-time as unix seconds, or null when it is not one. */
        @OptIn(ExperimentalTime::class)
        private fun seconds(text: String): Long? {
            if (!DATE_TIME.matches(text)) return null
            return try {
                Instant.parse(text).epochSeconds
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
