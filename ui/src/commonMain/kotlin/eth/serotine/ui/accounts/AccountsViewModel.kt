package eth.serotine.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.usecase.AccountValue
import eth.serotine.domain.usecase.EnsNameCheck
import eth.serotine.domain.usecase.ObserveAmountsHidden
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.ObserveEnsNames
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNetworkLive
import eth.serotine.domain.usecase.ObservePortfolio
import eth.serotine.domain.usecase.OfferHandoff
import eth.serotine.domain.usecase.ObserveSafes
import eth.serotine.domain.usecase.ObserveUpgradeWarnings
import eth.serotine.domain.usecase.Portfolio
import eth.serotine.domain.usecase.RefreshNetworkData
import eth.serotine.domain.usecase.SafeReading
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.SyncPastStaleAnchor
import eth.serotine.domain.usecase.ToggleAmountsHidden
import eth.serotine.domain.usecase.Total
import eth.serotine.domain.usecase.UpgradeWarning
import eth.serotine.domain.usecase.UpgradeWarnings
import eth.serotine.ui.components.AMOUNTS_NOT_SAVED
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.handoffChoice
import eth.serotine.ui.components.LightClientHealth
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.StaleAnchorWarning
import eth.serotine.ui.components.agoText
import eth.serotine.ui.components.asItems
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.components.maskAmount
import eth.serotine.ui.components.notTurnedOn
import eth.serotine.ui.components.redatedWhile
import eth.serotine.ui.components.spokenList
import eth.serotine.ui.components.totalNote
import eth.serotine.ui.components.totalText
import eth.serotine.ui.platform.formatDateTime
import eth.serotine.ui.update.UpgradeBannerItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Accounts screen's ViewModel: turns the domain's portfolio — the accounts, what each
 * holds and what it is worth ([ObservePortfolio]), whether the ENS name an account's name
 * follows still points to it ([ObserveEnsNames]) — each network's light client and whether it
 * is on, and each network's upgrade this version does not support into [AccountsUiState]: a
 * section per network with accounts (docs/networks.md, N1, N2 and N18), a banner per network with
 * an upgrade, and a network blocked alone said in its own section, its accounts' Send greyed out
 * (*Upgrades and the update screen*). A network that cannot price the display currency says so
 * in its section and stays out of the total (*Values*). It holds no
 * platform reference and exposes state only through [uiState]; what the user does with an
 * account is navigation, which the screen's route turns into the destination of the screen or
 * dialog that does it, except turning a network on again ([turnOnNetwork]) and letting a light
 * client parked on a stale checkpoint catch up anyway ([syncPastStaleAnchor]; owner's request,
 * 2026-10-24), which its section warns of ([NetworkSection.staleAnchor]).
 */
