package eth.serotine.domain.model

import kotlin.jvm.JvmInline

/**
 * The host a dApp states for itself, in the one form a per-app address is derived from (issue #252;
 * docs/keys-and-signing.md, *Per-app addresses*): lower case, ASCII, no port, no trailing dot. It is the
 * dApp's own claim (the Verify API is off, docs/walletconnect.md ruling 7), so equal hosts say nothing of
 * which site a dApp really is; they only pick the same address.
 *
 * Validated at construction ([parse]): an [AppHost] is always a DNS name of ASCII labels (letters, digits
 * and hyphens, at most 63 each, none starting or ending with a hyphen), at most 253 characters, never an
 * IPv6 address. A name with characters outside ASCII is refused rather than mapped: the ASCII form of an
 * internationalised name (`xn--…`) is accepted as it is.
 */
@JvmInline
value class AppHost private constructor(val name: String) {

    override fun toString(): String = name

    companion object {
        private const val MAX_LENGTH = 253
        private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        /**
         * The host of [url], the URL a dApp states in its WalletConnect metadata, or why it has none. The
         * scheme must be `https` or `http`; the user part, the port, the path, the query and the fragment
         * are dropped, the host is lower-cased and one trailing dot removed. Nothing else is repaired: an
         * authority holding whitespace or a backslash (which a browser reads as `/`, so that
         * `https://evil.test\@app.example/` reaches `evil.test`) has no host.
         */
        fun ofUrl(url: String): AppHostResult {
            val trimmed = url.trim()
            val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
            if (scheme != "https" && scheme != "http") return AppHostResult.NotAWebAddress
            val raw = trimmed.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            if (raw.any { it == '\\' || it.isWhitespace() }) return AppHostResult.NotAName
            val authority = raw.substringAfterLast('@')
            if (authority.startsWith("[")) return AppHostResult.NotAName
            val host = authority.substringBefore(':')
            val port = authority.substringAfter(':', missingDelimiterValue = "")
            if (authority.contains(':') && (port.isEmpty() || !port.all { it in '0'..'9' })) {
                return AppHostResult.NotAName
            }
            return normal(host)
        }

        /**
         * [text], a host alone (as the user types an old site's, or as a sign-in's domain names it without
         * its port), in its normal form, or why it is none.
         */
        fun parse(text: String): AppHostResult = normal(text.trim())

        /** [text] in its normal form, nothing trimmed: lower case, one trailing dot removed, checked. */
        private fun normal(text: String): AppHostResult {
            if (text.any { it.code > 0x7f }) return AppHostResult.NotAscii
            val name = text.lowercase().removeSuffix(".")
            if (name.isEmpty() || name.length > MAX_LENGTH) return AppHostResult.NotAName
            if (!name.split('.').all { LABEL.matches(it) }) return AppHostResult.NotAName
            return AppHostResult.Host(AppHost(name))
        }
    }
}

/** What [AppHost.ofUrl] and [AppHost.parse] find: a host, or why a per-app address cannot be made for it. */
sealed interface AppHostResult {

    /** The host, in its normal form. */
    data class Host(val host: AppHost) : AppHostResult

    /** The dApp stated no `https` or `http` URL. */
    data object NotAWebAddress : AppHostResult

    /** The host has characters outside ASCII: an internationalised name not in its `xn--` form. */
    data object NotAscii : AppHostResult

    /** The host is not a DNS name: empty, too long, an IPv6 address, a bad label or a bad port. */
    data object NotAName : AppHostResult
}

/**
 * The path of a per-app address (issue #252; docs/keys-and-signing.md, *Per-app addresses*):
 * `m/44'/60'/a'/0/0`, where `a` ([accountOf]) is the first 31 bits of
 * `keccak256("serotine/app-isolation/v1" ‖ host)`, read big-endian, and 1 where that gives 0. A phrase's
 * ordinary accounts are at account 0 (`m/44'/60'/0'/0/i`), so a per-app address is never one of them.
 * The derivation does not depend on the chain or on anything stored: the same phrase and host always give
 * the same address.
 */
object AppIsolation {

    /** The domain separator hashed before the host. Changing it changes every per-app address. */
    const val TAG = "serotine/app-isolation/v1"

    /** The BIP-44 account `a` of [host]'s address: hardened in the path, in `1..2³¹ - 1`. */
    fun accountOf(host: AppHost): Int {
        val hash = keccak256((TAG + host.name).encodeToByteArray())
        val first = ((hash[0].toInt() and 0xff) shl 24) or ((hash[1].toInt() and 0xff) shl 16) or
            ((hash[2].toInt() and 0xff) shl 8) or (hash[3].toInt() and 0xff)
        val account = first ushr 1
        return if (account == 0) 1 else account
    }

    /** [host]'s path as BIP-32 writes it, as the account's details show it. */
    fun pathOf(host: AppHost): String = "m/44'/60'/${accountOf(host)}'/0/0"

    /**
     * The host among [held], the hosts whose per-app addresses this device holds for one phrase, whose
     * account `a` is [host]'s, or null. Such a host is refused for [host] ("already used for another
     * app"), never shared silently: the two would see one address. [host] itself among [held] is no
     * collision.
     */
    fun collision(host: AppHost, held: Iterable<AppHost>): AppHost? {
        val account = accountOf(host)
        return held.firstOrNull { it != host && accountOf(it) == account }
    }
}
