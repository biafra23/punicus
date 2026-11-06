package eth.serotine.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenTransfers
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.CowApi
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderLife
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.CowPlacement
import eth.serotine.domain.repository.CowQuoteReading
import eth.serotine.domain.repository.CowUnavailable
import eth.serotine.domain.repository.CowVenueReading
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.LogCoverage
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptLog
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.StaleAnchorConsent
import eth.serotine.domain.repository.SwapVenueSetting
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.VenueChoice
import eth.serotine.domain.repository.VenueReading
import eth.serotine.domain.usecase.FollowSafeDeployments
import eth.serotine.domain.usecase.HistoryRecordingSentTransfers
import eth.serotine.domain.usecase.ListDeployedSafe
import eth.serotine.domain.usecase.NetworkDemand
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.ui.accountdetail.ACCOUNT_ADDRESS_TAG
import eth.serotine.ui.accountdetail.ACCOUNT_ASSET_TAG
import eth.serotine.ui.accountdetail.ACCOUNT_QUEUE_ROW_TAG
import eth.serotine.ui.accountdetail.ACCOUNT_REFRESH_TAG
import eth.serotine.ui.accountdetail.ACCOUNT_SWAP_TAG
import eth.serotine.ui.accountdetail.ACCOUNT_TOTAL_TAG
import eth.serotine.ui.accounts.ACCOUNTS_LIST_TAG
import eth.serotine.ui.accounts.ACCOUNTS_REFRESH_TAG
import eth.serotine.ui.accounts.NO_ACCOUNTS_HINT
import eth.serotine.ui.activity.ACTIVITY_ROW_TAG
import eth.serotine.ui.activitydetail.ACTIVITY_DETAIL_FIELD_TAG
import eth.serotine.ui.addtoken.ADD_TOKEN_ADDRESS_TAG
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.LIGHT_CLIENT_STRIP_TAG
import eth.serotine.ui.components.NETWORK_CHOICE_TAG
import eth.serotine.ui.components.SAFE_TX_QR_TAG
import eth.serotine.ui.components.SAFE_TX_REQUEST_TAG
import eth.serotine.ui.components.SEND_AMOUNT_TAG
import eth.serotine.ui.components.SEND_RECIPIENT_TAG
import eth.serotine.ui.components.SEND_REVIEW_TAG
import eth.serotine.ui.components.UNAVAILABLE_REASON_TAG
import eth.serotine.ui.coworder.COW_ORDER_UNISWAP_TAG
import eth.serotine.ui.createsafe.CREATE_SAFE_CONTINUE_TAG
import eth.serotine.ui.createsafe.CREATE_SAFE_DEPLOY_TAG
import eth.serotine.ui.createsafe.CREATE_SAFE_NAME_TAG
import eth.serotine.ui.createsafe.CREATE_SAFE_PASSWORD_TAG
import eth.serotine.ui.createsafe.CREATE_SAFE_THRESHOLD_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_FIELD_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_IMPORT_BUTTON_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_PASSWORD_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_REPEAT_TAG
import eth.serotine.ui.receive.RECEIVE_ADDRESS_TAG
import eth.serotine.ui.renameaccount.RENAME_FIELD_TAG
import eth.serotine.ui.safeimport.IMPORT_REQUEST_BUTTON_TAG
import eth.serotine.ui.safeimport.IMPORT_REQUEST_FIELD_TAG
import eth.serotine.ui.safepropose.PROPOSE_AMOUNT_REVIEWED_TAG
import eth.serotine.ui.safepropose.PROPOSE_PASSWORD_TAG
import eth.serotine.ui.safepropose.PROPOSE_SIGN_TAG
import eth.serotine.ui.safetx.SAFE_TX_HASH_TAG
import eth.serotine.ui.safetx.SAFE_TX_PASSWORD_TAG
import eth.serotine.ui.safetx.SAFE_TX_SHARE_TAG
import eth.serotine.ui.safetx.SAFE_TX_SIGN_TAG
import eth.serotine.ui.send.SEND_AMOUNT_REVIEWED_TAG
import eth.serotine.ui.send.SEND_PASSWORD_TAG
import eth.serotine.ui.send.SEND_SIGN_TAG
import eth.serotine.ui.settings.SETTINGS_DEBUG_SCREEN_TAG
import eth.serotine.ui.settings.SETTINGS_DETAILED_VIEW_TAG
import eth.serotine.ui.settings.SETTINGS_NETWORK_TAG
import eth.serotine.ui.settings.SETTINGS_VERSION_TAG
import eth.serotine.ui.tokens.TOKENS_ROW_TAG
import eth.serotine.ui.transferstatus.TRANSFER_HASH_TAG
import eth.serotine.ui.transferstatus.TRANSFER_SAFE_TAG
import eth.serotine.ui.update.UPDATE_REQUIRED_TAG
import eth.serotine.ui.watch.WATCH_ADDRESS_TAG
import eth.serotine.ui.watch.WATCH_NAME_TAG
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.DappTransactionRequest
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappMetadata
import eth.serotine.domain.repository.DappRequest
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.ui.dapprequest.DAPP_REQUEST_REFUSE_TAG
import eth.serotine.domain.usecase.OutboxLock

