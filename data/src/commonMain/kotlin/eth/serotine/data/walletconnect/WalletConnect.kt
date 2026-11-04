package eth.serotine.data.walletconnect

import eth.serotine.data.keys.EnvelopeCrypto
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * WalletConnect v2 as this app runs it (docs/walletconnect.md): the wallet role ([wallet]) over one
 * relay connection ([run]), its state in [store]. The host owns its lifecycle, as it owns the
 * engine's, and wires it to the domain's ports (part 1.2 of `feature/walletconnect-wallet`).
 *
 * [projectId] is the Reown project ID the build carries (ruling 2): it is sent to the relay, and is
 * no secret. The relay's auth key is made once and kept in [store] (ruling 8): one identity to the
 * relay for as long as that file lives.
 */
internal class WalletConnect(
    private val store: WcStore,
    connector: WcRelayConnector,
    private val projectId: String,
    metadata: WcMetadata,
    private val nowMillis: () -> Long,
    private val relayUrl: String = WcRelayAuth.DEFAULT_AUDIENCE,
) {
    init {
        // The one gate WalletConnectDapps decides "off" with: an ID it lets through is never refused here.
        require(WalletConnectDapps.isProjectId(projectId)) { "a Reown project ID is ASCII letters and digits" }
    }

    private var handler: WcWallet? = null

    private val relay = WcRelayClient(
        connector = connector,
        connectionUrl = ::connectionUrl,
        onMessage = { checkNotNull(handler).onMessage(it) },
        nowMillis = nowMillis,
    )

    /** The wallet's side of every session. */
    val wallet: WcWallet = WcWallet(store, relay, metadata, nowMillis).also { handler = it }

    /** Whether the relay connection is open. */
    val state: StateFlow<WcRelayClient.State> = relay.state

    /** Keeps the relay connection and the sessions going until cancelled. */
    suspend fun run(): Nothing = coroutineScope {
        launch { relay.run() }
        launch { relay.state.collect { if (it == WcRelayClient.State.CONNECTED) wallet.relayConnected() } }
        wallet.run()
    }

    /** The relay's URL with a fresh auth token: `<relay>?auth=<token>&projectId=<id>`. */
    private suspend fun connectionUrl(): String {
        val seed = relayAuthSeed()
        try {
            val token = WcRelayAuth.token(
                seed = seed,
                audience = relayUrl,
                subject = WcEncoding.hex(EnvelopeCrypto.randomBytes(WC_KEY_BYTES)),
                issuedAtSeconds = nowMillis() / 1000,
            )
            return "$relayUrl?auth=$token&projectId=$projectId"
        } finally {
            seed.fill(0)
        }
    }

    /** The relay-auth seed, made and kept on first use; a fresh one each time when it cannot be kept. */
    private suspend fun relayAuthSeed(): ByteArray {
        val fresh = WcKeys.newSeed()
        val kept = store.change<String?>(null) { state ->
            val existing = state.relayAuthSeed
            if (existing != null) {
                existing to null
            } else {
                WcEncoding.hex(fresh).let { it to state.copy(relayAuthSeed = it) }
            }
        }
        val seed = kept?.let { WcEncoding.hexOrNull(it, WC_KEY_BYTES) } ?: return fresh
        fresh.fill(0)
        return seed
    }
}
