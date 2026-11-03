@file:OptIn(ExperimentalCoroutinesApi::class)

package eth.serotine.data.walletconnect

import eth.serotine.data.walletconnect.FakeDapp.Companion.namespace
import eth.serotine.domain.model.Address
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** [WalletConnectDapps] against the [eth.serotine.domain.repository.DappSessions] contract, over [FakeRelay]. */
class WalletConnectDappsTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-wcd-${Random.nextLong().toULong()}")
    private val relay = FakeRelay()
    private val account = Address.of("0x00000000000000000000000000000000000000a1")

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
    fun aProposalWithoutSepoliaIsRefusedAsSuchAndItsRejectionSaysSo() = runTest {
        val dapps = dapps()
        val dapp = FakeDapp(relay, backgroundScope) { now() }
        dapp.subscribePairing()
        dapps.pair(dapp.uri())
        dapp.propose(
            2,
            JsonObject(emptyMap()),
            buildJsonObject { put("eip155", namespace(listOf("eip155:1"), listOf("personal_sign"), emptyList())) },
        )
        settle()
        val proposal = assertIs<DappConnections.On>(dapps.observe().first()).proposals.single()
        assertEquals(DappRefusal.NO_SEPOLIA, proposal.refusal)
        assertTrue(dapps.reject(proposal.id))
        settle()
        val answer = dapp.received.getValue(dapp.pairingTopic).single()
        assertEquals(5100, answer.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
    }

    @Test
    fun aV1UriIsAnUnsupportedVersion() = runTest {
        assertEquals(DappPairing.UnsupportedVersion, dapps().pair("wc:abc@1?bridge=x&key=y"))
    }
}
