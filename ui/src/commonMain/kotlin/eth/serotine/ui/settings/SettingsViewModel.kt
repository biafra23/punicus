package eth.serotine.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientStatus
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
import eth.serotine.domain.usecase.TokenOrigin
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.components.LightClientHealth
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.StaleAnchorWarning
import eth.serotine.ui.components.dated
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.components.redatedWhile
import eth.serotine.ui.platform.ScreenCaptureDebugSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Settings screen's ViewModel: the display currency, the known tokens, the networks with their
 * switches and light clients (docs/networks.md, N16 and N20), Detailed view's switch
 * (docs/simple-ui.md), the Debug tab's switch (in Detailed view only) and the version; and, in a
 * debug build, the switch that allows screen recordings of the protected screens. Turning a
 * network on asks nothing; turning off one with accounts asks first, and one without turns off at
 * once. The tokens are every network's, counted together; the currency is
 * the wallet's one.
 */
class SettingsViewModel(
    private val observeNetworkOverview: ObserveNetworkOverview,
    private val setNetworkEnabled: SetNetworkEnabled,
    observeKnownTokens: ObserveKnownTokens,
    observeDisplayCurrencies: ObserveDisplayCurrencies,
    /** The debug build's screen-capture switch; null in a release build, which offers none. */
    private val screenCapture: ScreenCaptureDebugSwitch?,
    observeDebugScreen: ObserveDebugScreen,
    private val setDebugScreen: SetDebugScreen,
    private val syncPastStaleAnchor: SyncPastStaleAnchor,
    observeInterfaceMode: ObserveInterfaceMode,
    private val setInterfaceMode: SetInterfaceMode,
    /** Whether each network updates live, which simple mode's rows say. */
    observeNetworkLive: ObserveNetworkLive,
    /** Now, in unix seconds: what the light clients' lines date the head block against. */
    private val clock: () -> Long = ::epochSecondsNow,
) : ViewModel() {

    /** Whether the last switch of the Debug tab could not be saved. */
    private val debugScreenNotSaved = MutableStateFlow(false)

    /** Whether the last switch of Detailed view could not be saved. */
    private val detailedViewNotSaved = MutableStateFlow(false)

    /** The networks whose last switch could not be saved. */
    private val networkNotSaved = MutableStateFlow(emptySet<Network>())

    /** The confirmation asked before a network is turned off, while it is asked. */
    private val turningOff = MutableStateFlow<TurnOffConfirmation?>(null)

    val uiState: StateFlow<SettingsUiState> =
        combine(
            combine(
                // Each row dates its head block, re-dated every second as the strip's line is.
                observeNetworkOverview().redatedWhile { networks -> networks.any { it.on && it.status.dated } },
                networkNotSaved,
                turningOff,
                combine(Network.entries.map { observeNetworkLive(it).onStart { emit(false) } }) { it.toList() },
            ) { networks, notSaved, confirming, live -> Rows(networks, notSaved, confirming, live) },
            combine(Network.entries.map { observeKnownTokens(it) }) { it.toList() },
            observeDisplayCurrencies(),
            screenCapture?.allowed ?: flowOf(null),
            combine(
                observeDebugScreen(),
                debugScreenNotSaved,
                observeInterfaceMode(),
                detailedViewNotSaved,
                ::Switches,
            ),
        ) { (networks, notSaved, confirming, live), known, currencies, captureAllowed, switches ->
            val tokens = known.flatMap { it.tokens }
            val builtIn = tokens.count { it.origin == TokenOrigin.BUILT_IN }
            val added = if (known.any { it.settingsUnavailable }) {
                "added ones cannot be read"
            } else {
                "${tokens.size - builtIn} added"
            }
            val currency = currencies.chosen.code + when {
                currencies.unreadable -> " (your choice cannot be read)"
                currencies.setAside -> " (your saved choice was damaged and set aside)"
                else -> ""
            }
            SettingsUiState(
                networks = networks.map { rowOf(it, it.network in notSaved, live[it.network.ordinal]) },
                version = APP_VERSION,
                tokens = "$builtIn built in · $added",
                currency = currency,
                screenCaptureAllowed = captureAllowed,
                // The Debug tab is Detailed view's alone: simple mode offers no switch for it.
                debugScreen = switches.debugScreen.takeIf { switches.mode == InterfaceMode.DETAILED },
                debugScreenNotSaved = switches.debugScreenNotSaved,
                detailedView = switches.mode == InterfaceMode.DETAILED,
                // Simple mode hides the tab and empties its log, as turning the tab off does: said where it is done.
                detailedViewNote = DEBUG_TAB_GOES.takeIf {
                    switches.mode == InterfaceMode.DETAILED && switches.debugScreen
                },
                detailedViewNotSaved = switches.detailedViewNotSaved,
                turningOff = confirming,
            )
        }
            .stateIn(
                viewModelScope,
                // A tab back after the timeout must not show an old head as current.
                SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
                SettingsUiState(emptyList(), APP_VERSION, screenCaptureAllowed = screenCapture?.allowed?.value),
            )

    /** The networks' rows as read, before they are drawn. */
    private data class Rows(
        val networks: List<NetworkOverview>,
        val notSaved: Set<Network>,
        val confirming: TurnOffConfirmation?,
        val live: List<Boolean>,
    )

    private fun rowOf(overview: NetworkOverview, notSaved: Boolean, live: Boolean): NetworkRow {
        val accounts = overview.accounts
        val count = when {
            accounts == null -> "Accounts cannot be read"
            accounts.isEmpty() -> "No account"
            accounts.size == 1 -> "1 account"
            else -> "${accounts.size} accounts"
        }
        val item = LightClientItem.from(overview.status, clock(), decidedHere = true)
        return NetworkRow(
            chainId = overview.network.chainId,
            name = overview.network.displayName,
            on = overview.on,
            line = if (overview.on) "$count · ${item.text}" else "$count · Off",
            health = if (overview.on) item.health else LightClientHealth.NOT_RUNNING,
            testnet = overview.network.testnet,
            notSaved = notSaved,
            staleAnchor = (overview.status as? LightClientStatus.StaleAnchor)
                ?.takeIf { overview.on }
                ?.let { StaleAnchorWarning.of(overview.network, it) },
            simpleLine = listOfNotNull(
                "Test network".takeIf { overview.network.testnet },
                when {
                    !overview.on -> "Off"
                    item.health == LightClientHealth.VERIFIED -> if (live) "live" else "Up to date"
                    item.health == LightClientHealth.NOT_RUNNING -> "Idle until an account needs it"
                    // On its way to a verified head; one synced but without a fresh head (STALE) cannot
                    // check, as Home's banner says of it.
                    item.health == LightClientHealth.NOT_VERIFIED && item.working -> "Catching up…"
                    else -> "Can't check right now"
                },
            ).joinToString(" · ").replaceFirstChar { it.uppercase() },
        )
    }

    /**
     * Lets the light client of [chainId]'s network catch up from its stale checkpoint anyway, for
     * this run of the app: what the user confirmed in its row's dialog.
     */
    fun syncPastStaleAnchor(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        viewModelScope.launch { syncPastStaleAnchor(network) }
    }

    /**
     * Turns the network of [chainId] on at once, or off: at once when no account is on it, after
     * the confirmation otherwise (N20). A switch that cannot be saved leaves the row as it was,
     * saying so.
     */
    fun switchNetwork(chainId: Long, on: Boolean) {
        val network = Network.ofChainId(chainId) ?: return
        viewModelScope.launch {
            if (!on) {
                val overview = observeNetworkOverview().first().first { it.network == network }
                // An account list that cannot be read asks too: it may have accounts on the network.
                if (overview.accounts?.isEmpty() != true) {
                    turningOff.value = confirmationOf(overview)
                    return@launch
                }
            }
            save(network, on)
        }
    }

    /** Turns off the network the confirmation asked about. */
    fun confirmTurnOff() {
        val asked = turningOff.value ?: return
        turningOff.value = null
        val network = Network.ofChainId(asked.chainId) ?: return
        viewModelScope.launch { save(network, on = false) }
    }

    /** Leaves the network on: the confirmation is dismissed. */
    fun cancelTurnOff() {
        turningOff.value = null
    }

    private suspend fun save(network: Network, on: Boolean) {
        val saved = setNetworkEnabled(network, on)
        networkNotSaved.value = if (saved) networkNotSaved.value - network else networkNotSaved.value + network
    }

    /** Allows screen recordings of the protected screens, or blocks them again; a release build has no switch. */
    fun allowScreenCapture(allowed: Boolean) {
        screenCapture?.allow(allowed)
    }

    /**
     * Shows the Debug tab, or hides it, which empties its log. A switch that cannot be saved
     * leaves the row as it was, saying so.
     */
    fun showDebugScreen(shown: Boolean) {
        viewModelScope.launch { debugScreenNotSaved.value = !setDebugScreen(shown) }
    }

    /**
     * Draws the screens in Detailed view, or in simple mode, which also hides the Debug tab (its
     * switch kept). A switch that cannot be saved leaves the row as it was, saying so.
     */
    fun showDetailedView(shown: Boolean) {
        viewModelScope.launch {
            detailedViewNotSaved.value = !setInterfaceMode(if (shown) InterfaceMode.DETAILED else InterfaceMode.SIMPLE)
        }
    }

    /** The switches at the bottom of Settings, as read and as last saved. */
    private data class Switches(
        val debugScreen: Boolean,
        val debugScreenNotSaved: Boolean,
        val mode: InterfaceMode,
        val detailedViewNotSaved: Boolean,
    )

    internal companion object {
        /** What turning Detailed view off does to a Debug tab that is on. */
        const val DEBUG_TAB_GOES = "Turning it off also hides the Debug tab and empties its log."

        /** The question before [overview]'s network, which has accounts, is turned off (N20). */
        fun confirmationOf(overview: NetworkOverview): TurnOffConfirmation {
            val name = overview.network.displayName
            val accounts = overview.accounts.orEmpty()
            val stay = when {
                overview.accounts == null ->
                    "Its accounts, if it has any, stay in your list: the list cannot be read right now."
                accounts.size == 1 -> "Its account, ${accounts[0].label}, stays in your list."
                accounts.size <= 3 ->
                    "Its ${accounts.size} accounts, ${names(accounts.map { it.label })}, stay in your list."
                else -> "Its ${accounts.size} accounts stay in your list."
            }
            val waiting = listOfNotNull(
                when (overview.unconfirmed) {
                    0 -> null
                    1 -> "A transaction sent from it is not confirmed yet."
                    else -> "${overview.unconfirmed} transactions sent from it are not confirmed yet."
                },
                when (overview.unlistedSafes) {
                    0 -> null
                    1 -> "A Safe deployed on it is not listed yet."
                    else -> "${overview.unlistedSafes} Safes deployed on it are not listed yet."
                },
            )
            return TurnOffConfirmation(
                chainId = overview.network.chainId,
                title = "Turn off $name?",
                text = "$stay Its balances are not checked and nothing can be sent from it until you turn " +
                    "$name on again.",
                waiting = waiting.takeIf { it.isNotEmpty() }
                    ?.joinToString(" ", postfix = " Following it waits until $name is on again; nothing is lost."),
            )
        }

        /** "Main", "Main and Savings", "Main, Savings and Daily". */
        private fun names(labels: List<String>): String =
            if (labels.size <= 1) labels.joinToString() else labels.dropLast(1).joinToString() + " and " + labels.last()
    }
}