/**
 * The shared [App] end to end, over fake ports: the tabs, the account screens and their dialogs,
 * and the update screen that replaces them all while a network upgrade leaves this version behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppNavigationTest {

    // The test drives the UI from its own thread, so that thread must count as the main one,
    // for viewModelScope and for the back stack's lifecycle checks alike. An OUTER rule: the
    // compose rule's teardown still dispatches to Main while it disposes the composition.
    @get:Rule(order = 0)
    val mainDispatcher = object : TestWatcher() {
        override fun starting(description: Description) = Dispatchers.setMain(UnconfinedTestDispatcher())

        override fun finished(description: Description) = Dispatchers.resetMain()
    }

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val accounts = MutableStateFlow<List<Account>>(emptyList())
    private val deletedKeys = mutableListOf<Address>()
    private var failKeyDeletes = false
    private val navController = mutableListOf<NavHostController>()
    private val tokenMetadata = FakeMetadata()
    private val historyStarts = MemoryHistoryStarts()
    private val logIndex = FakeLogIndex()
    private val userTokens = MemoryUserTokens()
    private val report = MutableStateFlow<NetworkUpgrade?>(null)
    private val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)
    private val nativeBalance = MutableStateFlow<BalanceReading>(BalanceReading.Syncing)
    private val recovery = FakeRecovery()
    private val signer = FakeSigner(recovery)
    private val safeTxQueue = MemorySafeTxQueue()
    private val codec = FakeCodec()

    /** Treasury's Safe: Daily and Carol own it, both must sign, and it is at nonce 4. */
    private val treasuryAddress = Address.of("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb")
    private val carol = Address.of("0x" + "33".repeat(20))
    private val broadcaster = FakeBroadcaster()
    private val history = MemoryTransactionHistory()
    private val cowOrders = MemoryCowOrders()
    private val sentRecord = MemorySentTransfers()
    private val sentTransfers = HistoryRecordingSentTransfers(sentRecord, history)
    private val receipts = FakeReceipts()

    /** The Safes deployed in this test, as the light client proves them once their code is there. */
    private val deployed = MutableStateFlow<Map<Address, SafeState>>(emptyMap())

    /** The account list, kept in [accounts]. */
    private val accountRepository = object : AccountRepository {
        override fun observeAccounts(): Flow<AccountList> = accounts.map { AccountList.Loaded(it) }

        override suspend fun add(account: Account): AccountAdd {
            accounts.update { it + account }
            return AccountAdd.Added
        }

        override suspend fun rename(id: AccountId, label: String): AccountChange {
            accounts.update { list ->
                list.map { if (it.id == id) it.copy(label = label, ensName = null) else it }
            }
            return AccountChange.Changed
        }

        override suspend fun setCustody(id: AccountId, custody: AccountCustody): AccountChange {
            accounts.update { list -> list.map { if (it.id == id) it.copy(custody = custody) else it } }
            return AccountChange.Changed
        }

        override suspend fun remove(id: AccountId): AccountChange {
            accounts.update { list -> list.filterNot { it.id == id } }
            return AccountChange.Changed
        }
    }

    /** Treasury is a Safe; any other address is an EOA until a Safe is [deployed] there. */
    private val addressKinds = object : AddressKinds {
        override fun observeKind(
            network: Network,
            address: Address,
        ): Flow<KindReading> = if (address == treasuryAddress) {
            val state = SafeState("1.4.1", l2 = false, listOf(daily.address, carol), 2, 4, emptyList(), null)
            flowOf(KindReading.Verified(AddressKind.Safe(state), 9_412_118))
        } else {
            deployed.map { safes ->
                KindReading.Verified(safes[address]?.let { AddressKind.Safe(it) } ?: AddressKind.Eoa, 9_412_118)
            }
        }
    }

    private val debugLog = FakeDebugLog()
    private val debugScreen = MemoryDebugScreen()
    // These tests were written for the detailed screens; simple mode's are tested on their own.
    private val interfaceMode = MemoryInterfaceMode(InterfaceMode.DETAILED)

    private val dappRequests = FakeDappRequests()

    private fun signInRequest(id: Long) = DappRequest(
        DappRequestId(DappSessionId("c".repeat(64)), id),
        DappMetadata("Example dApp", "https://app.example.org", ""),
        listOf(eth.serotine.domain.model.AccountId(Network.SEPOLIA, Address.of("0x" + "0".repeat(39) + "1"))),
        "eip155:11155111",
        DappAsk.PersonalSign(Address.of("0x" + "0".repeat(39) + "1"), PersonalMessage.of("Hello".encodeToByteArray())!!),
        Long.MAX_VALUE / 2,
    )

    private val graph = AppGraph(
        accounts = accountRepository,
        outbox = Outbox.None,
        outboxLock = OutboxLock(),
        keptTransactionInputs = FakeKeptInputs(),
        syncPolicy = MemorySyncPolicy(),
        keys = object : KeyStore {
            override suspend fun newMnemonic(): NewMnemonic = NewMnemonic.Unavailable

            override suspend fun enableBiometrics(password: WalletPassword, confirmation: String): BiometricChange =
                BiometricChange.NotOnThisDevice

            override suspend fun deriveAccount(from: Address, password: WalletPassword, held: Set<Address>) =
                KeyDerivation.Unavailable

            override suspend fun disableBiometrics(): BiometricChange = BiometricChange.NotOnThisDevice

            override suspend fun describe() = KeyStoreDescription.Ready(passwordSet = false, KeyProtection.HARDWARE)

            override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
                KeyImport.Stored(Address.of("0x" + "ab".repeat(20)), KeyProtection.HARDWARE)

            override suspend fun delete(address: Address): KeyDeletion {
                deletedKeys += address
                return if (failKeyDeletes) KeyDeletion.Failed else KeyDeletion.Deleted
            }
        },
        nativeBalances = object : NativeBalances {
            override fun observeBalance(network: Network, address: Address): Flow<BalanceReading> = nativeBalance
        },
        tokenCatalog = object : TokenCatalog {
            override fun observeTokens(network: Network): Flow<List<Token>> = flowOf(emptyList())
        },
        tokenBalances = object : TokenBalances {
            override fun observeBalance(network: Network, holder: Address, token: Address): Flow<TokenBalanceReading> =
                flowOf(TokenBalanceReading.Syncing)
        },
        tokenMetadata = tokenMetadata,
        userTokens = userTokens,
        priceFeeds = FakeFeeds(),
        displayCurrency = MemoryCurrencySetting(),
        addressKinds = addressKinds,
        lightClient = object : LightClient {
            override val datesFinalizedBlocks = true
            override fun observeStatus(network: Network): Flow<LightClientStatus> = status
        },
        staleAnchorConsent = object : StaleAnchorConsent {
            override fun syncPastStaleAnchor(network: Network) = Unit
        },
        networkUpgrades = object : NetworkUpgrades {
            override fun observeUpgrade(network: Network): Flow<NetworkUpgrade?> = report
        },
        knownUpgrades = object : KnownUpgradeStore {
            override fun observe(network: Network): Flow<KnownUpgrade?> = flowOf(null)
            override suspend fun save(network: Network, record: KnownUpgrade): Boolean = error("not used by this test")
        },
        transactionInputs = FactoryInputs(),
        broadcaster = broadcaster,
        receipts = receipts,
        blockTransactions = FakeBlockTransactions(),
        walletHandoff = FakeWalletHandoff(),
        names = FakeNames(),
        signer = signer,
        sentTransfers = sentTransfers,
        transactionHistory = history,
        historyStarts = historyStarts,
        logIndex = logIndex,
        nfts = FakeNftContracts(),
        ensNfts = FakeEnsNfts(),
        addedNfts = MemoryAddedNfts(),
        safeTxQueue = safeTxQueue,
        signingRequests = codec,
        signatureRecovery = recovery,
        safeApprovals = FakeApprovals(),
        safeReleases = FakeReleases(),
        uniswap = object : UniswapV3Chain {
            // Pinned and unproven here: Swap is offered, and no quote is ever proven.
            override suspend fun venue(network: Network): VenueReading =
                VenueReading.Unavailable(ReadUnavailable.NO_ENGINE)

            override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation =
                Simulation.Unavailable(ReadUnavailable.NO_ENGINE)
        },
        cowChain = object : CowChain {
            override suspend fun venue(network: Network): CowVenueReading = CowVenueReading.NotPinned

            override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation =
                Simulation.Unavailable(ReadUnavailable.NO_ENGINE)
        },
        cowApi = object : CowApi {
            override suspend fun quote(
                network: Network,
                sellToken: Address,
                buyToken: Address,
                sellAmount: UInt256,
                from: Address,
            ): CowQuoteReading = CowQuoteReading.Unavailable(CowUnavailable.NOT_PINNED)

            override suspend fun place(
                network: Network,
                order: CowOrder,
                owner: Address,
                signature: Signature,
                uid: CowOrderUid,
            ): CowPlacement = CowPlacement.Unclear(CowUnavailable.NOT_PINNED)

            override suspend fun life(network: Network, uid: CowOrderUid): CowOrderLife =
                CowOrderLife.Unavailable(CowUnavailable.NOT_PINNED)
        },
        cowOrders = cowOrders,
        swapVenue = object : SwapVenueSetting {
            override fun observe(): Flow<VenueChoice> = flowOf(VenueChoice.Chosen(null))

            override suspend fun choose(venue: SwapVenue): Boolean = true
        },
        waitingSwaps = MemoryWaitingSwaps(),
        debugLog = debugLog,
        debugScreen = debugScreen,
        interfaceMode = interfaceMode,
        amountsHidden = MemoryAmountsHidden(),
        networkFreshness = FakeFreshness(),
        screenCaptureDebugSwitch = null,
        networkDemand = NetworkDemand(),
        networkSettings = MemoryNetworkSettings(),
        dappSessions = OffDappSessions,
        dappRequests = dappRequests,
        dappConnectionsOff = true,
    )

    private val daily = Account(
        Network.SEPOLIA,
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )

    private val savings = Account(
        Network.SEPOLIA,
        Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"),
        AccountKind.EOA,
        "Savings",
        AccountCustody.Watched,
    )

    private val treasury = Account(
        Network.SEPOLIA,
        treasuryAddress,
        AccountKind.SAFE,
        "Treasury",
        AccountCustody.Watched,
    )

    /** 0.1 ETH from Treasury to Savings at its nonce, 4. */
    private val queued = SigningRequest.of(
        Network.SEPOLIA,
        treasuryAddress,
        SafeTx.call(savings.address, UInt256.of(100_000_000_000_000_000), ByteArray(0), 4),
    )

    /** What the window (desktop) or the activity (Android) provides the [App] with. */
    private val host = object : LifecycleOwner, ViewModelStoreOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val viewModelStore = ViewModelStore()
    }

    /** The host gone, as its window or activity ends: the ViewModels it scoped stop with it. */
    @After
    fun clearHost() = host.viewModelStore.clear()

    /**
     * Shows the [App] in the [host]; with [followDeployments], beside it the deployments' follower,
     * on the graph's own ports, as the hosts run it (`SerotineApplication`, `main()`).
     */
    private fun showApp(followDeployments: Boolean = false) = rule.setContent {
        CompositionLocalProvider(LocalLifecycleOwner provides host, LocalViewModelStoreOwner provides host) {
            if (followDeployments) {
                LaunchedEffect(Unit) {
                    val listing = ListDeployedSafe(addressKinds, WatchAddress(accountRepository, addressKinds))
                    FollowSafeDeployments(sentTransfers, receipts, accountRepository, listing)()
                }
            }
            val controller = rememberNavController()
            if (navController.isEmpty()) navController += controller
            App(graph, controller)
        }
    }

    @Test
    fun aWalletConnectLinkTheAppWasOpenedWithLandsOnConnectADappFilledIn() {
        showApp()
        val link = "wc:" + "a".repeat(64) + "@2?relay-protocol=irn&symKey=" + "b".repeat(64)
        graph.incomingDappLink.offer(link)
        rule.waitForIdle()
        rule.onNodeWithTag(eth.serotine.ui.pairdapp.PAIR_DAPP_FIELD_TAG).assertIsDisplayed()
        rule.onNodeWithText(link).assertExists()
        // Taken: nothing waits for the next screen to open.
        kotlin.test.assertNull(graph.incomingDappLink.pending.value)
    }

    @Test
    fun aLinkStillWaitingOpensConnectADappAgainOnceTheUserIsElsewhere() {
        showApp()
        val first = "wc:" + "a".repeat(64) + "@2?relay-protocol=irn&symKey=" + "b".repeat(64)
        graph.incomingDappLink.offer(first)
        rule.waitForIdle()
        rule.onNodeWithTag(eth.serotine.ui.pairdapp.PAIR_DAPP_FIELD_TAG).assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        // A link left waiting, as one is while a Connect is under way: the next screen shown opens it.
        val second = "wc:" + "c".repeat(64) + "@2?relay-protocol=irn&symKey=" + "d".repeat(64)
        graph.incomingDappLink.offer(second)
        rule.waitForIdle()
        rule.onNodeWithText(second).assertExists()
        kotlin.test.assertNull(graph.incomingDappLink.pending.value)
    }

    @Test
    fun aWalletConnectLinkWaitsWhileAnUpdateIsRequired() {
        showApp()
        report.value = NetworkUpgrade(NetworkUpgrade.Phase.ACTIVE, activationTime = null, forkId = null, 4)
        rule.waitUntil { rule.onAllNodesWithTag(UPDATE_REQUIRED_TAG).fetchSemanticsNodes().isNotEmpty() }
        val link = "wc:" + "a".repeat(64) + "@2?relay-protocol=irn&symKey=" + "b".repeat(64)
        graph.incomingDappLink.offer(link)
        rule.waitForIdle()
        // The update screen stays the only one, and the link waits for it to go.
        assertEquals(listOf("update-required"), screens())
        assertEquals(link, graph.incomingDappLink.pending.value)

        // The update screen goes: the link that waited lands on Connect a dApp.
        status.value = LightClientStatus.Synced(headBlock = 100, headTimestamp = null, finalized = null)
        rule.waitUntil { rule.onAllNodesWithText(link).fetchSemanticsNodes().isNotEmpty() }
        kotlin.test.assertNull(graph.incomingDappLink.pending.value)
    }

    @Test
    fun aWalletConnectLinkWaitsWhileAFlowCoversTheTabBar() {
        showApp()
        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Import recovery phrase").performClick()
        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).assertIsDisplayed()
        val link = "wc:" + "a".repeat(64) + "@2?relay-protocol=irn&symKey=" + "b".repeat(64)
        graph.incomingDappLink.offer(link)
        rule.waitForIdle()
        // The key operation stays in front, and the link waits for it to end.
        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).assertIsDisplayed()
        assertEquals(link, graph.incomingDappLink.pending.value)
    }

    @Test
    fun aDappsRequestToSignOpensItsReviewAsItArrivesAndOnlyOnce() {
        showApp()
        dappRequests.waiting.value = listOf(signInRequest(1))
        rule.waitUntil { rule.onAllNodesWithText("Message to sign").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Example dApp").assertIsDisplayed()
        // Back leaves it unanswered: it stays in Connected dApps, and is not opened again.
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Message to sign").assertDoesNotExist()
        dappRequests.waiting.value = listOf(signInRequest(1), signInRequest(2))
        rule.waitUntil { rule.onAllNodesWithText("Message to sign").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(DAPP_REQUEST_REFUSE_TAG).performScrollTo().performClick()
        rule.waitUntil { dappRequests.refusals.isNotEmpty() }
        assertEquals(DappRequestError.UNAUTHORIZED, dappRequests.refusals.single().second)
        rule.waitUntil { rule.onAllNodesWithText("Message to sign").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aDappsRequestToSignOpensOverTheSettingsScreenShownAndBackReturnsToIt() {
        showApp()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Wallet password · Biometric unlock").performClick()
        rule.onNodeWithText("Biometric unlock").assertIsDisplayed()
        dappRequests.waiting.value = listOf(signInRequest(1))
        rule.waitUntil { rule.onAllNodesWithText("Message to sign").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Back").performClick()
        // Security is still there, as the user left it: the request opened over it, not in its place.
        rule.onNodeWithText("Biometric unlock").assertIsDisplayed()
    }

    @Test
    fun aDappsTransactionOpensItsOwnReview() {
        showApp()
        val transaction = DappTransactionRequest(
            Address.of("0x" + "0".repeat(39) + "1"),
            Address.of("0x" + "0".repeat(39) + "2"),
            UInt256.ZERO,
            ByteArray(0),
            null,
        )
        dappRequests.waiting.value = listOf(signInRequest(5).copy(ask = DappAsk.SendTransaction(transaction)))
        rule.waitUntil { rule.onAllNodesWithText("Transaction request").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Message to sign").assertDoesNotExist()
    }

    @Test
    fun aDappsRequestToSignWaitsWhileAFlowCoversTheTabBar() {
        showApp()
        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Import recovery phrase").performClick()
        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).assertIsDisplayed()
        dappRequests.waiting.value = listOf(signInRequest(1))
        rule.waitForIdle()
        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).assertIsDisplayed()
        rule.onNodeWithText("Message to sign").assertDoesNotExist()
    }

    @Test
    fun theFloatingActionButtonAddsAnAccountFromItsSeedPhrase() {
        showApp()
        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
        // Every way the + button adds one, and why Create Safe is not among them on a fresh install.
        rule.onNodeWithText(NO_ACCOUNTS_HINT).assertIsDisplayed()

        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Create Safe")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
        rule.onNodeWithText("Import recovery phrase").performClick()
        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).performTextInput(
            "one two three four five six seven eight nine ten eleven twelve",
        )
        // The first import sets the wallet password.
        rule.onNodeWithTag(MNEMONIC_PASSWORD_TAG).performTextInput("correct horse")
        rule.onNodeWithTag(MNEMONIC_REPEAT_TAG).performTextInput("correct horse")
        // Which networks: none is ticked until the user ticks one (docs/networks.md, N9).
        rule.onNodeWithTag(NETWORK_CHOICE_TAG + Network.SEPOLIA.chainId).performScrollTo().performClick()
        rule.onNodeWithTag(MNEMONIC_IMPORT_BUTTON_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithText("Account 1").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Full control").assertIsDisplayed()
        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).assertDoesNotExist()
    }

    @Test
    fun aRequiredUpdateReplacesEveryScreenUntilTheLightClientVerifiesAgain() {
        showApp()
        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Import recovery phrase").performClick()
        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).assertIsDisplayed()

        // Activated by peers' report, and the light client agrees it is not verifying.
        report.value = NetworkUpgrade(NetworkUpgrade.Phase.ACTIVE, activationTime = null, forkId = null, 4)
        rule.waitUntil { rule.onAllNodesWithTag(UPDATE_REQUIRED_TAG).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).assertDoesNotExist()
        rule.onNodeWithContentDescription("Add account").assertDoesNotExist()
        // No tab bar either: the update screen is the only one.
        rule.onNodeWithText("Settings").assertDoesNotExist()

        status.value = LightClientStatus.Synced(headBlock = 100, headTimestamp = null, finalized = null)
        rule.waitUntil { rule.onAllNodesWithText("No accounts yet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(UPDATE_REQUIRED_TAG).assertDoesNotExist()
        rule.onNodeWithText("Network upgrade reported on Sepolia: update Serotine").assertIsDisplayed()
        rule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun aRequiredUpdateFromATabLeavesNothingUnderItAndRecoversToTheAccountsStart() {
        accounts.value = listOf(daily)
        showApp()
        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithTag(SETTINGS_VERSION_TAG).performScrollTo().assertIsDisplayed()

        report.value = NetworkUpgrade(NetworkUpgrade.Phase.ACTIVE, activationTime = null, forkId = null, 4)
        rule.waitUntil { rule.onAllNodesWithTag(UPDATE_REQUIRED_TAG).fetchSemanticsNodes().isNotEmpty() }
        // The account and Settings are gone, and both tabs' stacks with them: back leaves the app.
        assertEquals(listOf("update-required"), screens())

        status.value = LightClientStatus.Synced(headBlock = 100, headTimestamp = null, finalized = null)
        rule.waitUntil { rule.onAllNodesWithTag(ACCOUNTS_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(
            listOf("accounts"),
            screens(),
            "the Accounts start, with neither the account nor Settings under it",
        )
    }

    /** The screens on the back stack, bottom first, without the graphs that hold them. */
    private fun screens(): List<String?> = rule.runOnIdle {
        navController.single().currentBackStack.value.filter { it.destination !is NavGraph }.map { it.destination.route }
    }

    @Test
    fun theBackArrowReturnsToAccountsWithNothingAdded() {
        showApp()

        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Import recovery phrase").performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
    }

    @Test
    fun theSettingsTabKeepsTheAccountsTabWhereItWas() {
        accounts.value = listOf(daily)
        showApp()
        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithText("Receive").assertIsDisplayed()

        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithTag(SETTINGS_VERSION_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SETTINGS_NETWORK_TAG + Network.SEPOLIA.chainId).assertIsDisplayed()

        rule.onNodeWithText("Accounts").performClick()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()
        // Selecting the tab it is on returns to the tab's start.
        rule.onNodeWithText("Accounts").performClick()
        rule.onNodeWithTag(ACCOUNTS_LIST_TAG).assertIsDisplayed()
    }

    @Test
    fun theDebugTabShowsOnceTurnedOnInSettingsAndOpensOverTheAccountsTabAsSettingsDoes() {
        accounts.value = listOf(daily)
        debugLog.records.value = listOf(debugLog.record(1, message = "requestAccount: ok, 34 ms"))
        interfaceMode.mode.value = InterfaceMode.SIMPLE
        showApp()
        rule.onNodeWithText("Debug").assertDoesNotExist()
        rule.onNodeWithText("Daily").performClick()

        // The Debug tab is Detailed view's alone: its switch shows once that is on.
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithTag(SETTINGS_DEBUG_SCREEN_TAG).assertDoesNotExist()
        rule.onNodeWithTag(SETTINGS_DETAILED_VIEW_TAG).performScrollTo().performClick()
        rule.waitUntil { interfaceMode.mode.value == InterfaceMode.DETAILED }
        rule.onNodeWithTag(SETTINGS_DEBUG_SCREEN_TAG).performScrollTo().performClick()
        rule.waitUntil { debugScreen.enabled.value }
        rule.onNodeWithText("Debug").performClick()
        rule.onNodeWithText("Myotis · requestAccount: ok, 34 ms").assertIsDisplayed()

        // The filter typed outlives a tab switch, which pops the Debug screen.
        rule.onNodeWithText("Filter").performTextInput("request")
        rule.onNodeWithText("Accounts").performClick()
        rule.onNodeWithText("Debug").performClick()
        rule.onNodeWithText("request").assertIsDisplayed()
        rule.onNodeWithText("Filter").performTextClearance()

        // Debug took Settings' place over the Accounts tab: back leaves it for the account.
        rule.runOnIdle { navController.single().popBackStack() }
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()

        rule.onAllNodesWithText("Debug").assertCountEquals(1)
        rule.onNodeWithText("Debug").performClick()
        rule.onNodeWithText("Accounts").performClick()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()

        // Back in simple mode the tab goes, its switch kept for Detailed view.
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithTag(SETTINGS_DETAILED_VIEW_TAG).performScrollTo().performClick()
        rule.waitUntil { interfaceMode.mode.value == InterfaceMode.SIMPLE }
        rule.onNodeWithText("Debug").assertDoesNotExist()
        assertTrue(debugScreen.enabled.value)

        // Detailed view again: the tab is back, its switch kept.
        rule.onNodeWithTag(SETTINGS_DETAILED_VIEW_TAG).performScrollTo().performClick()
        rule.waitUntil { interfaceMode.mode.value == InterfaceMode.DETAILED }
        rule.onNodeWithText("Debug").assertIsDisplayed()

        // Turned off again, the tab goes.
        rule.onNodeWithTag(SETTINGS_DEBUG_SCREEN_TAG).performScrollTo().performClick()
        rule.waitUntil { !debugScreen.enabled.value }
        rule.onNodeWithText("Debug").assertDoesNotExist()
    }

    @Test
    fun inSimpleModeAnAccountShowsNoStripAndOffersARefresh() {
        interfaceMode.mode.value = InterfaceMode.SIMPLE
        accounts.value = listOf(daily)
        showApp()

        rule.onNodeWithTag(ACCOUNTS_REFRESH_TAG).assertIsDisplayed()
        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithTag(ACCOUNT_REFRESH_TAG).assertIsDisplayed()
        rule.onNodeWithTag(LIGHT_CLIENT_STRIP_TAG).assertDoesNotExist()
    }

    @Test
    fun anAccountOpensItsDetailAndItsReceiveScreen() {
        accounts.value = listOf(daily)
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithTag(ACCOUNT_ADDRESS_TAG).assertTextEquals("0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2")
        rule.onNodeWithText("Receive").performClick()

        rule.onNodeWithTag(RECEIVE_ADDRESS_TAG).assertTextEquals("0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2")
        rule.onNodeWithText("Only send Sepolia assets to this address").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()
    }

    @Test
    fun theLightClientsStripShowsWhereTheScreenDependsOnItAndNotInSettings() {
        accounts.value = listOf(daily)
        status.value = LightClientStatus.CatchingUp(done = 1, total = 4)
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()
        rule.onNodeWithTag(LIGHT_CLIENT_STRIP_TAG).assertIsDisplayed()
        rule.onNodeWithText("Verifying sync committees · 1 of 4 periods").assertIsDisplayed()
        status.value = LightClientStatus.Synced(headBlock = 100, headTimestamp = null, finalized = null)
        rule.onNodeWithText("Verified at block 100").assertIsDisplayed()

        // An address and its QR code need nothing verified.
        rule.onNodeWithText("Receive").performClick()
        rule.onNodeWithTag(RECEIVE_ADDRESS_TAG).assertIsDisplayed()
        rule.onNodeWithTag(LIGHT_CLIENT_STRIP_TAG).assertDoesNotExist()
        rule.onNodeWithContentDescription("Back").performClick()

        // Settings says it in a row of its own instead.
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithTag(SETTINGS_VERSION_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(LIGHT_CLIENT_STRIP_TAG).assertDoesNotExist()
    }

    @Test
    fun theLongPressMenuRenamesAnAccount() {
        accounts.value = listOf(daily)
        showApp()

        rule.onNodeWithText("Daily").performTouchInput { longClick() }
        rule.onNodeWithText("Rename").performClick()
        rule.onNodeWithTag(RENAME_FIELD_TAG).performTextClearance()
        rule.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("Spending")
        rule.onNodeWithText("Save").performClick()

        rule.waitUntil { rule.onAllNodesWithText("Spending").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(RENAME_FIELD_TAG).assertDoesNotExist()
        assertEquals(listOf("Spending"), accounts.value.map { it.label })
    }

    @Test
    fun removingAnAccountFromItsDetailDeletesItsKeyAndReturnsToTheList() {
        accounts.value = listOf(daily)
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("Remove").performClick()
        rule.onNodeWithText("Only its recovery phrase brings this account back", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()

        rule.waitUntil { rule.onAllNodesWithText("No accounts yet").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(daily.address), deletedKeys)
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertDoesNotExist()
    }

    @Test
    fun aKeyThatCouldNotBeDeletedIsSaidBeforeTheDetailScreenCloses() {
        accounts.value = listOf(daily)
        failKeyDeletes = true
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("Remove").performClick()
        rule.onNodeWithText("Remove").performClick()

        rule.waitUntil {
            rule.onAllNodesWithText("could not be deleted", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("OK").performClick()
        rule.waitUntil { rule.onAllNodesWithText("No accounts yet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertDoesNotExist()
    }

    @Test
    fun settingsOpensSecurityAndItsBackArrowReturns() {
        showApp()
        rule.onNodeWithText("Settings").performClick()

        rule.onNodeWithText("Wallet password · Biometric unlock").performClick()
        rule.onNodeWithText("Biometric unlock").assertIsDisplayed()
        // The fake key store is the desktop's: no biometric path, and it says so.
        rule.onNodeWithText("Not on this device yet", substring = true).assertIsDisplayed()

        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag(SETTINGS_VERSION_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theBackKeyLeavesSettingsForTheAccountsScreenAndAScreenLeftNeverComesBack() {
        accounts.value = listOf(daily)
        showApp()
        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithText("Settings").performClick()
        // The system's back, twice: from Settings to the account, from the account to the list.
        rule.runOnIdle { navController.single().popBackStack() }
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()
        rule.runOnIdle { navController.single().popBackStack() }
        rule.onNodeWithTag(ACCOUNTS_LIST_TAG).assertIsDisplayed()

        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Accounts").performClick()

        rule.onNodeWithTag(ACCOUNTS_LIST_TAG).assertIsDisplayed()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertDoesNotExist()
    }

    @Test
    fun aTokenIsAddedFromSettingsAndTheScreenReturnsToTheList() {
        val eurc = "0x08210F9170F89Ab7658F0B5E3fF39b0E03C594D4"
        tokenMetadata.of(Address.of(eurc)).value = MetadataReading.Verified("EURC", "Euro Coin", 6, 9_412_118)
        showApp()

        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("0 built in · 0 added").performClick()
        rule.onNodeWithText("Add token").performClick()
        rule.onNodeWithTag(ADD_TOKEN_ADDRESS_TAG).performTextInput(eurc)
        rule.waitUntil { rule.onAllNodesWithText("Euro Coin · EURC").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Add token") and hasClickAction()).performClick()

        // Back on the list, which has the token; Settings counts it.
        rule.waitUntil { rule.onAllNodesWithTag(TOKENS_ROW_TAG).fetchSemanticsNodes().size == 1 }
        rule.onNodeWithText("Added: as its contract describes itself").assertIsDisplayed()
        rule.runOnIdle { navController.single().popBackStack() }
        rule.onNodeWithText("0 built in · 1 added").assertIsDisplayed()
    }

    @Test
    fun anAccountSendsFromItsDetailThroughTheReviewToWhereTheTransferStands() {
        accounts.value = listOf(daily, savings)
        nativeBalance.value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithText("Send").performClick()
        // Over the bar, as every flow that ends in a transaction.
        rule.onNodeWithText("Settings").assertDoesNotExist()
        rule.onNodeWithText("Savings · 0xfB69…d359").performClick()
        rule.onNodeWithTag(SEND_AMOUNT_TAG).performTextInput("0.1")
        rule.onNodeWithTag(SEND_REVIEW_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithTag(SEND_AMOUNT_REVIEWED_TAG).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(SEND_AMOUNT_REVIEWED_TAG).assertTextEquals("0.1 ETH")
        rule.onNodeWithTag(SEND_RECIPIENT_TAG).assertTextEquals("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359")
        rule.onNodeWithTag(SEND_PASSWORD_TAG).performScrollTo().performTextInput("correct horse")
        rule.onNodeWithTag(SEND_SIGN_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithTag(TRANSFER_HASH_TAG).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Waiting for a block to include it").assertIsDisplayed()
        val hash = sentRecord.transfers.value.single().hash
        rule.onNodeWithTag(TRANSFER_HASH_TAG).performScrollTo().assertTextEquals(hash)
        assertEquals(listOf("correct horse"), signer.passwords)
        assertEquals(1, broadcaster.sent.size)
        // The signed review is gone from the back stack: Done returns to the account.
        assertEquals(listOf("accounts", "account/{chain}/{address}", "transfer/{chain}/{hash}"), screens())
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()
    }

    @Test
    fun anAccountOpensSwapFromItsDetailAndItsBackArrowReturns() {
        accounts.value = listOf(daily)
        nativeBalance.value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithTag(ACCOUNT_SWAP_TAG).performClick()
        // Over the bar, as every flow that ends in a transaction.
        rule.onNodeWithText("Settings").assertDoesNotExist()
        rule.onNodeWithText("Slippage tolerance").assertExists()
        val swap = "swap/{chain}/{address}?asset={asset}&buy={buy}&amount={amount}&venue={venue}&continue={continue}"
        assertEquals(listOf("accounts", "account/{chain}/{address}", swap), screens())

        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()
    }

    @Test
    fun aTapOnAnAssetRowSendsItFromAnAccountAndProposesItFromASafe() {
        accounts.value = listOf(daily, treasury)
        nativeBalance.value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onAllNodesWithTag(ACCOUNT_ASSET_TAG)[0].performClick()
        rule.onNodeWithText("Settings").assertDoesNotExist()
        assertEquals(
            listOf(
                "accounts",
                "account/{chain}/{address}",
                "send/{chain}/{address}?asset={asset}&to={to}&amount={amount}&fee={fee}",
            ),
            screens(),
        )
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        rule.onNodeWithText("Treasury").performClick()
        rule.waitUntil { rule.onAllNodesWithText("Co-signer").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag(ACCOUNT_ASSET_TAG)[0].performClick()
        rule.onNodeWithText("Send from a Safe").assertIsDisplayed()
        assertEquals(
            listOf("accounts", "account/{chain}/{address}", "safe/{chain}/{address}/propose?asset={asset}"),
            screens(),
        )
    }

    @Test
    fun anAccountsActivityListsWhatWasSentAndARowOpensItsDetailAndFromThereWhereItStands() {
        accounts.value = listOf(daily, savings)
        val sent = SentTransfer(
            network = Network.SEPOLIA,
            account = daily.address,
            nonce = 7,
            signed = SignedTransfer(byteArrayOf(2, 7)),
            action = SentAction.Transfer(savings.address, TransferAsset.Native, UInt256.of("100000000000000000")),
            maxCost = UInt256.ONE,
            sentAt = 1_800_000_000,
        )
        history.entries.value = listOf(HistoryEntry(sent, SentOutcome.Finalized(9_412_118, succeeded = true)))
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithText("Activity").performClick()
        rule.waitUntil { rule.onAllNodesWithTag(ACTIVITY_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Finalized in block 9,412,118").assertIsDisplayed()
        // Under the bar, as the account it is of.
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("Sent 0.1 ETH").performClick()

        rule.waitUntil { rule.onAllNodesWithTag(ACTIVITY_DETAIL_FIELD_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(sent.hash).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Status").performClick()
        rule.waitUntil { rule.onAllNodesWithTag(TRANSFER_HASH_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(TRANSFER_HASH_TAG).performScrollTo().assertTextEquals(sent.hash)
        assertEquals(
            listOf("accounts", "account/{chain}/{address}", DETAIL_ROUTE, "transfer/{chain}/{hash}"),
            screens(),
        )
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithText("Status").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onAllNodesWithTag(ACTIVITY_ROW_TAG).assertCountEquals(1)
    }

    @Test
    fun aCowOrderInActivityOpensItsStatusAndTheSameSwapOnUniswapTakesItsPlace() {
        accounts.value = listOf(daily)
        val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc")
        val settlement = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41")
        val order = CowOrder(
            usdc.address,
            CowOrder.NATIVE_BUY_TOKEN,
            daily.address,
            UInt256.of(40_000_000),
            UInt256.ONE,
            1_800_000_300,
        )
        cowOrders.orders.value = listOf(
            CowOrderRecord(
                account = daily.address,
                network = Network.SEPOLIA,
                settlement = settlement,
                order = order,
                uid = order.uid(Network.SEPOLIA.chainId, settlement, daily.address),
                signature = Signature("0".repeat(63) + "1", "0".repeat(63) + "1", 0),
                sell = TransferAsset.Erc20Token(usdc),
                buy = TransferAsset.Native,
                signedAt = 1_800_000_000,
                rangeStart = 9_412_000,
                end = CowOrderEnd.Unfilled(9_412_200, cancelled = false),
            ),
        )
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithText("Activity").performClick()
        rule.waitUntil { rule.onAllNodesWithTag(ACTIVITY_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("CoW order not filled: 40 USDC").performClick()
        rule.waitUntil { rule.onAllNodesWithTag(COW_ORDER_UNISWAP_TAG).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("accounts", "account/{chain}/{address}", "cow-order/{chain}/{uid}"), screens())

        rule.onNodeWithTag(COW_ORDER_UNISWAP_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithText("Slippage tolerance").fetchSemanticsNodes().isNotEmpty() }
        val swap = "swap/{chain}/{address}?asset={asset}&buy={buy}&amount={amount}&venue={venue}&continue={continue}"
        assertEquals(listOf("accounts", "account/{chain}/{address}", swap), screens(), "in the order's place")

        rule.onNodeWithContentDescription("Back").performClick()
        rule.onAllNodesWithTag(ACTIVITY_ROW_TAG).assertCountEquals(1)
    }

    @Test
    fun anUnlistedTokensTransferIsAddedAsATokenFromItsDetail() {
        accounts.value = listOf(daily, savings)
        val contract = Address.of("0x8617e340b3d01fa5f11f306f4090fd50e238070d")
        tokenMetadata.of(contract).value = MetadataReading.Verified("SPAM", "Spam Coin", 18, 9_402_000)
        historyStarts.starts.value = mapOf(daily.address to HistoryStart.Fixed(9_400_000))
        logIndex.coverage.value = LogCoverageReading.Read(
            mapOf(LogWatchKey.Mention(daily.address) to LogCoverage(9_400_000, 9_400_000L..9_420_000L)),
        )
        logIndex.logs = listOf(
            IndexedLog(
                ReceiptLog(
                    contract,
                    listOf(TokenTransfers.TRANSFER, topicOf(savings.address), topicOf(daily.address)),
                    "0x" + "de0b6b3a7640000".padStart(64, '0'),
                ),
                9_402_000,
                "0x" + "bb".repeat(32),
                "0x" + "e2".repeat(32),
                3,
            ),
        )
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithText("Activity").performClick()
        rule.waitUntil { rule.onAllNodesWithTag(ACTIVITY_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("An unlisted token logged a transfer to it").performClick()
        rule.waitUntil { rule.onAllNodesWithText("Add token").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Add token").performClick()

        // Add token by address, under the bar, with the contract filled in and read.
        rule.waitUntil { rule.onAllNodesWithText("Spam Coin · SPAM").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(
            listOf("accounts", "account/{chain}/{address}", DETAIL_ROUTE, "activity-add-token/{chain}/{address}"),
            screens(),
        )
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNode(hasText("Add token") and hasClickAction()).performClick()

        // Back on the detail, which says what moved now that the wallet lists the token.
        rule.waitUntil { rule.onAllNodesWithText("Received 1 SPAM").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("accounts", "account/{chain}/{address}", DETAIL_ROUTE), screens())
    }

    @Test
    fun settingsAddTokenLeavesToTokensWhileOneOpenedFromActivityIsOnTheAccountsStack() {
        accounts.value = listOf(daily)
        showApp()
        rule.runOnIdle {
            navController.single().navigate("activity-add-token/11155111/0x8617e340b3d01fa5f11f306f4090fd50e238070d")
        }

        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("0 built in · 0 added").performClick()
        rule.onNodeWithText("Add token").performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        // Back on Tokens, and the Accounts tab's Add token is still where it was.
        rule.waitUntil { screens().last() == "tokens" }
        assertEquals(listOf("accounts", "activity-add-token/{chain}/{address}", "settings", "tokens"), screens())
    }

    @Test
    fun aSafesQueuedTransactionOpensFromItsQueueAndIsSharedAsARequest() {
        accounts.value = listOf(daily, treasury)
        safeTxQueue.requests.value = listOf(queued)
        showApp()

        rule.onNodeWithText("Treasury").performClick()
        rule.waitUntil { rule.onAllNodesWithText("Queue (1)").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Queue (1)").performClick()
        rule.onAllNodesWithTag(ACCOUNT_QUEUE_ROW_TAG).assertCountEquals(1)
        rule.onNodeWithText("Send 0.1 ETH").performClick()

        rule.waitUntil { rule.onAllNodesWithTag(SAFE_TX_HASH_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(SAFE_TX_HASH_TAG).performScrollTo().assertTextEquals("0x" + queued.hash.hex)
        // A Safe transaction covers the bar, as every flow that ends in a signature or a transaction.
        rule.onNodeWithText("Settings").assertDoesNotExist()
        rule.onNodeWithTag(SAFE_TX_SHARE_TAG).performScrollTo().performClick()

        rule.onNodeWithTag(SAFE_TX_QR_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SAFE_TX_REQUEST_TAG).performScrollTo().assertTextEquals(codec.encode(queued))
        assertEquals(listOf("accounts", "account/{chain}/{address}", "safe/{chain}/{address}/tx/{hash}"), screens())
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()
    }

    @Test
    fun sendFromASafeProposesATransferThatAnOwnerHeldHereSignsAndQueues() {
        accounts.value = listOf(daily, treasury)
        nativeBalance.value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        showApp()

        rule.onNodeWithText("Treasury").performClick()
        rule.waitUntil { rule.onAllNodesWithText("Co-signer").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Send").performClick()
        rule.onNodeWithText("Send from a Safe").assertIsDisplayed()
        rule.onNodeWithText("Settings").assertDoesNotExist()
        rule.onNodeWithText("Daily · 0x8f3A…91c2").performScrollTo().performClick()
        rule.onNodeWithTag(SEND_AMOUNT_TAG).performScrollTo().performTextInput("0.1")
        rule.onNodeWithTag(SEND_REVIEW_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithTag(PROPOSE_AMOUNT_REVIEWED_TAG).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(PROPOSE_AMOUNT_REVIEWED_TAG).assertTextEquals("0.1 ETH")
        rule.onNodeWithText("Safe nonce 4").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(PROPOSE_PASSWORD_TAG).performScrollTo().performTextInput("correct horse")
        rule.onNodeWithTag(PROPOSE_SIGN_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithTag(SAFE_TX_HASH_TAG).fetchSemanticsNodes().isNotEmpty() }

        val proposed = safeTxQueue.requests.value.single()
        assertEquals(listOf(daily.address), proposed.signers)
        rule.onNodeWithTag(SAFE_TX_HASH_TAG).performScrollTo().assertTextEquals("0x" + proposed.hash.hex)
        assertEquals(listOf("correct horse"), signer.passwords)
        // The proposal is gone from the back stack: back from the transaction returns to the Safe.
        assertEquals(listOf("accounts", "account/{chain}/{address}", "safe/{chain}/{address}/tx/{hash}"), screens())
    }

    @Test
    fun aRequestImportedFromAnotherDeviceOpensItsTransaction() {
        accounts.value = listOf(daily, treasury)
        showApp()
        val signature = SafeSignature.Ecdsa(carol, recovery.sign(carol, queued.hash))
        val text = codec.encode(queued.withSignatures(listOf(signature)))

        rule.onNodeWithText("Treasury").performClick()
        rule.waitUntil { rule.onAllNodesWithText("Queue (0)").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Queue (0)").performClick()
        rule.onNodeWithText("Import a request").performClick()
        rule.onNodeWithTag(IMPORT_REQUEST_FIELD_TAG).performTextInput(text)
        rule.onNodeWithTag(IMPORT_REQUEST_BUTTON_TAG).performClick()
        rule.waitUntil { rule.onAllNodesWithTag(SAFE_TX_HASH_TAG).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(SAFE_TX_HASH_TAG).performScrollTo().assertTextEquals("0x" + queued.hash.hex)
        // The import's flag reached the new entry through its savedStateHandle: it says where it went.
        rule.onNodeWithText("Added to Treasury's queue on this device.").performScrollTo().assertIsDisplayed()
        assertEquals(listOf(carol), safeTxQueue.requests.value.single().signers)
        assertEquals(listOf("accounts", "account/{chain}/{address}", "safe/{chain}/{address}/tx/{hash}"), screens())
    }

    @Test
    fun anApprovalOnChainIsSentFromTheTransactionToWhereItStands() {
        accounts.value = listOf(daily, treasury)
        nativeBalance.value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        safeTxQueue.requests.value = listOf(queued)
        showApp()

        rule.onNodeWithText("Treasury").performClick()
        rule.waitUntil { rule.onAllNodesWithText("Queue (1)").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Queue (1)").performClick()
        rule.onNodeWithText("Send 0.1 ETH").performClick()
        rule.waitUntil { rule.onAllNodesWithText("Approve on chain").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Approve on chain").performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithTag(SAFE_TX_PASSWORD_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(SAFE_TX_PASSWORD_TAG).performScrollTo().performTextInput("correct horse")
        rule.onNodeWithTag(SAFE_TX_SIGN_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithTag(TRANSFER_HASH_TAG).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Approval of a Safe transaction").assertIsDisplayed()
        val approval = sentRecord.transfers.value.single()
        assertEquals(SentAction.SafeApproval(queued), approval.action)
        rule.onNodeWithTag(TRANSFER_HASH_TAG).performScrollTo().assertTextEquals(approval.hash)
        // The signed review is gone from the back stack: Done returns to the Safe.
        assertEquals(listOf("accounts", "account/{chain}/{address}", "transfer/{chain}/{hash}"), screens())
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()
    }

    @Test
    fun aWatchOnlyAccountShowsSendGreyedOutAndSendsNothing() {
        accounts.value = listOf(savings)
        showApp()

        rule.onNodeWithText("Savings").performClick()
        rule.onNodeWithText("Receive").assertIsDisplayed()
        rule.onNodeWithText("Send").performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertIsDisplayed()
        assertEquals(listOf("accounts", "account/{chain}/{address}"), screens(), "nothing opened")
    }

    @Test
    fun anAddressIsWatchedFromTheFloatingActionButton() {
        showApp()

        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Watch address").performClick()
        rule.onNodeWithTag(WATCH_ADDRESS_TAG).performTextInput("0xd8da6bf26964af9d7eed9e03e53415d37aa96045")
        rule.onNodeWithTag(WATCH_NAME_TAG).performTextInput("Vitalik")
        rule.waitUntil { rule.onAllNodesWithText("Checked at block 9,412,118").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Add").performClick()

        rule.waitUntil { rule.onAllNodesWithText("Vitalik").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Watch-only").assertIsDisplayed()
        assertEquals(listOf(AccountCustody.Watched), accounts.value.map { it.custody })
    }

    @Test
    fun aSafeIsCreatedFromTheFloatingActionButtonAndItsDeploymentFollowed() {
        accounts.value = listOf(daily, savings)
        nativeBalance.value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        showApp(followDeployments = true)

        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Create Safe").performClick()
        // Over the bar, as every flow that ends in a transaction.
        rule.onNodeWithText("Settings").assertDoesNotExist()
        rule.onNodeWithText("Create Safe 1/3").assertIsDisplayed()
        rule.onNodeWithTag(CREATE_SAFE_NAME_TAG).performTextInput("Family vault")
        rule.onNodeWithText("Savings").performClick()
        rule.onNodeWithTag(CREATE_SAFE_CONTINUE_TAG).performScrollTo().performClick()
        rule.onNodeWithText("Create Safe 2/3").assertIsDisplayed()
        rule.onNodeWithTag(CREATE_SAFE_THRESHOLD_TAG).assertTextEquals("1 of 2")
        rule.onNodeWithTag(CREATE_SAFE_CONTINUE_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithText("Verified simulation").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Create Safe 3/3").assertIsDisplayed()
        rule.onNodeWithTag(CREATE_SAFE_PASSWORD_TAG).performScrollTo().performTextInput("correct horse")
        rule.onNodeWithTag(CREATE_SAFE_DEPLOY_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithTag(TRANSFER_HASH_TAG).fetchSemanticsNodes().isNotEmpty() }

        val deployment = sentRecord.transfers.value.single()
        val action = deployment.action as SentAction.SafeDeployment
        assertEquals(listOf(daily.address, savings.address), action.owners)
        assertEquals("Family vault", action.label)
        rule.onNodeWithText("Deployment of a Safe").assertIsDisplayed()
        rule.onNodeWithTag(TRANSFER_SAFE_TAG).assertTextEquals(Eip55.format(action.safe))
        assertEquals(listOf("correct horse"), signer.passwords)
        // The signed review is gone from the back stack: Done returns to Accounts, right away.
        assertEquals(listOf("accounts", "transfer/{chain}/{hash}"), screens())
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithTag(ACCOUNTS_LIST_TAG).assertIsDisplayed()

        // Mined and proven with the status screen long gone: the Safe is listed all the same.
        receipts.of(deployment.hash).value = TransactionProgress.Included(9_412_120, succeeded = true)
        deployed.value = mapOf(
            action.safe to SafeState("1.4.1", l2 = false, action.owners, 1, 0, emptyList(), null),
        )
        rule.waitUntil { accounts.value.any { it.address == action.safe } }
        assertEquals(
            Account(Network.SEPOLIA, action.safe, AccountKind.SAFE, "Family vault", AccountCustody.Watched),
            accounts.value.last(),
        )
        rule.onNodeWithText("Family vault").assertIsDisplayed()
    }

    @Test
    fun withoutAKeyHereCreateSafeIsGreyedOutAndSaysWhyWithTheWaysToGetOne() {
        accounts.value = listOf(savings)
        showApp()

        // Owner ruling, 2026-10-18, #55: shown, greyed out, and a tap says what is missing.
        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Create Safe").performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals(
            "Creating a Safe needs an account whose key is on this device, to pay for its deployment. Import or " +
                "create an account, and fund it, first.",
        )
        assertEquals(listOf("accounts"), screens(), "nothing opened")

        // Its way out: Create account, straight from the dialog.
        rule.onNodeWithText("Create account").performClick()
        assertEquals(listOf("accounts", "create-account"), screens())
    }
}

/** The route of an Activity detail, as the back stack names it. */
private const val DETAIL_ROUTE = "activity/{chain}/{address}/tx/{hash}?log={log}"
