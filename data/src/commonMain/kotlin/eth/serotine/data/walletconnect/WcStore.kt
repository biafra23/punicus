package eth.serotine.data.walletconnect

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.keys.EnvelopeCrypto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A dApp's (or this wallet's) description of itself, as it states it: shown as its claim, never verified. */
@Serializable
internal data class WcMetadata(
    val name: String,
    val description: String = "",
    val url: String,
    val icons: List<String> = emptyList(),
)

/** A pairing: a topic and its key, from a `wc:` URI. */
@Serializable
internal class WcStoredPairing(
    val topic: String,
    val symKey: String,
    val expirySeconds: Long,
    /** Whether a session came of it: then it lives [WcProtocol.ACTIVE_PAIRING_SECONDS]. */
    val active: Boolean = false,
) {
    override fun toString(): String = "WcStoredPairing(topic=$topic, symKey=redacted)"
}

/** A proposal waiting for the user, kept whole so that the settlement can echo what it asked. */
@Serializable
internal class WcStoredProposal(
    val id: Long,
    val pairingTopic: String,
    val proposerPublicKey: String,
    val dapp: WcMetadata,
    val requiredNamespaces: JsonObject,
    val optionalNamespaces: JsonObject,
    val expirySeconds: Long,
)

/** A settled session: its topic, its key, and what it was approved with. */
@Serializable
internal class WcStoredSession(
    val topic: String,
    val symKey: String,
    val pairingTopic: String,
    val selfPublicKey: String,
    val peerPublicKey: String,
    val dapp: WcMetadata,
    /** CAIP-10 accounts, `eip155:11155111:0x…`. */
    val accounts: List<String>,
    val methods: List<String>,
    val events: List<String>,
    val expirySeconds: Long,
    /** Whether the dApp acknowledged the settlement. */
    val acknowledged: Boolean = false,
    /** The id of the settlement this wallet sent, whose answer [acknowledged] waits for. */
    val settleRequestId: Long,
    val requiredNamespaces: JsonObject = JsonObject(emptyMap()),
    val optionalNamespaces: JsonObject = JsonObject(emptyMap()),
) {
    override fun toString(): String = "WcStoredSession(topic=$topic, symKey=redacted)"

    fun copy(
        accounts: List<String> = this.accounts,
        expirySeconds: Long = this.expirySeconds,
        acknowledged: Boolean = this.acknowledged,
    ) = WcStoredSession(
        topic, symKey, pairingTopic, selfPublicKey, peerPublicKey, dapp, accounts, methods, events,
        expirySeconds, acknowledged, settleRequestId, requiredNamespaces, optionalNamespaces,
    )
}

/** A dApp's request waiting for its answer. */
@Serializable
internal class WcStoredRequest(
    val id: Long,
    val topic: String,
    val chainId: String,
    val method: String,
    val params: JsonElement,
    val expirySeconds: Long,
)

/**
 * A message this side has to send, kept until the relay takes it: sealed already, so it goes out
 * even once its session's key is forgotten (a delete). [expirySeconds]: when the relay would no
 * longer hold it, after which it is dropped.
 */
@Serializable
internal class WcStoredOutgoing(
    val key: Long,
    val topic: String,
    val message: String,
    val ttlSeconds: Long,
    val tag: Int,
    val expirySeconds: Long,
)

/**
 * Everything WalletConnect keeps (docs/walletconnect.md, ruling 8): the relay-auth seed, the
 * pairings and sessions with their keys, the proposals and requests waiting for the user, the
 * messages waiting for the relay, and per topic the request ids seen, so that a replay is dropped
 * across restarts too. No account key is
 * here, and none of it is ever logged: [toString] says nothing of the keys.
 */
