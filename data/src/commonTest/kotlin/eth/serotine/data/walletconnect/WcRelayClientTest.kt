@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eth.serotine.data.walletconnect

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WcRelayClientTest {

    private val relay = FakeRelay()

    private class Client(val client: WcRelayClient, val received: MutableList<WcRelayMessage>)

    private fun TestScope.client(
        name: String,
        onMessage: suspend (WcRelayMessage) -> Unit = {},
    ): Client {
        val received = mutableListOf<WcRelayMessage>()
        val client = WcRelayClient(
            connector = relay.connector(name),
            connectionUrl = { "wss://relay.test?auth=token" },
            onMessage = { onMessage(it); received += it },
            nowMillis = { testScheduler.currentTime },
            requestTimeout = 5.seconds,
            backoff = { 10.milliseconds },
        )
        backgroundScope.launch { client.run() }
        return Client(client, received)
    }

    /**
     * Runs a second of virtual time, background work included. Not advanceUntilIdle, which stops once
     * no foreground work is left and so never runs the clients' readers, and would never stop while a
     * client retries every 10 ms.
     */
    private fun TestScope.settle() {
        advanceTimeBy(1.seconds)
        runCurrent()
    }

    @Test
    fun aMessagePublishedOnATopicReachesItsSubscriberAndIsAcknowledged() = runTest {
        val wallet = client("wallet")
        val dapp = client("dapp")
        assertTrue(wallet.client.subscribe("t1"))
        assertTrue(dapp.client.publish("t1", "envelope", ttlSeconds = 300, tag = 1108))
        settle()
        assertEquals(listOf("envelope"), wallet.received.map { it.message })
        assertEquals(1108, wallet.received.single().tag)
        assertTrue(relay.allAcknowledgedBy("wallet"))
        assertEquals(WcRelayClient.State.CONNECTED, wallet.client.state.value)
    }

    @Test
    fun aMessageWhoseHandlingFailedIsNotAcknowledgedAndComesAgain() = runTest {
        var fail = true
        val wallet = client("wallet") { if (fail) error("crashed") }
        val dapp = client("dapp")
        wallet.client.subscribe("t1")
        dapp.client.publish("t1", "envelope", 300, 1108)
        settle()
        assertTrue(wallet.received.isEmpty())
        assertFalse(relay.allAcknowledgedBy("wallet"))
        fail = false
        relay.dropConnections()
        settle()
        assertEquals(listOf("envelope"), wallet.received.map { it.message })
        assertTrue(relay.allAcknowledgedBy("wallet"))
    }

    @Test
    fun afterAReconnectionTheTopicsAreSubscribedAgainAndTheMailboxDelivered() = runTest {
        val wallet = client("wallet")
        val dapp = client("dapp")
        wallet.client.subscribe("t1")
        settle()
        relay.refusing = true
        relay.dropConnections()
        settle()
        assertTrue(wallet.client.state.value != WcRelayClient.State.CONNECTED)
        relay.refusing = false
        // The dApp's own client reconnects too, publishes, and the wallet gets it once it is back.
        dapp.client.publish("t1", "while away", 300, 1108)
        settle()
        assertEquals(listOf("while away"), wallet.received.map { it.message })
    }

    @Test
    fun aPublishWaitsForAConnection() = runTest {
        relay.refusing = true
        val dapp = client("dapp")
        val wallet = client("wallet")
        backgroundScope.launch { wallet.client.subscribe("t1") }
        launch {
            delay(1.seconds)
            relay.refusing = false
        }
        assertTrue(dapp.client.publish("t1", "late", 300, 1108))
        settle()
        assertEquals(listOf("late"), wallet.received.map { it.message })
    }

    @Test
    fun aPublishThatNeverReachesARelayFailsAfterTheTimeout() = runTest {
        relay.refusing = true
        val dapp = client("dapp")
        assertFalse(dapp.client.publish("t1", "lost", 300, 1108))
    }

    @Test
    fun theClientDoesNotDeduplicateAReplayTheSessionLayerDoes() = runTest {
        val wallet = client("wallet")
        val dapp = client("dapp")
        wallet.client.subscribe("t1")
        dapp.client.publish("t1", "once", 300, 1108)
        settle()
        relay.replay("t1", "once")
        settle()
        assertEquals(listOf("once", "once"), wallet.received.map { it.message })
    }

    @Test
    fun anUnsubscribedTopicIsNotSubscribedAgain() = runTest {
        val wallet = client("wallet")
        val dapp = client("dapp")
        wallet.client.subscribe("t1")
        settle()
        wallet.client.unsubscribe("t1")
        relay.dropConnections()
        settle()
        wallet.client.state.first { it == WcRelayClient.State.CONNECTED }
        dapp.client.publish("t1", "gone", 300, 1108)
        settle()
        assertTrue(wallet.received.isEmpty())
    }

    @Test
    fun theBackoffDoublesToAMinuteWithJitter() {
        val first = WcRelayClient.defaultBackoff(0)
        assertTrue(first in 800.milliseconds..1200.milliseconds)
        val late = WcRelayClient.defaultBackoff(30)
        assertTrue(late in 48.seconds..72.seconds)
    }
}
