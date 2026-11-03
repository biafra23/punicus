@file:OptIn(ExperimentalCoroutinesApi::class)

package eth.serotine.data.walletconnect

import eth.serotine.data.walletconnect.FakeDapp.Companion.namespace
import eth.serotine.data.walletconnect.FakeDapp.Companion.request
import eth.serotine.data.walletconnect.FakeDapp.Companion.strings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The wallet role end to end: [WalletConnect] and a scripted dApp ([FakeDapp]) over [FakeRelay]. */
class WcWalletTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-wc-${Random.nextLong().toULong()}")
    private val file = Path(directory, "walletconnect.json")
    private val relay = FakeRelay()
    private val account = "0x00000000000000000000000000000000000000a1"
    private val sepoliaAsk = namespace(
        chains = listOf("eip155:1", WcProtocol.SEPOLIA),
        methods = listOf("eth_sendTransaction", "personal_sign", "eth_sign", "eth_signTransaction"),
        events = listOf("accountsChanged", "chainChanged", "message"),
    )

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    private fun TestScope.now(): Long = 1_700_000_000_000L + testScheduler.currentTime

    private class Running(val walletConnect: WalletConnect, val job: Job) {
        val wallet get() = walletConnect.wallet
    }

    /** While true, every write of the state fails, as on a full disk. */
    private var writesFail = false

    private fun TestScope.walletConnect(): Running {
        val store = WcStore(file.toString(), StandardTestDispatcher(testScheduler)) { path, bytes ->
            if (writesFail) throw kotlinx.io.IOException("disk full")
            WcStore.writePrivately(path, bytes)
        }
        val walletConnect = WalletConnect(
            store = store,
            connector = relay.connector("wallet"),
            projectId = "0123456789abcdef0123456789abcdef",
            metadata = WcMetadata(name = "Serotine", url = "https://github.com/biafra23/serotine"),
            nowMillis = { now() },
        )
        val job = backgroundScope.launch { walletConnect.run() }
        return Running(walletConnect, job)
    }

    private fun TestScope.dapp(name: String = "dapp") = FakeDapp(relay, backgroundScope, name) { now() }

    /** A second of virtual time, background work included (see WcRelayClientTest). */
    private fun TestScope.settle() {
        advanceTimeBy(1.seconds)
        runCurrent()
    }

    private suspend fun Running.view() = wallet.observe().first()

    /** Pairs, proposes, approves and acknowledges: a settled session, as most tests need one. */
    private suspend fun TestScope.settled(running: Running, dapp: FakeDapp): String {
        dapp.subscribePairing()
        assertIs<WcWallet.Pairing.Paired>(running.wallet.pair(dapp.uri()))
        dapp.propose(1, JsonObject(emptyMap()), buildJsonObject { put("eip155", sepoliaAsk) })
        settle()
        val approval = assertIs<WcWallet.Approval.Approved>(running.wallet.approve(dapp.pairingTopic, 1, listOf(account)))
        assertTrue(approval.delivered)
        settle()
        assertTrue(dapp.followSession(1))
        settle()
        val settle = dapp.received.getValue(approval.topic).single { it["method"]?.jsonPrimitive?.content == "wc_sessionSettle" }
        dapp.answerOnSession(settle.getValue("id"), JsonPrimitive(true))
        settle()
        return approval.topic
    }

    @Test
    fun aProposalIsApprovedWithSepoliaAndTheClosedListAloneAndSettled() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        dapp.subscribePairing()
        assertIs<WcWallet.Pairing.Paired>(running.wallet.pair(dapp.uri()))
        dapp.propose(1, JsonObject(emptyMap()), buildJsonObject { put("eip155", sepoliaAsk) })
        settle()

        val proposal = running.view().proposals.single()
        assertNull(proposal.refusal)
        assertEquals("Test dApp", proposal.dapp.name)
        assertEquals(setOf("eth_sendTransaction", "personal_sign"), proposal.methods) // never eth_sign
        assertEquals(setOf("accountsChanged", "chainChanged"), proposal.events)

        val approval = assertIs<WcWallet.Approval.Approved>(running.wallet.approve(dapp.pairingTopic, 1, listOf(account)))
        settle()
        // The dApp derives the same session key and topic from the wallet's answer.
        assertTrue(dapp.followSession(1))
        assertEquals(approval.topic, dapp.sessionTopic)
        settle()
        val settleRequest = dapp.received.getValue(approval.topic).single()
        assertEquals("wc_sessionSettle", settleRequest["method"]?.jsonPrimitive?.content)
        val namespaces = settleRequest.getValue("params").jsonObject.getValue("namespaces").jsonObject
        val eip155 = namespaces.getValue("eip155").jsonObject
        assertEquals(listOf(WcProtocol.SEPOLIA), eip155["chains"].strings())
        assertEquals(listOf("${WcProtocol.SEPOLIA}:$account"), eip155["accounts"].strings())
        assertEquals(listOf("eth_sendTransaction", "personal_sign"), eip155["methods"].strings())

        assertFalse(running.view().sessions.single().acknowledged)
        dapp.answerOnSession(settleRequest.getValue("id"), JsonPrimitive(true))
        settle()
        assertTrue(running.view().sessions.single().acknowledged)
        assertTrue(running.view().proposals.isEmpty())
    }

    @Test
    fun aProposalThatRequiresAnotherChainCanOnlyBeRejected() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        dapp.subscribePairing()
        running.wallet.pair(dapp.uri())
        dapp.propose(7, buildJsonObject { put("eip155", namespace(listOf("eip155:1"), listOf("personal_sign"), emptyList())) }, JsonObject(emptyMap()))
        settle()
        assertEquals(WcProtocol.Error.UNSUPPORTED_CHAINS, running.view().proposals.single().refusal)
        assertEquals(WcWallet.Approval.Refused(WcProtocol.Error.UNSUPPORTED_CHAINS), running.wallet.approve(dapp.pairingTopic, 7, listOf(account)))
        assertTrue(running.wallet.reject(dapp.pairingTopic, 7, WcProtocol.Error.UNSUPPORTED_CHAINS))
        settle()
        val answer = dapp.received.getValue(dapp.pairingTopic).single()
        assertEquals(5100, answer.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        assertTrue(running.view().proposals.isEmpty())
    }

    @Test
    fun aProposalThatRequiresEthSignCanOnlyBeRejected() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        dapp.subscribePairing()
        running.wallet.pair(dapp.uri())
        dapp.propose(8, buildJsonObject { put("eip155", namespace(listOf(WcProtocol.SEPOLIA), listOf("eth_sign"), emptyList())) }, JsonObject(emptyMap()))
        settle()
        assertEquals(WcProtocol.Error.UNSUPPORTED_METHODS, running.view().proposals.single().refusal)
    }

    @Test
    fun aRequestIsKeptUntilAnsweredAndItsReplayIsDropped() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        val topic = settled(running, dapp)
        val sign = request(
            42,
            "wc_sessionRequest",
            buildJsonObject {
                putJsonObject("request") {
                    put("method", "personal_sign")
                    putJsonArray("params") {
                        add(JsonPrimitive("0x48656c6c6f"))
                        add(JsonPrimitive(account))
                    }
                }
                put("chainId", WcProtocol.SEPOLIA)
            },
        )
        dapp.sendOnSession(sign)
        settle()
        val request = running.view().requests.single()
        assertEquals("personal_sign", request.method)
        assertEquals("Test dApp", request.dapp.name)
        assertEquals(WcWallet.Answer.DELIVERED, running.wallet.respond(topic, 42, JsonPrimitive("0xsignature")))
        settle()
        assertEquals("0xsignature", dapp.received.getValue(topic).last()["result"]?.jsonPrimitive?.content)

        // The relay delivers the same envelope again: it is dropped, never shown a second time.
        relay.replay(topic, dapp.sent.getValue(topic).last { true })
        settle()
        assertTrue(running.view().requests.isEmpty())
    }

    /** The wallet's answers the dApp received on [topic] (a replay reaches the dApp's own client too). */
    private fun answersTo(dapp: FakeDapp, topic: String) = dapp.received.getValue(topic).count { it["method"] == null }

    @Test
    fun aReplayIsDroppedAfterARestartToo() = runTest {
        val first = walletConnect()
        val dapp = dapp()
        val topic = settled(first, dapp)
        val ping = request(5, "wc_sessionPing", JsonObject(emptyMap()))
        dapp.sendOnSession(ping, tag = 1114)
        settle()
        val answers = answersTo(dapp, topic)
        assertEquals(1, answers)
        first.job.cancel()
        settle()

        // The same file, a new process: the session is still there, and the ping's id remembered.
        val second = walletConnect()
        settle()
        assertEquals(topic, second.view().sessions.single().topic)
        relay.replay(topic, dapp.sent.getValue(topic).last())
        settle()
        assertEquals(answers, answersTo(dapp, topic))
    }

    @Test
    fun anotherChainOrAMethodOffTheListIsAnsweredWithAnErrorAndNeverShown() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        val topic = settled(running, dapp)
        fun sessionRequest(id: Long, method: String, chain: String) = request(
            id,
            "wc_sessionRequest",
            buildJsonObject {
                putJsonObject("request") {
                    put("method", method)
                    putJsonArray("params") { }
                }
                put("chainId", chain)
            },
        )
        dapp.sendOnSession(sessionRequest(10, "eth_sign", WcProtocol.SEPOLIA))
        dapp.sendOnSession(sessionRequest(11, "personal_sign", "eip155:1"))
        settle()
        assertTrue(running.view().requests.isEmpty())
        val codes = dapp.received.getValue(topic)
            .filter { it["id"]?.jsonPrimitive?.content in setOf("10", "11") }
            .associate { it.getValue("id").jsonPrimitive.content to it.getValue("error").jsonObject.getValue("code").jsonPrimitive.int }
        assertEquals(mapOf("10" to 4200, "11" to 5100), codes)
    }

    @Test
    fun aPingIsAnsweredAndTheDappsDeleteEndsTheSession() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        val topic = settled(running, dapp)
        dapp.sendOnSession(request(20, "wc_sessionPing", JsonObject(emptyMap())), tag = 1114)
        settle()
        assertEquals(true, dapp.received.getValue(topic).last()["result"]?.jsonPrimitive?.content?.toBoolean())
        dapp.sendOnSession(
            request(21, "wc_sessionDelete", buildJsonObject { put("code", 6000); put("message", "bye") }),
            tag = 1112,
        )
        settle()
        assertTrue(running.view().sessions.isEmpty())
    }

    @Test
    fun disconnectingTellsTheDapp() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        val topic = settled(running, dapp)
        assertTrue(running.wallet.disconnect(topic))
        settle()
        assertEquals("wc_sessionDelete", dapp.received.getValue(topic).last()["method"]?.jsonPrimitive?.content)
        assertTrue(running.view().sessions.isEmpty())
    }

    @Test
    fun theDappMayNotUpdateOrExtendTheSession() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        val topic = settled(running, dapp)
        dapp.sendOnSession(request(30, "wc_sessionExtend", buildJsonObject { put("expiry", 4_000_000_000L) }), tag = 1106)
        settle()
        assertEquals(3004, dapp.received.getValue(topic).last().getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
    }

    @Test
    fun anExpiredUriOrOneWithoutProposalsIsNotPaired() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        assertEquals(WcWallet.Pairing.Expired, running.wallet.pair(dapp.uri("&expiryTimestamp=1600000000")))
        assertEquals(WcWallet.Pairing.NoProposal, running.wallet.pair(dapp.uri("&methods=%5Bwc_authRequest%5D")))
        assertIs<WcWallet.Pairing.Unreadable>(running.wallet.pair("wc:nonsense"))
    }

    @Test
    fun theStoredStateNamesNoKeyInToStringAndTheFileIsWrittenPrivately() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        settled(running, dapp)
        val text = SystemFileSystem.source(file).buffered().use { it.readString() }
        val pairingKey = WcEncoding.hex(dapp.pairingKey)
        assertTrue(pairingKey in text) // the file holds the keys (ruling 8)...
        val state = WcState(pairings = listOf(WcStoredPairing("t", pairingKey, 0)))
        assertFalse(pairingKey in state.toString()) // ...and no toString shows one
        assertFalse(pairingKey in state.pairings.single().toString())
    }

    private fun personalSign(id: Long, expiry: Long? = null) = request(
        id,
        "wc_sessionRequest",
        buildJsonObject {
            putJsonObject("request") {
                put("method", "personal_sign")
                putJsonArray("params") { add(JsonPrimitive("0x00")) }
                if (expiry != null) put("expiryTimestamp", expiry)
            }
            put("chainId", WcProtocol.SEPOLIA)
        },
    )

    @Test
    fun theSameRequestIdOnTwoSessionsIsAnsweredOnTheRightOne() = runTest {
        val running = walletConnect()
        val first = dapp("dapp-1")
        val second = dapp("dapp-2")
        val firstTopic = settled(running, first)
        val secondTopic = settled(running, second)
        first.sendOnSession(personalSign(99))
        second.sendOnSession(personalSign(99))
        settle()
        assertEquals(2, running.view().requests.size)
        assertEquals(WcWallet.Answer.DELIVERED, running.wallet.respond(secondTopic, 99, JsonPrimitive("0xsecond")))
        settle()
        assertEquals("0xsecond", second.received.getValue(secondTopic).last()["result"]?.jsonPrimitive?.content)
        assertTrue(first.received.getValue(firstTopic).none { it["result"]?.jsonPrimitive?.content == "0xsecond" })
        assertEquals(listOf(firstTopic), running.view().requests.map { it.topic })
    }

    @Test
    fun aRequestThatExpiresUnansweredIsAnsweredWithRequestExpired() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        val topic = settled(running, dapp)
        dapp.sendOnSession(personalSign(77, expiry = now() / 1000 + 10))
        settle()
        assertEquals(1, running.view().requests.size)
        repeat(70) { settle() } // past the expiry, and past the next minute's sweep
        val answer = dapp.received.getValue(topic).last()
        assertEquals(8000, answer.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        assertTrue(running.view().requests.isEmpty())
    }

    @Test
    fun nothingGoesOutForAnAnswerThatCouldNotBeSaved() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        val topic = settled(running, dapp)
        dapp.sendOnSession(personalSign(55))
        settle()
        val before = dapp.received.getValue(topic).size
        writesFail = true
        assertEquals(WcWallet.Answer.NOT_SAVED, running.wallet.respond(topic, 55, JsonPrimitive("0xsig")))
        assertFalse(running.wallet.disconnect(topic))
        settle()
        assertEquals(before, dapp.received.getValue(topic).size)
        writesFail = false
        assertEquals(1, running.view().requests.size)
        assertEquals(1, running.view().sessions.size)
    }

    @Test
    fun anAnswerTheRelayCouldNotTakeIsSentOnceItIsBackEvenAfterARestart() = runTest {
        val first = walletConnect()
        val dapp = dapp()
        val topic = settled(first, dapp)
        dapp.sendOnSession(personalSign(66))
        settle()
        relay.refusing = true
        relay.dropConnections()
        // Saved and taken off the list, but not delivered: kept in the outbox, never lost.
        assertEquals(WcWallet.Answer.NOT_DELIVERED, first.wallet.respond(topic, 66, JsonPrimitive("0xlate")))
        assertTrue(first.view().requests.isEmpty())
        first.job.cancel()
        settle()

        walletConnect()
        settle()
        relay.refusing = false
        repeat(30) { settle() } // past the attempt that waited for a connection, and the reconnection
        assertEquals("0xlate", dapp.received.getValue(topic).last()["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun aUriNamingAnOpenSessionsTopicIsRefusedAndTheSessionKept() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        val topic = settled(running, dapp)
        val crafted = "wc:$topic@2?relay-protocol=irn&symKey=${WcEncoding.hex(WcKeys.newSymKey())}"
        assertEquals(WcWallet.Pairing.TopicInUse, running.wallet.pair(crafted))
        dapp.sendOnSession(request(31, "wc_sessionPing", JsonObject(emptyMap())), tag = 1114)
        settle()
        assertEquals("true", dapp.received.getValue(topic).last()["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun aSessionMayHaveOnlySoManyRequestsWaiting() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        val topic = settled(running, dapp)
        repeat(WcProtocol.PENDING_REQUESTS_PER_SESSION + 1) { dapp.sendOnSession(personalSign(1_000L + it)) }
        settle()
        assertEquals(WcProtocol.PENDING_REQUESTS_PER_SESSION, running.view().requests.size)
        val refused = dapp.received.getValue(topic).last()
        assertEquals("${1_000L + WcProtocol.PENDING_REQUESTS_PER_SESSION}", refused["id"]?.jsonPrimitive?.content)
        assertEquals(-32005, refused.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
    }

    @Test
    fun aPairingMayHaveOnlySoManyProposalsWaiting() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        dapp.subscribePairing()
        assertIs<WcWallet.Pairing.Paired>(running.wallet.pair(dapp.uri()))
        repeat(WcProtocol.PENDING_PROPOSALS_PER_PAIRING + 1) {
            dapp.propose(100L + it, JsonObject(emptyMap()), buildJsonObject { put("eip155", sepoliaAsk) })
        }
        settle()
        assertEquals(WcProtocol.PENDING_PROPOSALS_PER_PAIRING, running.view().proposals.size)
        val refused = dapp.received.getValue(dapp.pairingTopic).last()
        assertEquals("${100L + WcProtocol.PENDING_PROPOSALS_PER_PAIRING}", refused["id"]?.jsonPrimitive?.content)
        assertEquals(-32005, refused.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
    }

    @Test
    fun anOptionalNamespaceOfAnotherChainAddsNothingToTheSepoliaSession() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        dapp.subscribePairing()
        assertIs<WcWallet.Pairing.Paired>(running.wallet.pair(dapp.uri()))
        dapp.propose(
            1,
            JsonObject(emptyMap()),
            buildJsonObject {
                put(WcProtocol.SEPOLIA, namespace(listOf(WcProtocol.SEPOLIA), listOf("personal_sign"), emptyList()))
                put("eip155:1", namespace(listOf("eip155:1"), listOf("eth_sendTransaction"), listOf("chainChanged")))
            },
        )
        settle()
        val proposal = running.view().proposals.single()
        assertNull(proposal.refusal)
        assertEquals(setOf("personal_sign"), proposal.methods, "what is asked on chain 1 is not Sepolia's")
        assertEquals(emptySet(), proposal.events)
    }

    @Test
    fun anIdForgottenFromTheSeenWindowIsStillSeen() {
        var state = WcState()
        repeat(WcProtocol.SEEN_IDS_PER_TOPIC) { state = state.seeing("t", 10_000L + it) }
        assertTrue(state.hasSeen("t", 10_000L))
        state = state.seeing("t", 20_000L) // the window is full: 10_000 is forgotten
        assertFalse(10_000L in state.seen.getValue("t"))
        assertTrue(state.hasSeen("t", 10_000L), "below the window: an old id, never prompted again")
        assertFalse(state.hasSeen("t", 30_000L), "a new id is not")
        assertFalse(state.hasSeen("other", 10_000L))
    }

    @Test
    fun requestsPastTheirExpiryDoNotCountTowardsTheCap() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        settled(running, dapp)
        val soon = now() / 1000 + 5
        repeat(WcProtocol.PENDING_REQUESTS_PER_SESSION) { dapp.sendOnSession(personalSign(2_000L + it, expiry = soon)) }
        settle()
        repeat(10) { settle() } // past their expiry, before the minute's sweep
        dapp.sendOnSession(personalSign(3_000))
        settle()
        assertEquals(listOf(3_000L), running.view().requests.map { it.id })
    }

    @Test
    fun anExtensionWhileTheRelayCannotBeReachedIsSavedAndSentOnceItIsBack() = runTest {
        val running = walletConnect()
        val dapp = dapp()
        val topic = settled(running, dapp)
        relay.refusing = true
        relay.dropConnections()
        // Saved, so true, although the relay has not taken it yet: it waits in the outbox.
        assertTrue(running.wallet.extend(topic))
        relay.refusing = false
        repeat(30) { settle() }
        assertEquals("wc_sessionExtend", dapp.received.getValue(topic).last()["method"]?.jsonPrimitive?.content)
    }
}