@Serializable
internal class WcState(
    val relayAuthSeed: String? = null,
    val pairings: List<WcStoredPairing> = emptyList(),
    val sessions: List<WcStoredSession> = emptyList(),
    val proposals: List<WcStoredProposal> = emptyList(),
    val requests: List<WcStoredRequest> = emptyList(),
    val seen: Map<String, List<Long>> = emptyMap(),
    val outbox: List<WcStoredOutgoing> = emptyList(),
) {
    override fun toString(): String = "WcState(pairings=${pairings.size}, sessions=${sessions.size}, " +
        "proposals=${proposals.size}, requests=${requests.size}, outbox=${outbox.size})"

    fun copy(
        relayAuthSeed: String? = this.relayAuthSeed,
        pairings: List<WcStoredPairing> = this.pairings,
        sessions: List<WcStoredSession> = this.sessions,
        proposals: List<WcStoredProposal> = this.proposals,
        requests: List<WcStoredRequest> = this.requests,
        seen: Map<String, List<Long>> = this.seen,
        outbox: List<WcStoredOutgoing> = this.outbox,
    ) = WcState(relayAuthSeed, pairings, sessions, proposals, requests, seen, outbox)

    /** Whether request [id] has been seen on [topic]. */
    fun hasSeen(topic: String, id: Long): Boolean = seen[topic]?.contains(id) == true

    /** With [id] recorded as seen on [topic], the oldest forgotten past [WcProtocol.SEEN_IDS_PER_TOPIC]. */
    fun seeing(topic: String, id: Long): WcState =
        copy(seen = seen + (topic to ((seen[topic].orEmpty() + id).takeLast(WcProtocol.SEEN_IDS_PER_TOPIC))))

    /** Without [topic]'s pairing or session, and what belongs to it but the messages waiting to go out. */
    fun forgetting(topic: String): WcState = copy(
        pairings = pairings.filterNot { it.topic == topic },
        sessions = sessions.filterNot { it.topic == topic },
        proposals = proposals.filterNot { it.pairingTopic == topic },
        requests = requests.filterNot { it.topic == topic },
        seen = seen - topic,
    )

    /** Without what expired by [nowSeconds]. */
    fun expiring(nowSeconds: Long): WcState {
        val expiredTopics = pairings.filter { it.expirySeconds <= nowSeconds }.map { it.topic } +
            sessions.filter { it.expirySeconds <= nowSeconds }.map { it.topic }
        val kept = expiredTopics.fold(this) { state, topic -> state.forgetting(topic) }
        return kept.copy(
            proposals = kept.proposals.filter { it.expirySeconds > nowSeconds },
            requests = kept.requests.filter { it.expirySeconds > nowSeconds },
            outbox = kept.outbox.filter { it.expirySeconds > nowSeconds },
        )
    }
}

/**
 * [WcState] in the file at [path], a [VersionedJsonFile] written as the key envelopes are: readable
 * by this user alone, flushed to the disk before it replaces the old one. Its directory is the
 * app's own, which the hosts keep out of backups and device transfers. A file that is damaged is set
 * aside and the state starts empty: the sessions are lost, and the dApps connect again.
 */
internal class WcStore(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    writeFile: (path: String, bytes: ByteArray) -> Unit = ::writePrivately,
) {
    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "WalletConnect sessions",
        formatVersion = FORMAT_VERSION,
        empty = WcState(),
        decode = { _, text -> JSON.decodeFromString(StoredFile.serializer(), text).state },
        encode = { state -> JSON.encodeToString(StoredFile.serializer(), StoredFile(FORMAT_VERSION, state)) },
        writeFile = writeFile,
    )

    /** The state, on each change; empty while the file cannot be read. */
    fun observe(): Flow<WcState> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> read.value
            is VersionedJsonFile.Read.Unreadable -> WcState()
        }
    }

    /**
     * Lets [decide] change the state as the file holds it now; [notSaved] when it cannot be read or
     * written. [decide] answers the result and the state to store, or null to store nothing.
     */
    suspend fun <R> change(notSaved: R, decide: (WcState) -> Pair<R, WcState?>): R = file.change(notSaved) { state ->
        val (answer, next) = decide(state)
        VersionedJsonFile.Decision(answer, next)
    }

    @Serializable
    private class StoredFile(val version: Int, val state: WcState)

    internal companion object Writing {
        /** [bytes] at [path], in a directory and a file this user alone can read, flushed to the disk first. */
        fun writePrivately(path: String, bytes: ByteArray) {
            val parent = kotlinx.io.files.Path(path).parent
            if (parent != null) EnvelopeCrypto.createPrivateDirectory(parent.toString())
            EnvelopeCrypto.writeDurably(path, bytes)
        }
    }

}

private const val FORMAT_VERSION = 1
private val JSON = Json
