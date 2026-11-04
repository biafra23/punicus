package eth.serotine.ui.update

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.usecase.ObserveUpgradeWarnings
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.MemoryNetworkSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRequiredViewModelTest {

    /** Sepolia's report; Ethereum's peers report nothing. */
    private val report = MutableStateFlow<NetworkUpgrade?>(null)
    private val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Synced(100, null, finalized = null))
    private val activated = NetworkUpgrade(NetworkUpgrade.Phase.ACTIVE, 1_791_294_816, "0x6c1d9423", 5)

    private val address = Address.of("0x" + "11".repeat(20))
    private val accounts =
        FakeAccounts(Account(Network.SEPOLIA, address, AccountKind.EOA, "Test", AccountCustody.InKeyStore))

    private fun viewModel(settings: MemoryNetworkSettings = MemoryNetworkSettings()) = UpdateRequiredViewModel(
        ObserveUpgradeWarnings(
            object : NetworkUpgrades {
                override fun observeUpgrade(network: Network): Flow<NetworkUpgrade?> =
                    if (network == Network.SEPOLIA) report else flowOf(null)
            },
            object : KnownUpgradeStore {
                override fun observe(network: Network): Flow<KnownUpgrade?> = flowOf(null)
                override suspend fun save(
                    network: Network,
                    record: KnownUpgrade,
                ): Boolean = error("not used by this test")
            },
            object : LightClient {
                override val datesFinalizedBlocks = true
                override fun observeStatus(network: Network): Flow<LightClientStatus> = status
            },
            settings,
            accounts,
            clock = { 1_791_400_000 },
        ),
        formatTime = { "<$it>" },
    )

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun requiredExactlyWhileTheLightClientAgreesItStoppedVerifying() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(UpdateRequiredUiState.NotRequired, viewModel.uiState.value)

        // Peers alone: the app keeps its screens, the Accounts banner says to update.
        report.value = activated
        runCurrent()
        assertEquals(UpdateRequiredUiState.NotRequired, viewModel.uiState.value)

        status.value = LightClientStatus.WaitingForHead(headAgeSeconds = 300)
        runCurrent()
        assertEquals(
            UpdateRequiredUiState.Required(
                text = "Peers report that Sepolia upgraded on <1791294816> to rules this version of Serotine does " +
                    "not support, and its light client is not verifying the chain. Update the app to keep using " +
                    "your wallet.",
                details = "Reported by peers in 5 networks, not verified · fork id 0x6c1d9423",
                version = "This is Serotine v$APP_VERSION.",
            ),
            viewModel.uiState.value,
        )

        // A light client that verifies again: the app comes back.
        status.value = LightClientStatus.Synced(200, null, finalized = null)
        runCurrent()
        assertEquals(UpdateRequiredUiState.NotRequired, viewModel.uiState.value)
    }

    @Test
    fun notRequiredWhileAnotherNetworkWithAccountsIsStillFollowed() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        accounts.accounts.value +=
            Account(Network.ETHEREUM, address, AccountKind.EOA, "Main", AccountCustody.InKeyStore)
        val viewModel = viewModel(MemoryNetworkSettings(Network.entries.toSet()))
        backgroundScope.launch { viewModel.uiState.collect {} }
        report.value = activated
        status.value = LightClientStatus.WaitingForHead(headAgeSeconds = 300)
        runCurrent()

        // Sepolia is blocked, Ethereum is not: Accounts says it in Sepolia's section, no update screen.
        assertEquals(UpdateRequiredUiState.NotRequired, viewModel.uiState.value)
    }
}
