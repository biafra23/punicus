package eth.serotine.ui.settings

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.CurrencyChoice
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.StaleAnchorConsent
import eth.serotine.domain.usecase.NetworkOverview
import eth.serotine.domain.usecase.ObserveDebugScreen
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveInterfaceMode
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveNetworkLive
import eth.serotine.domain.usecase.ObserveNetworkOverview
import eth.serotine.domain.usecase.SetDebugScreen
import eth.serotine.domain.usecase.SetInterfaceMode
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.SyncPastStaleAnchor
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeFreshness
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.ListCatalog
import eth.serotine.ui.MemoryCurrencySetting
import eth.serotine.ui.MemoryDebugScreen
import eth.serotine.ui.MemoryInterfaceMode
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.MemoryTransactionHistory
import eth.serotine.ui.MemoryUserTokens
import eth.serotine.ui.components.LightClientHealth
import eth.serotine.ui.platform.ScreenCaptureDebugSwitch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val usdc = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USDC", 6, "usdc")
    private val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)

    /** The networks the user let catch up from a stale checkpoint. */
    private val consented = mutableListOf<Network>()
    private val consent = object : StaleAnchorConsent {
        override fun syncPastStaleAnchor(network: Network) {
            consented += network
        }
    }
    private val lightClient = object : LightClient {
        override val datesFinalizedBlocks = true
        override fun observeStatus(network: Network): Flow<LightClientStatus> = status
    }
    private val main = account("Main", 0x11)
    private val accounts = FakeAccounts(main)
    private val networks = MemoryNetworkSettings()
    private val debugScreen = MemoryDebugScreen()
    private val interfaceMode = MemoryInterfaceMode()
    private val currency = MemoryCurrencySetting()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun account(label: String, byte: Int) = Account(
        Network.SEPOLIA,
        Address.of("0x" + byte.toString(16).padStart(2, '0').repeat(20)),
        AccountKind.EOA,
        label,
        AccountCustody.InKeyStore,
    )

    private fun TestScope.viewModel(screenCapture: ScreenCaptureDebugSwitch? = null): SettingsViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = SettingsViewModel(
            ObserveNetworkOverview(networks, accounts, lightClient, MemoryTransactionHistory(), clock = { 1_000 }),
            SetNetworkEnabled(networks),
            ObserveKnownTokens(ListCatalog(usdc), MemoryUserTokens(), FakeMetadata()),
            ObserveDisplayCurrencies(FakeFeeds(), currency, accounts),
            screenCapture,
            ObserveDebugScreen(debugScreen),
            SetDebugScreen(debugScreen),
            SyncPastStaleAnchor(lightClient, consent),
            ObserveInterfaceMode(interfaceMode),
            SetInterfaceMode(interfaceMode),
            ObserveNetworkLive(FakeFreshness(live = setOf(Network.SEPOLIA))),
            clock = { 1_000 },
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private val SettingsViewModel.sepolia: NetworkRow
        get() = uiState.value.networks.single { it.chainId == Network.SEPOLIA.chainId }

    @Test
    fun namesEachNetworkItsAccountsAndLightClientAndTheVersion() = runTest {
        val viewModel = viewModel()

        status.value = LightClientStatus.Synced(headBlock = 9_412_118, headTimestamp = 992, finalized = null)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(
            listOf(
                NetworkRow(
                    1,
                    "Ethereum",
                    on = false,
                    "No account · Off",
                    LightClientHealth.NOT_RUNNING,
                    simpleLine = "Off",
                ),
                NetworkRow(
                    100,
                    "Gnosis Chain",
                    on = false,
                    "No account · Off",
                    LightClientHealth.NOT_RUNNING,
                    simpleLine = "Off",
                ),
                NetworkRow(
                    11_155_111,
                    "Sepolia",
                    on = true,
                    "1 account · Verified at block 9,412,118 · 8 s ago",
                    LightClientHealth.VERIFIED,
                    testnet = true,
                    // Sepolia updates live: simple mode says so instead of the block.
                    simpleLine = "Test network · live",
                ),
            ),
            state.networks,
        )
        assertEquals(APP_VERSION, state.version)
        assertEquals("1 built in · 0 added", state.tokens)
        assertEquals("USD", state.currency)
        assertNull(state.screenCaptureAllowed, "a release build has no screen-capture switch")

        accounts.unreadable.value = true
        runCurrent()
        assertEquals(
            "Accounts cannot be read · Verified at block 9,412,118 · 8 s ago",
            viewModel.sepolia.line,
        )

        currency.choose(DisplayCurrency.EUR)
        runCurrent()
        assertEquals("EUR", viewModel.uiState.value.currency)

        currency.choice.value = CurrencyChoice.Unreadable
        runCurrent()
        assertEquals("USD (your choice cannot be read)", viewModel.uiState.value.currency)
    }

    @Test
    fun aNetworkWithoutAccountsTurnsOffAtOnceAndOnAgain() = runTest {
        accounts.accounts.value = emptyList()
        val viewModel = viewModel()

        viewModel.switchNetwork(Network.SEPOLIA.chainId, on = false)
        runCurrent()
        assertNull(viewModel.uiState.value.turningOff, "nothing to ask about")
        assertEquals(emptySet(), networks.on.value)
        assertEquals(
            NetworkRow(
                11_155_111,
                "Sepolia",
                false,
                "No account · Off",
                LightClientHealth.NOT_RUNNING,
                true,
                simpleLine = "Test network · Off",
            ),
            viewModel.sepolia,
        )

        viewModel.switchNetwork(Network.SEPOLIA.chainId, on = true)
        runCurrent()
        assertEquals(setOf(Network.SEPOLIA), networks.on.value)
        assertTrue(viewModel.sepolia.on)
    }

    @Test
    fun aNetworkWithAccountsIsTurnedOffOnlyOnceConfirmed() = runTest {
        val viewModel = viewModel()

        viewModel.switchNetwork(Network.SEPOLIA.chainId, on = false)
        runCurrent()
        assertEquals(
            TurnOffConfirmation(
                11_155_111,
                "Turn off Sepolia?",
                "Its account, Main, stays in your list. Its balances are not checked and nothing can be sent " +
                    "from it until you turn Sepolia on again.",
            ),
            viewModel.uiState.value.turningOff,
        )
        assertEquals(setOf(Network.SEPOLIA), networks.on.value, "not before it is confirmed")

        viewModel.cancelTurnOff()
        runCurrent()
        assertNull(viewModel.uiState.value.turningOff)
        assertEquals(setOf(Network.SEPOLIA), networks.on.value, "kept on")

        viewModel.switchNetwork(Network.SEPOLIA.chainId, on = false)
        viewModel.confirmTurnOff()
        runCurrent()
        assertNull(viewModel.uiState.value.turningOff)
        assertEquals(emptySet(), networks.on.value)
        assertEquals("1 account · Off", viewModel.sepolia.line)
        assertEquals(LightClientHealth.NOT_RUNNING, viewModel.sepolia.health)
    }

    @Test
    fun anUnreadableAccountListStillAsksBeforeTheNetworkGoesOff() = runTest {
        accounts.unreadable.value = true
        val viewModel = viewModel()

        viewModel.switchNetwork(Network.SEPOLIA.chainId, on = false)
        runCurrent()

        assertEquals(
            "Its accounts, if it has any, stay in your list: the list cannot be read right now. Its balances are " +
                "not checked and nothing can be sent from it until you turn Sepolia on again.",
            viewModel.uiState.value.turningOff?.text,
        )
        assertEquals(setOf(Network.SEPOLIA), networks.on.value, "not before it is confirmed")
    }

    @Test
    fun aRowRefusingAStaleCheckpointWarnsAndCatchesUpOnTheUsersWord() = runTest {
        val viewModel = viewModel()
        status.value = LightClientStatus.StaleAnchor(anchorAgePeriods = 6, boundPeriods = 3)
        runCurrent()

        val row = viewModel.sepolia
        assertEquals(
            "1 account · Not syncing: the last trusted checkpoint is 6 periods old, past the safe limit of 3",
            row.line,
            "the decision is offered here",
        )
        assertEquals("Catch up with Sepolia anyway?", row.staleAnchor?.title)

        viewModel.syncPastStaleAnchor(Network.SEPOLIA.chainId)
        runCurrent()
        assertEquals(listOf(Network.SEPOLIA), consented)
    }

    @Test
    fun aRowsHeadBlockAgeIsReDatedEverySecond() = runTest {
        var now = 1_000L
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = SettingsViewModel(
            ObserveNetworkOverview(networks, accounts, lightClient, MemoryTransactionHistory(), clock = { now }),
            SetNetworkEnabled(networks),
            ObserveKnownTokens(ListCatalog(usdc), MemoryUserTokens(), FakeMetadata()),
            ObserveDisplayCurrencies(FakeFeeds(), currency, accounts),
            null,
            ObserveDebugScreen(debugScreen),
            SetDebugScreen(debugScreen),
            SyncPastStaleAnchor(lightClient, consent),
            ObserveInterfaceMode(interfaceMode),
            SetInterfaceMode(interfaceMode),
            ObserveNetworkLive(FakeFreshness(live = setOf(Network.SEPOLIA))),
            clock = { now },
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        status.value = LightClientStatus.Synced(headBlock = 9_412_118, headTimestamp = 992, finalized = null)
        runCurrent()
        assertEquals("1 account · Verified at block 9,412,118 · 8 s ago", viewModel.sepolia.line)

        now += 5
        advanceTimeBy(1_001)
        assertEquals("1 account · Verified at block 9,412,118 · 13 s ago", viewModel.sepolia.line)
    }

    @Test
    fun aSwitchThatCannotBeSavedLeavesTheNetworkAsItWasAndSaysSo() = runTest {
        accounts.accounts.value = emptyList()
        networks.failSaves = true
        val viewModel = viewModel()

        viewModel.switchNetwork(Network.SEPOLIA.chainId, on = false)
        runCurrent()
        assertTrue(viewModel.sepolia.on)
        assertTrue(viewModel.sepolia.notSaved)

        networks.failSaves = false
        viewModel.switchNetwork(Network.SEPOLIA.chainId, on = false)
        runCurrent()
        assertFalse(viewModel.sepolia.on)
        assertFalse(viewModel.sepolia.notSaved)
    }

    @Test
    fun theConfirmationNamesUpToThreeAccountsAndWhatWaitsForTheNetwork() {
        fun overview(count: Int, unconfirmed: Int = 0, unlistedSafes: Int = 0) = NetworkOverview(
            Network.SEPOLIA,
            on = true,
            accounts = listOf("Main", "Savings", "Daily", "Vault").take(count).mapIndexed { i, label ->
                account(label, 0x11 + i)
            },
            LightClientStatus.Starting,
            unconfirmed,
            unlistedSafes,
        )

        val three = SettingsViewModel.confirmationOf(overview(3, unconfirmed = 2, unlistedSafes = 1))
        assertEquals(
            "Its 3 accounts, Main, Savings and Daily, stay in your list. Its balances are not checked and " +
                "nothing can be sent from it until you turn Sepolia on again.",
            three.text,
        )
        assertEquals(
            "2 transactions sent from it are not confirmed yet. A Safe deployed on it is not listed yet. " +
                "Following it waits until Sepolia is on again; nothing is lost.",
            three.waiting,
        )
        assertEquals(
            "Its 2 accounts, Main and Savings, stay in your list.",
            SettingsViewModel.confirmationOf(overview(2)).text.substringBefore(" Its balances"),
        )
        assertEquals(
            "Its 4 accounts stay in your list.",
            SettingsViewModel.confirmationOf(overview(4)).text.substringBefore(" Its balances"),
        )
        assertNull(SettingsViewModel.confirmationOf(overview(4)).waiting)
    }

    @Test
    fun theDebugBuildsSwitchAllowsScreenCaptureAndBlocksItAgain() = runTest {
        val switch = ScreenCaptureDebugSwitch()
        val viewModel = viewModel(switch)
        assertEquals(false, viewModel.uiState.value.screenCaptureAllowed, "every start begins with capture blocked")

        viewModel.allowScreenCapture(true)
        runCurrent()
        assertTrue(switch.allowed.value)
        assertEquals(true, viewModel.uiState.value.screenCaptureAllowed)

        viewModel.allowScreenCapture(false)
        runCurrent()
        assertFalse(switch.allowed.value)
        assertEquals(false, viewModel.uiState.value.screenCaptureAllowed)
    }

    @Test
    fun theDebugTabsSwitchTurnsItOnAndOffAndSaysWhenThatCouldNotBeSaved() = runTest {
        interfaceMode.mode.value = InterfaceMode.DETAILED
        val viewModel = viewModel()
        assertEquals(false, viewModel.uiState.value.debugScreen)

        viewModel.showDebugScreen(true)
        runCurrent()
        assertEquals(true, viewModel.uiState.value.debugScreen)
        assertFalse(viewModel.uiState.value.debugScreenNotSaved)

        debugScreen.failSaves = true
        viewModel.showDebugScreen(false)
        runCurrent()
        assertEquals(true, viewModel.uiState.value.debugScreen)
        assertTrue(viewModel.uiState.value.debugScreenNotSaved)

        debugScreen.failSaves = false
        viewModel.showDebugScreen(false)
        runCurrent()
        assertEquals(false, viewModel.uiState.value.debugScreen)
        assertFalse(viewModel.uiState.value.debugScreenNotSaved)
    }

    @Test
    fun simpleModeOffersNoDebugTabSwitchAndKeepsItsValueForDetailedView() = runTest {
        debugScreen.enabled.value = true
        val viewModel = viewModel()
        assertNull(viewModel.uiState.value.debugScreen, "simple mode, as in every build")

        viewModel.showDetailedView(true)
        runCurrent()
        assertEquals(true, viewModel.uiState.value.debugScreen)
        // Turning Detailed view off would empty the tab's log: its row says so.
        assertEquals(SettingsViewModel.DEBUG_TAB_GOES, viewModel.uiState.value.detailedViewNote)

        viewModel.showDebugScreen(false)
        runCurrent()
        assertNull(viewModel.uiState.value.detailedViewNote)
    }

    @Test
    fun detailedViewIsOffUntilTurnedOnAndSaysWhenThatCouldNotBeSaved() = runTest {
        val viewModel = viewModel()
        assertFalse(viewModel.uiState.value.detailedView, "simple mode, as in every build")

        viewModel.showDetailedView(true)
        runCurrent()
        assertEquals(InterfaceMode.DETAILED, interfaceMode.mode.value)
        assertTrue(viewModel.uiState.value.detailedView)
        assertFalse(viewModel.uiState.value.detailedViewNotSaved)

        interfaceMode.failSaves = true
        viewModel.showDetailedView(false)
        runCurrent()
        assertTrue(viewModel.uiState.value.detailedView)
        assertTrue(viewModel.uiState.value.detailedViewNotSaved)

        interfaceMode.failSaves = false
        viewModel.showDetailedView(false)
        runCurrent()
        assertEquals(InterfaceMode.SIMPLE, interfaceMode.mode.value)
        assertFalse(viewModel.uiState.value.detailedView)
        assertFalse(viewModel.uiState.value.detailedViewNotSaved)
    }

    @Test
    fun simpleModeSaysAStaleHeadCannotCheckAsHomeDoes() = runTest {
        val viewModel = viewModel()
        status.value = LightClientStatus.WaitingForHead(headAgeSeconds = 600)
        runCurrent()

        assertEquals("Test network · Can't check right now", viewModel.sepolia.simpleLine)
    }
}

