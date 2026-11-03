package eth.serotine.data.walletconnect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A message the relay delivered on a subscribed topic: an envelope ([WcEnvelope]), still sealed. */
internal data class WcRelayMessage(val topic: String, val message: String, val publishedAtMillis: Long, val tag: Int)

/**
 * The client side of the relay's JSON-RPC (WalletConnect's *Relay* API, the `irn_*` methods), over
 * one connection at a time that [run] keeps open.
 *
 * - **Reconnects** with an exponential backoff ([backoff]) whenever the connection cannot be had or
 *   drops, with a fresh URL each time ([connectionUrl]: a new auth token), and subscribes again to
 *   every topic it holds.
 * - **Every message is handed to [onMessage], then acknowledged** to the relay, so a message whose
 *   handling did not finish (the app killed in between) is delivered again. [onMessage] must not wait
 *   on this client: it runs on the connection's reader, which the answer would have to come through.
 * - **It never deduplicates**: a message delivered again, by the relay or by an attacker on the
 *   relay's side, reaches [onMessage] again. The session layer, which can read the request's id, drops
 *   what it has seen (docs/walletconnect.md, *What the relay learns*).
 * - **A request is sent again** on the next connection when its connection drops before its answer,
 *   until [requestTimeout]: a publish may therefore reach the relay twice, which the receiver's own
 *   record of ids makes harmless.
 *
 * It trusts the relay for nothing but delivery: what it hands on is sealed, and opened by the session
 * layer. Never throws, but for cancellation.
 */
