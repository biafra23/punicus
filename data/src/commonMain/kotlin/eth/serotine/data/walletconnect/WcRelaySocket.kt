package eth.serotine.data.walletconnect

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.ClosedReceiveChannelException

/** One open text connection to the relay, as [WcRelayClient] uses it. */
internal interface WcRelaySocket {

    /** The next text frame, or null once the connection is closed. Throws only for cancellation. */
    suspend fun receive(): String?

    /** Sends [text] as one frame. Throws when the connection is closed or breaks. */
    suspend fun send(text: String)

    /** Closes the connection; [receive] answers null afterwards. Never throws. */
    suspend fun close()
}

/** Opens a [WcRelaySocket] to a URL: Ktor's WebSocket client in the app ([KtorRelayConnector]), a fake relay in tests. */
internal fun interface WcRelayConnector {
    /** A connection to [url], or throws when it cannot be had. */
    suspend fun connect(url: String): WcRelaySocket
}

/**
 * [WcRelayConnector] over Ktor's WebSocket client, on the platform's [engine] ([walletConnectHttpEngine]):
 * CIO on Android and the desktop, NSURLSession on iOS. It pings every [PING_INTERVAL_MILLIS], so an
 * idle connection stays open and a dead one is noticed, and follows no redirect.
 */
internal class KtorRelayConnector(engine: HttpClientEngine) : WcRelayConnector {

    private val client = HttpClient(engine) {
        followRedirects = false
        install(WebSockets) {
            pingIntervalMillis = PING_INTERVAL_MILLIS
            maxFrameSize = MAX_FRAME_BYTES
        }
    }

    override suspend fun connect(url: String): WcRelaySocket = KtorRelaySocket(client.webSocketSession(url))

    private class KtorRelaySocket(private val session: DefaultClientWebSocketSession) : WcRelaySocket {

        override suspend fun receive(): String? {
            while (true) {
                val frame = try {
                    session.incoming.receive()
                } catch (e: ClosedReceiveChannelException) {
                    return null
                } catch (e: Throwable) {
                    // A connection that breaks fails its channel, with a cancellation of its own among
                    // the causes: only this coroutine's cancellation goes on up.
                    currentCoroutineContext().ensureActive()
                    return null
                }
                if (frame is Frame.Text) return frame.readText()
                // Binary frames are not part of the relay's protocol; pings and pongs Ktor answers itself.
            }
        }

        override suspend fun send(text: String) = session.send(Frame.Text(text))

        override suspend fun close() {
            try {
                session.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // already closed
            }
        }
    }

    companion object {
        const val PING_INTERVAL_MILLIS = 30_000L

        /** A frame larger than any envelope the client reads ([WcEnvelope.MAX_MESSAGE_CHARS]) with its JSON around it. */
        const val MAX_FRAME_BYTES = 1024L * 1024
    }
}

/** The platform's HTTP engine for the relay's WebSocket: CIO on Android and the desktop, Darwin on iOS. */
internal expect fun walletConnectHttpEngine(): HttpClientEngine
