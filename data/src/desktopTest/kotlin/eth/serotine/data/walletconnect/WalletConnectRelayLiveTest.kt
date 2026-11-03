package eth.serotine.data.walletconnect

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * WalletConnect's real relay, through Ktor's CIO engine: two clients, each with its own auth key and
 * token, connect with the project ID, one subscribes to a fresh topic, the other publishes an envelope
 * on it, and it arrives. It needs the network and a Reown project ID (docs/walletconnect.md, *Setting
 * up the project ID*), so it is skipped unless run with `-PwalletConnectProjectId=<id>`
 * (data/build.gradle.kts), e.g.
 * `./gradlew :data:desktopTest --tests '*WalletConnectRelayLiveTest*' -PwalletConnectProjectId=<id>`.
 * What it catches: a relay that no longer takes this client's token or its JSON-RPC (part 1.1's spike).
 */
class WalletConnectRelayLiveTest {

    @Test
    fun anEnvelopePublishedOnTheRealRelayReachesItsSubscriber(): Unit = runBlocking {
        val projectId = System.getProperty("serotine.walletConnectProjectId")
        assumeTrue("needs -PwalletConnectProjectId", !projectId.isNullOrEmpty())
        val connector = KtorRelayConnector(walletConnectHttpEngine())
        fun url(seed: ByteArray): String {
            val token = WcRelayAuth.token(
                seed,
                WcRelayAuth.DEFAULT_AUDIENCE,
                WcEncoding.hex(WcKeys.newSeed()),
                System.currentTimeMillis() / 1000,
            )
            return "${WcRelayAuth.DEFAULT_AUDIENCE}?auth=$token&projectId=$projectId"
        }
        val arrived = CompletableDeferred<WcRelayMessage>()
        val subscriberSeed = WcKeys.newSeed()
        val publisherSeed = WcKeys.newSeed()
        val subscriber = WcRelayClient(connector, { url(subscriberSeed) }, { arrived.complete(it) }, System::currentTimeMillis)
        val publisher = WcRelayClient(connector, { url(publisherSeed) }, {}, System::currentTimeMillis)
        val running = listOf(launch { subscriber.run() }, launch { publisher.run() })
        try {
            val key = WcKeys.newSymKey()
            val topic = WcKeys.topicOf(key)
            val envelope = WcEnvelope.sealType0(key, """{"id":1,"jsonrpc":"2.0","method":"wc_sessionPing","params":{}}""".encodeToByteArray())
            withTimeout(60.seconds) {
                assertTrue(subscriber.subscribe(topic), "the relay took the subscription")
                assertTrue(publisher.publish(topic, envelope, ttlSeconds = 300, tag = 1114), "the relay took the publish")
                val message = arrived.await()
                assertEquals(topic, message.topic)
                assertEquals(envelope, message.message)
            }
        } finally {
            running.forEach { it.cancelAndJoin() }
        }
    }
}
