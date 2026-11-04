package eth.serotine.ui.addnetwork

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.usecase.AddOnNetworks
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.WalletChanges
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.components.NetworkChoice
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** [AddOnNetworksViewModel]: an EOA added on the networks it is not on yet, one that is off turned on first. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddOnNetworksViewModelTest {

    private val main =
        Account(Network.SEPOLIA, Address.of("0x" + "11".repeat(20)), AccountKind.EOA, "Main", AccountCustody.InKeyStore)
    private val safe =
        Account(Network.SEPOLIA, Address.of("0x" + "22".repeat(20)), AccountKind.SAFE, "Club", AccountCustody.Watched)
    private val accounts = FakeAccounts(main, safe)
    private val networks = MemoryNetworkSettings()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(account: Account): AddOnNetworksViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = AddOnNetworksViewModel(
            account.id,
            ObserveAccounts(accounts),
            ObserveEnabledNetworks(networks),
            SetNetworkEnabled(networks),
            AddOnNetworks(accounts, WalletChanges()),
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    @Test
    fun anAccountOnEveryNetworkHasNoneToChoose() = runTest {
        val onEthereum = main.copy(network = Network.ETHEREUM)
        val onGnosis = main.copy(network = Network.GNOSIS)
        accounts.accounts.value += listOf(onEthereum, onGnosis)
        val viewModel = viewModel(main)

        assertEquals(AddOnNetworksUiState.Choose("Main", emptyList()), viewModel.uiState.value)
        viewModel.add()
        runCurrent()
        assertEquals(listOf(main, safe, onEthereum, onGnosis), accounts.accounts.value, "nothing added")
    }

    @Test
    fun anAccountIsAddedOnANetworkTurnedOnFromTheDialog() = runTest {
        val viewModel = viewModel(main)
        val ethereum = Network.ETHEREUM.chainId
        val offered = (viewModel.uiState.value as AddOnNetworksUiState.Choose).choices.first()
        assertEquals(NetworkChoice(ethereum, "Ethereum", testnet = false, on = false), offered, "off, so not chosen")

        viewModel.turnOn(ethereum)
        runCurrent()
        assertEquals(setOf(Network.ETHEREUM, Network.SEPOLIA), networks.on.value)
        val chosen = (viewModel.uiState.value as AddOnNetworksUiState.Choose).choices.first()
        assertEquals(true, chosen.on && chosen.chosen, "turned on, and ticked")
        viewModel.add()
        runCurrent()
        assertEquals(listOf(main, safe, main.copy(network = Network.ETHEREUM)), accounts.accounts.value)
    }

    @Test
    fun aSafeIsNeverAddedOnAnotherNetwork() = runTest {
        val viewModel = viewModel(safe)

        val unavailable = viewModel.uiState.value as AddOnNetworksUiState.Unavailable
        assertEquals("A Safe or another contract exists only on the network it was deployed on.", unavailable.message)
    }

    @Test
    fun aRemovedAccountEndsTheDialog() = runTest {
        val viewModel = viewModel(main)

        accounts.accounts.value = listOf(safe)
        runCurrent()

        assertEquals(AddOnNetworksUiState.Gone, viewModel.uiState.value)
    }
}
