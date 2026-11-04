package eth.serotine.ui.receive

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.components.NetworkLabel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveViewModelTest {

    private val eoa = Account(
        Network.SEPOLIA,
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Daily",
        AccountCustody.Watched,
    )
    private val safe = Account(
        Network.SEPOLIA,
        Address.of("0x" + "5a".repeat(20)),
        AccountKind.SAFE,
        "Family",
        AccountCustody.Watched,
    )
    private val accounts = FakeAccounts(eoa, safe)
    private val networks = MemoryNetworkSettings()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun viewModel(address: Address) = ReceiveViewModel(
        AccountId(Network.SEPOLIA, address),
        ObserveAccount(accounts),
        ObserveEnabledNetworks(networks),
    )

    @Test
    fun theAddressIsChecksummedAndTheWarningNamesTheNetwork() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel(eoa.address)
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(
            ReceiveUiState.Ready(
                "Daily",
                "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2",
                NetworkLabel.of(Network.SEPOLIA),
                "Only send Sepolia assets to this address",
            ),
            viewModel.uiState.value,
            "no other-networks note on a testnet",
        )
    }

    @Test
    fun onANetworkThatIsOffReceiveStillWorksAndSaysWhenWhatArrivesIsShown() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        networks.on.value = emptySet()
        val viewModel = viewModel(eoa.address)
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(
            "Sepolia is off in this wallet: what is sent here arrives, but is shown only once you turn it on.",
            (viewModel.uiState.value as ReceiveUiState.Ready).networkOff,
        )
    }

    @Test
    fun aSafeIsSaidToExistOnlyOnItsNetwork() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel(safe.address)
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals("This Safe exists only on Sepolia", (viewModel.uiState.value as ReceiveUiState.Ready).notice)
    }

    @Test
    fun aRemovedAccountEndsTheScreen() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel(eoa.address)
        backgroundScope.launch { viewModel.uiState.collect {} }

        accounts.accounts.value = listOf(safe)
        runCurrent()

        assertEquals(ReceiveUiState.Gone, viewModel.uiState.value)
    }
}
