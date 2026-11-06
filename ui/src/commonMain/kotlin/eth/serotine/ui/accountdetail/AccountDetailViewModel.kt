package eth.serotine.ui.accountdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenChange
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.AccountValue
import eth.serotine.domain.usecase.DecodedSafeTx
import eth.serotine.domain.usecase.DropWaitingSwap
import eth.serotine.domain.usecase.EnsNameCheck
import eth.serotine.domain.usecase.HasSwapVenue
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAmountsHidden
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.ObserveEnsNames
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNetworkLive
import eth.serotine.domain.usecase.AddNft
import eth.serotine.domain.usecase.NftAddition
import eth.serotine.domain.usecase.NftHoldings
import eth.serotine.domain.usecase.RemoveAddedNft
import eth.serotine.domain.usecase.ObserveNfts
import eth.serotine.domain.usecase.ObserveSafe
import eth.serotine.domain.usecase.ObserveSafeTxQueue
import eth.serotine.domain.usecase.ObserveUpgradeWarnings
import eth.serotine.domain.usecase.RefreshNetworkData
import eth.serotine.domain.usecase.SafeReading
import eth.serotine.domain.usecase.SafeTxQueueReading
import eth.serotine.domain.usecase.SafeTxRefusal
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.SetTokenHidden
import eth.serotine.domain.usecase.ToggleAmountsHidden
import eth.serotine.domain.usecase.UpgradeWarning
import eth.serotine.domain.usecase.Valuation
import eth.serotine.ui.accounts.UnreachableNetwork
import eth.serotine.ui.activity.AccountActivity
import eth.serotine.ui.activity.ActivityOrder
import eth.serotine.ui.activity.ActivityUiState
import eth.serotine.ui.activity.ordered
import eth.serotine.ui.components.AMOUNTS_NOT_SAVED
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.DecodingToken
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.LightClientHealth
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.accountName
import eth.serotine.ui.components.agoText
import eth.serotine.ui.components.balanceText
import eth.serotine.ui.components.displayName
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.ensNameNote
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.components.forDecoding
import eth.serotine.ui.components.formatUnits
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.lastKnownNote
import eth.serotine.ui.components.maskAmount
import eth.serotine.ui.components.notTurnedOn
import eth.serotine.ui.components.redatedWhile
import eth.serotine.ui.components.safeTxActionItem
import eth.serotine.ui.components.totalNote
import eth.serotine.ui.components.totalText
import eth.serotine.ui.components.valuationText
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The account detail screen's ViewModel: the account under [address], what it holds and what
 * that is worth in the display currency ([ObserveAccountValue]), on [network], as
 * [AccountDetailUiState], named as [ObserveEnsNames] finds; for a Safe also its verified
 * owners ([ObserveSafe]) and the transactions queued for them on this device
 * ([ObserveSafeTxQueue], decoded here, a token transfer's amount in the units of a token the
 * wallet knows: [ObserveKnownTokens]); and its Activity ([AccountActivity]), read only while its
 * tab is shown. Its intents are the order of the rows ([sortBy], and [orderActivity] for the Activity's), the
 * segment shown ([show]) and hiding a token
 * ([hideToken], said through [messageShown]); what else the user does from the screen is
 * navigation (Send, Receive, Rename, Remove, a row of its Activity, a queued transaction, Import a
 * request) or the
 * clipboard, which the route handles. Whether its network is on ([ObserveEnabledNetworks]) greys out
 * Send and Swap, and [turnOnNetwork] turns it on (docs/networks.md, N19).
 */
class AccountDetailViewModel(
    private val account: AccountId,
    observeAccount: ObserveAccount,
    observeAccountValue: ObserveAccountValue,
    observeSafe: ObserveSafe,
    observeSafeTxQueue: ObserveSafeTxQueue,
    observeKnownTokens: ObserveKnownTokens,
    private val setTokenHidden: SetTokenHidden,
    /** Whether the ENS name the account's name follows still points to it. */
    observeEnsNames: ObserveEnsNames,
    /** What its Activity tab lists. */
    activity: AccountActivity,
    observeEnabledNetworks: ObserveEnabledNetworks,
    private val setNetworkEnabled: SetNetworkEnabled,
    /** Whether this version can still follow the account's network; one blocked alone greys out Send and Swap. */
    observeUpgradeWarnings: ObserveUpgradeWarnings,
    /** Whether Swap is offered on the account's network: greyed out where no venue is pinned. */
    hasSwapVenue: HasSwapVenue,
    /** Proves the account's network's balances and prices again on [refresh]; its own reads are proven as it opens. */
    private val refreshNetworkData: RefreshNetworkData,
    /** Whether the account's network updates live, which simple mode's freshness line says. */
    observeNetworkLive: ObserveNetworkLive,
    /** The account's network's light client, which simple mode's "can't check" banner reads. */
    observeLightClient: ObserveLightClient,
    /** Lets a swap whose next step waits go, at the user's word: Dismiss on its card in the Activity tab. */
    private val dropWaitingSwap: DropWaitingSwap,
    /** Whether amounts are hidden (docs/wallet-ux.md, *Hiding amounts*): every amount masked, the eye closed. */
    observeAmountsHidden: ObserveAmountsHidden,
    /** Hides amounts or shows them again: what a tap on the eye asks. */
    private val toggleAmountsHidden: ToggleAmountsHidden,
    /** The account's NFTs, below its assets (docs/wallet-ux.md, *NFTs*, issue #246). */
    observeNfts: ObserveNfts,
    /** Adds an NFT by contract and token id, once the account is proven to hold it. */
    private val proveAndAddNft: AddNft,
    /** Takes an added NFT off the account's list. */
    private val removeAddedNft: RemoveAddedNft,
    /** How a row dates a balance last verified (unix seconds). */
    private val formatTime: (Long) -> String = ::formatDateTime,
    /** Now, in unix seconds: what simple mode's ages are dated against. */
    private val clock: () -> Long = ::epochSecondsNow,
) : ViewModel() {
    /** The account this screen is about: its address, on its network. */
    private val address: Address = account.address

    /** The account's network: everything here is read and sent there. */
    private val network: Network = account.network

    /**
     * Whether amounts are hidden, read once for the screen and its Activity. No value before the
     * choice is read: nothing is drawn until then, so a hidden amount never shows for a moment.
     */
    private val amountsHidden = observeAmountsHidden()
        // Its last value forgotten once it stops: back after that, the choice is read again, never replayed.
        .shareIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            replay = 1,
        )

    /** Proves the account's network's balances and prices again now: what pulling the screen down asks. */
    fun refresh() {
        lastRefresh.value = Refresh(clock(), oldestShown)
        refreshNetworkData(setOf(network))
    }

    /**
     * The last refresh asked: when ([Refresh.at], unix seconds), and the oldest proof shown then
     * ([Refresh.oldest]). The screen says it is refreshing until that changes, for 15 s at most.
     */
    private data class Refresh(val at: Long, val oldest: Long?)

    private val lastRefresh = MutableStateFlow<Refresh?>(null)

    /** The oldest proof the screen shows, as last computed: what a refresh waits to see change. */
    private var oldestShown: Long? = null

    /** Whether the account's network updates live: not until known. */
    private val live = observeNetworkLive(network).onStart { emit(false) }

    /** Whether the account's network is on: until known, as if it were, as nothing is sent from here. */
    private val networkOn =
        observeEnabledNetworks().map { network in it }.distinctUntilChanged().onStart { emit(true) }

    /** Whether this version can no longer follow the account's network: until known, as if it could. */
    private val networkBlocked = observeUpgradeWarnings()
        .map { warnings -> warnings.of(network) is UpgradeWarning.UpdateRequired }
        .distinctUntilChanged()
        .onStart { emit(false) }

    /** Whether a swap venue is pinned on the account's network: until known, as if one were. */
    private val swapVenue = flow { emit(hasSwapVenue(network)) }.onStart { emit(true) }.distinctUntilChanged()

    // The checksum once, not on every balance reading.
    private val display = Eip55.format(address)

    /** What the user chose to see, and what the last Hide token did until the screen has said it. */
    private data class View(
        val sort: AssetSort = AssetSort.VALUE,
        val activityOrder: ActivityOrder = ActivityOrder.NEWEST_FIRST,
        val segment: DetailSegment = DetailSegment.ASSETS,
        val message: String? = null,
    )

    private val view = MutableStateFlow(View())

    // Collected once, by both the Safe's reading and the state.
    private val lookup = observeAccount(account)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 1)

    private val isSafe = lookup.map { (it as? AccountLookup.Found)?.account?.kind == AccountKind.SAFE }
        .distinctUntilChanged()

    /** A Safe's verified state; read for a Safe only, none for any other account. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val safe = isSafe.flatMapLatest { isSafe ->
        // Never holding the screen back: a Safe's read takes a dozen proofs.
        if (isSafe) {
            observeSafe(account).map<SafeReading, SafeReading?> { it }.onStart { emit(SafeReading.Checking) }
        } else {
            flowOf(null)
        }
    }

    /** The check of the ENS name the account's name follows; none for a name of the user's, nor before the first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ensName = lookup.map { (it as? AccountLookup.Found)?.account?.ensName }
        .distinctUntilChanged()
        .flatMapLatest { name ->
            if (name == null) {
                flowOf(null)
            } else {
                observeEnsNames.check(
                    network,
                    address,
                    name,
                ).map<EnsNameCheck, EnsNameCheck?> { it }.onStart { emit(null) }
            }
        }

    /** A Safe's queue, with the tokens its transfers are shown in; none for any other account. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val queue = isSafe.flatMapLatest { isSafe ->
        if (isSafe) {
            combine(observeSafeTxQueue(address), observeKnownTokens(network)) { reading, tokens ->
                QueueView.Read(reading, tokens.forDecoding())
            }.onStart<QueueView> { emit(QueueView.Checking) }
        } else {
            flowOf(QueueView.None)
        }
    }

    /**
     * The account's Activity, read while its tab is shown and for 5 s after, as the screen's own
     * state is: back on the tab within that, it is there at once, and not read again.
     */
    private val sharedActivity = activity.of(account, amountsHidden).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        ActivityUiState.Loading,
    )

    /** The Activity while its tab is shown for an account of the wallet; Loading, and nothing read, otherwise. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val activityState = combine(
        view.map { it.segment == DetailSegment.ACTIVITY },
        lookup.map { it is AccountLookup.Found },
    ) { onTab, found -> onTab && found }
        .distinctUntilChanged()
        .flatMapLatest { shown -> if (shown) sharedActivity else flowOf(ActivityUiState.Loading) }

    /** Whether the unlisted collections' NFTs are shown: collapsed until the user opens them (owner ruling, #246). */
    private val unlistedNftsOpen = MutableStateFlow(false)

    /** The *Add an NFT* dialog while open. */
    private val addingNft = MutableStateFlow<AddNftForm?>(null)

    /**
     * The account's NFTs, read while its Assets tab is shown and for 5 s after, as its Activity is:
     * back within that, they are there at once and not proven again.
     */
    private val sharedNfts = observeNfts(account).stateIn<NftHoldings?>(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        null,
    )

    /** The account's NFTs, display-ready, while the Assets tab is shown; null otherwise, and until first read. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val nfts = combine(
        view.map { it.segment == DetailSegment.ASSETS }
            .distinctUntilChanged()
            .flatMapLatest { shown -> if (shown) sharedNfts else flowOf(null) },
        unlistedNftsOpen,
        networkOn,
        addingNft,
    ) { holdings, open, on, adding ->
        holdings?.let { nftSection(it, open, network.displayName.takeUnless { on }, formatTime, clock()) }
            ?.let { section ->
                section.copy(
                    addReason = if (on) {
                        section.addReason
                    } else {
                        "${network.displayName} is off in this wallet, so nothing on it is checked and no NFT can " +
                            "be proven held. Turned on, NFTs can be added again."
                    },
                    adding = adding,
                )
            }
    }

    /** What the queue segment is drawn from. */
    private sealed interface QueueView {
        data object None : QueueView
        data object Checking : QueueView
        data class Read(val reading: SafeTxQueueReading, val tokens: List<DecodingToken>) : QueueView
    }

    val uiState: StateFlow<AccountDetailUiState> =
        combine(
            lookup,
            observeAccountValue(account),
            safe,
            queue,
            combine(
                combine(view, ensName, ::Pair),
                activityState,
                combine(networkOn, networkBlocked, swapVenue, ::Triple),
                ::Triple,
            ),
        ) { lookup, value, safe, queue, (named, activity, networkState) ->
            val (networkOn, networkBlocked, swapVenue) = networkState
            val (view, ensName) = named
            val rows = rows(value, off = network.displayName.takeUnless { networkOn })
            val state = when (lookup) {
                is AccountLookup.Found -> AccountDetailUiState.Ready(
                    label = accountName(lookup.account, display, ensName),
                    address = display,
                    network = NetworkLabel.of(network),
                    kind = lookup.account.kind,
                    // A Safe's control is its verified owners'; until they are read, watch-only.
                    control = (safe as? SafeReading.Verified)?.let { Control.of(it.control) }
                        ?: Control.of(lookup.account.kind, lookup.account.custody),
                    total = totalText(value.total, value.currency),
                    totalNote = totalNote(value.total, network, value.currency).ifEmpty { null },
                    assets = sorted(rows, view.sort),
                    pendingAssets = value.tokens.count { awaitingFirst(it.holding.reading) },
                    sort = view.sort,
                    tokensNote = tokensNote(value),
                    pricesNote = (if (value.pooled()) PRICES_WITH_POOLS else PRICES) +
                        if (network.testnet) ", testnet prices" else "",
                    message = view.message,
                    safe = safe?.let(::safeDetails),
                    segment = view.segment,
                    kindNote = when {
                        lookup.account.kind != AccountKind.SAFE -> null
                        safe == SafeReading.NotASafe -> "Not a recognised Safe"
                        else -> "Safe"
                    },
                    threshold = (safe as? SafeReading.Verified)
                        ?.let { "${it.state.threshold}/${it.state.owners.size}" },
                    queue = queueOf(queue, safe),
                    nameNote = ensNameNote(lookup.account, ensName),
                    // Not the last one read while another tab is shown: that tab's state says Loading.
                    activity = if (view.segment == DetailSegment.ACTIVITY) {
                        activity.ordered(view.activityOrder)
                    } else {
                        ActivityUiState.Loading
                    },
                    activityOrder = view.activityOrder,
                    networkOn = networkOn,
                    networkBlocked = networkBlocked,
                    swapVenue = swapVenue,
                )
                AccountLookup.NotInList -> AccountDetailUiState.Gone
                AccountLookup.ListUnavailable -> AccountDetailUiState.Unavailable(LIST_UNAVAILABLE)
            }
            Built(state, rows, value, networkOn)
        }
            // Simple mode's ages, re-dated every second while one is shown; the rows and the queue are
            // built above once per change, and only dated below.
            .let { built -> combine(built, live, lastRefresh, observeLightClient(network), ::Dating) }
            .redatedWhile { it.dated }
            .map { it.finish(clock()) }
            .combine(nfts) { state, nfts ->
                if (state is AccountDetailUiState.Ready) state.copy(nfts = nfts) else state
            }
            // Masked last, on the finished state (the Activity masks its own, as it is built).
            .combine(amountsHidden) { state, hidden -> state.withAmountsHidden(hidden) }
            .stateIn(
            scope = viewModelScope,
            // As on Accounts: a screen back after the timeout must not show an old balance as current.
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            initialValue = AccountDetailUiState.Loading,
        )

    /** Hides amounts, or shows them again: the eye beside Back. A choice that could not be saved says so. */
    fun toggleAmounts() {
        viewModelScope.launch { if (!toggleAmountsHidden()) view.update { it.copy(message = AMOUNTS_NOT_SAVED) } }
    }

    /** Turns the account's network on again: the way to Send and Swap on one that is off (N19). */
    fun turnOnNetwork() {
        viewModelScope.launch {
            if (!setNetworkEnabled(network, on = true)) view.update { it.copy(message = notTurnedOn(network)) }
        }
    }

    /** Lets the swap selling the token at [sell] (its address) wait no longer: its card in the Activity tab goes. */
    fun dismissWaiting(sell: String) {
        val token = Address.parse(sell) ?: return
        // Run to its end, the screen left or not: a dismissed card does not come back.
        viewModelScope.launch { withContext(NonCancellable) { dropWaitingSwap(account, token) } }
    }

    /** Orders the rows by [order]. */
    fun sortBy(order: AssetSort) {
        view.update { it.copy(sort = order) }
    }

    /** Runs the Activity's rows the way [order] says: newest first, or the oldest on top. */
    fun orderActivity(order: ActivityOrder) {
        view.update { it.copy(activityOrder = order) }
    }

    /** Shows the account's [segment]: its Assets or Activity, or a Safe's Owners or Queue. */
    fun show(segment: DetailSegment) {
        view.update { it.copy(segment = segment) }
    }

    /**
     * Hides the token at [contract], shown as [symbol], from every account; Settings → Tokens
     * shows it again. How that went is the state's [AccountDetailUiState.Ready.message].
     */
    fun hideToken(contract: String, symbol: String) {
        val token = Address.parse(contract) ?: return
        viewModelScope.launch {
            val said = when (setTokenHidden(network, token, hidden = true)) {
                TokenChange.Changed, TokenChange.AlreadyPresent -> "$symbol hidden. Settings → Tokens shows it again."
                TokenChange.NotFound, TokenChange.NotSaved ->
                    "$symbol could not be hidden: the token settings cannot be saved right now."
            }
            view.update { it.copy(message = said) }
        }
    }

    /** Opens the *Add an NFT* dialog. */
    fun openAddNft() {
        addingNft.value = AddNftForm()
    }

    /** The proof of an NFT being added, while one is made: Cancel stops waiting for it. */
    private var addingJob: Job? = null

    /** Closes the *Add an NFT* dialog; a proof still being made is given up, and nothing is added. */
    fun closeAddNft() {
        addingJob?.cancel()
        addingJob = null
        addingNft.value = null
    }

    /**
     * Adds the token [tokenId] (decimal, or `0x` hex) of the contract at [contract], once the account is
     * proven to hold it; the dialog says why when nothing was added, and closes when it was.
     */
    fun addNft(contract: String, tokenId: String) {
        val address = Address.parse(contract)
        val id = parseTokenId(tokenId)
        val error = when {
            address == null -> "That is not a contract address: 0x and 40 hexadecimal digits."
            id == null -> "That is not a token id: a whole number, or 0x and hexadecimal digits."
            else -> null
        }
        if (error != null || address == null || id == null) {
            addingNft.value = AddNftForm(error = error)
            return
        }
        addingNft.value = AddNftForm(busy = true)
        addingJob?.cancel()
        addingJob = viewModelScope.launch {
            val outcome = proveAndAddNft(account, address, id)
            addingNft.value = when (outcome) {
                is NftAddition.Added -> null
                NftAddition.AlreadyAdded -> AddNftForm(error = "That NFT is added to this account already.")
                NftAddition.NotHeld -> AddNftForm(
                    error = "Nothing verified says this account holds token ${id.toDecimal()} of that contract.",
                )
                is NftAddition.Unavailable -> AddNftForm(
                    error = "Whether this account holds it cannot be proven right now. Try again in a moment.",
                )
                NftAddition.NotSaved -> AddNftForm(error = "The NFT could not be saved. Nothing was added.")
            }
            if (outcome is NftAddition.Added) {
                view.update { it.copy(message = "NFT added. It is listed once this account's NFTs are read.") }
            }
        }
    }

    /** Takes the added NFT [key] names ([NftRow.addedKey]) off the account's list. */
    fun removeNft(key: String) {
        val nft = nftOfKey(key) ?: return
        viewModelScope.launch {
            val removed = removeAddedNft(account, nft) == TokenChange.Changed
            view.update { it.copy(message = if (removed) "NFT removed" else "The NFT could not be removed.") }
        }
    }

    /** Opens the unlisted collections' NFTs, or collapses them again. */
    fun toggleUnlistedNfts() {
        unlistedNftsOpen.update { !it }
    }

    /** The screen has said [AccountDetailUiState.Ready.message]. */
    fun messageShown() {
        view.update { it.copy(message = null) }
    }

    /** A Safe's queue, display-ready, or none for another account. */
    private fun queueOf(view: QueueView, safe: SafeReading?): SafeQueue? = when (view) {
        QueueView.None -> null
        QueueView.Checking -> SafeQueue.Checking
        is QueueView.Read -> when (val reading = view.reading) {
            SafeTxQueueReading.Unreadable -> SafeQueue.Unavailable(QUEUE_UNREADABLE)
            is SafeTxQueueReading.Loaded -> {
                val threshold = (safe as? SafeReading.Verified)?.state?.threshold
                SafeQueue.Shown(
                    rows = reading.transactions.map { queueRow(it, reading.safeNonce, threshold, view.tokens) },
                    note = if (reading.safeNonce == null) QUEUE_UNVERIFIED else null,
                )
            }
        }
    }

    private fun queueRow(tx: DecodedSafeTx, safeNonce: Long?, threshold: Int?, tokens: List<DecodingToken>): QueueRow {
        val request = tx.request
        val action = safeTxActionItem(tx.action, request.nonce, tokens, network)
        val signed = request.signatures.size
        return QueueRow(
            hash = "0x" + request.hash.hex,
            action = action.title,
            about = action.addresses.firstOrNull()?.address,
            nonce = "Nonce ${request.nonce}" + when {
                safeNonce == null -> ""
                request.nonce == safeNonce -> " · next"
                else -> " · ${request.nonce - safeNonce} before it"
            },
            // Those this device holds: approvals on chain, and signatures elsewhere, show on the transaction.
            signatures = when {
                threshold != null -> "$signed of $threshold signatures"
                signed == 1 -> "1 signature"
                else -> "$signed signatures"
            } + " on this device",
            // A delegate call's warning is its refusal, said below.
            warning = action.warning.takeIf { tx.action !is SafeTxAction.DelegateCall },
            refusal = when (tx.refusal) {
                SafeTxRefusal.DelegateCall -> "Not signed here: a delegate call"
                SafeTxRefusal.GasTerms -> "Not signed here: it carries gas or refund terms"
                is SafeTxRefusal.UnpinnedFallbackHandler -> "Not signed here: a fallback handler that is not pinned"
                SafeTxRefusal.UndecodedSelfCall -> "Not signed here: a call to the Safe itself not decoded exactly"
                is SafeTxRefusal.ModuleEnabled -> "Not signed here: it enables a module"
                else -> null
            },
        )
    }

    /** A Safe's verified owners, display-ready, or why there are none to show. */
    private fun safeDetails(reading: SafeReading): SafeDetails = when (reading) {
        SafeReading.Checking -> SafeDetails.Checking
        is SafeReading.Verified -> {
            val state = reading.state
            val inWallet = reading.ownersInWallet.associateBy { it.address }
            SafeDetails.Shown(
                title = "Safe ${state.version}" + (if (state.l2) " (L2)" else "") + " · nonce ${state.nonce}",
                signers = "${state.threshold} of ${state.owners.size} owners must sign",
                owners = state.owners.map { owner ->
                    val account = inWallet[owner]
                    OwnerRow(Eip55.format(owner), account?.label, account?.custody == AccountCustody.InKeyStore)
                },
                modules = state.modules.map(Eip55::format),
                guard = state.guard?.let(Eip55::format),
                checkedAt = "Verified at ${block(reading.blockNumber)}",
                moduleGuard = state.moduleGuard?.let(Eip55::format),
            )
        }
        SafeReading.NotASafe ->
            SafeDetails.Unavailable("This address no longer holds a recognised Safe: its owners are not shown.")
        is SafeReading.Unavailable -> SafeDetails.Unavailable(
            when (reading.reason) {
                ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so the owners cannot be read."
                ReadUnavailable.NETWORK_OFF ->
                    "The network's light client is not running, so the owners cannot be read."
                ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so the owners cannot be read."
                ReadUnavailable.SYNCING -> "The light client is still syncing, so the owners cannot be read yet."
                ReadUnavailable.UNPROVEN -> "The Safe's storage could not be verified this time. Serotine keeps trying."
                ReadUnavailable.REFUSED -> "The light client on this device cannot read this Safe's storage."
            },
        )
    }

    /**
     * The screen's state as built from its readings, before simple mode's ages are added: [rows] with
     * when each amount last verified was ([Row.asOf]), the [value] the proofs' times come from, and
     * whether the network is [on].
     */
    private class Built(
        val state: AccountDetailUiState,
        val rows: List<Row>,
        val value: AccountValue,
        val on: Boolean,
    ) {
        /** The times of the verified heads the amounts shown were proven at; a token not held is not shown. */
        val proven: List<Long> = listOfNotNull((value.native as? BalanceReading.Verified)?.provenAt) +
            value.tokens.mapNotNull { held ->
                (held.holding.reading as? TokenBalanceReading.Verified)?.takeUnless { it.units.isZero }?.provenAt
            }

        /** Whether an amount shown is one last verified, from before. */
        val fromBefore: Boolean = rows.any { it.asset.lastKnown != null }
    }

    /** [built] with what simple mode dates it by: whether [live], the [refresh] asked, the light client's [status]. */
    private inner class Dating(
        val built: Built,
        val live: Boolean,
        val refresh: Refresh?,
        val status: LightClientStatus,
    ) {
        /** Whether something shown says an age, so that it is re-dated every second. */
        val dated: Boolean
            get() = built.fromBefore || (!live && built.proven.isNotEmpty()) || refresh != null

        /**
         * The state at [now] (unix seconds) with simple mode's leaves (docs/simple-ui.md, H4): each
         * amount from before with its age, how fresh the values are ("Updated 40 s ago", "Live",
         * "Checking…" while a refresh is answered, "Syncing Ethereum · 2 of 6 periods" while it
         * catches up) and a banner while the network cannot be checked.
         */
        fun finish(now: Long): AccountDetailUiState {
            val ready = built.state as? AccountDetailUiState.Ready ?: return built.state
            val off = network.displayName.takeUnless { built.on }
            val ages = built.rows.associate { it.asset to ageNote(it.asOf, it.asset.lastKnown != null, off, now) }
            val oldest = built.proven.minOrNull().takeUnless { live }
            oldestShown = oldest
            // A refresh's window is over: forgotten, so that nothing is re-dated on its account any more.
            if (refresh != null && now - refresh.at >= REFRESH_SHOWN_SECONDS) lastRefresh.compareAndSet(refresh, null)
            val refreshing = !live && refresh?.oldest != null && oldest == refresh.oldest &&
                now - refresh.at < REFRESH_SHOWN_SECONDS
            // On its way to a verified head (starting, catching up): what it is doing, with its progress.
            val syncing = LightClientItem.from(status, now, decidedHere = true, network = network.displayName)
                .takeIf { it.health == LightClientHealth.NOT_VERIFIED && it.working }
            val freshness = when {
                off != null -> null
                syncing != null -> syncing.simpleText
                // Amounts from before: the banner says how they stand, never "Live".
                built.fromBefore -> null
                refreshing -> "Checking ${network.displayName}…"
                live && built.proven.isNotEmpty() -> "Live"
                oldest != null -> "Updated ${agoText(oldest, now)} · pull down to refresh"
                else -> null
            }
            return ready.copy(
                assets = ready.assets.map { asset -> asset.copy(ageNote = ages[asset]) },
                freshness = freshness,
                refreshing = refreshing,
                cantCheck = cantCheck(off, now),
            )
        }

        /**
         * The banner while the network cannot be checked: amounts from before are shown, or its light
         * client has no fresh head to read at (STALE), or has stopped short of one; none while it is
         * on its way (starting, catching up) with nothing from before, or the network is off. With
         * amounts from before while it catches up, it says the network is still syncing.
         */
        private fun cantCheck(off: String?, now: Long): UnreachableNetwork? {
            // A network this version can no longer follow says so on its greyed-out actions: an update
            // is the way out, not the app trying again.
            if (off != null || (built.state as? AccountDetailUiState.Ready)?.networkBlocked == true) return null
            // Decided here: the banner's own closing sentence says where (Settings → Networks).
            val client = LightClientItem.from(status, now, decidedHere = true)
            val stuck = client.health == LightClientHealth.STALE ||
                (client.health == LightClientHealth.NOT_VERIFIED && !client.working)
            if (!built.fromBefore && !stuck) return null
            // Amounts from before while it catches up: it is syncing, not stuck, and says so.
            val syncing = client.health == LightClientHealth.NOT_VERIFIED && client.working
            val confirmed = built.rows.mapNotNull { it.asOf }.minOrNull()
            val said = when {
                status is LightClientStatus.StaleAnchor -> "${client.text}."
                confirmed != null -> "Its amounts were last confirmed ${agoText(confirmed, now)} and may have changed."
                built.fromBefore -> "Its amounts may have changed since they were last confirmed."
                else -> "Its amounts can't be read yet."
            }
            // A stale checkpoint waits for the user's word (owner's request, 2026-10-24), never a retry.
            val after = when {
                status is LightClientStatus.StaleAnchor ->
                    "Sending from it waits until you decide in Settings → Networks."
                syncing -> "Sending from it waits until it has caught up."
                else -> "Sending from it waits until it can be checked. The app keeps trying."
            }
            val title = if (syncing) {
                "${network.displayName} is still syncing"
            } else {
                "Can't check ${network.displayName} right now"
            }
            return UnreachableNetwork(network.chainId, title, "$said $after")
        }
    }

    /**
     * The native row and a row per token held: a token whose verified balance is zero is not
     * held. Each row names the block its own amount was verified at: the readings are polled
     * one by one and need not share one. While no head can be read at, a row shows the
     * amount last verified and says that it may have changed since, or, on a network that is off
     * ([off] names it), that it is off.
     */
    private fun rows(value: AccountValue, off: String?): List<Row> {
        val native = value.native
        val nativeRow = Row(
            AssetRow(
                symbol = network.nativeSymbol,
                name = network.nativeName,
                amount = native?.let { balanceText(it, network) } ?: "Checking…",
                icon = network.nativeIcon,
                verified = native is BalanceReading.Verified,
                verifiedAt = (native as? BalanceReading.Verified)?.let { block(it.blockNumber) },
                value = valuationText(value.nativeValue, value.currency, network),
                lastKnown = (native as? BalanceReading.LastKnown)
                    ?.let { lastKnownNote(it.blockNumber, it.asOf, off, formatTime) },
            ),
            value.nativeValue,
            asOf = (native as? BalanceReading.LastKnown)?.asOf,
        )
        val tokenRows = value.tokens.mapNotNull { (holding, valuation) ->
            val shown = shownBalance(holding.reading, off) ?: return@mapNotNull null
            val asOf = (holding.reading as? TokenBalanceReading.LastKnown)?.asOf
            if (shown.units.isZero) return@mapNotNull null
            val token = holding.token
            val symbol = displaySymbol(token.symbol)
            Row(
                AssetRow(
                    symbol = symbol,
                    name = displayName(token.name),
                    amount = formatUnits(shown.units.decimal, token.decimals, symbol),
                    icon = token.icon,
                    verified = shown.lastKnown == null,
                    // A balance last verified says when in its note, not as a verified one does.
                    verifiedAt = shown.blockNumber?.takeIf { shown.lastKnown == null }?.let(::block),
                    value = valuationText(valuation, value.currency, network),
                    contract = Eip55.format(token.address),
                    warning = if (holding.sharesBuiltInSymbol) "Not the built-in $symbol: another contract" else null,
                    lastKnown = shown.lastKnown,
                ),
                valuation,
                asOf = asOf,
            )
        }
        return listOf(nativeRow) + tokenRows
    }

    /**
     * The token amount a row shows for [reading], the block it was verified at, and, for one
     * last verified, what the row says of it; null when there is no amount to show.
     */
    private fun shownBalance(reading: TokenBalanceReading?, off: String?): ShownBalance? = when (reading) {
        is TokenBalanceReading.Verified -> ShownBalance(reading.units, reading.blockNumber, lastKnown = null)
        is TokenBalanceReading.LastKnown -> ShownBalance(
            reading.units,
            reading.blockNumber,
            lastKnownNote(reading.blockNumber, reading.asOf, off, formatTime),
        )
        else -> null
    }

    private class ShownBalance(val units: TokenUnits, val blockNumber: Long?, val lastKnown: String?)

    /**
     * What simple mode says of an amount last verified ([lastKnown]) at [asOf] (unix seconds; null:
     * unknown), or on a network that is off ([off] names it): its age instead of its time.
     */
    private fun ageNote(asOf: Long?, lastKnown: Boolean, off: String?, now: Long): String? {
        val age = asOf?.let { agoText(it, now) }
        return when {
            off != null -> listOfNotNull("$off is off", age).joinToString(" · ")
            lastKnown -> listOfNotNull(age, "may have changed").joinToString(" · ")
            else -> null
        }
    }

    /** A row, the value it is sorted by, and when its amount was last verified when it is one from before. */
    private class Row(val asset: AssetRow, val value: Valuation?, val asOf: Long? = null)

    private fun sorted(rows: List<Row>, order: AssetSort): List<AssetRow> {
        val byName = compareBy<Row> { it.asset.symbol.lowercase() }
        return when (order) {
            AssetSort.NAME -> rows.sortedWith(byName)
            AssetSort.VALUE -> {
                val (priced, others) = rows.partition { it.value is Valuation.Valued }
                priced.sortedByDescending { (it.value as Valuation.Valued).amount } + others.sortedWith(byName)
            }
        }.map { it.asset }
    }

    /**
     * What is not known (yet) about the tokens no row shows: still being checked, or why not;
     * and that the user's token settings cannot be read, so the rows ignore them.
     */
    private fun tokensNote(value: AccountValue): String? {
        val readings = value.tokens.map { it.holding.reading }
        val checking = readings.count(::checking)
        val refused = readings.count { (it as? TokenBalanceReading.Unavailable)?.reason == ReadUnavailable.REFUSED }
        val unverified = readings.count { it is TokenBalanceReading.Unavailable } - refused
        val notTokens = readings.count { it == TokenBalanceReading.NotAToken }
        val parts = listOfNotNull(
            checking.takeIf { it > 0 }?.let { "checking ${tokens(it)}" },
            unverified.takeIf { it > 0 }?.let { "${tokens(it)} not verified" },
            refused.takeIf { it > 0 }?.let { "${tokens(it)} cannot be read on this device" },
            notTokens.takeIf { it > 0 }?.let { "${tokens(it)} did not answer as a token" },
            SETTINGS_UNAVAILABLE.takeIf { value.settingsUnavailable },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.replaceFirstChar { it.uppercase() }
    }

    /**
     * Whether a token's balance is still being checked: no row yet, and none known not to come.
     * A token last known to hold none has no row, and is not known to hold none now.
     */
    private fun checking(reading: TokenBalanceReading?): Boolean =
        reading == null || reading == TokenBalanceReading.Syncing ||
            (reading as? TokenBalanceReading.LastKnown)?.units?.isZero == true

    /**
     * Whether a token's balance has not been read at all yet: a ghost row holds its place. One last
     * known to hold none is still checked, but holds no place: it most likely has no row to come,
     * and it may wait for a readable head for long.
     */
    private fun awaitingFirst(reading: TokenBalanceReading?): Boolean =
        reading == null || reading == TokenBalanceReading.Syncing

    private fun tokens(count: Int) = if (count == 1) "1 token" else "$count tokens"

    private fun block(number: Long) = "block ${grouped(number)}"

    private companion object {
        /** For how long at most the screen says a refresh is being answered, in seconds. */
        const val REFRESH_SHOWN_SECONDS = 15L

        const val PRICES = "Prices: Chainlink feeds, read through the light client"

        /** The footnote while a value is a pool's average (owner ruling, 2026-10-29, #205). */
        const val PRICES_WITH_POOLS =
            "Prices: Chainlink feeds and Uniswap 30-min averages, read through the light client"
        const val LIST_UNAVAILABLE = "Your account list cannot be read right now. Serotine keeps trying."
        const val QUEUE_UNREADABLE =
            "This device's queue of Safe transactions cannot be read right now. Serotine keeps trying."
        const val QUEUE_UNVERIFIED =
            "The Safe cannot be verified right now, so neither its nonce nor these signatures are checked."
        const val SETTINGS_UNAVAILABLE =
            "your token settings cannot be read right now: hidden tokens show, added ones are missing"
    }
}