internal class WcRelayClient(
    private val connector: WcRelayConnector,
    private val connectionUrl: () -> String,
    private val onMessage: suspend (WcRelayMessage) -> Unit,
    private val nowMillis: () -> Long,
    private val requestTimeout: Duration = 20.seconds,
    private val backoff: (attempt: Int) -> Duration = ::defaultBackoff,
) {
    /** Whether a connection is open. */
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    private val mutableState = MutableStateFlow(State.DISCONNECTED)

    /** The connection's state, for the screens to say whether dApp requests can arrive. */
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val socket = MutableStateFlow<WcRelaySocket?>(null)
    private val lock = Mutex()
    private val pending = HashMap<Long, CompletableDeferred<Answer>>()

    /** The topics to be subscribed to, and the relay's subscription id of each, once it has given one. */
    private val topics = LinkedHashMap<String, String?>()
    private var counter = 0L

    private sealed interface Answer {
        class Result(val value: JsonElement) : Answer
        class Error(val code: Long?, val message: String?) : Answer
        data object Disconnected : Answer
    }

    /** Keeps a connection open until cancelled. Call it once, from the scope that owns the relay connection. */
    suspend fun run(): Nothing {
        var attempt = 0
        while (true) {
            mutableState.value = State.CONNECTING
            val connected = try {
                connector.connect(connectionUrl())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Serotine: the WalletConnect relay cannot be reached (${e::class.simpleName})")
                null
            }
            if (connected == null) {
                mutableState.value = State.DISCONNECTED
                delay(backoff(attempt++))
                continue
            }
            attempt = 0
            try {
                coroutineScope {
                    val reader = launch { read(connected) }
                    socket.value = connected
                    mutableState.value = State.CONNECTED
                    val resubscribing = launch { resubscribe() }
                    reader.join()
                    resubscribing.cancel()
                }
            } finally {
                socket.value = null
                mutableState.value = State.DISCONNECTED
                withContext(NonCancellable) {
                    failPending()
                    connected.close()
                }
            }
            delay(backoff(attempt++))
        }
    }

    /**
     * Subscribes to [topic], and again after every reconnection, until [unsubscribe]. True once the
     * relay acknowledged it; false when it did not within [requestTimeout] (the topic is still
     * subscribed to on the next connection).
     */
    suspend fun subscribe(topic: String): Boolean {
        lock.withLock { if (topic !in topics) topics[topic] = null }
        return subscribeNow(topic)
    }

    /** Stops following [topic]: no longer subscribed to on a reconnection, and unsubscribed now where the relay gave an id. */
    suspend fun unsubscribe(topic: String) {
        val id = lock.withLock { if (topic in topics) topics.remove(topic) else return }
        if (id == null || socket.value == null) return
        call(
            "irn_unsubscribe",
            buildJsonObject {
                put("topic", topic)
                put("id", id)
            },
        )
    }

    /**
     * Publishes the envelope [message] on [topic], for the relay to hold [ttlSeconds] for its
     * subscribers, under the Sign protocol's [tag]. True once the relay acknowledged it.
     */
    suspend fun publish(topic: String, message: String, ttlSeconds: Long, tag: Int): Boolean {
        val answer = call(
            "irn_publish",
            buildJsonObject {
                put("topic", topic)
                put("message", message)
                put("ttl", ttlSeconds)
                put("tag", tag)
            },
        )
        return answer is Answer.Result
    }

    private suspend fun subscribeNow(topic: String): Boolean {
        val answer = call("irn_subscribe", buildJsonObject { put("topic", topic) })
        if (answer !is Answer.Result) return false
        val id = (answer.value as? JsonPrimitive)?.contentOrNull
        lock.withLock { if (topic in topics) topics[topic] = id }
        return true
    }

    private suspend fun resubscribe() {
        for (topic in lock.withLock { topics.keys.toList() }) subscribeNow(topic)
    }

    /** [method] with [params] on the open connection, waiting for one when there is none; null past [requestTimeout]. */
    private suspend fun call(method: String, params: JsonObject): Answer? =
        withTimeoutOrNull(requestTimeout) { callUntilAnswered(method, params) }

    private suspend fun callUntilAnswered(method: String, params: JsonObject): Answer {
        while (true) {
            val current = socket.filterNotNull().first()
            val answer = CompletableDeferred<Answer>()
            val id = lock.withLock { nextId().also { pending[it] = answer } }
            val request = buildJsonObject {
                put("id", id)
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }
            val sent = try {
                current.send(request.toString())
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!sent) {
                lock.withLock { pending.remove(id) }
                // Wait for this connection to be replaced rather than send into it again.
                socket.first { it !== current }
                continue
            }
            val result = try {
                answer.await()
            } finally {
                lock.withLock { pending.remove(id) }
            }
            if (result !is Answer.Disconnected) return result
        }
    }

    private suspend fun read(connection: WcRelaySocket) {
        while (true) {
            val text = connection.receive() ?: return
            val payload = try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: IllegalArgumentException) {
                continue // not JSON-RPC: nothing the relay should send
            }
            val method = (payload["method"] as? JsonPrimitive)?.contentOrNull
            if (method == "irn_subscription") {
                val message = subscriptionMessage(payload["params"]) ?: continue
                try {
                    onMessage(message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Not acknowledged: the relay delivers it again on the next connection.
                    println("Serotine: a WalletConnect message could not be handled (${e::class.simpleName})")
                    continue
                }
                acknowledge(connection, payload["id"] ?: continue)
            } else if (method == null) {
                answer(payload)
            }
            // Any other request from the relay is none this client serves.
        }
    }

    private fun subscriptionMessage(params: JsonElement?): WcRelayMessage? {
        try {
            val data = params?.jsonObject?.get("data")?.jsonObject ?: return null
            return WcRelayMessage(
                topic = data["topic"]?.jsonPrimitive?.contentOrNull ?: return null,
                message = data["message"]?.jsonPrimitive?.contentOrNull ?: return null,
                publishedAtMillis = data["publishedAt"]?.jsonPrimitive?.longOrNull ?: nowMillis(),
                tag = data["tag"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
            )
        } catch (e: IllegalArgumentException) {
            return null
        }
    }

    private suspend fun acknowledge(connection: WcRelaySocket, id: JsonElement) {
        val ack = buildJsonObject {
            put("id", id)
            put("jsonrpc", "2.0")
            put("result", true)
        }
        try {
            connection.send(ack.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The connection broke: the relay delivers the message again on the next one.
        }
    }

    private suspend fun answer(payload: JsonObject) {
        val id = (payload["id"] as? JsonPrimitive)?.longOrNull ?: return
        val waiting = lock.withLock { pending[id] } ?: return
        val error = payload["error"]
        val answer = when {
            error != null && error !is JsonNull -> {
                val fields = error as? JsonObject
                Answer.Error(
                    code = (fields?.get("code") as? JsonPrimitive)?.longOrNull,
                    message = (fields?.get("message") as? JsonPrimitive)?.contentOrNull,
                )
            }
            "result" in payload -> Answer.Result(payload.getValue("result"))
            else -> return
        }
        // A relay answering false to a publish or a subscribe has not taken it.
        if (answer is Answer.Result && (answer.value as? JsonPrimitive)?.booleanOrNull == false) {
            waiting.complete(Answer.Error(code = null, message = "false"))
        } else {
            waiting.complete(answer)
        }
    }

    private suspend fun failPending() {
        lock.withLock {
            for (waiting in pending.values) waiting.complete(Answer.Disconnected)
            pending.clear()
        }
    }

    /** A JSON-RPC id: the time in milliseconds, then three digits of a counter, as the reference clients make them. */
    private fun nextId(): Long = nowMillis() * 1000 + (counter++ % 1000)

    companion object {
        /** 1 s, doubling to at most a minute, each within ±20 % so that many clients do not retry at once. */
        fun defaultBackoff(attempt: Int): Duration {
            val base = 1_000L shl minOf(attempt, 6)
            val capped = minOf(base, 60_000L)
            return (capped * Random.nextDouble(0.8, 1.2)).toLong().milliseconds
        }
    }
}
