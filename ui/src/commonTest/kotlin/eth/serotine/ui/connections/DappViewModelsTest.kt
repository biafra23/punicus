package eth.serotine.ui.connections

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.DappConnections
import eth.serotine.domain.repository.DappMetadata
import eth.serotine.domain.repository.DappPairing
import eth.serotine.domain.repository.DappProposal
import eth.serotine.domain.repository.DappProposalId
import eth.serotine.domain.repository.DappRefusal
import eth.serotine.domain.repository.DappRelayLink
import eth.serotine.domain.repository.DappSession
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.usecase.ApproveDappProposal
import eth.serotine.domain.usecase.DisconnectDapp
import eth.serotine.domain.usecase.ExtendDappSession
import eth.serotine.domain.usecase.ObserveDappConnections
import eth.serotine.domain.usecase.PairDapp
import eth.serotine.domain.usecase.RejectDappProposal
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeDappSessions
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.OffDappSessions
import eth.serotine.ui.dappproposal.DappProposalViewModel
import eth.serotine.ui.pairdapp.PairDappViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ViewModels of Connected dApps (D1), Connect a dApp (D2) and a connection request (D3). */
@OptIn(ExperimentalCoroutinesApi::class)
class DappViewModelsTest {

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun account(network: Network, last: Char, custody: AccountCustody = AccountCustody.InKeyStore) =
        Account(network, Address.of("0x" + "0".repeat(39) + last), AccountKind.EOA, "Account $last", custody)

    private val held = account(Network.SEPOLIA, '1')
    private val watched = account(Network.SEPOLIA, '2', AccountCustody.Watched)
    private val accounts = FakeAccounts(held, watched)
    private val networks = MemoryNetworkSettings(setOf(Network.SEPOLIA))
    private val sessions = FakeDappSessions()
    private val dapp = DappMetadata("Test dApp", "https://dapp.test", "A dApp")
    private val proposalId = DappProposalId("a".repeat(64), 7)

    private fun proposal(refusal: DappRefusal? = null) = DappProposal(
        proposalId, dapp, emptySet(), setOf("eip155:11155111"), setOf("personal_sign"), refusal, 1_700_000_300,
    )

    private fun key(account: Account) = DappProposalViewModel.accountKey(account.network.chainId, account.address.hex)

    private fun TestScope.observe() = ObserveDappConnections(sessions, accounts, networks)

    private fun TestScope.main() = Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

    @Test
    fun connectionsListProposalsAndSessionsWithTheirEnd() = runTest {
        main()
        sessions.connections.value = DappConnections.On(
            DappRelayLink.CONNECTED,
            listOf(proposal()),
            listOf(DappSession(DappSessionId("t1"), dapp, listOf(held.address), listOf("personal_sign"), 1_700_000_000 + 6 * 86_400 + 10, true)),
        )
        val vm = ConnectionsViewModel(observe(), DisconnectDapp(sessions), ExtendDappSession(sessions)) { 1_700_000_000 }
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val state = vm.uiState.value
        assertEquals("Connected to WalletConnect's relay", state.link)
        assertEquals("${proposalId.pairing}/7", state.proposals.single().key)
        assertEquals("Sees 1 account · Ends in 6 days", state.sessions.single().line)
        vm.disconnect("t1")
        runCurrent()
        assertEquals(listOf(DappSessionId("t1")), sessions.disconnected)
        sessions.saves = false
        vm.extend("t1")
        runCurrent()
        assertEquals(ConnectionsViewModel.NOT_SAVED, vm.uiState.value.notice)
    }

