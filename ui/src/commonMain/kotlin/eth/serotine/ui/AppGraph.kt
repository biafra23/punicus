package eth.serotine.ui

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.AmountsHiddenSetting
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.CowApi
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.CowOrders
import eth.serotine.domain.repository.DebugLog
import eth.serotine.domain.repository.DebugScreenSetting
import eth.serotine.domain.repository.DappRequests
import eth.serotine.domain.repository.DappSessions
import eth.serotine.domain.repository.DisplayCurrencySetting
import eth.serotine.domain.usecase.ApproveDappProposal
import eth.serotine.domain.usecase.DisconnectDapp
import eth.serotine.domain.usecase.ExtendDappSession
import eth.serotine.domain.usecase.ObserveDappConnections
import eth.serotine.domain.usecase.ObserveDappMessages
import eth.serotine.domain.usecase.PairDapp
import eth.serotine.domain.usecase.RefuseDappRequest
import eth.serotine.domain.usecase.RejectDappProposal
import eth.serotine.domain.usecase.SignDappMessage
import eth.serotine.domain.repository.HistoryStarts
import eth.serotine.domain.repository.InterfaceModeSetting
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkFreshness
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SafeApprovals
import eth.serotine.domain.repository.SafeReleases
import eth.serotine.domain.repository.SafeTxQueue
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.SignatureRecovery
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.SigningRequestCodec
import eth.serotine.domain.repository.StaleAnchorConsent
import eth.serotine.domain.repository.SwapVenueSetting
import eth.serotine.domain.repository.WaitingSwaps
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.repository.TokenMetadata
import eth.serotine.domain.repository.TransactionHistory
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.UserTokens
import eth.serotine.domain.usecase.AddOnNetworks
import eth.serotine.domain.usecase.AddToken
import eth.serotine.domain.usecase.ApproveSafeTxOnChain
import eth.serotine.domain.usecase.CheckSafeSwap
import eth.serotine.domain.usecase.ChooseSwapVenue
import eth.serotine.domain.usecase.ClearDebugLog
import eth.serotine.domain.usecase.CowOfferedOnThisDevice
import eth.serotine.domain.usecase.DeploySafe
import eth.serotine.domain.usecase.DeriveAccount
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.DisableBiometricUnlock
import eth.serotine.domain.usecase.DropWaitingSwap
import eth.serotine.domain.usecase.EnableBiometricUnlock
import eth.serotine.domain.usecase.EncodeSigningRequest
import eth.serotine.domain.usecase.ExecuteSafeTx
import eth.serotine.domain.usecase.FindUniswapRoute
import eth.serotine.domain.usecase.FindWaitingSwap
import eth.serotine.domain.usecase.HasSwapVenue
import eth.serotine.domain.usecase.HoldNetwork
import eth.serotine.domain.usecase.ImportMnemonicAccount
import eth.serotine.domain.usecase.ImportSigningRequest
import eth.serotine.domain.usecase.KeepWaitingSwap
import eth.serotine.domain.usecase.NetworkDemand
import eth.serotine.domain.usecase.NewRecoveryPhrase
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAccountCowOrders
import eth.serotine.domain.usecase.ObserveAccountHistory
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveAddressKind
import eth.serotine.domain.usecase.ObserveAmountsHidden
import eth.serotine.domain.usecase.ObserveCowOrder
import eth.serotine.domain.usecase.ObserveCowOrderLife
import eth.serotine.domain.usecase.ObserveDebugLog
import eth.serotine.domain.usecase.ObserveDebugScreen
import eth.serotine.domain.usecase.ObserveDebugTab
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.ObserveEnsNames
import eth.serotine.domain.usecase.ObserveInterfaceMode
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveNetworkLive
import eth.serotine.domain.usecase.ObserveNetworkOverview
import eth.serotine.domain.usecase.ObservePortfolio
import eth.serotine.domain.usecase.ObserveSafe
import eth.serotine.domain.usecase.ObserveSafeHistory
import eth.serotine.domain.usecase.ObserveSafeTxQueue
import eth.serotine.domain.usecase.ObserveSafes
import eth.serotine.domain.usecase.ObserveSentToAddresses
import eth.serotine.domain.usecase.ObserveSentTransfer
import eth.serotine.domain.usecase.ObserveSwapVenue
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveTokenMetadata
import eth.serotine.domain.usecase.ObserveTokenTransfers
import eth.serotine.domain.usecase.ObserveUpgradeWarnings
import eth.serotine.domain.usecase.ObserveUsdPrice
import eth.serotine.domain.usecase.ObserveWaitingSwaps
import eth.serotine.domain.usecase.PlaceCowOrder
import eth.serotine.domain.usecase.PlanSafe
import eth.serotine.domain.usecase.ProposeSafeSettingsChange
import eth.serotine.domain.usecase.ProposeSafeSwap
import eth.serotine.domain.usecase.ProposeSafeTx
import eth.serotine.domain.usecase.QuoteCowCancellation
import eth.serotine.domain.usecase.QuoteCowSwap
import eth.serotine.domain.usecase.QuoteSafeApproval
import eth.serotine.domain.usecase.QuoteSafeDeployment
import eth.serotine.domain.usecase.QuoteSafeExecution
import eth.serotine.domain.usecase.QuoteSwap
import eth.serotine.domain.usecase.QuoteTokenApproval
import eth.serotine.domain.usecase.QuoteTransfer
import eth.serotine.domain.usecase.ReadSafeTxOwners
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.RefreshNetworkData
import eth.serotine.domain.usecase.RemoveAccount
import eth.serotine.domain.usecase.RemoveQueuedSafeTx
import eth.serotine.domain.usecase.RemoveToken
import eth.serotine.domain.usecase.RenameAccount
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.SendCowCancellation
import eth.serotine.domain.usecase.SendSwap
import eth.serotine.domain.usecase.SendTokenApproval
import eth.serotine.domain.usecase.SendTransfer
import eth.serotine.domain.usecase.SetDebugLogLevel
import eth.serotine.domain.usecase.SetDebugScreen
import eth.serotine.domain.usecase.SetDisplayCurrency
import eth.serotine.domain.usecase.SetInterfaceMode
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.SetTokenHidden
import eth.serotine.domain.usecase.SignSafeTx
import eth.serotine.domain.usecase.SyncPastStaleAnchor
import eth.serotine.domain.usecase.ToggleAmountsHidden
import eth.serotine.domain.usecase.WalletChanges
import eth.serotine.domain.usecase.WarmFeeHistory
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.ui.accountdetail.AccountDetailViewModel
import eth.serotine.ui.accounts.AccountsViewModel
import eth.serotine.ui.activity.AccountActivity
import eth.serotine.ui.activity.ActivityKey
import eth.serotine.ui.activitydetail.ActivityDetailViewModel
import eth.serotine.ui.addnetwork.AddOnNetworksViewModel
import eth.serotine.ui.addtoken.AddTokenViewModel
import eth.serotine.ui.coworder.CowOrderViewModel
import eth.serotine.ui.createaccount.CreateAccountViewModel
import eth.serotine.ui.createsafe.CreateSafeViewModel
import eth.serotine.ui.connections.ConnectionsViewModel
import eth.serotine.ui.currency.CurrencyViewModel
import eth.serotine.ui.dappproposal.DappProposalViewModel
import eth.serotine.ui.dapprequest.DappRequestViewModel
import eth.serotine.ui.dapprequest.DappRequestsWaitingViewModel
import eth.serotine.ui.pairdapp.IncomingDappLink
import eth.serotine.ui.pairdapp.PairDappViewModel
import eth.serotine.ui.debug.DebugTabViewModel
import eth.serotine.ui.debug.DebugViewModel
import eth.serotine.ui.deriveaccount.DeriveAccountViewModel
import eth.serotine.ui.lightclient.LightClientViewModel
import eth.serotine.ui.mnemonicimport.MnemonicImportViewModel
import eth.serotine.ui.mode.InterfaceModeViewModel
import eth.serotine.ui.platform.ScreenCaptureDebugSwitch
import eth.serotine.ui.receive.ReceiveViewModel
import eth.serotine.ui.removeaccount.RemoveAccountViewModel
import eth.serotine.ui.renameaccount.RenameAccountViewModel
import eth.serotine.ui.safeimport.SafeImportViewModel
import eth.serotine.ui.safepropose.SafeProposeViewModel
import eth.serotine.ui.safesettings.SafeSettingsViewModel
import eth.serotine.ui.safeswap.SafeSwapViewModel
import eth.serotine.ui.safetx.SafeTxViewModel
import eth.serotine.ui.security.SecurityViewModel
import eth.serotine.ui.send.SendViewModel
import eth.serotine.ui.settings.SettingsViewModel
import eth.serotine.ui.swap.SwapFill
import eth.serotine.ui.swap.SwapViewModel
import eth.serotine.ui.tokens.TokensViewModel
import eth.serotine.ui.transferstatus.TransferStatusViewModel
import eth.serotine.ui.update.UpdateRequiredViewModel
import eth.serotine.ui.watch.WatchViewModel

