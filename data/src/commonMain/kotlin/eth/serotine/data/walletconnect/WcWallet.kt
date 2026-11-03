package eth.serotine.data.walletconnect

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/** What the relay client needs from the wallet's side: [WcRelayClient] in the app, a fake in tests. */
internal interface WcTransport {
    suspend fun subscribe(topic: String): Boolean
    suspend fun unsubscribe(topic: String)
    suspend fun publish(topic: String, message: String, ttlSeconds: Long, tag: Int): Boolean
}

/** A proposal waiting for the user, as the screens show it. No key is in it. */
internal data class WcProposalView(
    val pairingTopic: String,
    val id: Long,
    val dapp: WcMetadata,
    val requiredChains: Set<String>,
    val optionalChains: Set<String>,
    val methods: Set<String>,
    val events: Set<String>,
    /** Why it can only be rejected, or null when it can be approved. */
    val refusal: WcProtocol.Error?,
    val expirySeconds: Long,
)

/** A settled session, as the screens show it. */
internal data class WcSessionView(
    val topic: String,
    val dapp: WcMetadata,
    val accounts: List<String>,
    val methods: List<String>,
    val events: List<String>,
    val expirySeconds: Long,
    val acknowledged: Boolean,
)

/** A dApp's request waiting for its answer. Its [params] are the dApp's, untrusted, for the use case to decode. */
internal data class WcRequestView(
    val id: Long,
    val topic: String,
    val dapp: WcMetadata,
    val chainId: String,
    val method: String,
    val params: JsonElement,
    val expirySeconds: Long,
)

/** What is waiting and what is open. */
internal data class WcView(
    val proposals: List<WcProposalView> = emptyList(),
    val sessions: List<WcSessionView> = emptyList(),
    val requests: List<WcRequestView> = emptyList(),
)

/**
 * The wallet role of WalletConnect's Sign protocol v2 (docs/walletconnect.md, *Direction 1*): pairs
 * from a `wc:` URI, keeps the proposals, sessions and requests in [store], and answers the dApp.
 *
 * - **Every message is opened and checked here**, then dropped when its request id has been seen on
 *   its topic before (a replay, or the relay delivering again), and only then handled. The state it
 *   leaves, the id among it, is saved before [onMessage] returns, so the relay's acknowledgement
 *   follows the save.
 * - **Sepolia alone, the closed list of methods** ([WcProtocol]): a proposal that requires more can
 *   only be rejected, and a request for another chain or another method is answered with an error
 *   at once, never shown.
 * - **What it keeps is no account key**: the pairings' and sessions' keys, which protect messages
 *   only (ruling 8). The session's own X25519 private key exists only during [approve].
 * - **Every message this side sends is saved first**, in the state's outbox, in the same write as
 *   the change it belongs to, and sent from there: removed once the relay took it, sent again on
 *   the next connection, at the next start, or within a minute when it did not. An answer is never
 *   lost to a relay that could not be reached, and [onMessage], which runs on the relay client's
 *   reader, never waits on the relay.
 *
 * [nowMillis] is the clock. Never throws, but for cancellation and for [onMessage] when the state
 * cannot be saved (the message is then not acknowledged, and comes again).
 */