    @Test
    fun connectionsSayWhenTheyAreOff() = runTest {
        main()
        val vm = ConnectionsViewModel(
            ObserveDappConnections(OffDappSessions, accounts, networks),
            DisconnectDapp(OffDappSessions),
            ExtendDappSession(OffDappSessions),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertTrue(vm.uiState.value.off)
    }

    @Test
    fun connectWithoutALinkSaysWhatIsMissingAndAPairingEndsTheScreen() = runTest {
        main()
        val vm = PairDappViewModel(PairDapp(sessions))
        vm.connect()
        assertEquals(PairDappViewModel.PASTE_FIRST, vm.uiState.value.message)
        assertTrue(sessions.paired.isEmpty())
        sessions.pairing = DappPairing.Expired
        vm.edit("wc:old")
        vm.connect()
        runCurrent()
        assertEquals(PairDappViewModel.message(DappPairing.Expired), vm.uiState.value.message)
        assertFalse(vm.uiState.value.paired)
        sessions.pairing = DappPairing.Paired(waitingForRelay = false)
        vm.connect()
        runCurrent()
        assertTrue(vm.uiState.value.paired)
    }

    @Test
    fun aRequestOffersSepoliaAccountsWithAKeyAndExplainsTheOthers() = runTest {
        main()
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(proposal()), emptyList())
        val vm = DappProposalViewModel(
            "${proposalId.pairing}/7",
            observe(),
            ApproveDappProposal(sessions, accounts, networks),
            RejectDappProposal(sessions),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val state = vm.uiState.value
        assertEquals("Test dApp", state.name)
        assertNull(state.accounts.first { it.key == key(held) }.unavailable)
        assertNotNull(state.accounts.first { it.key == key(watched) }.unavailable)
        assertTrue(state.approveUnavailable)

        // A greyed-out account says why, and is not chosen.
        vm.toggle(key(watched))
        assertEquals("Not offered to dApps", vm.uiState.value.explaining?.title)
        vm.dismissExplanation()
        // Approve with nothing chosen says what is missing.
        vm.approve()
        assertEquals(DappProposalViewModel.CHOOSE_ACCOUNT, vm.uiState.value.explaining?.reason)
        vm.dismissExplanation()

        vm.toggle(key(held))
        assertFalse(vm.uiState.value.approveUnavailable)
        vm.approve()
        runCurrent()
        assertEquals(listOf(proposalId to listOf(held.address)), sessions.approved)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun aRequestThatAsksForTooMuchCanOnlyBeRejected() = runTest {
        main()
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(proposal(DappRefusal.OTHER_CHAIN)), emptyList())
        val vm = DappProposalViewModel(
            "${proposalId.pairing}/7",
            observe(),
            ApproveDappProposal(sessions, accounts, networks),
            RejectDappProposal(sessions),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertEquals(DappProposalViewModel.refusalLine(DappRefusal.OTHER_CHAIN), vm.uiState.value.refusal)
        vm.toggle(key(held))
        vm.approve()
        assertEquals("Cannot be approved", vm.uiState.value.explaining?.title)
        assertTrue(sessions.approved.isEmpty())
        vm.dismissExplanation()
        vm.reject()
        runCurrent()
        assertEquals(listOf(proposalId), sessions.rejected)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun aProposalNoLongerWaitingEndsTheScreen() = runTest {
        main()
        val vm = DappProposalViewModel("${proposalId.pairing}/7", observe(), ApproveDappProposal(sessions, accounts, networks), RejectDappProposal(sessions))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertTrue(vm.uiState.value.gone)
    }

    @Test
    fun aRequestThatExpiresAsApproveIsTappedSaysSoInsteadOfLeaving() = runTest {
        main()
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(proposal()), emptyList())
        sessions.approval = { _, _ ->
            sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, emptyList(), emptyList())
            eth.serotine.domain.repository.DappApproval.Expired
        }
        val vm = DappProposalViewModel(
            "${proposalId.pairing}/7",
            observe(),
            ApproveDappProposal(sessions, accounts, networks),
            RejectDappProposal(sessions),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.toggle(key(held))
        vm.approve()
        runCurrent()
        val state = vm.uiState.value
        assertTrue(state.ended)
        assertFalse(state.gone)
        assertEquals("This request has expired. Connect again from the dApp.", state.notice)
    }

    @Test
    fun aSessionPastItsEndSaysItEnded() {
        assertEquals("Ended", ConnectionsViewModel.endsIn(-5))
        assertEquals("Ends in a few minutes", ConnectionsViewModel.endsIn(120))
    }
}

