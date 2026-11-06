package eth.serotine.ui.accounts

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.AmountsHiddenSetting
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.HandoffAvailability
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.StaleAnchorConsent
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveAmountsHidden
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.ObserveEnsNames
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveNetworkLive
import eth.serotine.domain.usecase.ObservePortfolio
import eth.serotine.domain.usecase.ObserveSafe
import eth.serotine.domain.usecase.ObserveSafes
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveUpgradeWarnings
import eth.serotine.domain.usecase.ObserveUsdPrice
import eth.serotine.domain.usecase.OfferHandoff
import eth.serotine.domain.usecase.QUEUED_UNATTENDED_SECONDS
import eth.serotine.domain.usecase.RefreshNetworkData
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.SyncPastStaleAnchor
import eth.serotine.domain.usecase.ToggleAmountsHidden
import eth.serotine.domain.usecase.UpgradeWarning
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeFreshness
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.FakeNames
import eth.serotine.ui.FakeTokenBalances
import eth.serotine.ui.FakeTokenCatalog
import eth.serotine.ui.FakeWalletHandoff
import eth.serotine.ui.MemoryAmountsHidden
import eth.serotine.ui.MemoryCurrencySetting
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.MemoryUserTokens
import eth.serotine.ui.components.AMOUNT_MASK
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.HandoffChoice
import eth.serotine.ui.components.LightClientHealth
import eth.serotine.ui.components.LightClientHealth.NOT_VERIFIED
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NO_WALLET
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.update.UpgradeBannerItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import eth.serotine.ui.MemoryOutbox
import eth.serotine.domain.usecase.AcknowledgeLostOutbox
import eth.serotine.domain.usecase.ObserveOutbox
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.QueueConflict
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.DraftBasis
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Eip1559Transaction

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {

    private val repository = FakeAccountRepository()
    private val balances = FakeBalances()
    private val lightClient = FakeLightClient()

    /** The networks the user let catch up from a stale checkpoint. */
    private val consented = mutableListOf<Network>()
    private val consent = object : StaleAnchorConsent {
        override fun syncPastStaleAnchor(network: Network) {
            consented += network
        }
    }
    private val upgrades = FakeNetworkUpgrades()
    /** A light client serving verified reads: what a verified balance is read at. */
    private val synced = LightClientStatus.Synced(headBlock = 11_923_074, headTimestamp = null, finalized = null)

    private val starting = LightClientItem.from(LightClientStatus.Starting, 0, network = "Sepolia")
    private var now = 1_792_100_000L
    private val feeds = FakeFeeds()
    private val kind = MutableStateFlow<KindReading>(KindReading.Syncing)
    private val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> = kind
    }
    private val currency = MemoryCurrencySetting()
    private val names = FakeNames()
    private val networks = MemoryNetworkSettings()

    /** Sepolia's section with [accounts] under [status]. */
    private fun sepolia(
        status: LightClientItem,
        accounts: List<AccountItem>,
        total: String? = "Checking…",
        totalNote: String? = "Testnet prices · still checking",
    ) = NetworkSection(Network.SEPOLIA.chainId, "Sepolia", testnet = true, on = true, status, accounts, total, totalNote)

    private fun viewModel(
        hiddenSetting: AmountsHiddenSetting = amountsHidden,
        offerHandoff: OfferHandoff? = null,
    ): AccountsViewModel {
        val currencies = ObserveDisplayCurrencies(feeds, currency, repository)
        val value = ObserveAccountValue(
            ObserveNativeBalance(balances),
            ObserveTokenHoldings(
                ObserveKnownTokens(FakeTokenCatalog(), MemoryUserTokens(), FakeMetadata()),
                FakeTokenBalances(),
            ),
            ObserveUsdPrice(feeds),
            currencies,
        )
        return AccountsViewModel(
            ObservePortfolio(ObserveAccounts(repository), value, currencies),
            ObserveSafes(repository, ObserveSafe(kinds, repository)),
            ObserveEnsNames(repository, names),
            ObserveLightClient(lightClient),
            ObserveEnabledNetworks(networks),
            SetNetworkEnabled(networks),
            ObserveUpgradeWarnings(upgrades, NoRecord, lightClient, networks, repository) { now },
            SyncPastStaleAnchor(lightClient, consent),
            RefreshNetworkData(freshness),
            ObserveNetworkLive(freshness),
            ObserveAmountsHidden(hiddenSetting),
            ToggleAmountsHidden(hiddenSetting),
            ObserveOutbox(outbox),
            AcknowledgeLostOutbox(outbox),
            clock = { now },
            formatTime = { "<$it>" },
            offerHandoff = offerHandoff,
        )
    }

    private val freshness = FakeFreshness()

    /** Lenient mode's outbox, empty unless a test queues something. */
    private val outbox = MemoryOutbox()

    private val amountsHidden = MemoryAmountsHidden()

    private val treasury =
        Account(
            Network.SEPOLIA,
            Address.of("0x" + "ab".repeat(20)),
            AccountKind.SAFE,
            "Treasury",
            AccountCustody.InKeyStore,
        )
    private val daily = Account(
        Network.SEPOLIA,
        Address.of("0x" + "cd".repeat(20)),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )

    private fun treasuryItem(balance: String, verified: Boolean) =
        AccountItem(
            "Treasury",
            Eip55.format(treasury.address),
            Network.SEPOLIA.chainId,
            AccountKind.SAFE,
            Control.WATCH_ONLY,
            balance,
            verified,
            kindNote = "Safe",
            network = NetworkLabel.of(Network.SEPOLIA),
            networkLine = "Sepolia · Safe · test network",
            accountTotal = "no real value",
        )

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun loadsUntilTheFirstEmissionThenMirrorsTheAccountsWithTheirBalances() = runTest {
        // viewModelScope runs on Dispatchers.Main; route it through this test's scheduler.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()

        // Nothing collects yet: WhileSubscribed has not started the upstream.
        assertEquals(AccountsUiState.Loading(), viewModel.uiState.value)

        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(AccountsUiState.Ready(emptyList()), viewModel.uiState.value)

        repository.accounts = listOf(treasury)
        runCurrent()
        assertEquals(
            AccountsUiState.Ready(
                listOf(sepolia(starting, listOf(treasuryItem("Syncing…", verified = false)))),
                total = "Checking…",
                totalLabel = "Total · USD · Sepolia",
                totalNote = "Testnet prices · still checking",
                freshness = "Syncing Sepolia · starting",
            ),
            viewModel.uiState.value,
        )

        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("2500000000000000000"), 9)
        runCurrent()
        assertEquals(
            treasuryItem("2.5 ETH", verified = true).copy(value = "Checking price…"),
            (viewModel.uiState.value as AccountsUiState.Ready).accounts.single(),
        )
    }

    @Test
    fun aWatchedEoaIsOfferedSendFromAnotherWalletAndAKeyHeldHereOrASafeIsNot() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val watched =
            Account(Network.SEPOLIA, Address.of("0x" + "cd".repeat(20)), AccountKind.EOA, "Cold", AccountCustody.Watched)
        val held = watched.copy(
            address = Address.of("0x" + "ef".repeat(20)),
            label = "Daily",
            custody = AccountCustody.InKeyStore,
        )
        repository.accounts = listOf(watched, held, treasury)
        val wallets = FakeWalletHandoff()
        val viewModel = viewModel(offerHandoff = OfferHandoff(wallets))
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val rows = (viewModel.uiState.value as AccountsUiState.Ready).accounts.associateBy { it.label }
        assertEquals(HandoffChoice(), rows.getValue("Cold").handoff, "offered")
        assertEquals(null, rows.getValue("Daily").handoff, "Send signs for it")
        assertEquals(null, rows.getValue("Treasury").handoff, "a link cannot sign a Safe transaction")

        // No wallet app: shown greyed out, with what to install.
        wallets.availability = HandoffAvailability.NO_WALLET
        balances.of(watched.address).value = BalanceReading.Verified(Wei.of("1"), 9)
        runCurrent()
        val cold = (viewModel.uiState.value as AccountsUiState.Ready).accounts.first { it.label == "Cold" }
        assertEquals(HandoffChoice(NO_WALLET), cold.handoff)
    }

    @Test
    fun aRowIsAlwaysShownInTheCurrencyItsValueWasComputedIn() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 9)
        feeds.answer("ETH", 2_000.0)
        feeds.answer("EUR", 1.25)
        runCurrent()
        val before = (viewModel.uiState.value as AccountsUiState.Ready).accounts.single().value
        assertEquals("$2,000.00 · testnet prices", before)

        currency.choose(DisplayCurrency.EUR)
        runCurrent()
        val values = (viewModel.uiState.value as AccountsUiState.Ready).accounts.map { it.value }
        assertEquals(listOf("€1,600.00 · testnet prices"), values, "never $2,000 marked €")
    }

    @Test
    fun theHeaderTotalsEveryAccountInTheChosenCurrency() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury, daily)
        currency.choose(DisplayCurrency.JPY)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("2500000000000000000"), 9)
        balances.of(daily.address).value = BalanceReading.Verified(Wei.of("500000000000000000"), 9)
        feeds.answer("ETH", 2_000.0)
        feeds.answer("JPY", 0.0064) // USD per yen
        runCurrent()

        val ready = viewModel.uiState.value as AccountsUiState.Ready
        assertEquals("¥937,500", ready.total)
        assertEquals("Testnet prices", ready.totalNote)
        assertEquals(
            listOf("¥781,250 · testnet prices", "¥156,250 · testnet prices"),
            ready.accounts.map { it.value },
        )
    }

    @Test
    fun hiddenAmountsMaskEveryAmountUntilTheEyeOpensAgain() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury, daily)
        currency.choose(DisplayCurrency.JPY)
        amountsHidden.hidden.value = true
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("2500000000000000000"), 9)
        balances.of(daily.address).value = BalanceReading.Unavailable(ReadUnavailable.UNPROVEN)
        feeds.answer("ETH", 2_000.0)
        feeds.answer("JPY", 0.0064)
        runCurrent()

        val hidden = viewModel.uiState.value as AccountsUiState.Ready
        assertTrue(hidden.amountsHidden)
        assertEquals("¥$AMOUNT_MASK", hidden.total)
        hidden.sections.forEach { assertFalse(it.total.orEmpty().any(Char::isDigit), "${it.name}: ${it.total}") }
        // An amount is masked beside its unit; what is not one ("Not verified") says what it says.
        assertEquals(listOf("$AMOUNT_MASK ETH", "Not verified"), hidden.accounts.map { it.balance })
        assertEquals("¥$AMOUNT_MASK · testnet prices", hidden.accounts.first().value)
        // On a testnet an account has no real value: nothing to mask.
        assertEquals("no real value", hidden.accounts.first().accountTotal)

        // The eye opens: saved, and the amounts are back.
        viewModel.toggleAmounts()
        runCurrent()
        assertFalse(amountsHidden.hidden.value)
        val shown = viewModel.uiState.value as AccountsUiState.Ready
        assertFalse(shown.amountsHidden)
        assertEquals("¥781,250", shown.total)
        assertEquals("2.5 ETH", shown.accounts.first().balance)

        viewModel.toggleAmounts()
        runCurrent()
        assertTrue(amountsHidden.hidden.value)
    }

    @Test
    fun nothingIsDrawnBeforeTheChoiceIsRead() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury)
        val unread = object : AmountsHiddenSetting {
            override fun observe(): Flow<Boolean> = flow { awaitCancellation() }
            override suspend fun set(hidden: Boolean) = true
        }
        val viewModel = viewModel(unread)
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("2500000000000000000"), 9)
        runCurrent()

        // A balance hidden before must never show for a moment: the list waits for the choice.
        assertTrue(viewModel.uiState.value is AccountsUiState.Loading)
    }

    @Test
    fun eachRowFollowsItsOwnAccountsBalance() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury, daily)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        balances.of(daily.address).value = BalanceReading.Unavailable(ReadUnavailable.UNPROVEN)
        runCurrent()

        val rows = (viewModel.uiState.value as AccountsUiState.Ready).accounts
        assertEquals(listOf("Syncing…", "Not verified"), rows.map { it.balance })
    }

    @Test
    fun aBalanceLastVerifiedIsShownDimmedAndDatedAndLeftOutOfTheTotal() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        feeds.answer("ETH", 2_000.0)

        balances.of(treasury.address).value =
            BalanceReading.LastKnown(Wei.of("2500000000000000000"), 9, asOf = 1_792_100_000)
        runCurrent()

        val ready = viewModel.uiState.value as AccountsUiState.Ready
        assertEquals(
            treasuryItem("2.5 ETH", verified = false)
                .copy(
                    balanceNote = "Last verified <1792100000> · may have changed since",
                    ageNote = "just now · may have changed",
                ),
            ready.accounts.single(),
        )
        assertEquals("Checking…", ready.total, "a balance that may have changed is not added up")
    }

    @Test
    fun eachSectionsStatusFollowsItsNetworksLightClient() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(daily)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        lightClient.status.value = LightClientStatus.CatchingUp(done = 1, total = 4)
        runCurrent()

        assertEquals(
            LightClientItem(
                "Verifying sync committees · 1 of 4 periods",
                0.25f,
                true,
                NOT_VERIFIED,
                simpleText = "Syncing Sepolia · 1 of 4 periods",
            ),
            viewModel.sepoliaStatus,
        )
    }

    @Test
    fun aLightClientRefusingAStaleCheckpointIsWarnedOfAndCaughtUpOnlyOnTheUsersWord() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(daily)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        lightClient.status.value = LightClientStatus.StaleAnchor(anchorAgePeriods = 6, boundPeriods = 3)
        runCurrent()

        val warning = assertNotNull(viewModel.sepoliaSection.staleAnchor)
        assertEquals(
            "Serotine's last trusted checkpoint for Sepolia is about 7 days old, older than the 3 days it can " +
                "safely catch up from on its own. Nothing on Sepolia is verified until you decide.",
            warning.banner,
        )
        assertEquals("Catch up with Sepolia anyway?", warning.title)
        assertTrue(warning.body.contains("cannot reveal your keys or move your funds by itself"), warning.body)
        assertTrue(consented.isEmpty(), "never without the user")

        viewModel.syncPastStaleAnchor(Network.SEPOLIA.chainId)
        runCurrent()
        assertEquals(listOf(Network.SEPOLIA), consented)

        lightClient.status.value = LightClientStatus.CatchingUp(done = 1, total = 6)
        runCurrent()
        assertNull(viewModel.sepoliaSection.staleAnchor, "the warning goes once it catches up")
        viewModel.syncPastStaleAnchor(Network.SEPOLIA.chainId)
        runCurrent()
        assertEquals(listOf(Network.SEPOLIA), consented, "nothing asked of a light client that is not refusing")
    }

    @Test
    fun aSectionDatesTheHeadBlockAndKeepsItCurrent() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(daily)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        lightClient.status.value =
            LightClientStatus.Synced(headBlock = 11_923_074, headTimestamp = now - 3, finalized = null)
        runCurrent()
        assertEquals("Verified at block 11,923,074 · 3 s ago", viewModel.sepoliaStatus.text)

        now += 5
        advanceTimeBy(1_001)
        assertEquals("Verified at block 11,923,074 · 8 s ago", viewModel.sepoliaStatus.text)
    }

    private val AccountsViewModel.sepoliaStatus: LightClientItem
        get() = sepoliaSection.status

    private val AccountsViewModel.sepoliaSection: NetworkSection
        get() = (uiState.value as AccountsUiState.Ready).sections.single()

    @Test
    fun aNetworkThatIsOffKeepsItsAccountsListedWithTheirLastBalanceOutOfEveryTotal() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury, daily)
        networks.on.value = emptySet()
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(treasury.address).value =
            BalanceReading.LastKnown(Wei.of("2500000000000000000"), 9, asOf = 1_792_100_000)
        balances.of(daily.address).value = BalanceReading.Unavailable(ReadUnavailable.NETWORK_OFF)
        feeds.answer("ETH", 2_000.0)
        runCurrent()

        val ready = viewModel.uiState.value as AccountsUiState.Ready
        val section = ready.sections.single()
        assertEquals(false, section.on)
        assertEquals("Off", section.total)
        assertEquals(
            LightClientItem("Off in this wallet · nothing is checked", null, false, LightClientHealth.NOT_RUNNING),
            section.status,
        )
        assertEquals(
            listOf("2.5 ETH" to "Last verified <1792100000> · Sepolia is off", "Not checked" to "Sepolia is off"),
            section.accounts.map { it.balance to it.balanceNote },
        )
        assertEquals(listOf(null, null), section.accounts.map { it.value })
        assertNull(ready.total, "nothing on a network that is on to add up")
        val row = section.accounts[1]
        assertEquals(false, row.canSend)
        assertEquals("Sepolia", row.networkOff)

        // A setting that cannot be saved leaves it off, and says so once.
        networks.failSaves = true
        viewModel.turnOnNetwork(Network.SEPOLIA.chainId)
        runCurrent()
        assertEquals(
            "Sepolia could not be turned on: the setting cannot be saved right now. Try again, or in " +
                "Settings → Networks.",
            (viewModel.uiState.value as AccountsUiState.Ready).message,
        )
        viewModel.messageShown()
        runCurrent()
        assertEquals(null, (viewModel.uiState.value as AccountsUiState.Ready).message)

        networks.failSaves = false
        viewModel.turnOnNetwork(Network.SEPOLIA.chainId)
        runCurrent()
        assertEquals(setOf(Network.SEPOLIA), networks.on.value)
        assertEquals(true, (viewModel.uiState.value as AccountsUiState.Ready).sections.single().on)
    }

    @Test
    fun aRowShowsUpAtOnceAndSaysItIsCheckingUntilItsFirstReading() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury)
        balances.silent += treasury.address
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val row = (viewModel.uiState.value as AccountsUiState.Ready).accounts.single()
        assertEquals("Checking…", row.balance)
        assertEquals(false, row.balanceVerified)
    }

    @Test
    fun aStoredListThatIsNotWhatIsShownIsSaidSo() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        repository.list.value = AccountList.Unreadable(AccountList.Reason.NEWER_FORMAT)
        runCurrent()
        assertEquals(
            AccountsUiState.ListUnavailable(
                "Your account list was saved by a newer version of Serotine. Update the app to see it.",
            ),
            viewModel.uiState.value,
            "its own state, not an empty list",
        )

        repository.list.value = AccountList.Loaded(emptyList(), damagedListSetAside = true)
        runCurrent()
        assertEquals(
            "Your saved account list was damaged and has been set aside. Add your accounts again.",
            (viewModel.uiState.value as AccountsUiState.Ready).notice,
        )

        // Accounts added since the list was set aside: the notice stays, beside their balances.
        repository.list.value = AccountList.Loaded(listOf(treasury), damagedListSetAside = true)
        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 9)
        runCurrent()
        val ready = viewModel.uiState.value as AccountsUiState.Ready
        assertEquals(
            "Your saved account list was damaged and has been set aside. Add your accounts again.",
            ready.notice,
        )
        assertEquals(listOf("1 ETH"), ready.accounts.map { it.balance })
    }

    @Test
    fun aListThatBecomesReadableAgainShowsItsRowsWithBalances() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.list.value = AccountList.Unreadable(AccountList.Reason.READ_FAILED)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(AccountsUiState.ListUnavailable::class, viewModel.uiState.value::class)

        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("2500000000000000000"), 9)
        repository.list.value = AccountList.Loaded(listOf(treasury))
        runCurrent()

        val ready = viewModel.uiState.value as AccountsUiState.Ready
        assertEquals(listOf("2.5 ETH"), ready.accounts.map { it.balance })
        assertEquals(null, ready.notice)
    }

    @Test
    fun anUpgradePeersReportIsABannerAboveTheList() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(emptyList(), viewModel.uiState.value.upgrades)

        val ahead = NetworkUpgrade(NetworkUpgrade.Phase.SCHEDULED, now + 86_400, "0x6c1d9423", 5)
        upgrades.report.value = ahead
        runCurrent()

        assertEquals(
            listOfNotNull(UpgradeBannerItem.from(UpgradeWarning.Ahead(ahead), Network.SEPOLIA) { "<$it>" }),
            viewModel.uiState.value.upgrades,
            "Sepolia's banner alone: Ethereum's peers report nothing",
        )
        assertEquals(
            "Network upgrade ahead on Sepolia: update Serotine",
            viewModel.uiState.value.upgrades.single().title,
        )
    }

    @Test
    fun anUnreadableListStillShowsTheUpgradeBanner() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        repository.list.value = AccountList.Unreadable(AccountList.Reason.READ_FAILED)
        upgrades.report.value = NetworkUpgrade(NetworkUpgrade.Phase.SCHEDULED, now + 86_400, "0x6c1d9423", 5)
        runCurrent()

        val state = viewModel.uiState.value
        assertIs<AccountsUiState.ListUnavailable>(state)
        assertEquals("Network upgrade ahead on Sepolia: update Serotine", state.upgrades.single().title)
    }

    @Test
    fun accountItemShowsTheChecksummedAddressShortenedForTheRow() {
        val item = AccountItem.from(
            Account(
                Network.SEPOLIA,
                Address.of("0xd8da6bf26964af9d7eed9e03e53415d37aa96045"),
                AccountKind.EOA,
                "Vitalik",
                AccountCustody.InKeyStore,
            ),
            BalanceReading.Syncing,
            Network.SEPOLIA,
        )
        assertEquals("0xd8dA…6045", item.shortAddress)
        assertEquals("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", item.address)
        assertEquals(Control.FULL_CONTROL, item.control)
    }

    @Test
    fun aSafeIsNeverShownWithMoreControlThanIsKnown() {
        val item = AccountItem.from(
            Account(
                Network.SEPOLIA,
                Address.of("0x" + "5a".repeat(20)),
                AccountKind.SAFE,
                "Family",
                AccountCustody.InKeyStore,
            ),
            null,
            Network.SEPOLIA,
        )
        assertEquals(Control.WATCH_ONLY, item.control)
    }

    @Test
    fun aWatchedAccountIsWatchOnly() {
        val item = AccountItem.from(
            Account(
                Network.SEPOLIA,
                Address.of("0x" + "ee".repeat(20)),
                AccountKind.EOA,
                "Savings",
                AccountCustody.Watched,
            ),
            null,
            Network.SEPOLIA,
        )
        assertEquals(Control.WATCH_ONLY, item.control)
        assertEquals("Checking…", item.balance)
    }

    @Test
    fun aVerifiedSafeShowsItsThresholdOfOwnersAndTheControlTheKeysHeldHereGive() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury, daily)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        val before = (viewModel.uiState.value as AccountsUiState.Ready).accounts.first()
        assertEquals(Control.WATCH_ONLY, before.control, "not read yet: never more than watch-only")
        assertEquals(null, before.signers)

        val other = Address.of("0x" + "ee".repeat(20))
        val state = SafeState("1.4.1", l2 = false, listOf(daily.address, other), 1, 0, emptyList(), guard = null)
        kind.value = KindReading.Verified(AddressKind.Safe(state), 9)
        runCurrent()

        val row = (viewModel.uiState.value as AccountsUiState.Ready).accounts.first()
        assertEquals("1/2", row.signers)
        assertEquals("Safe", row.kindNote)
        assertEquals(Control.FULL_CONTROL, row.control, "Daily's key is held here, and one signature is enough")

        // The address no longer verifies as a Safe: said, and no more than watch-only.
        kind.value = KindReading.Verified(AddressKind.Contract, 10)
        runCurrent()
        val after = (viewModel.uiState.value as AccountsUiState.Ready).accounts.first()
        assertEquals("Not a recognised Safe", after.kindNote)
        assertEquals(Control.WATCH_ONLY, after.control)
        assertEquals(null, after.signers)
    }

    @Test
    fun anAccountNamedByAnEnsNameShowsItUntilTheNameNoLongerPointsToIt() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val savings = Account(
            Network.SEPOLIA,
            Address.of("0x" + "ef".repeat(20)),
            AccountKind.EOA,
            "savings.eth",
            AccountCustody.Watched,
            ensName = "savings.eth",
        )
        repository.accounts = listOf(savings)
        names.names["savings.eth"] = NameResolution.Resolved("savings.eth", savings.address, 90)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val named = (viewModel.uiState.value as AccountsUiState.Ready).accounts.single()
        assertEquals("savings.eth", named.label)
        assertEquals(null, named.nameNote)

        // Checked again after the interval: the name points elsewhere now.
        names.names["savings.eth"] = NameResolution.Resolved("savings.eth", daily.address, 140)
        advanceTimeBy(10.minutes + 1.seconds)
        val moved = (viewModel.uiState.value as AccountsUiState.Ready).accounts.single()
        assertEquals(shortAddress(Eip55.format(savings.address)), moved.label)
        assertEquals("ENS name “savings.eth” no longer points to this address", moved.nameNote)
    }

    private class FakeAccountRepository : AccountRepository {
        val list = MutableStateFlow<AccountList>(AccountList.Loaded(emptyList()))
        var accounts: List<Account>
            get() = (list.value as AccountList.Loaded).accounts
            set(value) {
                list.value = AccountList.Loaded(value)
            }
        override fun observeAccounts(): Flow<AccountList> = list
        override suspend fun add(account: Account): AccountAdd = error("not used by this test")
        override suspend fun rename(id: AccountId, label: String): AccountChange = error("not used by this test")
        override suspend fun remove(id: AccountId): AccountChange = error("not used by this test")
        override suspend fun setCustody(id: AccountId, custody: AccountCustody): AccountChange =
            error("not used by this test")
    }

    /** Sepolia's peers report [report]; every other network's, nothing. */
    private class FakeNetworkUpgrades : NetworkUpgrades {
        val report = MutableStateFlow<NetworkUpgrade?>(null)
        override fun observeUpgrade(network: Network): Flow<NetworkUpgrade?> =
            if (network == Network.SEPOLIA) report else flowOf(null)
    }

    private object NoRecord : KnownUpgradeStore {
        override fun observe(network: Network): Flow<KnownUpgrade?> = flowOf(null)
        override suspend fun save(network: Network, record: KnownUpgrade): Boolean = error("not used by this test")
    }

    private class FakeLightClient : LightClient {
        val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)

        /** A network's own status, where a test gives it one; [status] otherwise. */
        val of = mutableMapOf<Network, MutableStateFlow<LightClientStatus>>()
        override val datesFinalizedBlocks = true
        override fun observeStatus(network: Network): Flow<LightClientStatus> = of[network] ?: status
    }

    private class FakeBalances : NativeBalances {
        private val readings = mutableMapOf<Address, MutableStateFlow<BalanceReading>>()

        /** Addresses whose balance stream has not emitted yet. */
        val silent = mutableSetOf<Address>()

        fun of(address: Address) = readings.getOrPut(address) { MutableStateFlow(BalanceReading.Syncing) }

        override fun observeBalance(network: Network, address: Address): Flow<BalanceReading> =
            if (address in silent) emptyFlow<BalanceReading>().onStart { awaitCancellation() } else of(address)
    }

    @Test
    fun aRefreshProvesEveryNetworkAgain() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        assertTrue(freshness.refreshed.isEmpty(), "opening asks nothing: the list's own reads are fresh")

        viewModel.refresh()
        assertEquals(Network.entries.toList(), freshness.refreshed.toList())
    }

    /** A real-funds account on Ethereum, whose key is held here. */
    private val ethereumMain = Account(
        Network.ETHEREUM,
        Address.of("0x" + "e1".repeat(20)),
        AccountKind.EOA,
        "Main",
        AccountCustody.InKeyStore,
    )

    @Test
    fun simpleModeSaysHowOldTheValuesOfANetworkThatIsNotLiveAre() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lightClient.status.value = synced
        networks.on.value = Network.entries.toSet()
        repository.accounts = listOf(ethereumMain)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(ethereumMain.address).value =
            BalanceReading.Verified(Wei.of("1000000000000000000"), 9, provenAt = now - 120)
        runCurrent()

        assertEquals(
            "Updated 2 min ago · pull down to refresh",
            (viewModel.uiState.value as AccountsUiState.Ready).freshness,
        )
    }

    @Test
    fun simpleModeSaysLiveWhenEveryNetworkShownUpdatesLive() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lightClient.status.value = synced
        freshness.live.value = setOf(Network.SEPOLIA)
        repository.accounts = listOf(treasury)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(treasury.address).value =
            BalanceReading.Verified(Wei.of("1000000000000000000"), 9, provenAt = now - 600)
        runCurrent()

        assertEquals("Live", (viewModel.uiState.value as AccountsUiState.Ready).freshness)
    }

    @Test
    fun aRefreshSaysCheckingUntilANewerProofArrives() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lightClient.status.value = synced
        networks.on.value = Network.entries.toSet()
        repository.accounts = listOf(ethereumMain)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        val balance = balances.of(ethereumMain.address)
        balance.value = BalanceReading.Verified(Wei.of("1000000000000000000"), 9, provenAt = now - 120)
        runCurrent()

        viewModel.refresh()
        runCurrent()
        val refreshing = viewModel.uiState.value as AccountsUiState.Ready
        assertTrue(refreshing.refreshing)
        assertEquals("Checking Ethereum…", refreshing.freshness)

        balance.value = BalanceReading.Verified(Wei.of("1000000000000000000"), 12, provenAt = now)
        runCurrent()
        val done = viewModel.uiState.value as AccountsUiState.Ready
        assertEquals(false, done.refreshing)
        assertEquals("Updated just now · pull down to refresh", done.freshness)
    }

    @Test
    fun aNetworkShowingBalancesFromBeforeGetsABanner() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        networks.on.value = Network.entries.toSet()
        repository.accounts = listOf(ethereumMain)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(ethereumMain.address).value =
            BalanceReading.LastKnown(Wei.of("1000000000000000000"), 9, asOf = now - 3 * 3_600)
        runCurrent()

        val ready = viewModel.uiState.value as AccountsUiState.Ready
        assertEquals(
            listOf(
                UnreachableNetwork(
                    Network.ETHEREUM.chainId,
                    "Ethereum is still syncing",
                    "Its balances were last confirmed 3 h ago and may have changed. Sending from its accounts " +
                        "waits until it has caught up.",
                ),
            ),
            ready.unreachable,
            "starting: on its way, not stuck",
        )
        assertEquals("Syncing Ethereum · starting", ready.freshness)
        assertEquals("3 h ago · may have changed", ready.accounts.single().ageNote)

        lightClient.status.value = LightClientStatus.WaitingForHead(headAgeSeconds = 600)
        runCurrent()

        val stuck = viewModel.uiState.value as AccountsUiState.Ready
        assertEquals(
            listOf(
                UnreachableNetwork(
                    Network.ETHEREUM.chainId,
                    "Can't check Ethereum right now",
                    "Its balances were last confirmed 3 h ago and may have changed. Sending from its accounts " +
                        "waits until they can be checked. The app keeps trying.",
                ),
            ),
            stuck.unreachable,
        )
        assertEquals("1 Ethereum account not counted", ready.simpleTotalNote, "said instead of still checking")
        assertEquals("Still checking", ready.totalNote, "Detailed view's note as before")
    }

    @Test
    fun aNetworkThatSyncsIsSaidWithTheAgeOfWhatTheOthersShow() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        networks.on.value = Network.entries.toSet()
        lightClient.status.value = synced
        lightClient.of[Network.SEPOLIA] = MutableStateFlow(LightClientStatus.CatchingUp(done = 2, total = 6))
        repository.accounts = listOf(ethereumMain, daily)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(ethereumMain.address).value =
            BalanceReading.Verified(Wei.of("1000000000000000000"), 9, provenAt = now - 120)
        runCurrent()

        assertEquals(
            "Syncing Sepolia · 2 of 6 periods · updated 2 min ago",
            (viewModel.uiState.value as AccountsUiState.Ready).freshness,
            "Ethereum's balances count in the total: their age stays in sight",
        )
    }

    @Test
    fun theTotalStillSaysItIsCheckingWhileAnotherNetworkIsReadBesideOneFromBefore() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        networks.on.value = Network.entries.toSet()
        val gnosisSpare = Account(
            Network.GNOSIS,
            Address.of("0x" + "e2".repeat(20)),
            AccountKind.EOA,
            "Spare",
            AccountCustody.InKeyStore,
        )
        repository.accounts = listOf(ethereumMain, gnosisSpare)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(ethereumMain.address).value =
            BalanceReading.LastKnown(Wei.of("1000000000000000000"), 9, asOf = now - 3 * 3_600)
        runCurrent()

        val ready = viewModel.uiState.value as AccountsUiState.Ready
        assertEquals("1 Ethereum account not counted · still checking", ready.simpleTotalNote)

        balances.of(gnosisSpare.address).value = BalanceReading.Verified(Wei.of("0"), 12, provenAt = now)
        runCurrent()

        val read = viewModel.uiState.value as AccountsUiState.Ready
        assertEquals("1 Ethereum account not counted", read.simpleTotalNote, "nothing else is being read")
    }

    @Test
    fun aNetworkWhoseLightClientIsStuckGetsABannerWithoutBalancesFromBefore() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        networks.on.value = Network.entries.toSet()
        repository.accounts = listOf(ethereumMain)
        lightClient.status.value = LightClientStatus.WaitingForHead(headAgeSeconds = 600)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(
            "Its balances can't be read yet. Sending from its accounts waits until they can be checked. " +
                "The app keeps trying.",
            (viewModel.uiState.value as AccountsUiState.Ready).unreachable.single().text,
        )
    }

    @Test
    fun anAccountOnANetworkThatIsOffSaysSoBesideItsAge() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        networks.on.value = emptySet()
        repository.accounts = listOf(ethereumMain)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(ethereumMain.address).value =
            BalanceReading.LastKnown(Wei.of("1000000000000000000"), 9, asOf = now - 3 * 3_600)
        runCurrent()

        val row = (viewModel.uiState.value as AccountsUiState.Ready).accounts.single()
        assertEquals("Ethereum is off · 3 h ago", row.ageNote)
    }

    @Test
    fun aRefreshOnLiveNetworksAloneShowsNoSpinner() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        freshness.live.value = setOf(Network.SEPOLIA)
        repository.accounts = listOf(treasury)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(treasury.address).value =
            BalanceReading.Verified(Wei.of("1000000000000000000"), 9, provenAt = now - 10)
        runCurrent()

        viewModel.refresh()
        runCurrent()
        assertEquals(false, (viewModel.uiState.value as AccountsUiState.Ready).refreshing)
    }

    @Test
    fun aStaleCheckpointHasItsOwnWarningAndNoKeepsTryingBanner() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        networks.on.value = Network.entries.toSet()
        repository.accounts = listOf(ethereumMain)
        lightClient.status.value = LightClientStatus.StaleAnchor(anchorAgePeriods = 6, boundPeriods = 3)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(ethereumMain.address).value =
            BalanceReading.LastKnown(Wei.of("1000000000000000000"), 9, asOf = now - 3_600)
        runCurrent()

        val ready = viewModel.uiState.value as AccountsUiState.Ready
        assertTrue(ready.unreachable.isEmpty(), "only the user's word lets it catch up: no retry is promised")
        assertNotNull(ready.sections.single().staleAnchor)
    }

    // Lenient mode's outbox (docs/lenient-mode.md, *Where it shows*; owner rulings, 2026-11-06).

    /** A transfer queued from Daily, in conflict when [conflict] says so. */
    private fun queuedFromDaily(id: String, conflict: QueueConflict? = null) = QueuedTransaction(
        network = Network.SEPOLIA,
        account = daily.address,
        id = id,
        action = SentAction.Transfer(treasury.address, TransferAsset.Native, UInt256.of(1)),
        level = FeeLevel.NORMAL,
        transaction = Eip1559Transaction(
            chainId = Network.SEPOLIA.chainId,
            nonce = 1,
            maxPriorityFeePerGas = UInt256.of(1),
            maxFeePerGas = UInt256.of(2),
            gasLimit = 50_000,
            to = treasury.address,
            value = UInt256.of(1),
        ),
        signed = SignedTransfer(byteArrayOf(2, 1)).takeIf { conflict == null },
        maxCost = UInt256.of(100_001),
        signedAt = now - 60,
        builtOn = DraftBasis(1, null, null, null, 1, null, 1, null),
        conflict = conflict,
    )

    @Test
    fun whatANetworkHasQueuedIsSaidUnderItsLineInBothViews() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(daily)
        outbox.list.value = OutboxList.Loaded(listOf(queuedFromDaily("0x" + "9a".repeat(32))))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val ready = assertIs<AccountsUiState.Ready>(viewModel.uiState.value)
        assertEquals("1 waiting to send · Keep Serotine open to send it sooner", ready.sections.single().queued)
        assertEquals(listOf("Sepolia: 1 waiting to send · Keep Serotine open to send it sooner"), ready.queued)

        // Its light client verifies: it is checked and sent shortly, with nothing to ask of the user.
        lightClient.status.value = LightClientStatus.Synced(headBlock = 9, headTimestamp = now, finalized = null)
        runCurrent()
        assertEquals(
            "1 waiting to send",
            assertIs<AccountsUiState.Ready>(viewModel.uiState.value).sections.single().queued,
        )

        outbox.list.value = OutboxList.Loaded(
            listOf(queuedFromDaily("0x" + "9b".repeat(32), QueueConflict.InsufficientFunds)),
        )
        runCurrent()
        assertEquals(
            "1 needs your signature again",
            assertIs<AccountsUiState.Ready>(viewModel.uiState.value).sections.single().queued,
        )
    }

    @Test
    fun queuedBytesLostAreSaidOnceUntilDismissed() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(daily)
        outbox.list.value = OutboxList.Loaded(emptyList(), lost = true)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val notice = assertNotNull(assertIs<AccountsUiState.Ready>(viewModel.uiState.value).lostNotice)
        assertTrue(notice.startsWith("A transaction waiting to be sent was lost"), notice)

        viewModel.dismissLost()
        runCurrent()
        assertNull(assertIs<AccountsUiState.Ready>(viewModel.uiState.value).lostNotice)
    }

    @Test
    fun oneQueuedPastItsHourIsSaidAsWaitingForTheUsersAnswerNotAsWaitingToSend() {
        val past = queuedFromDaily("0x" + "9a".repeat(32)).copy(signedAt = 1_000)
        assertEquals(
            "1 waits for your answer: still send it?",
            AccountsViewModel.queuedLine(listOf(past), verifies = true, now = 1_000 + QUEUED_UNATTENDED_SECONDS + 1),
        )
        // A tap gives it an hour of its own.
        val tapped = past.copy(confirmedAt = 1_000 + QUEUED_UNATTENDED_SECONDS)
        assertEquals(
            "1 waiting to send",
            AccountsViewModel.queuedLine(listOf(tapped), verifies = true, now = 1_000 + QUEUED_UNATTENDED_SECONDS + 1),
        )
    }

    @Test
    fun anOutboxThatCannotBeReadIsSaidOnHome() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(daily)
        outbox.list.value = OutboxList.Unreadable
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val notice = assertNotNull(assertIs<AccountsUiState.Ready>(viewModel.uiState.value).notice)
        assertTrue("nothing is sent from this wallet until they can be" in notice, notice)
    }
}