internal class WcWallet(
    private val store: WcStore,
    private val transport: WcTransport,
    private val metadata: WcMetadata,
    private val nowMillis: () -> Long,
) {
    private sealed interface Outgoing {
        class Publish(val topic: String, val message: String, val ttlSeconds: Long, val tag: Int) : Outgoing
        class Unsubscribe(val topic: String) : Outgoing
    }

    /** Unsubscriptions, which need no saving: a topic followed for too long costs only the relay's deliveries. */
    private val unsubscriptions = Channel<String>(Channel.UNLIMITED)

    /** A signal that the saved outbox has something to send, which [run] sends. */
    private val outboxFilled = Channel<Unit>(Channel.CONFLATED)
    private val sending = Mutex()

    /** [publishes] added to the outbox, sent from there; the keys they are kept under go to [keys]. */
    private fun WcState.queuing(publishes: List<Outgoing.Publish>, now: Long, keys: MutableList<Long>? = null): WcState {
        if (publishes.isEmpty()) return this
        val entries = publishes.map {
            WcStoredOutgoing(Random.nextLong(), it.topic, it.message, it.ttlSeconds, it.tag, now + it.ttlSeconds)
        }
        keys?.addAll(entries.map { it.key })
        return copy(outbox = outbox + entries)
    }

    /**
     * Sends the outbox in order, each message removed once the relay took it, and stops at the first
     * the relay did not take: the rest wait for the next connection. With [only], those messages
     * alone. True when every one of them went out.
     */
    private suspend fun sendQueued(only: List<Long>? = null): Boolean = sending.withLock {
        val queued = store.change(emptyList<WcStoredOutgoing>()) { state -> state.outbox to null }
            .filter { only == null || it.key in only }
        val now = nowSeconds()
        for (entry in queued) {
            if (entry.expirySeconds <= now) continue // the relay would not hold it any longer: forgotten by expiry
            if (!transport.publish(entry.topic, entry.message, entry.ttlSeconds, entry.tag)) return@withLock false
            store.change(Unit) { state -> Unit to state.copy(outbox = state.outbox.filterNot { it.key == entry.key }) }
        }
        true
    }

    /** The relay connection is open again: what waits in the outbox goes out ([WalletConnect.run] calls it). */
    fun relayConnected() {
        outboxFilled.trySend(Unit)
    }

    private fun nowSeconds(): Long = nowMillis() / 1000

    /** The proposals, sessions and requests, on each change, without what has expired. */
    fun observe(): Flow<WcView> = store.observe().map { view(it.expiring(nowSeconds())) }.distinctUntilChanged()

    /**
     * Subscribes to every stored topic, sends the automatic answers, and forgets what expires, until
     * cancelled. The relay client runs beside it ([WalletConnect.run]).
     */
    suspend fun run(): Nothing = coroutineScope {
        launch {
            expireNow()
            outboxFilled.trySend(Unit) // what an earlier run could not send
        }
        launch {
            val state = store.observe().first()
            for (topic in state.pairings.map { it.topic } + state.sessions.map { it.topic }) {
                launch { transport.subscribe(topic) }
            }
        }
        launch { for (topic in unsubscriptions) transport.unsubscribe(topic) }
        launch { for (signal in outboxFilled) sendQueued() }
        launch {
            while (true) {
                delay(1.minutes)
                expireNow()
                outboxFilled.trySend(Unit)
            }
        }
        awaitCancellation()
    }

    /**
     * Forgets what has expired. A request that expired on a session still open is answered with
     * [WcProtocol.Error.REQUEST_EXPIRED]: a dApp is never left without an answer while it may listen.
     */
    private suspend fun expireNow() {
        val now = nowSeconds()
        val gone = store.change<List<String>>(emptyList()) { state ->
            val kept = state.expiring(now)
            val gone = (state.pairings.map { it.topic } + state.sessions.map { it.topic }) -
                (kept.pairings.map { it.topic } + kept.sessions.map { it.topic }).toSet()
            val open = kept.sessions.associateBy { it.topic }
            val expired = state.requests.filterNot { request -> kept.requests.any { it.topic == request.topic && it.id == request.id } }
            val errors = expired.mapNotNull { request ->
                val session = open[request.topic] ?: return@mapNotNull null
                expiredAnswer(session, request.id)
            }
            val changed = kept.proposals.size != state.proposals.size || expired.isNotEmpty() ||
                kept.outbox.size != state.outbox.size
            gone to (if (gone.isEmpty() && !changed) null else kept.queuing(errors, now))
        }
        for (topic in gone) unsubscriptions.send(topic)
    }

    private fun expiredAnswer(session: WcStoredSession, requestId: Long): Outgoing.Publish {
        val key = checkNotNull(WcEncoding.hexOrNull(session.symKey, WC_KEY_BYTES))
        return Outgoing.Publish(
            session.topic,
            WcEnvelope.sealType0(key, error(requestId, WcProtocol.Error.REQUEST_EXPIRED)),
            WcProtocol.SESSION_REQUEST.ttlSeconds,
            WcProtocol.SESSION_REQUEST.responseTag,
        )
    }

    // ----- The user's side -----

    /** What [pair] made of a URI. */
    sealed interface Pairing {
        /** Paired on [topic]: the dApp's proposal arrives there. [subscribed] false when the relay could not be reached yet. */
        data class Paired(val topic: String, val subscribed: Boolean) : Pairing

        /** Not a pairing URI this build reads ([WcPairingUri.Parse]). */
        data class Unreadable(val why: WcPairingUri.Parse) : Pairing

        /** Its expiry has passed: the dApp has stopped waiting. */
        data object Expired : Pairing

        /** It names the methods the dApp will send, and a session proposal is not among them. */
        data object NoProposal : Pairing

        /** Its topic is an open session's: a URI made to take that session over, refused. */
        data object TopicInUse : Pairing

        /** The state could not be saved. */
        data object NotSaved : Pairing
    }

    suspend fun pair(text: String): Pairing {
        val uri = when (val parsed = WcPairingUri.parse(text)) {
            is WcPairingUri.Parse.Parsed -> parsed.uri
            else -> return Pairing.Unreadable(parsed)
        }
        val now = nowSeconds()
        if (uri.expirySeconds != null && uri.expirySeconds <= now) return Pairing.Expired
        if (uri.methods.isNotEmpty() && WcProtocol.SESSION_PROPOSE.name !in uri.methods) return Pairing.NoProposal
        val stored = WcStoredPairing(
            topic = uri.topic,
            symKey = WcEncoding.hex(uri.symKey),
            expirySeconds = (uri.expirySeconds ?: (now + WcProtocol.INACTIVE_PAIRING_SECONDS))
                .coerceAtMost(now + WcProtocol.ACTIVE_PAIRING_SECONDS),
        )
        val saved = store.change<Pairing?>(Pairing.NotSaved) { state ->
            when {
                // A session's topic would have its messages opened with the URI's key, and the session
                // forgotten with the pairing: refused.
                state.sessions.any { it.topic == stored.topic } -> Pairing.TopicInUse to null
                // A known pairing keeps its own key.
                state.pairings.any { it.topic == stored.topic } -> null to null
                else -> null to state.copy(pairings = state.pairings + stored)
            }
        }
        if (saved != null) return saved
        return Pairing.Paired(uri.topic, subscribed = transport.subscribe(uri.topic))
    }

    /** What [approve] did. */
    sealed interface Approval {
        /** Settled on [topic]; [delivered] false when the relay has not taken both messages yet: they are sent again. */
        data class Approved(val topic: String, val delivered: Boolean) : Approval

        data object NoSuchProposal : Approval
        data object Expired : Approval

        /** The proposal asks for what no session here may have ([WcProposalView.refusal]). */
        data class Refused(val why: WcProtocol.Error) : Approval

        /** No account, an address out of shape, or one twice. */
        data object InvalidAccounts : Approval
        data object NotSaved : Approval
    }

    /**
     * Approves proposal [proposalId] on [pairingTopic] with the Sepolia accounts at [addresses] (`0x` and 40 hex
     * digits): settles a session with the methods and events the proposal asks for that are on the
     * lists, then answers the proposal with this side's public key.
     */
    suspend fun approve(pairingTopic: String, proposalId: Long, addresses: List<String>): Approval {
        if (addresses.isEmpty() || addresses.toSet().size != addresses.size || !addresses.all(::isAddress)) {
            return Approval.InvalidAccounts
        }
        val now = nowSeconds()
        val selfPrivate = WcKeys.newPrivateKey()
        try {
            var settledTopic: String? = null
            val keys = mutableListOf<Long>()
            val outcome = store.change<Approval>(Approval.NotSaved) { state ->
                val proposal = state.proposals.find { it.pairingTopic == pairingTopic && it.id == proposalId }
                    ?: return@change Approval.NoSuchProposal to null
                if (proposal.expirySeconds <= now) {
                    return@change Approval.Expired to state.copy(proposals = state.proposals - proposal)
                }
                val ask = WcProtocol.ask(proposal.requiredNamespaces, proposal.optionalNamespaces)
                    ?: return@change Approval.Refused(WcProtocol.Error.UNSUPPORTED_NAMESPACE_KEY) to null
                ask.refusal?.let { return@change Approval.Refused(it) to null }
                val pairing = state.pairings.find { it.topic == proposal.pairingTopic }
                    ?: return@change Approval.NoSuchProposal to state.copy(proposals = state.proposals - proposal)
                val pairingKey = checkNotNull(WcEncoding.hexOrNull(pairing.symKey, WC_KEY_BYTES))
                val peerPublic = checkNotNull(WcEncoding.hexOrNull(proposal.proposerPublicKey, WC_KEY_BYTES))
                val symKey = WcKeys.symKey(selfPrivate, peerPublic)
                    ?: return@change Approval.Refused(WcProtocol.Error.SESSION_SETTLEMENT_FAILED) to null
                val selfPublic = WcEncoding.hex(WcCrypto.x25519PublicKey(selfPrivate))
                val topic = WcKeys.topicOf(symKey)
                val accounts = addresses.map { "${WcProtocol.SEPOLIA}:${it.lowercase()}" }
                val methods = ask.methods.sorted()
                val events = ask.events.sorted()
                val settleId = rpcId()
                val session = WcStoredSession(
                    topic = topic,
                    symKey = WcEncoding.hex(symKey),
                    pairingTopic = pairing.topic,
                    selfPublicKey = selfPublic,
                    peerPublicKey = proposal.proposerPublicKey,
                    dapp = proposal.dapp,
                    accounts = accounts,
                    methods = methods,
                    events = events,
                    expirySeconds = now + WcProtocol.SESSION_SECONDS,
                    settleRequestId = settleId,
                    requiredNamespaces = proposal.requiredNamespaces,
                    optionalNamespaces = proposal.optionalNamespaces,
                )
                val settleParams = buildJsonObject {
                    putJsonObject("relay") { put("protocol", "irn") }
                    put("namespaces", namespaces(accounts, methods, events))
                    putJsonObject("controller") {
                        put("publicKey", selfPublic)
                        put("metadata", Json.encodeToJsonElement(WcMetadata.serializer(), metadata))
                    }
                    put("expiry", session.expirySeconds)
                    put("requiredNamespaces", proposal.requiredNamespaces)
                    put("optionalNamespaces", proposal.optionalNamespaces)
                    put("pairingTopic", pairing.topic)
                }
                val answerResult = buildJsonObject {
                    putJsonObject("relay") { put("protocol", "irn") }
                    put("responderPublicKey", selfPublic)
                }
                val settle = Outgoing.Publish(
                    topic,
                    WcEnvelope.sealType0(symKey, request(settleId, WcProtocol.SESSION_SETTLE.name, settleParams)),
                    WcProtocol.SESSION_SETTLE.ttlSeconds,
                    WcProtocol.SESSION_SETTLE.requestTag,
                )
                val answer = Outgoing.Publish(
                    pairing.topic,
                    WcEnvelope.sealType0(pairingKey, result(proposal.id, answerResult)),
                    WcProtocol.SESSION_PROPOSE.ttlSeconds,
                    WcProtocol.SESSION_PROPOSE.responseTag,
                )
                symKey.fill(0)
                settledTopic = topic
                val activePairing = WcStoredPairing(pairing.topic, pairing.symKey, now + WcProtocol.ACTIVE_PAIRING_SECONDS, true)
                Approval.Approved(topic, delivered = false) to state.copy(
                    proposals = state.proposals - proposal,
                    sessions = state.sessions + session,
                    pairings = state.pairings.map { if (it.topic == pairing.topic) activePairing else it },
                ).queuing(listOf(settle, answer), now, keys)
            }
            val topic = settledTopic
            if (outcome !is Approval.Approved || topic == null) return outcome
            transport.subscribe(topic)
            return Approval.Approved(topic, delivered = sendQueued(keys))
        } finally {
            selfPrivate.fill(0)
        }
    }

    /**
     * Rejects proposal [proposalId] on [pairingTopic] with [why] (the user's refusal, unless the proposal
     * asked for more than a session here may have). True once saved and delivered; saved but not yet
     * delivered, it is sent again.
     */
    suspend fun reject(pairingTopic: String, proposalId: Long, why: WcProtocol.Error = WcProtocol.Error.USER_REJECTED): Boolean {
        val keys = mutableListOf<Long>()
        val saved = store.change(false) { state ->
            val proposal = state.proposals.find { it.pairingTopic == pairingTopic && it.id == proposalId }
                ?: return@change false to null
            val pairing = state.pairings.find { it.topic == proposal.pairingTopic }
            val rejection = pairing?.let {
                val key = checkNotNull(WcEncoding.hexOrNull(it.symKey, WC_KEY_BYTES))
                Outgoing.Publish(
                    it.topic,
                    WcEnvelope.sealType0(key, error(proposal.id, why)),
                    WcProtocol.SESSION_PROPOSE.ttlSeconds,
                    WcProtocol.SESSION_PROPOSE_REJECT_TAG,
                )
            }
            true to state.copy(proposals = state.proposals - proposal).queuing(listOfNotNull(rejection), nowSeconds(), keys)
        }
        // Nothing goes out for a change that was not saved: the proposal would still be shown.
        if (!saved || keys.isEmpty()) return false
        return sendQueued(keys)
    }

    /**
     * What [respond] and [respondError] did. [NOT_DELIVERED]: saved, and the relay has not taken it
     * yet; it is sent again on the next connection, never lost.
     */
    enum class Answer { DELIVERED, NOT_DELIVERED, NO_SUCH_REQUEST, EXPIRED, NOT_SAVED }

    /**
     * Answers request [requestId] on session [topic] with [value], the method's result. A request is
     * named by its topic and id together: ids are unique per topic only.
     */
    suspend fun respond(topic: String, requestId: Long, value: JsonElement): Answer =
        answer(topic, requestId) { result(requestId, value) }

    /** Answers request [requestId] on session [topic] with [why]. */
    suspend fun respondError(topic: String, requestId: Long, why: WcProtocol.Error): Answer =
        answer(topic, requestId) { error(requestId, why) }

    private suspend fun answer(topic: String, requestId: Long, payload: () -> ByteArray): Answer {
        val now = nowSeconds()
        var message: Outgoing.Publish? = null
        val keys = mutableListOf<Long>()
        val outcome = store.change(Answer.NOT_SAVED) { state ->
            val request = state.requests.find { it.topic == topic && it.id == requestId }
                ?: return@change Answer.NO_SUCH_REQUEST to null
            val session = state.sessions.find { it.topic == topic }
            val kept = state.copy(requests = state.requests - request)
            if (session == null) return@change Answer.EXPIRED to kept
            if (request.expirySeconds <= now) {
                // Past its expiry: the dApp is told so, never handed a late answer.
                return@change Answer.EXPIRED to kept.queuing(listOf(expiredAnswer(session, requestId)), now, keys)
            }
            val key = checkNotNull(WcEncoding.hexOrNull(session.symKey, WC_KEY_BYTES))
            message = Outgoing.Publish(
                topic,
                WcEnvelope.sealType0(key, payload()),
                WcProtocol.SESSION_REQUEST.ttlSeconds,
                WcProtocol.SESSION_REQUEST.responseTag,
            )
            Answer.DELIVERED to kept.queuing(listOfNotNull(message), now, keys)
        }
        // Nothing goes out for a change that was not saved: the request would still be shown, and answerable again.
        if (outcome == Answer.NOT_SAVED || keys.isEmpty()) return outcome
        val sent = sendQueued(keys)
        return when {
            outcome == Answer.EXPIRED -> Answer.EXPIRED
            sent -> Answer.DELIVERED
            else -> Answer.NOT_DELIVERED
        }
    }

    /** Ends session [topic], telling the dApp. True when it was a session here and its end was saved. */
    suspend fun disconnect(topic: String): Boolean {
        val keys = mutableListOf<Long>()
        val known = store.change(false) { state ->
            val session = state.sessions.find { it.topic == topic } ?: return@change false to null
            val key = checkNotNull(WcEncoding.hexOrNull(session.symKey, WC_KEY_BYTES))
            val params = buildJsonObject {
                put("code", WcProtocol.Error.USER_DISCONNECTED.code)
                put("message", WcProtocol.Error.USER_DISCONNECTED.message)
            }
            val delete = Outgoing.Publish(
                topic,
                WcEnvelope.sealType0(key, request(rpcId(), WcProtocol.SESSION_DELETE.name, params)),
                WcProtocol.SESSION_DELETE.ttlSeconds,
                WcProtocol.SESSION_DELETE.requestTag,
            )
            // The delete stays in the outbox, sealed, after the session's key is forgotten.
            true to state.forgetting(topic).queuing(listOf(delete), nowSeconds(), keys)
        }
        // Not saved: the session is still here and still followed, so the dApp is not told it ended.
        if (!known) return false
        sendQueued(keys)
        transport.unsubscribe(topic)
        return true
    }

    /** Extends session [topic] to [WcProtocol.SESSION_SECONDS] from now. True once delivered. */
    suspend fun extend(topic: String): Boolean {
        val expiry = nowSeconds() + WcProtocol.SESSION_SECONDS
        val keys = mutableListOf<Long>()
        val saved = store.change(false) { state ->
            val session = state.sessions.find { it.topic == topic } ?: return@change false to null
            val key = checkNotNull(WcEncoding.hexOrNull(session.symKey, WC_KEY_BYTES))
            val extension = Outgoing.Publish(
                topic,
                WcEnvelope.sealType0(key, request(rpcId(), WcProtocol.SESSION_EXTEND.name, buildJsonObject { put("expiry", expiry) })),
                WcProtocol.SESSION_EXTEND.ttlSeconds,
                WcProtocol.SESSION_EXTEND.requestTag,
            )
            true to state.copy(sessions = state.sessions.map { if (it.topic == topic) it.copy(expirySeconds = expiry) else it })
                .queuing(listOf(extension), nowSeconds(), keys)
        }
        if (!saved) return false
        return sendQueued(keys)
    }

    /**
     * Gives session [topic] the Sepolia accounts at [addresses] instead: a session update, then the
     * `accountsChanged` event where the session carries it. True once both are delivered.
     */
    suspend fun updateAccounts(topic: String, addresses: List<String>): Boolean {
        if (addresses.isEmpty() || addresses.toSet().size != addresses.size || !addresses.all(::isAddress)) return false
        val messages = mutableListOf<Outgoing.Publish>()
        val keys = mutableListOf<Long>()
        val saved = store.change(false) { state ->
            val session = state.sessions.find { it.topic == topic } ?: return@change false to null
            val key = checkNotNull(WcEncoding.hexOrNull(session.symKey, WC_KEY_BYTES))
            val accounts = addresses.map { "${WcProtocol.SEPOLIA}:${it.lowercase()}" }
            val update = buildJsonObject { put("namespaces", namespaces(accounts, session.methods, session.events)) }
            messages += Outgoing.Publish(
                topic,
                WcEnvelope.sealType0(key, request(rpcId(), WcProtocol.SESSION_UPDATE.name, update)),
                WcProtocol.SESSION_UPDATE.ttlSeconds,
                WcProtocol.SESSION_UPDATE.requestTag,
            )
            if ("accountsChanged" in session.events) {
                val event = buildJsonObject {
                    putJsonObject("event") {
                        put("name", "accountsChanged")
                        putJsonArray("data") { addresses.forEach { add(JsonPrimitive(it.lowercase())) } }
                    }
                    put("chainId", WcProtocol.SEPOLIA)
                }
                messages += Outgoing.Publish(
                    topic,
                    WcEnvelope.sealType0(key, request(rpcId(), WcProtocol.SESSION_EVENT.name, event)),
                    WcProtocol.SESSION_EVENT.ttlSeconds,
                    WcProtocol.SESSION_EVENT.requestTag,
                )
            }
            true to state.copy(sessions = state.sessions.map { if (it.topic == topic) it.copy(accounts = accounts) else it })
                .queuing(messages, nowSeconds(), keys)
        }
        if (!saved) return false
        return sendQueued(keys)
    }

    // ----- The dApp's side -----

    /**
     * Handles a message the relay delivered ([WcRelayClient]'s `onMessage`). Throws when the state
     * cannot be saved, so that it is not acknowledged and comes again.
     */
    suspend fun onMessage(message: WcRelayMessage) {
        val now = nowSeconds()
        val outgoing = store.change<List<Outgoing>?>(null) { state ->
            val (outgoing, next) = handle(state, message, now)
            val publishes = outgoing.filterIsInstance<Outgoing.Publish>()
            // The answers are saved with the request's id, in one write, and sent from the outbox after.
            outgoing to (if (publishes.isEmpty()) next else (next ?: state).queuing(publishes, now))
        } ?: error("the WalletConnect state could not be saved")
        for (item in outgoing) if (item is Outgoing.Unsubscribe) unsubscriptions.send(item.topic)
        if (outgoing.any { it is Outgoing.Publish }) outboxFilled.trySend(Unit)
    }

    private fun handle(state: WcState, message: WcRelayMessage, now: Long): Pair<List<Outgoing>, WcState?> {
        val nothing = emptyList<Outgoing>() to null
        val pairing = state.pairings.find { it.topic == message.topic }
        val session = state.sessions.find { it.topic == message.topic }
        val keyHex = pairing?.symKey ?: session?.symKey ?: return nothing
        val key = WcEncoding.hexOrNull(keyHex, WC_KEY_BYTES) ?: return nothing
        // Type 1 is for a peer without the topic's key, which no message to this wallet is.
        val parsed = WcEnvelope.parse(message.message) as? WcEnvelope.Parsed.Type0 ?: return nothing
        val plaintext = WcEnvelope.open(key, parsed) ?: return nothing
        val payload = try {
            Json.parseToJsonElement(plaintext.decodeToString()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return nothing
        val id = (payload["id"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return nothing
        val method = (payload["method"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (method == null) {
            return if (session != null) onSessionAnswer(state, session, id, payload) else nothing
        }
        // A request seen before on this topic is a replay, or the relay delivering it again: dropped.
        if (state.hasSeen(message.topic, id)) return nothing
        val seen = state.seeing(message.topic, id)
        val params = payload["params"]
        return if (pairing != null) {
            onPairingRequest(seen, pairing, key, id, method, params, now)
        } else {
            onSessionRequest(seen, checkNotNull(session), key, id, method, params, now)
        }
    }

    private fun onPairingRequest(
        state: WcState,
        pairing: WcStoredPairing,
        key: ByteArray,
        id: Long,
        method: String,
        params: JsonElement?,
        now: Long,
    ): Pair<List<Outgoing>, WcState?> {
        fun reply(payload: ByteArray, method: WcProtocol.Method) =
            Outgoing.Publish(pairing.topic, WcEnvelope.sealType0(key, payload), method.ttlSeconds, method.responseTag)
        return when (method) {
            WcProtocol.SESSION_PROPOSE.name -> {
                val proposal = proposal(id, pairing.topic, params as? JsonObject, now)
                    ?: return listOf(reply(invalidParams(id), WcProtocol.SESSION_PROPOSE)) to state
                if (proposal.expirySeconds <= now) return emptyList<Outgoing>() to state
                emptyList<Outgoing>() to state.copy(proposals = state.proposals + proposal)
            }
            WcProtocol.PAIRING_PING.name -> listOf(reply(result(id, JsonPrimitive(true)), WcProtocol.PAIRING_PING)) to state
            WcProtocol.PAIRING_DELETE.name -> listOf(
                reply(result(id, JsonPrimitive(true)), WcProtocol.PAIRING_DELETE),
                Outgoing.Unsubscribe(pairing.topic),
            ) to state.forgetting(pairing.topic)
            // Not answered in v1 (docs/walletconnect.md): the dApp falls back to its session proposal.
            "wc_sessionAuthenticate" -> emptyList<Outgoing>() to state
            // Answered under the method's own tag where it is one of Sign's, a pairing ping's otherwise.
            else -> listOf(
                reply(error(id, WcProtocol.Error.WC_METHOD_UNSUPPORTED), WcProtocol.BY_NAME[method] ?: WcProtocol.PAIRING_PING),
            ) to state
        }
    }

    private fun onSessionRequest(
        state: WcState,
        session: WcStoredSession,
        key: ByteArray,
        id: Long,
        method: String,
        params: JsonElement?,
        now: Long,
    ): Pair<List<Outgoing>, WcState?> {
        val known = WcProtocol.BY_NAME[method]
        fun reply(payload: ByteArray) = Outgoing.Publish(
            session.topic,
            WcEnvelope.sealType0(key, payload),
            known?.ttlSeconds ?: WcProtocol.SESSION_REQUEST.ttlSeconds,
            known?.responseTag ?: WcProtocol.SESSION_REQUEST.responseTag,
        )
        return when (method) {
            WcProtocol.SESSION_REQUEST.name -> {
                val fields = params as? JsonObject ?: return listOf(reply(invalidParams(id))) to state
                val request = fields["request"] as? JsonObject ?: return listOf(reply(invalidParams(id))) to state
                val chainId = fields.string("chainId") ?: return listOf(reply(invalidParams(id))) to state
                val name = request.string("method") ?: return listOf(reply(invalidParams(id))) to state
                if (chainId != WcProtocol.SEPOLIA) {
                    return listOf(reply(error(id, WcProtocol.Error.UNSUPPORTED_CHAINS))) to state
                }
                if (name !in session.methods) {
                    return listOf(reply(error(id, WcProtocol.Error.PROVIDER_UNSUPPORTED_METHOD))) to state
                }
                val stated = request["expiryTimestamp"]?.let { (it as? JsonPrimitive)?.longOrNull ?: -1 }
                if (stated != null && stated <= now) {
                    return listOf(reply(error(id, WcProtocol.Error.REQUEST_EXPIRED))) to state
                }
                // Only those still live: expired ones wait for the minute's sweep, and count for nothing.
                val waiting = state.requests.count { it.topic == session.topic && it.expirySeconds > now }
                if (waiting >= WcProtocol.PENDING_REQUESTS_PER_SESSION) {
                    return listOf(reply(error(id, WcProtocol.Error.LIMIT_EXCEEDED))) to state
                }
                val expiry = (stated ?: (now + WcProtocol.REQUEST_SECONDS)).coerceAtMost(now + WcProtocol.MAX_REQUEST_SECONDS)
                val stored = WcStoredRequest(id, session.topic, chainId, name, request["params"] ?: JsonNull, expiry)
                emptyList<Outgoing>() to state.copy(requests = state.requests + stored)
            }
            WcProtocol.SESSION_PING.name -> listOf(reply(result(id, JsonPrimitive(true)))) to state
            WcProtocol.SESSION_DELETE.name -> listOf(
                reply(result(id, JsonPrimitive(true))),
                Outgoing.Unsubscribe(session.topic),
            ) to state.forgetting(session.topic)
            // This wallet is the session's controller: only it updates and extends.
            WcProtocol.SESSION_UPDATE.name -> listOf(reply(error(id, WcProtocol.Error.UNAUTHORIZED_UPDATE_REQUEST))) to state
            WcProtocol.SESSION_EXTEND.name -> listOf(reply(error(id, WcProtocol.Error.UNAUTHORIZED_EXTEND_REQUEST))) to state
            WcProtocol.SESSION_EVENT.name -> listOf(reply(error(id, WcProtocol.Error.UNAUTHORIZED_EVENT))) to state
            else -> listOf(reply(error(id, WcProtocol.Error.WC_METHOD_UNSUPPORTED))) to state
        }
    }

    private fun onSessionAnswer(
        state: WcState,
        session: WcStoredSession,
        id: Long,
        payload: JsonObject,
    ): Pair<List<Outgoing>, WcState?> {
        if (id != session.settleRequestId || session.acknowledged) return emptyList<Outgoing>() to null
        val error = payload["error"]
        return if (error != null && error !is JsonNull) {
            // The dApp did not take the settlement: the session never existed for it.
            listOf(Outgoing.Unsubscribe(session.topic)) to state.forgetting(session.topic)
        } else if ((payload["result"] as? JsonPrimitive)?.booleanOrNull == true) {
            emptyList<Outgoing>() to state.copy(
                sessions = state.sessions.map { if (it.topic == session.topic) it.copy(acknowledged = true) else it },
            )
        } else {
            emptyList<Outgoing>() to null
        }
    }

    /** A `wc_sessionPropose`'s params as a proposal, or null when they are out of shape or ask for another relay. */
    private fun proposal(id: Long, pairingTopic: String, params: JsonObject?, now: Long): WcStoredProposal? {
        if (params == null) return null
        val relays = params["relays"] as? JsonArray ?: return null
        val onlyIrn = relays.isNotEmpty() && relays.all { relay ->
            val fields = relay as? JsonObject
            fields != null && fields.string("protocol") == "irn" && fields["data"] == null
        }
        if (!onlyIrn) return null
        val proposer = params["proposer"] as? JsonObject ?: return null
        val publicKey = proposer.string("publicKey")?.takeIf { WcEncoding.hexOrNull(it, WC_KEY_BYTES) != null } ?: return null
        val dapp = metadataOf(proposer["metadata"] as? JsonObject) ?: return null
        val required = (params["requiredNamespaces"] ?: JsonObject(emptyMap())) as? JsonObject ?: return null
        val optional = (params["optionalNamespaces"] ?: JsonObject(emptyMap())) as? JsonObject ?: return null
        if (WcProtocol.ask(required, optional) == null) return null
        val stated = params["expiryTimestamp"]?.let { (it as? JsonPrimitive)?.longOrNull ?: return null }
        val expiry = (stated ?: (now + WcProtocol.SESSION_PROPOSE.ttlSeconds)).coerceAtMost(now + WcProtocol.ACTIVE_PAIRING_SECONDS)
        return WcStoredProposal(id, pairingTopic, publicKey.lowercase(), dapp, required, optional, expiry)
    }

    private fun view(state: WcState): WcView {
        val sessions = state.sessions.associateBy { it.topic }
        return WcView(
            proposals = state.proposals.map { proposal ->
                val ask = WcProtocol.ask(proposal.requiredNamespaces, proposal.optionalNamespaces)
                WcProposalView(
                    pairingTopic = proposal.pairingTopic,
                    id = proposal.id,
                    dapp = proposal.dapp,
                    requiredChains = ask?.requiredChains.orEmpty(),
                    optionalChains = ask?.optionalChains.orEmpty(),
                    methods = ask?.methods.orEmpty(),
                    events = ask?.events.orEmpty(),
                    refusal = ask?.refusal ?: if (ask == null) WcProtocol.Error.UNSUPPORTED_NAMESPACE_KEY else null,
                    expirySeconds = proposal.expirySeconds,
                )
            },
            sessions = state.sessions.map {
                WcSessionView(it.topic, it.dapp, it.accounts, it.methods, it.events, it.expirySeconds, it.acknowledged)
            },
            requests = state.requests.mapNotNull { request ->
                val session = sessions[request.topic] ?: return@mapNotNull null
                WcRequestView(request.id, request.topic, session.dapp, request.chainId, request.method, request.params, request.expirySeconds)
            },
        )
    }

    private fun namespaces(accounts: List<String>, methods: List<String>, events: List<String>) = buildJsonObject {
        putJsonObject("eip155") {
            putJsonArray("chains") { add(JsonPrimitive(WcProtocol.SEPOLIA)) }
            putJsonArray("accounts") { accounts.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("methods") { methods.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("events") { events.forEach { add(JsonPrimitive(it)) } }
        }
    }

    /**
     * A JSON-RPC id for a request of this side: the time in milliseconds, then three random digits, as
     * the reference clients' `payloadId()` makes them. Below 2^53, so a dApp that reads it as a JavaScript
     * number reads it exactly, and its answer names the same id.
     */
    private fun rpcId(): Long = nowMillis() * 1_000 + Random.nextInt(1_000)

    companion object {
        /** The longest name, description or URL a dApp's metadata may state, and the most icons. */
        const val MAX_METADATA_CHARS = 1_000
        const val MAX_ICONS = 10

        fun isAddress(text: String): Boolean =
            text.length == 42 && text.startsWith("0x") && WcEncoding.hexOrNull(text.substring(2), 20) != null

        /** A dApp's stated metadata, or null when it is out of shape: shown as its claim, so only its shape is checked. */
        fun metadataOf(fields: JsonObject?): WcMetadata? {
            if (fields == null) return null
            val name = fields.string("name") ?: return null
            val url = fields.string("url") ?: return null
            val description = fields["description"]?.let { (it as? JsonPrimitive)?.contentOrNull ?: return null } ?: ""
            val icons = (fields["icons"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: return null }.orEmpty()
            if (listOf(name, url, description).any { it.length > MAX_METADATA_CHARS } || icons.size > MAX_ICONS) return null
            return WcMetadata(name, description, url, icons.filter { it.length <= MAX_METADATA_CHARS })
        }

        fun request(id: Long, method: String, params: JsonElement): ByteArray = buildJsonObject {
            put("id", id)
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }.toString().encodeToByteArray()

        fun result(id: Long, result: JsonElement): ByteArray = buildJsonObject {
            put("id", id)
            put("jsonrpc", "2.0")
            put("result", result)
        }.toString().encodeToByteArray()

        fun error(id: Long, why: WcProtocol.Error): ByteArray = buildJsonObject {
            put("id", id)
            put("jsonrpc", "2.0")
            putJsonObject("error") {
                put("code", why.code)
                put("message", why.message)
            }
        }.toString().encodeToByteArray()

        /** JSON-RPC's own error for params out of shape. */
        fun invalidParams(id: Long): ByteArray = buildJsonObject {
            put("id", id)
            put("jsonrpc", "2.0")
            putJsonObject("error") {
                put("code", -32602)
                put("message", "Invalid params.")
            }
        }.toString().encodeToByteArray()

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
