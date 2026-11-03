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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        override suspend fun disconnect(session: DappSessionId) = true
        override suspend fun extend(session: DappSessionId) = true
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
    fun aPastedUriIsTrimmed() = runTest {
        PairDapp(sessions)("  wc:abc@2?x \n")
        assertEquals(listOf("wc:abc@2?x"), sessions.paired)
    }
}