/** Whether a value shown of this account is a pool's average: the native currency's or a token's. */
private fun AccountValue.pooled(): Boolean =
    (nativeValue as? Valuation.Valued)?.poolAverage == true ||
        tokens.any { (it.value as? Valuation.Valued)?.poolAverage == true }

/**
 * [this] as the screen draws it while amounts are [hidden] or not (docs/wallet-ux.md, *Hiding
 * amounts*): the eye's state, and the total and each asset's amount and value masked
 * ([maskAmount]). The Activity comes masked already ([AccountActivity]); what is not an amount
 * ("Checking…", "No price", a note) stays as it is.
 */
internal fun AccountDetailUiState.withAmountsHidden(hidden: Boolean): AccountDetailUiState {
    val ready = this as? AccountDetailUiState.Ready ?: return this
    if (!hidden) return ready.copy(amountsHidden = false)
    return ready.copy(
        amountsHidden = true,
        total = maskAmount(ready.total),
        assets = ready.assets.map { asset -> asset.copy(amount = maskAmount(asset.amount), value = maskAmount(asset.value)) },
        // How many of an NFT the account holds is how much it holds, too.
        nfts = ready.nfts?.let { nfts ->
            nfts.copy(
                shown = nfts.shown.map { it.copy(amount = maskAmount(it.amount)) },
                unlisted = nfts.unlisted.map { it.copy(amount = maskAmount(it.amount)) },
            )
        },
    )
}
