package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.DappApproval
import eth.serotine.domain.repository.DappConnections
import eth.serotine.domain.repository.DappPairing
import eth.serotine.domain.repository.DappProposalId
import eth.serotine.domain.repository.DappRelayLink
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.DappSessions
import eth.serotine.domain.repository.NetworkSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DappConnectionsTest {

    private fun account(network: Network, last: Char, kind: AccountKind = AccountKind.EOA, custody: AccountCustody = AccountCustody.InKeyStore) =
        Account(network, Address.of("0x" + "0".repeat(39) + last), kind, "Account $last", custody)

    private val held = account(Network.SEPOLIA, '1')
    private val watched = account(Network.SEPOLIA, '2', custody = AccountCustody.Watched)
    private val watchedSafe = account(Network.SEPOLIA, '3', kind = AccountKind.SAFE, custody = AccountCustody.Watched)
    private val mainnet = account(Network.ETHEREUM, '4')

    private val accounts = ListAccountRepository(held, watched, watchedSafe, mainnet)
    private val enabled = MutableStateFlow(setOf(Network.ETHEREUM, Network.SEPOLIA))
    private val networks = object : NetworkSettings {
        override fun observe(): Flow<Set<Network>> = enabled
        override suspend fun setEnabled(network: Network, on: Boolean): Boolean = error("not used")
    }

    private class FakeSessions : DappSessions {
        val connections = MutableStateFlow<DappConnections>(DappConnections.On(DappRelayLink.CONNECTED, emptyList(), emptyList()))
        val approved = mutableListOf<Pair<DappProposalId, List<Address>>>()
        val paired = mutableListOf<String>()
        override fun observe(): Flow<DappConnections> = connections
        override suspend fun pair(uri: String): DappPairing = DappPairing.Paired(false).also { paired += uri }
        override suspend fun approve(proposal: DappProposalId, accounts: List<Address>): DappApproval {
            approved += proposal to accounts
            return DappApproval.Approved(DappSessionId("t"), delivered = true)
        }
        override suspend fun reject(proposal: DappProposalId) = true
        override suspend fun disconnect(session: DappSessionId): Boolean {
            disconnected += session
            connections.value = (connections.value as DappConnections.On).let { on ->
                on.copy(sessions = on.sessions.filterNot { it.id == session })
            }
            return true
        }
        override suspend fun extend(session: DappSessionId) = true
        val updated = mutableListOf<Pair<DappSessionId, List<Address>>>()
        val disconnected = mutableListOf<DappSessionId>()
        override suspend fun updateAccounts(session: DappSessionId, accounts: List<Address>): Boolean {
            updated += session to accounts
            connections.value = (connections.value as DappConnections.On).let { on ->
                on.copy(sessions = on.sessions.map { if (it.id == session) it.copy(accounts = accounts) else it })
            }
            return true
        }
    }

    private val sessions = FakeSessions()
    private val proposal = DappProposalId("pairing", 1)

    @Test
    fun everyAccountIsListedAndOnlySepoliaAccountsThatCanSignAreOffered() = runTest {
        val overview = ObserveDappConnections(sessions, accounts, networks)().first()
        assertEquals(
            listOf(DappOffer.OFFERED, DappOffer.NO_KEY, DappOffer.OFFERED, DappOffer.NOT_SEPOLIA),
            overview.accounts.map { it.offer },
        )
    }

    @Test
    fun withSepoliaOffItsAccountsAreGreyedOutWithTheWayToTurnItOn() = runTest {
        enabled.value = setOf(Network.ETHEREUM)
        val overview = ObserveDappConnections(sessions, accounts, networks)().first()
        assertEquals(DappOffer.SEPOLIA_OFF, overview.accounts.first { it.account == held }.offer)
    }

    @Test
    fun anUnreadableAccountListOffersNothing() = runTest {
        accounts.unreadable = true
        val overview = ObserveDappConnections(sessions, accounts, networks)().first()
        assertTrue(overview.accountsUnreadable)
        assertTrue(overview.accounts.isEmpty())
    }

    @Test
    fun anApprovalNamesOfferedAccountsOnly() = runTest {
        val approve = ApproveDappProposal(sessions, accounts, networks)
        assertEquals(DappApproval.InvalidAccounts, approve(proposal, emptyList()))
        assertEquals(DappApproval.InvalidAccounts, approve(proposal, listOf(watched.address)))
        assertEquals(DappApproval.InvalidAccounts, approve(proposal, listOf(mainnet.address)))
        assertEquals(DappApproval.InvalidAccounts, approve(proposal, listOf(held.address, held.address)))
        assertTrue(sessions.approved.isEmpty())
        assertTrue(approve(proposal, listOf(held.address, watchedSafe.address)) is DappApproval.Approved)
        assertEquals(listOf(proposal to listOf(held.address, watchedSafe.address)), sessions.approved)
    }

    @Test
    fun anAccountRemovedFromTheWalletLeavesEverySessionAndASessionLeftWithNoneEnds() = runTest {
        val metadata = eth.serotine.domain.repository.DappMetadata("dApp", "https://dapp.test", "")
        fun session(topic: String, vararg seen: Account) = eth.serotine.domain.repository.DappSession(
            DappSessionId(topic), metadata, seen.map { it.address }, listOf("personal_sign"), 0, true,
        )
        sessions.connections.value = DappConnections.On(
            DappRelayLink.CONNECTED,
            emptyList(),
            listOf(session("both", held, watchedSafe), session("only", held), session("other", watchedSafe)),
        )
        val following = backgroundScope.launch { FollowDappAccounts(sessions, accounts, networks)() }
        runCurrent()
        assertTrue(sessions.updated.isEmpty() && sessions.disconnected.isEmpty())
        accounts.remove(held.id)
        runCurrent()
        assertEquals(listOf(DappSessionId("both") to listOf(watchedSafe.address)), sessions.updated)
        assertEquals(listOf(DappSessionId("only")), sessions.disconnected)
        following.cancel()
    }

    @Test
    fun anAccountWhoseKeyWasRemovedOrWhoseNetworkWasTurnedOffLeavesItsSessions() = runTest {
        val metadata = eth.serotine.domain.repository.DappMetadata("dApp", "https://dapp.test", "")
        sessions.connections.value = DappConnections.On(
            DappRelayLink.CONNECTED,
            emptyList(),
            listOf(
                eth.serotine.domain.repository.DappSession(
                    DappSessionId("s"), metadata, listOf(held.address, watchedSafe.address), listOf("personal_sign"), 0, true,
                ),
            ),
        )
        val following = backgroundScope.launch { FollowDappAccounts(sessions, accounts, networks)() }
        runCurrent()
        accounts.setCustody(held.id, AccountCustody.Watched)
        runCurrent()
        assertEquals(listOf(DappSessionId("s") to listOf(watchedSafe.address)), sessions.updated)
        enabled.value = setOf(Network.ETHEREUM)
        runCurrent()
        assertEquals(listOf(DappSessionId("s")), sessions.disconnected)
        following.cancel()
    }

    @Test
    fun aNetworkOffFromTheStartEndsNoSessionOnlyOneTurnedOffWhileRunning() = runTest {
        val metadata = eth.serotine.domain.repository.DappMetadata("dApp", "https://dapp.test", "")
        sessions.connections.value = DappConnections.On(
            DappRelayLink.CONNECTED,
            emptyList(),
            listOf(
                eth.serotine.domain.repository.DappSession(
                    DappSessionId("s"), metadata, listOf(held.address), listOf("personal_sign"), 0, true,
                ),
            ),
        )
        // An unreadable setting reads as a release build's defaults: Sepolia off.
        enabled.value = setOf(Network.ETHEREUM)
        val following = backgroundScope.launch { FollowDappAccounts(sessions, accounts, networks)() }
        runCurrent()
        assertTrue(sessions.disconnected.isEmpty(), "never read as on, so never known to be off")
        enabled.value = setOf(Network.ETHEREUM, Network.SEPOLIA)
        runCurrent()
        enabled.value = setOf(Network.ETHEREUM)
        runCurrent()
        assertEquals(listOf(DappSessionId("s")), sessions.disconnected, "turned off while running")
        following.cancel()
    }

    @Test
    fun aPastedUriIsTrimmed() = runTest {
        PairDapp(sessions)("  wc:abc@2?x \n")
        assertEquals(listOf("wc:abc@2?x"), sessions.paired)
    }
}
