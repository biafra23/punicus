package eth.serotine.data.walletconnect

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * An in-memory relay with the `irn_*` semantics the client relies on: a mailbox per topic, a
 * message delivered to each subscribed client other than its publisher until that client
 * acknowledges it, again on its next subscription when it did not. Clients are told apart by the
 * name their connector carries, so a reconnection is the same client. It can drop every connection
 * ([dropConnections]), refuse new ones ([refusing]) and deliver a message again ([replay]), as a
 * relay may.
 */
internal class FakeRelay {

    private class Stored(val id: Long, val topic: String, val message: String, val tag: Int, val publisher: String)

    private inner class Socket(val client: String) : WcRelaySocket {
        val inbox = Channel<String>(Channel.UNLIMITED)
        val subscriptions = mutableSetOf<String>()

        override suspend fun receive(): String? = inbox.receiveCatching().getOrNull()

        override suspend fun send(text: String) {
            if (inbox.isClosedForSend) throw IllegalStateException("closed")
            handle(this, text)
        }

        override suspend fun close() {
            inbox.close()
            lock.withLock { sockets.remove(this) }
        }
    }

    private val lock = Mutex()
    private val sockets = mutableListOf<Socket>()
    private val stored = mutableListOf<Stored>()
    private val acknowledged = mutableSetOf<Pair<String, Long>>()
    private val delivering = mutableMapOf<Long, Pair<String, Long>>()
    private val inFlight = mutableMapOf<Long, Pair<Socket, Long>>()
    private var nextId = 1L

    /** While true, every connection attempt fails. */
    var refusing = false

    /** Every envelope published, in order: topic, envelope, tag. */
    val published = mutableListOf<Triple<String, String, Int>>()

    /** How many connections have been opened. */
    var connections = 0
        private set

    /** A connector whose connections are the client [name]'s. */
    fun connector(name: String): WcRelayConnector = WcRelayConnector {
        if (refusing) throw IllegalStateException("refused")
        val socket = Socket(name)
        lock.withLock {
            sockets += socket
            connections++
        }
        socket
    }

    /** Closes every open connection, as a relay restart does. */
    suspend fun dropConnections() {
        val open = lock.withLock { sockets.toList().also { sockets.clear() } }
        for (socket in open) socket.inbox.close()
    }

    /** Delivers [message] on [topic] to its subscribers again, as a relay replaying a recording would. */
    suspend fun replay(topic: String, message: String, tag: Int = 1108) {
        lock.withLock {
            val again = Stored(nextId++, topic, message, tag, publisher = "replay")
            stored += again
            for (socket in sockets) if (topic in socket.subscriptions) deliver(socket, again)
        }
    }

    /** Whether the client [name] has acknowledged every message stored for it. */
    suspend fun allAcknowledgedBy(name: String): Boolean = lock.withLock {
        stored.filter { it.publisher != name }
            .filter { s -> sockets.any { it.client == name && s.topic in it.subscriptions } }
            .all { (name to it.id) in acknowledged }
    }

    private suspend fun handle(from: Socket, text: String) {
        val payload = Json.parseToJsonElement(text).jsonObject
        val method = (payload["method"] as? JsonPrimitive)?.contentOrNull
        lock.withLock {
            if (method == null) {
                // An acknowledgement of a delivery.
                val id = payload["id"]?.jsonPrimitive?.long ?: return
                delivering.remove(id)?.let { acknowledged += it }
                inFlight.remove(id)
                return
            }
            val id = payload.getValue("id").jsonPrimitive.long
            val params = payload.getValue("params").jsonObject
            when (method) {
                "irn_subscribe" -> {
                    val topic = params.getValue("topic").jsonPrimitive.content
                    from.subscriptions += topic
                    reply(from, id, JsonPrimitive("sub-$topic"))
                    for (s in stored) if (s.topic == topic && s.publisher != from.client) deliver(from, s)
                }
                "irn_unsubscribe" -> {
                    from.subscriptions -= params.getValue("topic").jsonPrimitive.content
                    reply(from, id, JsonPrimitive(true))
                }
                "irn_publish" -> {
                    val s = Stored(
                        id = nextId++,
                        topic = params.getValue("topic").jsonPrimitive.content,
                        message = params.getValue("message").jsonPrimitive.content,
                        tag = params.getValue("tag").jsonPrimitive.int,
                        publisher = from.client,
                    )
                    stored += s
                    published += Triple(s.topic, s.message, s.tag)
                    reply(from, id, JsonPrimitive(true))
                    for (socket in sockets) {
                        if (socket.client != from.client && s.topic in socket.subscriptions) deliver(socket, s)
                    }
                }
                else -> from.inbox.trySend(
                    buildJsonObject {
                        put("id", id)
                        put("jsonrpc", "2.0")
                        put("error", buildJsonObject { put("code", -32601); put("message", "no such method") })
                    }.toString(),
                )
            }
        }
    }

    private fun reply(to: Socket, id: Long, result: kotlinx.serialization.json.JsonElement) {
        to.inbox.trySend(
            buildJsonObject {
                put("id", id)
                put("jsonrpc", "2.0")
                put("result", result)
            }.toString(),
        )
    }

    /** Sends [s] to [to] unless that client has acknowledged it already. Called under [lock]. */
    private fun deliver(to: Socket, s: Stored) {
        if ((to.client to s.id) in acknowledged) return
        // Not again on the same connection while its acknowledgement is awaited.
        if (inFlight.values.any { (socket, id) -> socket === to && id == s.id }) return
        val deliveryId = 9_000_000L + nextId++
        delivering[deliveryId] = to.client to s.id
        inFlight[deliveryId] = to to s.id
        val request: JsonObject = buildJsonObject {
            put("id", deliveryId)
            put("jsonrpc", "2.0")
            put("method", "irn_subscription")
            put(
                "params",
                buildJsonObject {
                    put("id", "sub-${s.topic}")
                    put(
                        "data",
                        buildJsonObject {
                            put("topic", s.topic)
                            put("message", s.message)
                            put("publishedAt", 1_700_000_000_000L)
                            put("tag", s.tag)
                        },
                    )
                },
            )
        }
        to.inbox.trySend(request.toString())
    }
}