class AccountsViewModel(
    observePortfolio: ObservePortfolio,
    observeSafes: ObserveSafes,
    observeEnsNames: ObserveEnsNames,
    observeLightClient: ObserveLightClient,
    observeEnabledNetworks: ObserveEnabledNetworks,
    private val setNetworkEnabled: SetNetworkEnabled,
    observeUpgradeWarnings: ObserveUpgradeWarnings,
    private val syncPastStaleAnchor: SyncPastStaleAnchor,
    /** Proves every network's balances and prices again on [refresh]; the list's own reads are proven as it opens. */
    private val refreshNetworkData: RefreshNetworkData,
    /** Whether each network updates live, which simple mode's freshness line says. */
    observeNetworkLive: ObserveNetworkLive,
    /** Whether amounts are hidden (docs/wallet-ux.md, *Hiding amounts*): every amount masked, the eye closed. */
    observeAmountsHidden: ObserveAmountsHidden,
    /** Hides amounts or shows them again: what a tap on the eye asks. */
    private val toggleAmountsHidden: ToggleAmountsHidden,
    /** Now, in unix seconds: what the sections date their head blocks against. */
    private val clock: () -> Long = ::epochSecondsNow,
    /** How the banner dates an upgrade, and a row a balance last verified (unix seconds). */
    private val formatTime: (Long) -> String = ::formatDateTime,
    /**
     * Whether a row's menu offers Send from another wallet (issue #255): asked anew for each list
     * drawn, since a wallet app may have been installed meanwhile. Null: never offered.
     */
    private val offerHandoff: OfferHandoff? = null,
) : ViewModel() {

    /**
     * The last refresh asked: when ([Refresh.at], unix seconds), and the oldest balance proof shown
     * then ([Refresh.oldest]). The list says it is refreshing until that changes, or for
     * [REFRESH_SHOWN_SECONDS] at most.
     */
    private data class Refresh(val at: Long, val oldest: Long?)

    private val lastRefresh = MutableStateFlow<Refresh?>(null)

    /** The oldest balance proof the list shows, as last computed: what a refresh waits to see change. */
    private var oldestShown: Long? = null

    /** Proves every network's balances and prices again now: what pulling the list down asks. */
    fun refresh() {
        lastRefresh.value = Refresh(clock(), oldestShown)
        refreshNetworkData(Network.entries.toSet())
    }

    /** Which networks update live, in `Network` order: not live until known. */
    private val live = combine(Network.entries.map { observeNetworkLive(it).onStart { emit(false) } }) { it.toList() }

    /** Each network's light client status, in `Network` order: one subscription each, shared by the two flows below. */
    private val clientStatuses = Network.entries.map { network ->
        observeLightClient(network).shareIn(viewModelScope, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MS), 1)
    }

    /** Each network's light client as its section's strip, in `Network` order. */
    private val statuses = combine(
        Network.entries.zip(clientStatuses) { network, statusOf ->
            statusOf.asItems(clock, decidedHere = true, network = network.displayName)
        },
    ) { it.toList() }

    /** Each network's stale-checkpoint warning, in `Network` order: null while it is not refusing to sync. */
    private val staleAnchors = combine(
        Network.entries.zip(clientStatuses) { network, statusOf ->
            statusOf
                .map { status ->
                    (status as? LightClientStatus.StaleAnchor)?.let { StaleAnchorWarning.of(network, it) }
                }
                .distinctUntilChanged()
        },
    ) { it.toList() }

    /** Each network's upgrade warning, none until the first: Accounts never waits for them. */
    private val upgrades = observeUpgradeWarnings().onStart { emit(UpgradeWarnings(emptyMap(), emptySet())) }

    // The checksums once per account, not on every balance reading.
    private val checksums = mutableMapOf<Address, String>()

    // Accounts never waits for a name's check: until it comes, a name stands as it was verified at Watch.
    private val ensNames = observeEnsNames().onStart { emit(emptyMap()) }

    /** What the screen says once, until it has: a network that could not be turned on. */
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AccountsUiState> = combine(
        observePortfolio(),
        combine(observeSafes(), ensNames, ::Pair),
        combine(statuses, staleAnchors, observeEnabledNetworks(), ::Triple),
        combine(upgrades, live, ::Pair),
        combine(message, lastRefresh, ::Pair),
    ) { portfolio, (safes, ensNames), (lightClients, staleAnchors, on), (upgrades, live), (message, refresh) ->
        Inputs(portfolio, safes, ensNames, lightClients, staleAnchors, on.toSet(), upgrades, live, message, refresh)
    }
        // Simple mode's ages ("Updated 2 min ago") are re-dated every second while one is shown.
        .redatedWhile { inputs ->
            inputs.portfolio.values.values.any { value ->
                value.provenAts().isNotEmpty() || value.native is BalanceReading.LastKnown
            }
        }
        .map { inputs ->
            with(inputs) {
                stateOf(portfolio, safes, ensNames, lightClients, staleAnchors, on, upgrades).let { state ->
                    if (state is AccountsUiState.Ready) {
                        simple(state, portfolio, on, live, lightClients, refresh).copy(message = message)
                    } else {
                        state
                    }
                }
            }
        }
        // Masked last, on the finished state; nothing is drawn before the choice is read, so a hidden
        // amount never shows for a moment at the start.
        .combine(observeAmountsHidden()) { state, hidden -> state.withAmountsHidden(hidden) }
        .stateIn(
            scope = viewModelScope,
            // Keeps collecting across a configuration change (the screen re-subscribes within
            // the timeout) but stops when the screen is really gone, and then forgets the
            // last state: a screen back after that must not show old balances as current.
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            initialValue = AccountsUiState.Loading(),
        )

    /** Hides amounts, or shows them again: the eye at the top left. A choice that could not be saved says so. */
    fun toggleAmounts() {
        viewModelScope.launch { if (!toggleAmountsHidden()) message.value = AMOUNTS_NOT_SAVED }
    }

    /** What [uiState] is made of, gathered before it is re-dated. */
    private data class Inputs(
        val portfolio: Portfolio,
        val safes: Map<AccountId, SafeReading>,
        val ensNames: Map<AccountId, EnsNameCheck>,
        val lightClients: List<LightClientItem>,
        val staleAnchors: List<StaleAnchorWarning?>,
        val on: Set<Network>,
        val upgrades: UpgradeWarnings,
        val live: List<Boolean>,
        val message: String?,
        val refresh: Refresh?,
    )

    /**
     * [state] with what simple mode draws instead of the strips (docs/simple-ui.md, H1 to H3): how
     * fresh the values are, a banner per network that cannot be checked (one showing balances from
     * before, or whose light client is stuck), what the total leaves out of them, and whether a
     * refresh is being answered.
     */
    private fun simple(
        state: AccountsUiState.Ready,
        portfolio: Portfolio,
        on: Set<Network>,
        live: List<Boolean>,
        lightClients: List<LightClientItem>,
        refresh: Refresh?,
    ): AccountsUiState.Ready {
        val now = clock()
        val accounts = (portfolio.accounts as? AccountList.Loaded)?.accounts.orEmpty().filter { it.network in on }
        val held = Network.entries.filter { network -> accounts.any { it.network == network } }
        val readings = accounts.associateWith { portfolio.values[it.id]?.native }
        // How old the oldest proof shown is, on the networks that are not live, a token's among them:
        // what "Updated" says.
        val notLive = held.filter { !live[it.ordinal] }
        val oldest = accounts.filter { it.network in notLive }
            .flatMap { portfolio.values[it.id]?.provenAts().orEmpty() }
            .minOrNull()
        oldestShown = oldest
        // Until the oldest proof shown is a newer one, for 15 s at most; nothing to wait for on live networks.
        val refreshing = refresh?.oldest != null && notLive.isNotEmpty() && oldest == refresh.oldest &&
            now - refresh.at < REFRESH_SHOWN_SECONDS
        // The networks on their way to a verified head (starting, catching up): said before how fresh the rest is.
        val syncing = held.filter { network ->
            lightClients[network.ordinal].let { it.health == LightClientHealth.NOT_VERIFIED && it.working }
        }
        // The age of the values shown from the other networks stays beside it: they count in the total.
        val shownAge = oldest?.let { " · updated ${agoText(it, now)}" }.orEmpty()
        val freshness = when {
            syncing.size == 1 -> lightClients[syncing.single().ordinal].simpleText?.plus(shownAge)
                ?: "Syncing ${syncing.single().displayName}$shownAge"
            syncing.isNotEmpty() -> "Syncing ${spokenList(syncing.map { it.displayName })}$shownAge"
            refreshing && notLive.isNotEmpty() -> "Checking ${spokenList(notLive.map { it.displayName })}…"
            oldest != null -> "Updated ${agoText(oldest, now)} · pull down to refresh"
            held.isNotEmpty() && notLive.isEmpty() && readings.values.any { it is BalanceReading.Verified } -> "Live"
            else -> null
        }
        val before = held.associateWith { network ->
            readings.filterKeys { it.network == network }.values.filterIsInstance<BalanceReading.LastKnown>()
        }
        val unreachable = held.mapNotNull { network ->
            val client = lightClients[network.ordinal]
            // A stale checkpoint or a network this version can no longer follow has its own warning, with
            // its own way out (the user's "Catch up anyway…", an update): no "the app keeps trying" here.
            val alarmed = state.sections.any {
                it.chainId == network.chainId && (it.staleAnchor != null || it.blocked != null)
            }
            if (alarmed) return@mapNotNull null
            // Balances from before, or a light client that has no fresh head to read at (STALE) or has
            // stopped short of one; a client on its way (starting, catching up) is not stuck.
            val stuck = client.health == LightClientHealth.STALE ||
                (client.health == LightClientHealth.NOT_VERIFIED && !client.working)
            val shown = before.getValue(network)
            if (shown.isEmpty() && !stuck) return@mapNotNull null
            val confirmed = shown.mapNotNull { it.asOf }.minOrNull()
            // Balances from before while it catches up: it is syncing, not stuck, and says so.
            val catchingUp = network in syncing
            UnreachableNetwork(
                network.chainId,
                if (catchingUp) {
                    "${network.displayName} is still syncing"
                } else {
                    "Can't check ${network.displayName} right now"
                },
                listOf(
                    when {
                        confirmed != null ->
                            "Its balances were last confirmed ${agoText(confirmed, now)} and may have changed."
                        shown.isNotEmpty() -> "Its balances may have changed since they were last confirmed."
                        else -> "Its balances can't be read yet."
                    },
                    if (catchingUp) {
                        "Sending from its accounts waits until it has caught up."
                    } else {
                        "Sending from its accounts waits until they can be checked. The app keeps trying."
                    },
                ).joinToString(" "),
            )
        }
        // What the total leaves out of the networks that cannot be checked; "still checking" only while
        // something else in it is still being read, since those balances from before explain the rest.
        val counted = countedIn(portfolio, on)
        val notCounted = counted.mapNotNull { network ->
            when (val n = before.getValue(network).size) {
                0 -> null
                1 -> "1 ${network.displayName} account not counted"
                else -> "$n ${network.displayName} accounts not counted"
            }
        }
        val stillChecking = accounts.any { account ->
            val value = portfolio.values[account.id]
            account.network in counted && readings[account] !is BalanceReading.LastKnown &&
                (value == null || value.currency != portfolio.currency || !value.total.complete)
        }
        val simpleTotalNote = if (notCounted.isEmpty()) {
            null
        } else {
            // The detailed note's first part, capitalised as its start, is no longer the start; a network's
            // name keeps its capital.
            val rest = state.totalNote.orEmpty().split(" · ")
                .map { if (it in CAPITALISED_FIRST) it.replaceFirstChar(Char::lowercase) else it }
                .filter { it.isNotEmpty() && (stillChecking || it != "still checking") }
            (notCounted + rest).joinToString(" · ").replaceFirstChar { it.uppercase() }
        }
        return state.copy(
            freshness = freshness,
            unreachable = unreachable,
            refreshing = refreshing,
            simpleTotalNote = simpleTotalNote,
        )
    }

    /** Turns the network of [chainId] on again: the way out of a greyed-out action on one that is off (N19). */
    fun turnOnNetwork(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        viewModelScope.launch {
            // A setting that cannot be saved leaves the network off, and says so.
            if (!setNetworkEnabled(network, on = true)) message.value = notTurnedOn(network)
        }
    }

    /**
     * Lets the light client of [chainId]'s network catch up from its stale checkpoint anyway, for
     * this run of the app: what the user confirmed in its section's dialog.
     */
    fun syncPastStaleAnchor(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        viewModelScope.launch { syncPastStaleAnchor(network) }
    }

    /** The screen has said [AccountsUiState.Ready.message]. */
    fun messageShown() {
        message.value = null
    }

    private fun stateOf(
        portfolio: Portfolio,
        safes: Map<AccountId, SafeReading>,
        ensNames: Map<AccountId, EnsNameCheck>,
        lightClients: List<LightClientItem>,
        staleAnchors: List<StaleAnchorWarning?>,
        on: Set<Network>,
        warnings: UpgradeWarnings,
    ): AccountsUiState {
        // A network that is off shows no banner, even with an upgrade recorded (docs/networks.md).
        val upgrade = Network.entries.filter { it in on }
            .mapNotNull { UpgradeBannerItem.from(warnings.of(it), it, formatTime) }
        return when (val list = portfolio.accounts) {
            is AccountList.Unreadable -> AccountsUiState.ListUnavailable(
                when (list.reason) {
                    AccountList.Reason.NEWER_FORMAT -> NEWER_FORMAT
                    AccountList.Reason.READ_FAILED -> READ_FAILED
                },
                upgrade,
            )
            is AccountList.Loaded -> {
                val currency = portfolio.currency
                val sections = Network.entries.mapIndexedNotNull { i, network ->
                    val accounts = list.accounts.filter { it.network == network }
                    if (accounts.isEmpty()) return@mapIndexedNotNull null
                    val isOn = network in on
                    // A network this version can no longer follow, while others still can (the update
                    // screen is for when none can): said in its section, its accounts' Send greyed out.
                    val blocked = (warnings.of(network) as? UpgradeWarning.UpdateRequired)
                        ?.let { networkBlockedReason(network.displayName) }
                        ?.takeIf { isOn }
                    // A row from the start, "Checking…" until its first reading: an account just
                    // added, or a screen just back, never waits for every proof.
                    // Which apps take payment links: asked once for the whole list, not once per row.
                    val handoffs = offerHandoff?.availability()
                    val items = accounts.map { account ->
                        val value = portfolio.values[account.id]
                        // In the currency the value was computed in: just after a change of
                        // currency, an account not revalued yet is still in the one before.
                        AccountItem.from(
                            account,
                            value?.native,
                            account.network,
                            checksums.getOrPut(account.address) { Eip55.format(account.address) },
                            value?.let { it.nativeValue to it.currency },
                            safes[account.id],
                            formatTime,
                            ensNames[account.id],
                            networkOn = isOn,
                            now = clock(),
                            total = value?.total,
                        ).let { item -> if (blocked != null) item.copy(networkBlocked = blocked) else item }
                            .let { item ->
                                val availability = handoffs ?: return@let item
                                val offer = offerHandoff?.invoke(account, availability) ?: return@let item
                                item.copy(handoff = handoffChoice(offer, item.networkOff))
                            }
                    }
                    val total = portfolio.totalOn(network)
                    NetworkSection(
                        chainId = network.chainId,
                        name = network.displayName,
                        testnet = network.testnet,
                        on = isOn,
                        status = if (isOn) lightClients[i] else OFF,
                        accounts = items,
                        total = if (isOn) totalText(total, currency) else "Off",
                        totalNote = sectionNote(total, network, currency, portfolio).takeIf { isOn }?.ifEmpty { null },
                        blocked = blocked,
                        staleAnchor = staleAnchors[i].takeIf { isOn },
                    )
                }
                // The top total adds the real networks that are on; a testnet only when the wallet has
                // no account on a real network at all, on or off (docs/networks.md, N1).
                val held = Network.entries.filter { network -> list.accounts.any { it.network == network } }
                val counted = countedIn(portfolio, on)
                val total = counted.fold(Total.NONE) { sum, network -> sum + portfolio.totalOn(network) }
                // A testnet left out of the total, said so; one that is off says it in its own section.
                val leftOut = sections.filter { it.testnet && it.on && counted.none { n -> n.testnet } }.map { it.name }
                AccountsUiState.Ready(
                    sections,
                    notice = if (list.damagedListSetAside) DAMAGED_SET_ASIDE else null,
                    total = counted.firstOrNull()?.let { totalText(total, currency) },
                    totalLabel = counted.takeIf { it.isNotEmpty() }
                        ?.let { "Total · ${currency.code} · ${spokenList(it.map { n -> n.displayName })}" },
                    totalNote = counted.firstOrNull()?.let { first ->
                        val unpriced = held.filter { it in on && it in portfolio.unpricedOn }
                            .map { "${it.displayName} has no ${currency.code} rate" }
                        (listOf(totalNote(total, first, currency)) + leftOut.map { "$it not included" } + unpriced)
                            .filter { it.isNotEmpty() }
                            .joinToString(" · ")
                            .ifEmpty { null }
                    },
                    upgrades = upgrade,
                )
            }
        }
    }

    /**
     * The networks the top total adds: the real ones that are on and can price the display
     * currency; a testnet only when the wallet has no account on a real network at all, on or off
     * (docs/networks.md, N1).
     */
    private fun countedIn(portfolio: Portfolio, on: Set<Network>): List<Network> {
        val accounts = (portfolio.accounts as? AccountList.Loaded)?.accounts.orEmpty()
        val held = Network.entries.filter { network -> accounts.any { it.network == network } }
        return held.filterNot { it.testnet }.ifEmpty { held }.filter { it in on && it !in portfolio.unpricedOn }
    }

    /**
     * What a network's section says under its total: [totalNote]'s, or that [network] cannot price
     * the display currency, which leaves its accounts out of the total (docs/networks.md, *Values*).
     */
    private fun sectionNote(total: Total, network: Network, currency: DisplayCurrency, portfolio: Portfolio): String =
        if (network in portfolio.unpricedOn) {
            "No ${currency.code} rate on ${network.displayName} · not in the total"
        } else {
            totalNote(total, network, currency)
        }

    private companion object {
        /** For how long at most the list says a refresh is being answered, in seconds. */
        const val REFRESH_SHOWN_SECONDS = 15L

        /** How long a network's shared status outlives its last subscriber: across a configuration change. */
        const val SHARE_TIMEOUT_MS = 5_000L

        /**
         * The parts of the total's note that are capitalised only because they open it: lowercased
         * where simple mode puts the accounts not counted before them.
         */
        val CAPITALISED_FIRST = setOf("Still checking", "Testnet prices", "Includes Uniswap averages")

        /** A network's status line while it is off in this wallet (N18). */
        val OFF =
            LightClientItem("Off in this wallet · nothing is checked", null, false, LightClientHealth.NOT_RUNNING)

        const val DAMAGED_SET_ASIDE =
            "Your saved account list was damaged and has been set aside. Add your accounts again."
        const val NEWER_FORMAT =
            "Your account list was saved by a newer version of Serotine. Update the app to see it."
        const val READ_FAILED =
            "Your saved account list cannot be read right now. Nothing has been changed, and no " +
                "account can be added until it can be read. Serotine keeps trying."
    }
}

