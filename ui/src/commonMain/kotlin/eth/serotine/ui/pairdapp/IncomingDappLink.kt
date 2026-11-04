package eth.serotine.ui.pairdapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * A `wc:` link the platform opened the app with (docs/walletconnect.md, part 1.2a), waiting for
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
     * Offers [uri] when it is a `wc:` link no longer than a pairing URI can be; false for anything
     * else, which is dropped.
     */
    fun offer(uri: String): Boolean {
        if (!uri.startsWith("wc:") || uri.length > MAX_CHARS) return false
        link.value = uri
        return true
    }

    /** The link waiting, taken: no one else gets it. */
    fun take(): String? = link.getAndUpdate { null }

    private companion object {
        /** As the pairing URI parser's own limit. */
        const val MAX_CHARS = 2048
    }
}
