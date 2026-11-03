package eth.serotine.data.walletconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration.Companion.milliseconds

/**
 * A dApp's side of the Sign protocol, scripted step by step, as the reference clients speak it: it
 * makes a pairing, proposes a session, derives the session's key from the wallet's answer, and sends
 * requests. Everything it receives is opened and kept per topic ([received]).
 */
internal class FakeDapp(relay: FakeRelay, scope: CoroutineScope, name: String = "dapp", nowMillis: () -> Long) {

    val pairingKey: ByteArray = WcKeys.newSymKey()
    val pairingTopic: String = WcKeys.topicOf(pairingKey)
    private val privateKey = WcKeys.newPrivateKey()
    val publicKey: ByteArray = WcCrypto.x25519PublicKey(privateKey)

    var sessionKey: ByteArray? = null
        private set
    var sessionTopic: String? = null
        private set

    /** Every message received, opened, by topic. */
    val received = mutableMapOf<String, MutableList<JsonObject>>()

    /** Every envelope this dApp published, by topic, to replay. */
    val sent = mutableMapOf<String, MutableList<String>>()

    val client = WcRelayClient(
        connector = relay.connector(name),
        connectionUrl = { "wss://relay.test?auth=dapp" },
        onMessage = { message ->
            val key = if (message.topic == pairingTopic) pairingKey else sessionKey
            val parsed = WcEnvelope.parse(message.message)
            if (key != null && parsed != null) {
                WcEnvelope.open(key, parsed)?.let { plain ->
                    received.getOrPut(message.topic) { mutableListOf() } +=
                        Json.parseToJsonElement(plain.decodeToString()).jsonObject
                }
            }
        },
        nowMillis = nowMillis,
        backoff = { 10.milliseconds },
    )

    init {
        scope.launch { client.run() }
    }

    fun uri(extra: String = ""): String = "wc:$pairingTopic@2?relay-protocol=irn&symKey=${WcEncoding.hex(pairingKey)}$extra"

    suspend fun subscribePairing() = client.subscribe(pairingTopic)

    /** Proposes a session as request [id], with [required] and [optional] namespaces. */
    suspend fun propose(id: Long, required: JsonObject, optional: JsonObject, extra: JsonObject.() -> JsonObject = { this }) {
        val params = buildJsonObject {
            putJsonArray("relays") { add(buildJsonObject { put("protocol", "irn") }) }
            putJsonObject("proposer") {
                put("publicKey", WcEncoding.hex(publicKey))
                putJsonObject("metadata") {
                    put("name", "Test dApp")
                    put("description", "A dApp of the tests")
                    put("url", "https://dapp.test")
                    putJsonArray("icons") { }
                }
            }
            put("requiredNamespaces", required)
            put("optionalNamespaces", optional)
            put("pairingTopic", pairingTopic)
        }.extra()
        publish(pairingTopic, pairingKey, request(id, "wc_sessionPropose", params), 1100)
    }

    /** Takes the session from the wallet's answer to proposal [proposalId], and subscribes to its topic. */
    suspend fun followSession(proposalId: Long): Boolean {
        val answer = received[pairingTopic].orEmpty().lastOrNull { it["id"]?.jsonPrimitive?.content == "$proposalId" }
            ?: return false
        val responder = answer["result"]?.jsonObject?.get("responderPublicKey")?.jsonPrimitive?.content ?: return false
        val key = checkNotNull(WcKeys.symKey(privateKey, checkNotNull(WcEncoding.hexOrNull(responder, 32))))
        sessionKey = key
        sessionTopic = WcKeys.topicOf(key)
        return client.subscribe(checkNotNull(sessionTopic))
    }

    /** Sends [payload] on the session. */
    suspend fun sendOnSession(payload: JsonObject, tag: Int = 1108) =
        publish(checkNotNull(sessionTopic), checkNotNull(sessionKey), payload, tag)

    /** Answers the wallet's request [id] on the session with [result]. */
    suspend fun answerOnSession(id: JsonElement, result: JsonElement) = sendOnSession(
        buildJsonObject {
            put("id", id)
            put("jsonrpc", "2.0")
            put("result", result)
        },
        tag = 1103,
    )

    private suspend fun publish(topic: String, key: ByteArray, payload: JsonObject, tag: Int) {
        val envelope = WcEnvelope.sealType0(key, payload.toString().encodeToByteArray())
        sent.getOrPut(topic) { mutableListOf() } += envelope
        check(client.publish(topic, envelope, 300, tag))
    }

    companion object {
        fun request(id: Long, method: String, params: JsonElement) = buildJsonObject {
            put("id", id)
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }

        fun namespace(chains: List<String>, methods: List<String>, events: List<String>) = buildJsonObject {
            putJsonArray("chains") { chains.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("methods") { methods.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("events") { events.forEach { add(JsonPrimitive(it)) } }
        }

        fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
    }
}