/** The times of the verified heads this account's proofs shown were made at: its native balance's and each token's. */
private fun AccountValue.provenAts(): List<Long> =
    listOfNotNull((native as? BalanceReading.Verified)?.provenAt) +
        tokens.mapNotNull { (it.holding.reading as? TokenBalanceReading.Verified)?.provenAt }

/**
 * [this] as the screen draws it while amounts are [hidden] or not (docs/wallet-ux.md, *Hiding
 * amounts*): the eye's state, and every amount it shows masked ([maskAmount]): the total, each
 * network's, and each account's balance, value and total. What is not an amount ("Checking…", "No
 * price", a note) stays as it is.
 */
internal fun AccountsUiState.withAmountsHidden(hidden: Boolean): AccountsUiState {
    val ready = this as? AccountsUiState.Ready ?: return this
    if (!hidden) return ready.copy(amountsHidden = false)
    return ready.copy(
        amountsHidden = true,
        total = maskAmount(ready.total),
        sections = ready.sections.map { section ->
            section.copy(
                total = maskAmount(section.total),
                accounts = section.accounts.map { account ->
                    account.copy(
                        balance = maskAmount(account.balance),
                        value = maskAmount(account.value),
                        accountTotal = maskAmount(account.accountTotal),
                    )
                },
            )
        },
    )
}