/**
 * The composition root's contract: everything the shared UI needs, handed in as the
 * domain's PORTS. Each host builds exactly one at start-up with its own adapters
 * (`SerotineApplication` on Android, `main()` on desktop) and passes it to [App]; the UI
 * assembles use cases and ViewModels from it and never constructs an adapter itself.
 *
 * Manual constructor injection, no DI framework: the graph is small enough to read in one
 * place, and a missing dependency is a compile error rather than a start-up crash. Ports in,
 * ViewModel factories out: the work no screen owns (following upgrade reports, listing the Safes
 * this wallet deployed) is the host's, beside this graph, on the same adapter instances.
 */
class AppGraph(
    private val accounts: AccountRepository,
    /** Where imported seeds are kept: the key adapter's store, behind its port. */
    private val keys: KeyStore,
    /** Balances verified by each network's embedded light client. */
    private val nativeBalances: NativeBalances,
    /** The built-in tokens of each network. */
    private val tokenCatalog: TokenCatalog,
    /** Token balances verified by that light client. */
    private val tokenBalances: TokenBalances,
    /** What token contracts say about themselves, verified by that light client. */
    private val tokenMetadata: TokenMetadata,
    /** The tokens the user added and hid. */
    private val userTokens: UserTokens,
    /** Prices from the network's pinned Chainlink feeds, verified by that light client. */
    private val priceFeeds: PriceFeeds,
    /** The user's choice of display currency. */
    private val displayCurrency: DisplayCurrencySetting,
    /** What is at an address, verified by that light client. */
    private val addressKinds: AddressKinds,
    /** That light client's own state. */
    private val lightClient: LightClient,
    /** The user's consent to a light client catching up from a stale checkpoint, for this run. */
    private val staleAnchorConsent: StaleAnchorConsent,
    /** Network upgrades this version does not support, as peers report them to that light client. */
    private val networkUpgrades: NetworkUpgrades,
    /**
     * The upgrade recorded from earlier reports. The host's own notices write it too, so it is
     * one instance per process, shared with them.
     */
    private val knownUpgrades: KnownUpgradeStore,
    /** What a transfer needs from the chain before it is signed, verified by that light client. */
    private val transactionInputs: TransactionInputs,
    /** Hands a signed transfer to that light client's peers. */
    private val broadcaster: Broadcaster,
    /** A sent transfer's receipt, verified by that light client. */
    private val receipts: ReceiptTracker,
    /** ENS names, resolved by that light client against finalized state. */
    private val names: NameResolver,
    /**
     * Signs with the keys held in [keys]: the key adapter's signer, the same instance as [keys],
     * whose calls take turns (docs/keys-and-signing.md).
     */
    private val signer: Signer,
    /**
     * The transfers this wallet signed, kept across restarts: the host's record that also adds
     * each one to [transactionHistory] (`HistoryRecordingSentTransfers`).
     */
    private val sentTransfers: SentTransfers,
    /** Every transaction this wallet sent, kept for good, with how each ended (issue #85). */
    private val transactionHistory: TransactionHistory,
    /** Where each account's history starts, which the host's `FollowAccountLogs` fixes (issue #85). */
    private val historyStarts: HistoryStarts,
    /** The light client's verified log index, which the host's `FollowAccountLogs` keeps following the accounts. */
    private val logIndex: LogIndex,
    /** The Safe transactions waiting for their owners, kept on this device across restarts. */
    private val safeTxQueue: SafeTxQueue,
    /** The text a Safe's signing request travels in between devices, for every network the app knows. */
    private val signingRequests: SigningRequestCodec,
    /** Who made a secp256k1 signature: what a co-signer's signature is checked with. */
    private val signatureRecovery: SignatureRecovery,
    /** Owners' approvals of Safe transactions on chain, verified by that light client. */
    private val safeApprovals: SafeApprovals,
    /** The Safe release a new Safe on a network is created from: pinned singleton, factory and proxy code. */
    private val safeReleases: SafeReleases,
    /** Uniswap v3 on each network: its pinned contracts, proven by their code, and verified calls of them. */
    private val uniswap: UniswapV3Chain,
    /** CoW Protocol's settlement contracts on each network, proven by their code, and verified calls of them. */
    private val cowChain: CowChain,
    /**
     * CoW Protocol's order API, an off-chain service, used only for a swap the user sends to CoW
     * (CLAUDE.md, *Data sources*).
     */
    private val cowApi: CowApi,
    /** The CoW orders signed here, live until their end is proven: what Send and a swap hold back. */
    private val cowOrders: CowOrders,
    /** The venue the user last chose on Swap, compose, which the next swap starts at. */
    private val swapVenue: SwapVenueSetting,
    /** The swaps whose next step waits for the user, which the account's Activity offers with Continue. */
    private val waitingSwaps: WaitingSwaps,
    /**
     * The app's debug log, which the Debug tab shows: the host's one instance, which the engine's
     * node writes each request into.
     */
    private val debugLog: DebugLog,
    /**
     * Whether the user turned the Debug tab on in Settings: the host's one instance, which its
     * `FollowDebugScreen` keeps [debugLog] to.
     */
    private val debugScreen: DebugScreenSetting,
    /** The view the user chose in Settings, simple mode or Detailed view (docs/simple-ui.md). */
    private val interfaceMode: InterfaceModeSetting,
    /** Whether the user hides amounts with the eye of the screens that show them (docs/wallet-ux.md). */
    private val amountsHidden: AmountsHiddenSetting,
    /** How fresh each network's values are kept: live, or proven on demand in simple mode (docs/simple-ui.md). */
    private val networkFreshness: NetworkFreshness,
    /**
     * The debug build's switch that allows screen recordings of the protected screens; null in a
     * release build, and on a host whose screens block nothing (docs/keys-and-signing.md, rule 8).
     */
    internal val screenCaptureDebugSwitch: ScreenCaptureDebugSwitch?,
    /**
     * The screens' holds on a network's light client, with no account on it yet (Watch, Add
     * token): the host's one instance, which its `FollowNetworks` follows.
     */
    private val networkDemand: NetworkDemand,
    /** Which networks are on: the host's one instance, which its `FollowNetworks` follows. */
    private val networkSettings: NetworkSettings,
    /** dApp sessions over WalletConnect (docs/walletconnect.md): the host's one instance, whose relay it runs. */
    private val dappSessions: DappSessions,
    /** The requests dApps send over those sessions: the same adapter's. */
    private val dappRequests: DappRequests,
    /**
     * Whether this build carries no WalletConnect project ID, so that dApp connections are switched
     * off and Settings shows Connected dApps greyed out (ruling 2).
     */
    val dappConnectionsOff: Boolean,
) {
    /** The one [WalletChanges] of the app: imports and removals never interleave. */
    private val walletChanges = WalletChanges()

    /** The one toggle of the eye for every screen: its taps take turns app-wide, not only within a screen. */
    private val toggleAmountsHidden = ToggleAmountsHidden(amountsHidden)

    /** Every network's upgrade warning, and whether the update screen replaces the app (docs/networks.md). */
    private fun observeUpgradeWarnings() =
        ObserveUpgradeWarnings(networkUpgrades, knownUpgrades, lightClient, networkSettings, accounts)

    fun accountsViewModel(): AccountsViewModel =
        AccountsViewModel(
            ObservePortfolio(ObserveAccounts(accounts), accountValue(), displayCurrencies()),
            ObserveSafes(accounts, observeSafe()),
            ObserveEnsNames(accounts, names),
            ObserveLightClient(lightClient),
            ObserveEnabledNetworks(networkSettings),
            SetNetworkEnabled(networkSettings),
            observeUpgradeWarnings(),
            SyncPastStaleAnchor(lightClient, staleAnchorConsent),
            RefreshNetworkData(networkFreshness),
            ObserveNetworkLive(networkFreshness),
            ObserveAmountsHidden(amountsHidden),
            toggleAmountsHidden,
        )

    /** The light clients' status strips of the screens that depend on them: one for the app, in the host's scope. */
    fun lightClientViewModel(): LightClientViewModel = LightClientViewModel(ObserveLightClient(lightClient))

    fun accountDetailViewModel(account: AccountId): AccountDetailViewModel =
        AccountDetailViewModel(
            account,
            ObserveAccount(accounts),
            accountValue(),
            observeSafe(),
            observeSafeTxQueue(account.network),
            knownTokens(),
            SetTokenHidden(userTokens),
            ObserveEnsNames(accounts, names),
            accountActivity(),
            ObserveEnabledNetworks(networkSettings),
            SetNetworkEnabled(networkSettings),
            observeUpgradeWarnings(),
            HasSwapVenue(uniswap),
            RefreshNetworkData(networkFreshness),
            ObserveNetworkLive(networkFreshness),
            ObserveLightClient(lightClient),
            DropWaitingSwap(waitingSwaps),
            ObserveAmountsHidden(amountsHidden),
            toggleAmountsHidden,
        )

    /**
     * The Safe transaction [hash] of the Safe at [safe] (docs/wallet-ux.md, screen 17); [imported]
     * when an import has just queued it, which its overview says first.
     */
    fun safeTxViewModel(safe: AccountId, hash: Digest, imported: Boolean = false): SafeTxViewModel = SafeTxViewModel(
        safe,
        hash,
        ObserveAccounts(accounts),
        observeSafeTxQueue(safe.network),
        knownTokens(),
        ReadSafeTxOwners(observeSafe(), signatureRecovery, safeApprovals, safe.network),
        EncodeSigningRequest(signingRequests),
        DescribeKeyStore(keys),
        signSafeTx(safe.network),
        QuoteSafeApproval(observeSafe(), safeApprovals, nativeBalances, transactionInputs, sentTransfers, safe.network),
        ApproveSafeTxOnChain(signer, broadcaster, sentTransfers),
        QuoteSafeExecution(
            observeSafe(),
            signatureRecovery,
            safeApprovals,
            nativeBalances,
            transactionInputs,
            sentTransfers,
            safe.network,
        ),
        ExecuteSafeTx(signer, broadcaster, sentTransfers),
        RebroadcastTransfer(broadcaster, sentTransfers, receipts),
        RemoveQueuedSafeTx(safeTxQueue),
        WarmFeeHistory(transactionInputs),
        CheckSafeSwap(uniswap, lightClient, safe.network),
        imported = imported,
    )

    /** Send from the Safe at [safe], with the token at [asset] chosen (null: the network's currency). */
    fun safeProposeViewModel(safe: AccountId, asset: Address?): SafeProposeViewModel = SafeProposeViewModel(
        safe,
        asset,
        ObserveAccounts(accounts),
        accountValue(),
        observeSafe(),
        DescribeKeyStore(keys),
        ResolveRecipient(names),
        ProposeSafeTx(observeSafe(), nativeBalances, tokenBalances, transactionInputs, safeTxQueue, safe.network),
        signSafeTx(safe.network),
        EncodeSigningRequest(signingRequests),
    )

    /**
     * Swap from the Safe at [safe], with the token at [asset] sold (null: the network's currency); or,
     * with [replacing], the swap queued under that hash proposed again in its place.
     */
    fun safeSwapViewModel(safe: AccountId, asset: Address?, replacing: Digest?): SafeSwapViewModel = SafeSwapViewModel(
        safe,
        asset,
        replacing,
        ObserveAccounts(accounts),
        accountValue(),
        observeSafe(),
        observeSafeTxQueue(safe.network),
        DescribeKeyStore(keys),
        ProposeSafeSwap(
            observeSafe(),
            FindUniswapRoute(uniswap, transactionInputs),
            uniswap,
            nativeBalances,
            tokenBalances,
            transactionInputs,
            safeTxQueue,
            lightClient,
            safe.network,
        ),
        signSafeTx(safe.network),
    )

    /** The settings of the Safe at [safe], and the changes of them its owners held here propose. */
    fun safeSettingsViewModel(safe: AccountId): SafeSettingsViewModel = SafeSettingsViewModel(
        safe,
        ObserveAccounts(accounts),
        observeSafe(),
        DescribeKeyStore(keys),
        ProposeSafeSettingsChange(observeSafe(), transactionInputs, safeTxQueue, safe.network),
        signSafeTx(safe.network),
        EncodeSigningRequest(signingRequests),
    )

    /** Import a signing request for the Safe at [safe]. */
    fun safeImportViewModel(safe: AccountId): SafeImportViewModel = SafeImportViewModel(
        safe,
        ObserveAccount(accounts),
        ImportSigningRequest(signingRequests, observeSafe(), signatureRecovery, safeTxQueue),
    )

    private fun observeSafe() = ObserveSafe(addressKinds, accounts)

    private fun observeSafeTxQueue(network: Network) =
        ObserveSafeTxQueue(safeTxQueue, observeSafe(), signatureRecovery, network)

    private fun signSafeTx(network: Network) = SignSafeTx(
        signer,
        observeSafe(),
        signatureRecovery,
        safeTxQueue,
        network,
    )

    /** Send from the account at [from], with the token at [asset] chosen (null: the network's currency). */
    fun sendViewModel(from: AccountId, asset: Address?): SendViewModel = SendViewModel(
        from,
        asset,
        ObserveAccounts(accounts),
        accountValue(),
        DescribeKeyStore(keys),
        ResolveRecipient(names),
        QuoteTransfer(
            transactionInputs,
            nativeBalances,
            tokenBalances,
            sentTransfers,
            cowOrders,
            cowChain,
            lightClient,
            from.network,
        ),
        SendTransfer(signer, broadcaster, sentTransfers),
        RebroadcastTransfer(broadcaster, sentTransfers, receipts),
        WarmFeeHistory(transactionInputs),
    )

    /**
     * Swap from the account at [from], selling the token at [sell] (null: the network's currency),
     * opened with [fill] filled in when given: the same swap again; [continuing] the swap of it whose
     * next step waits, quoted again.
     */
    fun swapViewModel(
        from: AccountId,
        sell: Address?,
        fill: SwapFill? = null,
        continuing: Boolean = false,
    ): SwapViewModel = SwapViewModel(
        from,
        sell,
        ObserveAccounts(accounts),
        accountValue(),
        DescribeKeyStore(keys),
        QuoteSwap(
            FindUniswapRoute(uniswap, transactionInputs),
            transactionInputs,
            nativeBalances,
            tokenBalances,
            sentTransfers,
            cowOrders,
            cowChain,
            lightClient,
            from.network,
        ),
        SendSwap(transactionInputs, signer, broadcaster, sentTransfers),
        QuoteTokenApproval(transactionInputs, nativeBalances, sentTransfers, from.network),
        SendTokenApproval(signer, broadcaster, sentTransfers),
        RebroadcastTransfer(broadcaster, sentTransfers, receipts),
        WarmFeeHistory(transactionInputs),
        ObserveSwapVenue(swapVenue),
        ChooseSwapVenue(swapVenue),
        CowOfferedOnThisDevice(lightClient),
        QuoteCowSwap(
            cowChain,
            cowApi,
            FindUniswapRoute(uniswap, transactionInputs),
            transactionInputs,
            tokenBalances,
            sentTransfers,
            cowOrders,
            historyStarts,
            lightClient,
            from.network,
        ),
        PlaceCowOrder(FindUniswapRoute(uniswap, transactionInputs), signer, cowOrders, cowApi, lightClient),
        KeepWaitingSwap(waitingSwaps),
        DropWaitingSwap(waitingSwaps),
        FindWaitingSwap(waitingSwaps),
        fill = fill,
        continuing = continuing,
    )

    /**
     * Where the CoW order [uid] signed here on [network] stands: its end as the chain proves it, CoW's
     * word on it while it may fill, and its cancellation on chain.
     */
    fun cowOrderViewModel(network: Network, uid: CowOrderUid): CowOrderViewModel = CowOrderViewModel(
        uid,
        ObserveCowOrder(cowOrders, network),
        ObserveCowOrderLife(cowApi, network),
        ObserveAccounts(accounts),
        DescribeKeyStore(keys),
        QuoteCowCancellation(transactionInputs, nativeBalances, sentTransfers, cowChain, lightClient, network),
        SendCowCancellation(signer, broadcaster, sentTransfers),
        RebroadcastTransfer(broadcaster, sentTransfers, receipts),
        network,
    )

    /** Where the transfer [hash] on [network] stands, and for a Safe it deployed, where its listing does. */
    fun transferStatusViewModel(network: Network, hash: String): TransferStatusViewModel = TransferStatusViewModel(
        hash,
        ObserveSentTransfer(sentTransfers, receipts, transactionHistory),
        ObserveAccounts(accounts),
        RebroadcastTransfer(broadcaster, sentTransfers, receipts),
        network,
    )

    /**
     * What an account's Activity tab lists: the transactions this device sent for it, its token
     * transfers and, for a Safe, its events on chain (docs/wallet-ux.md, screen 25).
     */
    private fun accountActivity(): AccountActivity = AccountActivity(
        ObserveAccountHistory(transactionHistory),
        ObserveAccounts(accounts),
        ObserveSafeHistory(logIndex, historyStarts),
        ObserveTokenTransfers(logIndex, historyStarts),
        knownTokens(),
        ObserveSentToAddresses(transactionHistory),
        ObserveAccountCowOrders(cowOrders),
        ObserveWaitingSwaps(waitingSwaps),
    )

    /**
     * The detail of the row [key] of the Activity of the [account] (docs/wallet-ux.md, screen 26),
     * read from what its Activity is read from.
     */
    fun activityDetailViewModel(
        account: AccountId,
        key: ActivityKey,
    ): ActivityDetailViewModel = ActivityDetailViewModel(
        account,
        key,
        ObserveAccountHistory(transactionHistory),
        ObserveAccounts(accounts),
        ObserveSafeHistory(logIndex, historyStarts),
        ObserveTokenTransfers(logIndex, historyStarts),
        knownTokens(),
        ObserveSentToAddresses(transactionHistory),
        ObserveAmountsHidden(amountsHidden),
        toggleAmountsHidden,
    )

    /** Create Safe (docs/wallet-ux.md, screens 13 to 15). */
    fun createSafeViewModel(): CreateSafeViewModel = CreateSafeViewModel(
        ObserveAccounts(accounts),
        DescribeKeyStore(keys),
        ResolveRecipient(names),
        PlanSafe(safeReleases),
        QuoteSafeDeployment(addressKinds, nativeBalances, transactionInputs, sentTransfers),
        DeploySafe(signer, broadcaster, sentTransfers),
        RebroadcastTransfer(broadcaster, sentTransfers, receipts),
        WarmFeeHistory(transactionInputs),
        ObserveEnabledNetworks(networkSettings),
        SetNetworkEnabled(networkSettings),
    )

    /** Add on another network (docs/networks.md, N6), for the EOA [account]. */
    fun addOnNetworksViewModel(account: AccountId): AddOnNetworksViewModel =
        AddOnNetworksViewModel(
            account,
            ObserveAccounts(accounts),
            ObserveEnabledNetworks(networkSettings),
            SetNetworkEnabled(networkSettings),
            AddOnNetworks(accounts, walletChanges),
        )

    fun deriveAccountViewModel(account: AccountId): DeriveAccountViewModel =
        DeriveAccountViewModel(account, ObserveAccount(accounts), DeriveAccount(keys, accounts, walletChanges))

    fun receiveViewModel(account: AccountId): ReceiveViewModel =
        ReceiveViewModel(account, ObserveAccount(accounts), ObserveEnabledNetworks(networkSettings))

    fun renameAccountViewModel(account: AccountId): RenameAccountViewModel =
        RenameAccountViewModel(account, ObserveAccount(accounts), RenameAccount(accounts))

    fun removeAccountViewModel(account: AccountId): RemoveAccountViewModel =
        RemoveAccountViewModel(
            account,
            ObserveAccount(accounts),
            ObserveAccounts(accounts),
            RemoveAccount(accounts, keys, walletChanges),
        )

    fun watchViewModel(): WatchViewModel = WatchViewModel(
        ObserveAddressKind(addressKinds),
        ObserveAccounts(accounts),
        WatchAddress(accounts, addressKinds),
        ResolveRecipient(names),
        ObserveEnabledNetworks(networkSettings),
        SetNetworkEnabled(networkSettings),
        HoldNetwork(networkDemand),
    )

    fun settingsViewModel(): SettingsViewModel =
        SettingsViewModel(
            ObserveNetworkOverview(networkSettings, accounts, lightClient, transactionHistory),
            SetNetworkEnabled(networkSettings),
            knownTokens(),
            displayCurrencies(),
            screenCaptureDebugSwitch,
            ObserveDebugScreen(debugScreen),
            SetDebugScreen(debugScreen),
            SyncPastStaleAnchor(lightClient, staleAnchorConsent),
            ObserveInterfaceMode(interfaceMode),
            SetInterfaceMode(interfaceMode),
            ObserveNetworkLive(networkFreshness),
        )

    /** Whether the bottom bar shows the Debug tab: one for the app, in the host's scope. */
    fun debugTabViewModel(): DebugTabViewModel = DebugTabViewModel(ObserveDebugTab(debugScreen, interfaceMode))

    /** The view the screens are drawn in: one for the app, in the host's scope. */
    fun interfaceModeViewModel(): InterfaceModeViewModel = InterfaceModeViewModel(ObserveInterfaceMode(interfaceMode))

    /** The Debug tab: the debug log. */
    fun debugViewModel(): DebugViewModel =
        DebugViewModel(ObserveDebugLog(debugLog), SetDebugLogLevel(debugLog), ClearDebugLog(debugLog))

    private fun observeDappConnections() = ObserveDappConnections(dappSessions, accounts, networkSettings)

    private fun observeDappMessages() = ObserveDappMessages(dappRequests, accounts, networkSettings)

    fun connectionsViewModel(): ConnectionsViewModel = ConnectionsViewModel(
        observeDappConnections(),
        observeDappMessages(),
        DisconnectDapp(dappSessions),
        ExtendDappSession(dappSessions),
    )

    /** The requests to sign waiting, which the app opens their review for as each arrives. */
    fun dappRequestsWaitingViewModel(): DappRequestsWaitingViewModel =
        DappRequestsWaitingViewModel(observeDappMessages())

    /** The review of the request [requestKey] names (`topic/id`, as its route carries it): screen D4. */
    fun dappRequestViewModel(requestKey: String): DappRequestViewModel = DappRequestViewModel(
        requestKey,
        observeDappMessages(),
        SignDappMessage(dappRequests, accounts, networkSettings, signer),
        RefuseDappRequest(dappRequests, accounts, networkSettings),
        DescribeKeyStore(keys),
    )

    /** The `wc:` link the platform opened the app with, which the host offers and Connect a dApp takes. */
    val incomingDappLink: IncomingDappLink = IncomingDappLink()

    fun pairDappViewModel(): PairDappViewModel = PairDappViewModel(PairDapp(dappSessions), incomingDappLink)

    /** The review of the proposal [proposalKey] names (`pairing/id`, as its route carries it). */
    fun dappProposalViewModel(proposalKey: String): DappProposalViewModel = DappProposalViewModel(
        proposalKey,
        observeDappConnections(),
        ApproveDappProposal(dappSessions, accounts, networkSettings),
        RejectDappProposal(dappSessions),
    )

    fun currencyViewModel(): CurrencyViewModel =
        CurrencyViewModel(displayCurrencies(), SetDisplayCurrency(priceFeeds, displayCurrency, accounts))

    fun tokensViewModel(): TokensViewModel =
        TokensViewModel(knownTokens(), SetTokenHidden(userTokens), RemoveToken(userTokens))

    /** Add token by address (docs/wallet-ux.md, screen 20), its field starting with [address]. */
    fun addTokenViewModel(address: String = "", network: Network? = null): AddTokenViewModel =
        AddTokenViewModel(
            knownTokens(),
            ObserveTokenMetadata(tokenMetadata),
            AddToken(userTokens, tokenMetadata, knownTokens()),
            ObserveEnabledNetworks(networkSettings),
            SetNetworkEnabled(networkSettings),
            HoldNetwork(networkDemand),
            address,
            network,
        )

    private fun knownTokens() = ObserveKnownTokens(tokenCatalog, userTokens, tokenMetadata)

    private fun displayCurrencies() = ObserveDisplayCurrencies(priceFeeds, displayCurrency, accounts)

    private fun accountValue() = ObserveAccountValue(
        ObserveNativeBalance(nativeBalances),
        ObserveTokenHoldings(knownTokens(), tokenBalances),
        ObserveUsdPrice(priceFeeds),
        displayCurrencies(),
    )

    fun createAccountViewModel(): CreateAccountViewModel = CreateAccountViewModel(
        NewRecoveryPhrase(keys),
        ImportMnemonicAccount(keys, accounts, walletChanges),
        DescribeKeyStore(keys),
        ObserveEnabledNetworks(networkSettings),
        SetNetworkEnabled(networkSettings),
    )

    fun updateRequiredViewModel(): UpdateRequiredViewModel =
        UpdateRequiredViewModel(observeUpgradeWarnings())

    fun securityViewModel(): SecurityViewModel =
        SecurityViewModel(DescribeKeyStore(keys), EnableBiometricUnlock(keys), DisableBiometricUnlock(keys))

    fun mnemonicImportViewModel(): MnemonicImportViewModel =
        MnemonicImportViewModel(
            ImportMnemonicAccount(keys, accounts, walletChanges),
            DescribeKeyStore(keys),
            ObserveEnabledNetworks(networkSettings),
            SetNetworkEnabled(networkSettings),
        )
}
