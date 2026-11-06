@file:OptIn(ExperimentalCoroutinesApi::class)

package eth.serotine.data.walletconnect

import eth.serotine.data.walletconnect.FakeDapp.Companion.namespace
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.Signature
import eth.serotine.domain.repository.DappAnswer
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.DappApproval
import eth.serotine.domain.repository.DappConnections
import eth.serotine.domain.repository.DappPairing
import eth.serotine.domain.repository.DappRefusal
import eth.serotine.domain.repository.DappRelayLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** [WalletConnectDapps] against the [eth.serotine.domain.repository.DappSessions] contract, over [FakeRelay]. */
class WalletConnectDappsTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-wcd-${Random.nextLong().toULong()}")
    private val relay = FakeRelay()
    private val address = Address.of("0x00000000000000000000000000000000000000a1")
    private val account = AccountId(Network.SEPOLIA, address)

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    private fun TestScope.now() = 1_700_000_000_000L + testScheduler.currentTime

    private fun TestScope.dapps(projectId: String = "0123456789abcdef0123456789abcdef"): WalletConnectDapps {
        val dapps = WalletConnectDapps(
            Path(directory, "walletconnect.json").toString(),
            projectId,
            relay.connector("wallet"),
            StandardTestDispatcher(testScheduler),
        ) { now() }
        backgroundScope.launch { dapps.run() }
        return dapps
    }

    private fun TestScope.settle() {
        advanceTimeBy(1.seconds)
        runCurrent()
    }

    @Test
    fun withoutAProjectIdDappConnectionsAreOff() = runTest {
        val off = dapps(projectId = "")
        assertEquals(DappConnections.Off, off.observe().first())
        assertEquals(DappPairing.Off, off.pair("wc:x"))
        assertEquals(DappApproval.Off, off.approve(eth.serotine.domain.repository.DappProposalId("p", 1), listOf(account)))
    }

    @Test
    fun aProposalBecomesASessionWithTheChosenAccount() = runTest {
        val dapps = dapps()
        val dapp = FakeDapp(relay, backgroundScope) { now() }
        dapp.subscribePairing()
        assertEquals(DappPairing.Paired(waitingForRelay = false), dapps.pair(dapp.uri()))
        dapp.propose(
            1,
            JsonObject(emptyMap()),
            buildJsonObject { put("eip155", namespace(listOf(WcProtocol.SEPOLIA), listOf("personal_sign"), emptyList())) },
        )
        settle()
        val on = assertIs<DappConnections.On>(dapps.observe().first())
        assertEquals(DappRelayLink.CONNECTED, on.link)
        val proposal = on.proposals.single()
        assertEquals("Test dApp", proposal.dapp.name)
        assertEquals("https://dapp.test", proposal.dapp.url)
        assertEquals(setOf("personal_sign"), proposal.methods)

        assertIs<DappApproval.Approved>(dapps.approve(proposal.id, listOf(account)))
        settle()
        val session = assertIs<DappConnections.On>(dapps.observe().first()).sessions.single()
        assertEquals(listOf(account), session.accounts)
        assertEquals(listOf("personal_sign"), session.methods)
        assertTrue(dapps.disconnect(session.id))
        assertTrue(assertIs<DappConnections.On>(dapps.observe().first()).sessions.isEmpty())
    }

    @Test
    fun aProposalForNoNetworkServedHereIsRefusedAsSuchAndItsRejectionSaysSo() = runTest {
        val dapps = dapps()
        val dapp = FakeDapp(relay, backgroundScope) { now() }
        dapp.subscribePairing()
        dapps.pair(dapp.uri())
        dapp.propose(
            2,
            JsonObject(emptyMap()),
            buildJsonObject { put("eip155", namespace(listOf("eip155:137"), listOf("personal_sign"), emptyList())) },
        )
        settle()
        val proposal = assertIs<DappConnections.On>(dapps.observe().first()).proposals.single()
        assertEquals(DappRefusal.NO_NETWORK, proposal.refusal)
        assertTrue(dapps.reject(proposal.id))
        settle()
        val answer = dapp.received.getValue(dapp.pairingTopic).single()
        assertEquals(5100, answer.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
    }

    @Test
    fun aProposalRequiringRealFundsAndTestnetTogetherIsRefusedAsSuch() = runTest {
        val dapps = dapps()
        val dapp = FakeDapp(relay, backgroundScope) { now() }
        dapp.subscribePairing()
        dapps.pair(dapp.uri())
        dapp.propose(
            5,
            buildJsonObject { put("eip155", namespace(listOf("eip155:1", WcProtocol.SEPOLIA), listOf("personal_sign"), emptyList())) },
            JsonObject(emptyMap()),
        )
        settle()
        val proposal = assertIs<DappConnections.On>(dapps.observe().first()).proposals.single()
        assertEquals(DappRefusal.MIXED_FUNDS, proposal.refusal)
    }

    @Test
    fun aRealFundsSessionHoldsEthereumAndGnosisChainAndSwitchesBetweenThemOnly() = runTest {
        val dapps = dapps()
        val dapp = FakeDapp(relay, backgroundScope) { now() }
        dapp.subscribePairing()
        dapps.pair(dapp.uri())
        val methods = listOf("personal_sign", "wallet_switchEthereumChain")
        dapp.propose(
            6,
            buildJsonObject { put("eip155", namespace(listOf("eip155:1"), methods, listOf("chainChanged"))) },
            buildJsonObject { put("eip155", namespace(listOf("eip155:100", WcProtocol.SEPOLIA), methods, emptyList())) },
        )
        settle()
        val proposal = assertIs<DappConnections.On>(dapps.observe().first()).proposals.single()
        assertEquals(setOf(Network.ETHEREUM, Network.GNOSIS), proposal.networks)
        val ethereum = AccountId(Network.ETHEREUM, address)
        val gnosis = AccountId(Network.GNOSIS, address)
        // Testnet beside real funds, or a chain not asked for, is no approval.
        assertEquals(DappApproval.InvalidAccounts, dapps.approve(proposal.id, listOf(ethereum, account)))
        assertIs<DappApproval.Approved>(dapps.approve(proposal.id, listOf(ethereum, gnosis)))
        settle()
        val session = assertIs<DappConnections.On>(dapps.observe().first()).sessions.single()
        assertEquals(listOf(ethereum, gnosis), session.accounts)
        assertEquals(setOf(Network.ETHEREUM, Network.GNOSIS), session.networks)
        assertTrue(dapp.followSession(6))
        fun sessionRequest(id: Long, chain: String, chainId: String) = FakeDapp.request(
            id,
            "wc_sessionRequest",
            buildJsonObject {
                putJsonObject("request") {
                    put("method", "wallet_switchEthereumChain")
                    put("params", buildJsonArray { addJsonObject { put("chainId", chainId) } })
                }
                put("chainId", chain)
            },
        )
        dapp.sendOnSession(sessionRequest(40, "eip155:1", "0x64"))
        // Asked on Sepolia, a chain the session does not hold: refused by the adapter at once.
        dapp.sendOnSession(sessionRequest(41, WcProtocol.SEPOLIA, "0x1"))
        settle()
        val switch = dapps.requests.observe().first().single()
        assertEquals(40L, switch.id.id)
        assertEquals(Network.ETHEREUM, switch.network)
        assertEquals(setOf(Network.ETHEREUM, Network.GNOSIS), switch.sessionNetworks)
        assertEquals(listOf(ethereum, gnosis), switch.sessionAccounts)
        assertEquals(DappAnswer.DELIVERED, dapps.requests.answerChainSwitch(switch.id, Network.GNOSIS))
        settle()
        val received = dapp.received.getValue(checkNotNull(dapp.sessionTopic))
        val answers = received.filter { "id" in it && ("result" in it || "error" in it) }
            .associateBy { it.getValue("id").jsonPrimitive.content }
        assertEquals("null", answers.getValue("40").getValue("result").toString())
        assertEquals(5100, answers.getValue("41").getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        val event = received.single { it["method"]?.jsonPrimitive?.content == "wc_sessionEvent" }.getValue("params").jsonObject
        assertEquals("eip155:100", event.getValue("chainId").jsonPrimitive.content)
        assertEquals("chainChanged", event.getValue("event").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(100, event.getValue("event").jsonObject.getValue("data").jsonPrimitive.int)
    }

    @Test
    fun aSessionsRequestsArriveDecodedAndAreAnswered() = runTest {
        val dapps = dapps()
        val dapp = FakeDapp(relay, backgroundScope) { now() }
        dapp.subscribePairing()
        dapps.pair(dapp.uri())
        val methods = listOf("personal_sign", "wallet_switchEthereumChain")
        dapp.propose(
            3,
            JsonObject(emptyMap()),
            buildJsonObject { put("eip155", namespace(listOf(WcProtocol.SEPOLIA), methods, emptyList())) },
        )
        settle()
        val proposal = assertIs<DappConnections.On>(dapps.observe().first()).proposals.single()
        assertIs<DappApproval.Approved>(dapps.approve(proposal.id, listOf(account)))
        settle()
        assertTrue(dapp.followSession(3))
        fun sessionRequest(id: Long, method: String, params: JsonArray) = FakeDapp.request(
            id,
            "wc_sessionRequest",
            buildJsonObject {
                putJsonObject("request") {
                    put("method", method)
                    put("params", params)
                }
                put("chainId", WcProtocol.SEPOLIA)
            },
        )
        dapp.sendOnSession(
            sessionRequest(20, "personal_sign", buildJsonArray { add(JsonPrimitive("0x68656c6c6f")); add(JsonPrimitive(address.hex)) }),
        )
        dapp.sendOnSession(
            sessionRequest(21, "wallet_switchEthereumChain", buildJsonArray { addJsonObject { put("chainId", "0x1") } }),
        )
        settle()
        val waiting = dapps.requests.observe().first()
        assertEquals(listOf(20L, 21L), waiting.map { it.id.id })
        val sign = waiting.first()
        assertEquals(listOf(account), sign.sessionAccounts)
        assertEquals("Test dApp", sign.dapp.name)
        assertEquals(WcProtocol.SEPOLIA, sign.chain)
        assertEquals(DappAsk.PersonalSign(address, PersonalMessage.ofHex("68656c6c6f")!!), sign.ask)
        assertEquals(DappAsk.SwitchChain(1), waiting.last().ask)

        val signature = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), 0)
        assertEquals(DappAnswer.DELIVERED, dapps.requests.answerSignature(sign.id, signature))
        assertEquals(DappAnswer.DELIVERED, dapps.requests.refuse(waiting.last().id, DappRequestError.UNRECOGNIZED_CHAIN))
        assertEquals(DappAnswer.NO_SUCH_REQUEST, dapps.requests.answerNull(sign.id))
        settle()
        val answers = dapp.received.getValue(checkNotNull(dapp.sessionTopic)).associateBy { it.getValue("id").jsonPrimitive.content }
        assertEquals(WcRequests.signatureHex(signature), answers.getValue("20").getValue("result").jsonPrimitive.content)
        assertEquals(4902, answers.getValue("21").getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        assertTrue(dapps.requests.observe().first().isEmpty())
    }

    @Test
    fun aSafeTransactionRecordedForARequestIsKeptWithItOnceAndForAll() = runTest {
        val dapps = dapps()
        val dapp = FakeDapp(relay, backgroundScope) { now() }
        dapp.subscribePairing()
        dapps.pair(dapp.uri())
        dapp.propose(
            4,
            JsonObject(emptyMap()),
            buildJsonObject { put("eip155", namespace(listOf(WcProtocol.SEPOLIA), listOf("eth_sendTransaction"), emptyList())) },
        )
        settle()
        val proposal = assertIs<DappConnections.On>(dapps.observe().first()).proposals.single()
        assertIs<DappApproval.Approved>(dapps.approve(proposal.id, listOf(account)))
        settle()
        assertTrue(dapp.followSession(4))
        dapp.sendOnSession(
            FakeDapp.request(
                30,
                "wc_sessionRequest",
                buildJsonObject {
                    putJsonObject("request") {
                        put("method", "eth_sendTransaction")
                        put(
                            "params",
                            buildJsonArray {
                                addJsonObject {
                                    put("from", address.hex)
                                    put("to", "0x00000000000000000000000000000000000000b2")
                                    put("value", "0x1")
                                }
                            },
                        )
                    }
                    put("chainId", WcProtocol.SEPOLIA)
                },
            ),
        )
        settle()
        val request = dapps.requests.observe().first().single()
        assertEquals(null, request.safeTx)
        val safeTx = Digest.of("ab".repeat(32))
        val told = dapp.received[checkNotNull(dapp.sessionTopic)].orEmpty().size
        assertTrue(dapps.requests.recordSafeTx(request.id, safeTx))
        assertTrue(dapps.requests.recordSafeTx(request.id, safeTx), "the same one again: kept")
        assertFalse(dapps.requests.recordSafeTx(request.id, Digest.of("cd".repeat(32))), "never another")
        assertEquals(safeTx, dapps.requests.observe().first().single().safeTx)
        assertFalse(dapps.requests.recordSafeTx(DappRequestId(request.id.session, 31), safeTx), "no such request")
        settle()
        assertEquals(told, dapp.received[checkNotNull(dapp.sessionTopic)].orEmpty().size, "the dApp is told nothing")

        // Answered with the execution's hash, it is gone, its record with it.
        assertEquals(DappAnswer.DELIVERED, dapps.requests.answerTransaction(request.id, "0x" + "12".repeat(32)))
        assertTrue(dapps.requests.observe().first().isEmpty())
        assertFalse(dapps.requests.recordSafeTx(request.id, safeTx))
    }

    @Test
    fun withoutAProjectIdThereAreNoRequests() = runTest {
        val off = dapps(projectId = "")
        assertTrue(off.requests.observe().first().isEmpty())
        val id = DappRequestId(DappSessionId("t"), 1)
        assertEquals(DappAnswer.OFF, off.requests.refuse(id, DappRequestError.USER_REJECTED))
    }

    @Test
    fun aV1UriIsAnUnsupportedVersion() = runTest {
        assertEquals(DappPairing.UnsupportedVersion, dapps().pair("wc:abc@1?bridge=x&key=y"))
    }
}
