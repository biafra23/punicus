package eth.serotine.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SwapVenue
import eth.serotine.ui.addnetwork.AddOnNetworksRoute
import eth.serotine.ui.accountdetail.AccountDetailRoute
import eth.serotine.ui.accounts.AccountAction
import eth.serotine.ui.accounts.AccountsRoute
import eth.serotine.ui.activity.ActivityKey
import eth.serotine.ui.activitydetail.ActivityDetailRoute
import eth.serotine.ui.addtoken.AddTokenRoute
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.coworder.CowOrderRoute
import eth.serotine.ui.coworder.SwapAgain
import eth.serotine.ui.createaccount.CreateAccountRoute
import eth.serotine.ui.createsafe.CreateSafeRoute
import eth.serotine.ui.connections.ConnectionsRoute
import eth.serotine.ui.currency.CurrencyRoute
import eth.serotine.ui.dappproposal.DappProposalRoute
import eth.serotine.ui.dapprequest.DappRequestRoute
import eth.serotine.ui.dapprequest.DappRequestsWaitingViewModel
import eth.serotine.ui.pairdapp.PairDappRoute
import eth.serotine.ui.debug.DebugRoute
import eth.serotine.ui.debug.DebugViewModel
import eth.serotine.ui.deriveaccount.DeriveAccountRoute
import eth.serotine.ui.lightclient.LightClientViewModel
import eth.serotine.ui.mnemonicimport.MnemonicImportRoute
import eth.serotine.ui.mode.LocalInterfaceMode
import eth.serotine.ui.platform.LocalScreenCaptureDebugSwitch
import eth.serotine.ui.platform.ScreenCaptureWarnings
import eth.serotine.ui.receive.ReceiveRoute
import eth.serotine.ui.removeaccount.RemoveAccountRoute
import eth.serotine.ui.renameaccount.RenameAccountRoute
import eth.serotine.ui.safeimport.SafeImportRoute
import eth.serotine.ui.safepropose.SafeProposeRoute
import eth.serotine.ui.safesettings.SafeSettingsRoute
import eth.serotine.ui.safeswap.SafeSwapRoute
import eth.serotine.ui.safetx.SafeTxRoute
import eth.serotine.ui.security.SecurityRoute
import eth.serotine.ui.send.SendRoute
import eth.serotine.ui.settings.SettingsRoute
import eth.serotine.ui.swap.SwapFill
import eth.serotine.ui.swap.SwapRoute
import eth.serotine.ui.theme.SerotineTheme
import eth.serotine.ui.tokens.TokensRoute
import eth.serotine.ui.transferstatus.TransferStatusRoute
import eth.serotine.ui.update.UpdateRequiredRoute
import eth.serotine.ui.update.UpdateRequiredUiState
import eth.serotine.ui.update.UpdateRequiredViewModel
import eth.serotine.ui.watch.WatchRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * The whole shared UI, identical on every host. A host renders this inside its own
 * window or activity, after building the [graph] with its platform adapters.
 *
 * Two tabs, Accounts and Settings, under a bottom bar (a rail on a wide window), around the
 * one [NavHost] (docs/architecture.md), and a third, Debug, once the user turns it on in
 * Settings. Each tab is a nested graph with its own back stack;
 * a flow that ends in a key operation covers the bar. While a network upgrade has left this
 * version unable to follow the network, it shows the update screen and nothing else.
 */
@Composable
fun App(graph: AppGraph) = App(graph, rememberNavController())

/** [App] with the [navController] a test drives. */
@Composable
internal fun App(graph: AppGraph, navController: NavHostController) {
    // The view every Route draws its screen in: the app's, so scoped to the host.
    val interfaceMode = viewModel { graph.interfaceModeViewModel() }
    // Nothing is drawn until the choice is read (a file read, quick), so no screen is drawn in one
    // view and then again in the other: nothing acts on a guess.
    val mode = interfaceMode.mode.collectAsStateWithLifecycle().value ?: return
    CompositionLocalProvider(
        LocalScreenCaptureDebugSwitch provides graph.screenCaptureDebugSwitch,
        LocalInterfaceMode provides mode,
    ) {
        AppContent(graph, navController)
    }
}

/** [App] under the host's screen-capture switch and the view the screens are drawn in. */
@Composable
private fun AppContent(graph: AppGraph, navController: NavHostController) {
    SerotineTheme {
        // Scoped to the host, not to a destination: it decides which destinations there are.
        val update = viewModel { graph.updateRequiredViewModel() }
        // One strip for every screen that draws it, followed while one of them is shown.
        val lightClient = viewModel { graph.lightClientViewModel() }
        // Whether the bar has a Debug tab: the app's tabs, so scoped to the host too.
        val debugTab = viewModel { graph.debugTabViewModel() }
        val debugShown by debugTab.shown.collectAsStateWithLifecycle()
        // The Debug screen's filter outlives its destination, which a tab switch pops: scoped to the host too.
        val debug = viewModel { graph.debugViewModel() }
        // The requests to sign waiting, whose review the app opens as each arrives: scoped to the host too.
        val dappRequests = viewModel { graph.dappRequestsWaitingViewModel() }
        val tabs = Tab.entries.filter { it != Tab.DEBUG || debugShown == true }
        val entry by navController.currentBackStackEntryAsState()
        val selected = entry?.destination?.hierarchy?.firstNotNullOfOrNull { destination ->
            Tab.entries.firstOrNull { it.graph == destination.route }
        }
        val select: (Tab) -> Unit = { tab -> navController.select(tab, current = selected) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE_WINDOW
            // One layout for both widths, so the NavHost stays where it is in the composition
            // when the window crosses WIDE_WINDOW: only the bar or the rail comes and goes.
            Row(Modifier.fillMaxSize()) {
                if (wide && selected != null) {
                    NavigationRail {
                        tabs.forEach { tab ->
                            NavigationRailItem(
                                selected = tab == selected,
                                onClick = { select(tab) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    // The screens' own scaffolds take the system insets; this one only makes
                    // room for the bar, which takes the navigation bar's.
                    contentWindowInsets = WindowInsets(0.dp),
                    bottomBar = {
                        if (!wide && selected != null) {
                            NavigationBar {
                                tabs.forEach { tab ->
                                    NavigationBarItem(
                                        selected = tab == selected,
                                        onClick = { select(tab) },
                                        icon = { Icon(tab.icon, contentDescription = null) },
                                        label = { Text(tab.label) },
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    // Every screen ends at the top of the on-screen keyboard (imePadding, less the
                    // bar it already clears), so what the keyboard would cover can be scrolled to.
                    Destinations(
                        graph,
                        navController,
                        update,
                        lightClient,
                        debugShown,
                        debug,
                        dappRequests,
                        Modifier.padding(padding).consumeWindowInsets(padding).imePadding(),
                    )
                }
            }
        }
        ScreenCaptureWarnings()
    }
}

/** The tabs of the bottom bar: each the [graph] of its screens, entered at [start]. */
private enum class Tab(val label: String, val icon: ImageVector, val graph: String, val start: String) {
    ACCOUNTS("Accounts", SerotineIcons.Wallet, "accounts-tab", Destination.ACCOUNTS),
    SETTINGS("Settings", Icons.Filled.Settings, "settings-tab", Destination.SETTINGS),

    /** Shown only while the user has it turned on in Settings. */
    DEBUG("Debug", Icons.Filled.Build, "debug-tab", Destination.DEBUG),
}

/**
 * Shows [tab]. The current one again returns to its start. Settings and Debug each open over
 * the Accounts tab's stack, which stays as the user left it, one at a time: either replaces the
 * other, and Accounts closes it back down to that stack — as the back key does. No stack is
 * saved aside, so none can come back after the user has left it by another way
 * (saveState/restoreState would bring back an account screen left by the back key from Settings).
 */
private fun NavController.select(tab: Tab, current: Tab?) {
    when {
        tab == current -> popBackStack(tab.start, inclusive = false)
        tab == Tab.ACCOUNTS -> current?.let { popBackStack(it.graph, inclusive = true) }
        else -> {
            current?.takeIf { it != Tab.ACCOUNTS }?.let { popBackStack(it.graph, inclusive = true) }
            navigate(tab.graph) { launchSingleTop = true }
        }
    }
}

/**
 * Opens the first route [wanted] names that is not [done] in the Settings tab, as a tap there would:
 * over the screen shown when that is the Settings tab's, so nothing typed there is lost, else in that
 * tab. Not while [enabled] is false (an update is required, and that screen replaces every other),
 * nor while the screen shown is one of [waitOn], nor while a flow outside the tabs covers the bar (a
 * key operation, a review being signed): it waits. Looked at again whenever the screen shown changes,
 * so what waited opens once that screen is left, never stranded. [onOpen] is told what opens.
 */
@Composable
private fun OpenInSettings(
    navController: NavHostController,
    enabled: Boolean,
    wanted: Flow<List<String>>,
    waitOn: Set<String>,
    done: (String) -> Boolean = { false },
    onOpen: (String) -> Unit = {},
) {
    val isDone by rememberUpdatedState(done)
    val opening by rememberUpdatedState(onOpen)
    LaunchedEffect(enabled, wanted) {
        if (!enabled) return@LaunchedEffect
        combine(wanted, navController.currentBackStackEntryFlow) { routes, entry -> routes to entry }
            .collect { (routes, entry) ->
                val next = routes.firstOrNull { !isDone(it) } ?: return@collect
                if (entry.destination.route in waitOn) return@collect
                val current = entry.destination.hierarchy.firstNotNullOfOrNull { route ->
                    Tab.entries.firstOrNull { it.graph == route.route }
                } ?: return@collect
                opening(next)
                if (current != Tab.SETTINGS) navController.select(Tab.SETTINGS, current)
                navController.navigate(next) { launchSingleTop = true }
            }
    }
}

/**
 * The one [NavHost]. While [update] says an update is required, its screen replaces every
 * other: the one navigation no screen asks for (docs/architecture.md).
 */
@Composable
private fun Destinations(
    graph: AppGraph,
    navController: NavHostController,
    update: UpdateRequiredViewModel,
    lightClient: LightClientViewModel,
    debugShown: Boolean?,
    debug: DebugViewModel,
    dappRequests: DappRequestsWaitingViewModel,
    modifier: Modifier = Modifier,
) {
    val updateState by update.uiState.collectAsStateWithLifecycle()
    val updateRequired = updateState is UpdateRequiredUiState.Required
    // In the NavHost's own composition, so the graph it navigates is set by the time this runs.
    LaunchedEffect(updateRequired) {
        if (updateRequired) {
            // The whole back stack goes: every screen's ViewModel but the Debug tab's is cleared, the seed
            // screen's words with it, and back leaves the app instead of reaching a screen.
            navController.navigate(Destination.UPDATE_REQUIRED) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        } else if (navController.currentDestination?.route == Destination.UPDATE_REQUIRED) {
            navController.navigate(Tab.ACCOUNTS.graph) {
                popUpTo(Destination.UPDATE_REQUIRED) { inclusive = true }
            }
        }
    }
    // A `wc:` link the platform opened the app with opens Connect a dApp in the Settings tab, as a tap
    // there would, and the screen takes the link from there. Not while a Connect is under way there,
    // nor over a request's review: the link waits, and is opened once that screen is left.
    OpenInSettings(
        navController,
        enabled = !updateRequired,
        wanted = remember(graph) {
            graph.incomingDappLink.pending.map { link -> listOfNotNull(link?.let { Destination.PAIR_DAPP }) }
        },
        waitOn = setOf(Destination.PAIR_DAPP, DAPP_REQUEST_ROUTE),
    )
    // A dApp's request to sign opens its review (screen D4) as it arrives, each one once in this run:
    // one left unanswered stays in Connected dApps. Not over another request's review, nor over a dApp
    // being connected (Connect a dApp, a connection request): it waits for that screen to be left.
    OpenInSettings(
        navController,
        enabled = !updateRequired,
        wanted = remember(dappRequests) {
            dappRequests.waiting.map { keys -> keys.map { "${Destination.DAPP_REQUEST}/$it" } }
        },
        waitOn = setOf(DAPP_REQUEST_ROUTE, Destination.PAIR_DAPP, DAPP_PROPOSAL_ROUTE),
        done = { route -> dappRequests.wasOpened(route.removePrefix("${Destination.DAPP_REQUEST}/")) },
        onOpen = { route -> dappRequests.opened(route.removePrefix("${Destination.DAPP_REQUEST}/")) },
    )
    // The Debug tab turned off while its screen is shown: that screen goes, back to Accounts, and
    // the filter typed goes with the log it was for. Not before the setting is read (null), so a
    // Debug screen the system restored stays.
    LaunchedEffect(debugShown) {
        if (debugShown == false) {
            navController.popBackStack(Tab.DEBUG.graph, inclusive = true)
            debug.setFilter("")
        }
    }
    // One destination per screen. Inside a destination, viewModel {} is scoped to its
    // back-stack entry: the ViewModel survives configuration changes while the screen is
    // on the back stack, and is cleared when the screen is popped off it.
    NavHost(navController, startDestination = Tab.ACCOUNTS.graph, modifier = modifier) {
        navigation(startDestination = Destination.ACCOUNTS, route = Tab.ACCOUNTS.graph) {
            composable(Destination.ACCOUNTS) {
                AccountsRoute(
                    viewModel = viewModel { graph.accountsViewModel() },
                    onWatchAddress = { navController.navigate(Destination.WATCH) { launchSingleTop = true } },
                    onImportPhrase = { navController.navigate(Destination.MNEMONIC_IMPORT) { launchSingleTop = true } },
                    onCreateAccount = { navController.navigate(Destination.CREATE_ACCOUNT) { launchSingleTop = true } },
                    onCreateSafe = { navController.navigate(Destination.CREATE_SAFE) { launchSingleTop = true } },
                    onAccountAction = { action -> navController.open(action) },
                )
            }
            composable(Destination.WATCH) {
                WatchRoute(
                    viewModel = viewModel { graph.watchViewModel() },
                    lightClient = lightClient,
                    onBack = { navController.leave(Destination.WATCH) },
                    onAdded = { navController.leave(Destination.WATCH) },
                )
            }
            withAccount(Destination.ACCOUNT, navController) { account ->
                val chainId = account.network.chainId
                AccountDetailRoute(
                    viewModel = viewModel { graph.accountDetailViewModel(account) },
                    lightClient = lightClient.item(account.network),
                    onBack = { navController.leave(Destination.ACCOUNT) },
                    onGone = { navController.leave(Destination.ACCOUNT) },
                    onSend = { contract -> navController.send(account, contract) },
                    onPropose = { contract -> navController.propose(account, contract) },
                    onSwap = { contract -> navController.swap(account, contract) },
                    onSafeSwap = { contract -> navController.safeSwap(account, contract) },
                    onReceive = { navController.open(AccountAction.Receive(chainId, account.address.hex)) },
                    onRename = { navController.open(AccountAction.Rename(chainId, account.address.hex)) },
                    onRemove = { navController.open(AccountAction.Remove(chainId, account.address.hex)) },
                    onAddOnNetwork = { navController.open(AccountAction.AddOnNetwork(chainId, account.address.hex)) },
                    onDerive = { navController.open(AccountAction.Derive(chainId, account.address.hex)) },
                    onOpenSafeTx = { hash -> navController.openSafeTx(account, hash) },
                    onImportRequest = { navController.importRequest(account) },
                    onSafeSettings = { navController.safeSettings(account) },
                    onOpenActivity = { key -> navController.openActivityDetail(account, key) },
                    onContinueSwap = { sell -> navController.continueSwap(account, sell) },
                )
            }
            composable(
                ACTIVITY_DETAIL_ROUTE,
                arguments = listOf(
                    navArgument(LOG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val account = entry.account()
                val hash = entry.savedStateHandle.get<String>(HASH)?.lowercase()?.takeIf(TRANSACTION_HASH::matches)
                val log = entry.savedStateHandle.get<String>(LOG)
                val logIndex = log?.toIntOrNull()?.takeIf { it >= 0 }
                if (account != null && hash != null && (log == null || logIndex != null)) {
                    ActivityDetailRoute(
                        viewModel = viewModel { graph.activityDetailViewModel(account, ActivityKey(hash, logIndex)) },
                        lightClient = lightClient.item(account.network),
                        onBack = { navController.popBackStack(ACTIVITY_DETAIL_ROUTE, inclusive = true) },
                        onStatus = { sent -> navController.openTransfer(account.network, sent) },
                        onAddToken = { contract -> navController.addToken(account.network, contract) },
                    )
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack(ACTIVITY_DETAIL_ROUTE, inclusive = true) }
                }
            }
            // Add token from an Activity detail: in this tab, its field filled in with the contract, on the
            // Activity's account's network, which the route carries as an account's does. A name of its
            // own, so that leaving Settings' Add token never finds this one (leave() tries the
            // /{chain}/{address} form of a pattern first).
            withAccount(Destination.ACTIVITY_ADD_TOKEN, navController) { contract ->
                AddTokenRoute(
                    viewModel { graph.addTokenViewModel(Eip55.format(contract.address), contract.network) },
                    lightClient,
                    onBack = { navController.leave(Destination.ACTIVITY_ADD_TOKEN) },
                    onAdded = { navController.leave(Destination.ACTIVITY_ADD_TOKEN) },
                )
            }
            // A screen, not a dialog: it takes the wallet password, under the window's capture protection.
            withAccount(Destination.DERIVE, navController) { account ->
                DeriveAccountRoute(
                    viewModel = viewModel { graph.deriveAccountViewModel(account) },
                    onBack = { navController.leave(Destination.DERIVE) },
                )
            }
            withAccount(Destination.RECEIVE, navController) { account ->
                ReceiveRoute(
                    viewModel = viewModel { graph.receiveViewModel(account) },
                    onBack = { navController.leave(Destination.RECEIVE) },
                    onGone = { navController.leave(Destination.RECEIVE) },
                )
            }
            // In the Accounts graph, so the bar stays while they are open over its screens.
            dialogWithAccount(Destination.RENAME, navController) { account ->
                RenameAccountRoute(
                    viewModel = viewModel { graph.renameAccountViewModel(account) },
                    onClose = { navController.leave(Destination.RENAME) },
                )
            }
            dialogWithAccount(Destination.REMOVE, navController) { account ->
                RemoveAccountRoute(
                    viewModel = viewModel { graph.removeAccountViewModel(account) },
                    onClose = { navController.leave(Destination.REMOVE) },
                )
            }
            dialogWithAccount(Destination.ADD_ON_NETWORK, navController) { account ->
                AddOnNetworksRoute(
                    viewModel = viewModel { graph.addOnNetworksViewModel(account) },
                    onClose = { navController.leave(Destination.ADD_ON_NETWORK) },
                )
            }
        }
        navigation(startDestination = Destination.SETTINGS, route = Tab.SETTINGS.graph) {
            composable(Destination.SETTINGS) {
                SettingsRoute(
                    viewModel { graph.settingsViewModel() },
                    onCurrency = { navController.navigate(Destination.CURRENCY) { launchSingleTop = true } },
                    onTokens = { navController.navigate(Destination.TOKENS) { launchSingleTop = true } },
                    onSecurity = { navController.navigate(Destination.SECURITY) { launchSingleTop = true } },
                    onConnections = { navController.navigate(Destination.CONNECTIONS) { launchSingleTop = true } },
                    connectionsOff = graph.dappConnectionsOff,
                )
            }
            composable(Destination.CONNECTIONS) {
                ConnectionsRoute(
                    viewModel { graph.connectionsViewModel() },
                    onBack = { navController.leave(Destination.CONNECTIONS) },
                    onPair = { navController.navigate(Destination.PAIR_DAPP) { launchSingleTop = true } },
                    onProposal = { key ->
                        navController.navigate("${Destination.DAPP_PROPOSAL}/$key") { launchSingleTop = true }
                    },
                    onRequest = { key ->
                        navController.navigate("${Destination.DAPP_REQUEST}/$key") { launchSingleTop = true }
                    },
                )
            }
            composable(Destination.PAIR_DAPP) {
                PairDappRoute(
                    viewModel { graph.pairDappViewModel() },
                    onBack = { navController.leave(Destination.PAIR_DAPP) },
                    onPaired = { navController.leave(Destination.PAIR_DAPP) },
                )
            }
            composable(DAPP_PROPOSAL_ROUTE) { entry ->
                val pairing = entry.savedStateHandle.get<String>(PAIRING)?.lowercase()?.takeIf(TOPIC::matches)
                val id = entry.savedStateHandle.get<String>(PROPOSAL)?.toLongOrNull()
                if (pairing != null && id != null) {
                    DappProposalRoute(
                        viewModel { graph.dappProposalViewModel("$pairing/$id") },
                        onBack = { navController.popBackStack(DAPP_PROPOSAL_ROUTE, inclusive = true) },
                        onDone = { navController.popBackStack(DAPP_PROPOSAL_ROUTE, inclusive = true) },
                    )
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack(DAPP_PROPOSAL_ROUTE, inclusive = true) }
                }
            }
            composable(DAPP_REQUEST_ROUTE) { entry ->
                val topic = entry.savedStateHandle.get<String>(DAPP_SESSION)?.lowercase()?.takeIf(TOPIC::matches)
                val id = entry.savedStateHandle.get<String>(DAPP_REQUEST_ID)?.toLongOrNull()
                if (topic != null && id != null) {
                    DappRequestRoute(
                        viewModel { graph.dappRequestViewModel("$topic/$id") },
                        onBack = { navController.popBackStack(DAPP_REQUEST_ROUTE, inclusive = true) },
                        onDone = { navController.popBackStack(DAPP_REQUEST_ROUTE, inclusive = true) },
                    )
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack(DAPP_REQUEST_ROUTE, inclusive = true) }
                }
            }
            composable(Destination.SECURITY) {
                SecurityRoute(
                    viewModel { graph.securityViewModel() },
                    onBack = { navController.leave(Destination.SECURITY) },
                )
            }
            composable(Destination.CURRENCY) {
                CurrencyRoute(
                    viewModel { graph.currencyViewModel() },
                    onBack = { navController.leave(Destination.CURRENCY) },
                    onChosen = { navController.leave(Destination.CURRENCY) },
                )
            }
            composable(Destination.TOKENS) {
                TokensRoute(
                    viewModel { graph.tokensViewModel() },
                    onBack = { navController.leave(Destination.TOKENS) },
                    onAddToken = { navController.navigate(Destination.ADD_TOKEN) { launchSingleTop = true } },
                )
            }
            composable(Destination.ADD_TOKEN) {
                AddTokenRoute(
                    viewModel { graph.addTokenViewModel() },
                    lightClient,
                    onBack = { navController.leave(Destination.ADD_TOKEN) },
                    onAdded = { navController.leave(Destination.ADD_TOKEN) },
                )
            }
        }
        navigation(startDestination = Destination.DEBUG, route = Tab.DEBUG.graph) {
            composable(Destination.DEBUG) {
                DebugRoute(debug)
            }
        }
        // Outside the tabs: a key operation covers the bar until it ends.
        composable(Destination.CREATE_ACCOUNT) {
            CreateAccountRoute(
                viewModel = viewModel { graph.createAccountViewModel() },
                onBack = { navController.leave(Destination.CREATE_ACCOUNT) },
                onCreated = { navController.leave(Destination.CREATE_ACCOUNT) },
            )
        }
        // A Safe's creation ends in its deployment: then where that stands, and back from it to Accounts.
        composable(Destination.CREATE_SAFE) {
            CreateSafeRoute(
                viewModel = viewModel { graph.createSafeViewModel() },
                lightClient = lightClient,
                onBack = { navController.leave(Destination.CREATE_SAFE) },
                onSent = { network, hash ->
                    navController.navigate(transferRoute(network, hash)) {
                        popUpTo(Destination.CREATE_SAFE) { inclusive = true }
                    }
                },
            )
        }
        composable(Destination.MNEMONIC_IMPORT) {
            MnemonicImportRoute(
                viewModel = viewModel { graph.mnemonicImportViewModel() },
                onBack = { navController.leave(Destination.MNEMONIC_IMPORT) },
                onImported = { navController.leave(Destination.MNEMONIC_IMPORT) },
            )
        }
        // Send, and then where the transfer stands: the transaction's flow, over the bar too.
        composable(
            SEND_ROUTE,
            arguments = listOf(
                navArgument(ASSET) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val account = entry.account()
            val asked = entry.savedStateHandle.get<String>(ASSET)
            val asset = asked?.let(Address::parse)
            if (account != null && (asked == null || asset != null)) {
                SendRoute(
                    viewModel = viewModel { graph.sendViewModel(account, asset) },
                    lightClient = lightClient.item(account.network),
                    onBack = { navController.popBackStack(SEND_ROUTE, inclusive = true) },
                    // Back from the status goes where Send was opened from, not to a signed review.
                    onSent = { hash ->
                        navController.navigate(transferRoute(account.network, hash)) {
                            popUpTo(SEND_ROUTE) { inclusive = true }
                        }
                    },
                    // An earlier transaction that may still be on its way: its status, over Send.
                    onOpenTransfer = { hash ->
                        navController.navigate(transferRoute(account.network, hash)) { launchSingleTop = true }
                    },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(SEND_ROUTE, inclusive = true) }
            }
        }
        // Swap, and then where the swap stands: the transaction's flow, over the bar too.
        composable(
            SWAP_ROUTE,
            arguments = listOf(ASSET, BUY, AMOUNT, VENUE, CONTINUE).map { name ->
                navArgument(name) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            },
        ) { entry ->
            val account = entry.account()
            val asked = entry.savedStateHandle.get<String>(ASSET)
            val asset = asked?.let(Address::parse)
            val fill = swapFillOf(
                entry.savedStateHandle.get<String>(BUY),
                entry.savedStateHandle.get<String>(AMOUNT),
                entry.savedStateHandle.get<String>(VENUE),
            )
            val continuing = entry.savedStateHandle.get<String>(CONTINUE) == CONTINUING
            if (account != null && (asked == null || asset != null)) {
                SwapRoute(
                    viewModel = viewModel { graph.swapViewModel(account, asset, fill, continuing) },
                    lightClient = lightClient.item(account.network),
                    onBack = { navController.popBackStack(SWAP_ROUTE, inclusive = true) },
                    // Back from the status goes where Swap was opened from, not to a signed review.
                    onSent = { hash ->
                        navController.navigate(transferRoute(account.network, hash)) {
                            popUpTo(SWAP_ROUTE) { inclusive = true }
                        }
                    },
                    // The approval, an earlier swap, or one that may still be on its way: its status, over Swap.
                    onOpenTransfer = { hash ->
                        navController.navigate(transferRoute(account.network, hash)) { launchSingleTop = true }
                    },
                    // A CoW order's status, the one placed or an earlier one, over Swap.
                    onOpenOrder = { uid -> navController.openCowOrder(account.network, uid) },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(SWAP_ROUTE, inclusive = true) }
            }
        }
        // A CoW order signed here: where it stands, its cancellation on chain, the same swap again.
        composable(COW_ORDER_ROUTE) { entry ->
            val network = entry.network()
            val uid = entry.savedStateHandle.get<String>(UID)?.lowercase()?.takeIf(ORDER_UID::matches)
            val parsed = uid?.let(CowOrderUid::parse)
            if (network != null && parsed != null) {
                CowOrderRoute(
                    viewModel = viewModel { graph.cowOrderViewModel(network, parsed) },
                    lightClient = lightClient.item(network),
                    onBack = { navController.popBackStack(COW_ORDER_ROUTE, inclusive = true) },
                    // Back from the cancellation's status goes where the order was opened from.
                    onCancelSent = { hash ->
                        navController.navigate(transferRoute(network, hash)) {
                            popUpTo(COW_ORDER_ROUTE) { inclusive = true }
                        }
                    },
                    onOpenTransfer = { hash -> navController.openTransfer(network, hash) },
                    // The same swap on Uniswap, in the order's place.
                    onSwapAgain = { again -> navController.swapAgain(network, again) },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(COW_ORDER_ROUTE, inclusive = true) }
            }
        }
        // A Safe's transactions: proposing one, importing one, and one of them; over the bar too,
        // as each ends in a signature or a transaction.
        composable(
            SAFE_PROPOSE_ROUTE,
            arguments = listOf(
                navArgument(ASSET) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val account = entry.account()
            val asked = entry.savedStateHandle.get<String>(ASSET)
            val asset = asked?.let(Address::parse)
            if (account != null && (asked == null || asset != null)) {
                SafeProposeRoute(
                    viewModel = viewModel { graph.safeProposeViewModel(account, asset) },
                    lightClient = lightClient.item(account.network),
                    onBack = { navController.popBackStack(SAFE_PROPOSE_ROUTE, inclusive = true) },
                    // Signed and queued: the transaction's screen, and back from it to where Send was opened.
                    onProposed = { hash -> navController.openSafeTx(account, hash, replacing = SAFE_PROPOSE_ROUTE) },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(SAFE_PROPOSE_ROUTE, inclusive = true) }
            }
        }
        composable(
            SAFE_SWAP_ROUTE,
            arguments = listOf(
                navArgument(ASSET) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(REPLACING) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val account = entry.account()
            val asked = entry.savedStateHandle.get<String>(ASSET)
            val asset = asked?.let(Address::parse)
            val replaced = entry.savedStateHandle.get<String>(REPLACING)
            val replacing = replaced?.lowercase()?.takeIf(TRANSACTION_HASH::matches)?.let(Digest::parse)
            if (account != null && (asked == null || asset != null) && (replaced == null || replacing != null)) {
                SafeSwapRoute(
                    viewModel = viewModel { graph.safeSwapViewModel(account, asset, replacing) },
                    lightClient = lightClient.item(account.network),
                    onBack = { navController.popBackStack(SAFE_SWAP_ROUTE, inclusive = true) },
                    // Signed and queued: the swap's screen, and back from it to where Swap was opened.
                    onProposed = { hash -> navController.openSafeTx(account, hash, replacing = SAFE_SWAP_ROUTE) },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(SAFE_SWAP_ROUTE, inclusive = true) }
            }
        }
        composable(SAFE_SETTINGS_ROUTE) { entry ->
            val account = entry.account()
            if (account != null) {
                SafeSettingsRoute(
                    viewModel = viewModel { graph.safeSettingsViewModel(account) },
                    lightClient = lightClient.item(account.network),
                    onBack = { navController.popBackStack(SAFE_SETTINGS_ROUTE, inclusive = true) },
                    // Signed and queued: the change's screen, and back from it to the Safe.
                    onProposed = { hash -> navController.openSafeTx(account, hash, replacing = SAFE_SETTINGS_ROUTE) },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(SAFE_SETTINGS_ROUTE, inclusive = true) }
            }
        }
        composable(SAFE_IMPORT_ROUTE) { entry ->
            val account = entry.account()
            if (account != null) {
                SafeImportRoute(
                    viewModel = viewModel { graph.safeImportViewModel(account) },
                    lightClient = lightClient.item(account.network),
                    onBack = { navController.popBackStack(SAFE_IMPORT_ROUTE, inclusive = true) },
                    // Checked and queued: the transaction's screen, which says where it went.
                    onImported = { hash ->
                        navController.openSafeTx(account, hash, replacing = SAFE_IMPORT_ROUTE, imported = true)
                    },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(SAFE_IMPORT_ROUTE, inclusive = true) }
            }
        }
        composable(SAFE_TX_ROUTE) { entry ->
            val account = entry.account()
            val hash = entry.savedStateHandle.get<String>(HASH)?.lowercase()?.takeIf(TRANSACTION_HASH::matches)
                ?.let(Digest::parse)
            if (account != null && hash != null) {
                SafeTxRoute(
                    // Said once, by the ViewModel this entry makes first: not again after a restore.
                    viewModel = viewModel {
                        val imported = entry.savedStateHandle.remove<Boolean>(IMPORTED) == true
                        graph.safeTxViewModel(account, hash, imported)
                    },
                    lightClient = lightClient.item(account.network),
                    onBack = { navController.popBackStack(SAFE_TX_ROUTE, inclusive = true) },
                    // Back from the status goes where the transaction was opened from, not to a signed review.
                    onSent = { sent ->
                        navController.navigate(transferRoute(account.network, sent)) {
                            popUpTo(SAFE_TX_ROUTE) { inclusive = true }
                        }
                    },
                    // Over this one: once the new swap is signed, its own screen takes Swap's place.
                    onProposeAgain = { navController.safeSwap(account, asset = null, replacing = hash) },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(SAFE_TX_ROUTE, inclusive = true) }
            }
        }
        composable(TRANSFER_ROUTE) { entry ->
            val network = entry.network()
            val hash = entry.savedStateHandle.get<String>(HASH)?.lowercase()?.takeIf(TRANSACTION_HASH::matches)
            if (network != null && hash != null) {
                TransferStatusRoute(
                    viewModel = viewModel { graph.transferStatusViewModel(network, hash) },
                    lightClient = lightClient.item(network),
                    onDone = { navController.popBackStack(TRANSFER_ROUTE, inclusive = true) },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(TRANSFER_ROUTE, inclusive = true) }
            }
        }
        // Outside the tabs too, and alone on the back stack while it is shown.
        composable(Destination.UPDATE_REQUIRED) {
            UpdateRequiredRoute(viewModel = update)
        }
    }
}

/** Opens what [action] asks for; Copy is the screen's own, done on the clipboard. */
private fun NavController.open(action: AccountAction) {
    val network = Network.ofChainId(action.chainId) ?: return
    // The route carries the address in lower case: an identifier, never display text.
    val account = AccountId(network, Address.parse(action.address) ?: return)
    val route = when (action) {
        is AccountAction.Send -> return send(account, asset = null)
        is AccountAction.Propose -> return propose(account, asset = null)
        is AccountAction.Open -> Destination.ACCOUNT
        is AccountAction.Receive -> Destination.RECEIVE
        is AccountAction.Rename -> Destination.RENAME
        is AccountAction.Remove -> Destination.REMOVE
        is AccountAction.AddOnNetwork -> Destination.ADD_ON_NETWORK
        is AccountAction.Derive -> Destination.DERIVE
        is AccountAction.Copy -> return
    }
    // A second tap while the first one's screen is opening: one screen, the first one asked
    // for. (Not launchSingleTop: that would reuse the entry, and its account, for another one.)
    if (currentDestination?.route == "$route/{$CHAIN}/{$ADDRESS}") return
    navigate("$route/${account.route}")
}

/**
 * Opens Send from the [account], with the token at [asset] chosen (null: the network's currency).
 * The route carries both in lower case: identifiers, never display text.
 */
private fun NavController.send(account: AccountId, asset: String?) {
    // A second tap while the first one's screen is opening: one screen, the first one asked for.
    if (currentDestination?.route == SEND_ROUTE) return
    val token = asset?.let { Address.parse(it) ?: return }
    navigate("${Destination.SEND}/${account.route}" + (token?.let { "?$ASSET=${it.hex}" } ?: ""))
}

/**
 * Opens Swap from the [account], selling the token at [asset] (null: the network's currency); both
 * in lower case, as identifiers.
 */
private fun NavController.swap(account: AccountId, asset: String?) {
    // A second tap while the first one's screen is opening: one screen, the first one asked for.
    if (currentDestination?.route == SWAP_ROUTE) return
    val token = asset?.let { Address.parse(it) ?: return }
    navigate("${Destination.SWAP}/${account.route}" + (token?.let { "?$ASSET=${it.hex}" } ?: ""))
}

/**
 * Opens Swap from [account] with its swap selling the token at [sell] whose next step waits, quoted
 * again (docs/simple-ui.md, H5): Continue on its card in the account's Activity.
 */
private fun NavController.continueSwap(account: AccountId, sell: String) {
    if (currentDestination?.route == SWAP_ROUTE) return
    val token = Address.parse(sell) ?: return
    navigate("${Destination.SWAP}/${account.route}?$ASSET=${token.hex}&$CONTINUE=$CONTINUING")
}

/**
 * Opens Send from the Safe [safe] — the proposal of a Safe transaction — with the token at [asset]
 * chosen (null: the network's currency); both in lower case, as identifiers.
 */
private fun NavController.propose(safe: AccountId, asset: String?) {
    // A second tap while the first one's screen is opening: one screen, the first one asked for.
    if (currentDestination?.route == SAFE_PROPOSE_ROUTE) return
    val token = asset?.let { Address.parse(it) ?: return }
    navigate("${Destination.SAFE}/${safe.route}/${Destination.PROPOSE}" + (token?.let { "?$ASSET=${it.hex}" } ?: ""))
}

/**
 * Opens Swap from the Safe [safe] — the proposal of its Safe transactions — selling the token at
 * [asset] (null: the network's currency; one that is no address opens nothing); or, with
 * [replacing], the swap queued under that hash proposed again in its place.
 */
private fun NavController.safeSwap(safe: AccountId, asset: String?, replacing: Digest? = null) {
    // A second tap while the first one's screen is opening: one screen, the first one asked for.
    if (currentDestination?.route == SAFE_SWAP_ROUTE) return
    val token = asset?.let { Address.parse(it) ?: return }
    val query = listOfNotNull(token?.let { "$ASSET=${it.hex}" }, replacing?.let { "$REPLACING=0x${it.hex}" })
        .joinToString("&")
    navigate("${Destination.SAFE}/${safe.route}/${Destination.SWAP}" + if (query.isEmpty()) "" else "?$query")
}

/** Opens the detail of the row [key] of the Activity of the [account]; for a CoW order, its status. */
private fun NavController.openActivityDetail(account: AccountId, key: ActivityKey) {
    if (key.cowOrder) return openCowOrder(account.network, key.hash)
    // A second tap while the first one's screen is opening: one screen, the first one asked for.
    // (Not launchSingleTop: that would reuse the entry, and its key, for another one.)
    if (currentDestination?.route == ACTIVITY_DETAIL_ROUTE) return
    val hash = key.hash.lowercase().takeIf(TRANSACTION_HASH::matches) ?: return
    navigate(
        "${Destination.ACTIVITY}/${account.route}/${Destination.ACTIVITY_DETAIL}/$hash" +
            (key.logIndex?.let { "?$LOG=$it" } ?: ""),
    )
}

/** Opens Add token by address over the Accounts tab, with the [contract] (an address) filled in. */
private fun NavController.addToken(network: Network, contract: String) {
    if (currentDestination?.route == "${Destination.ACTIVITY_ADD_TOKEN}/{$CHAIN}/{$ADDRESS}") return
    val address = Address.parse(contract) ?: return
    navigate("${Destination.ACTIVITY_ADD_TOKEN}/${network.chainId}/${address.hex}")
}

/** The status of the transaction [hash] on [network], as a route carries it: the hash in lower case. */
private fun transferRoute(network: Network, hash: String): String =
    "${Destination.TRANSFER}/${network.chainId}/${hash.lowercase()}"

/**
 * Opens the status of the CoW order [uid] (`0x` and 112 hex digits) signed on [network], as a route
 * carries it: in lower case.
 */
private fun NavController.openCowOrder(network: Network, uid: String) {
    // A second tap while the first one's screen is opening: one screen, the first one asked for.
    if (currentDestination?.route == COW_ORDER_ROUTE) return
    val route = uid.lowercase().takeIf(ORDER_UID::matches) ?: return
    navigate("${Destination.COW_ORDER}/${network.chainId}/$route")
}

/**
 * Opens Swap with [again] filled in, on Uniswap, on [network], in place of the order's status, and of
 * the Swap it was opened from if it was (whose order this was): back from it goes where that was
 * opened from.
 */
private fun NavController.swapAgain(network: Network, again: SwapAgain) {
    if (currentDestination?.route == SWAP_ROUTE) return
    val from = AccountId(network, Address.parse(again.from) ?: return)
    val sell = Address.parse(again.sell) ?: return
    val buy = again.buy?.let { Address.parse(it)?.hex ?: return } ?: NATIVE_PICK
    val amount = again.amount.takeIf(AMOUNT_TEXT::matches) ?: return
    popBackStack(COW_ORDER_ROUTE, inclusive = true)
    if (currentDestination?.route == SWAP_ROUTE) popBackStack()
    navigate("${Destination.SWAP}/${from.route}?$ASSET=${sell.hex}&$BUY=$buy&$AMOUNT=$amount&$VENUE=$UNISWAP_VENUE")
}

/**
 * What Swap's route asks to be filled in: what it buys ([buy], a token's address or [NATIVE_PICK]),
 * the [amount] as text and the [venue]; null when it asks for none, or not in a form this build reads.
 */
private fun swapFillOf(buy: String?, amount: String?, venue: String?): SwapFill? {
    if (buy == null || amount == null || venue == null) return null
    val token = if (buy == NATIVE_PICK) null else Address.parse(buy) ?: return null
    val chosen = when (venue) {
        UNISWAP_VENUE -> SwapVenue.UNISWAP
        COW_VENUE -> SwapVenue.COW
        else -> return null
    }
    return SwapFill(token, amount.takeIf(AMOUNT_TEXT::matches) ?: return null, chosen)
}

/** Opens the status of the transaction [hash] (`0x` and 64 hex digits) on [network]. */
private fun NavController.openTransfer(network: Network, hash: String) {
    // A second tap while the first one's screen is opening: one screen, the first one asked for.
    // (Not launchSingleTop: that would reuse the entry, and its hash, for another one.)
    if (currentDestination?.route == TRANSFER_ROUTE) return
    val checked = hash.lowercase().takeIf(TRANSACTION_HASH::matches) ?: return
    navigate(transferRoute(network, checked))
}

/** Opens Import a request for the Safe [safe]. */
private fun NavController.importRequest(safe: AccountId) {
    if (currentDestination?.route == SAFE_IMPORT_ROUTE) return
    navigate("${Destination.SAFE}/${safe.route}/${Destination.IMPORT}")
}

/** Opens the settings of the Safe [safe]. */
private fun NavController.safeSettings(safe: AccountId) {
    // A second tap while the first one's screen is opening: one screen, the first one asked for.
    if (currentDestination?.route == SAFE_SETTINGS_ROUTE) return
    navigate("${Destination.SAFE}/${safe.route}/${Destination.SAFE_SETTINGS}")
}

/**
 * Opens the Safe transaction [hash] (`0x` and 64 hex digits) of the Safe [safe]; in place of the
 * destination [replacing] when set (the proposal or import that led to it), so back from it returns
 * to where that was opened from. [imported] when an import has just queued it: the screen says so.
 */
private fun NavController.openSafeTx(
    safe: AccountId,
    hash: String,
    replacing: String? = null,
    imported: Boolean = false,
) {
    if (replacing == null && currentDestination?.route == SAFE_TX_ROUTE) return
    val digest = Digest.parse(hash) ?: return
    navigate("${Destination.SAFE}/${safe.route}/${Destination.SAFE_TX}/0x${digest.hex}") {
        if (replacing != null) popUpTo(replacing) { inclusive = true }
    }
    // The entry just opened, before it is first drawn: its ViewModel takes the flag when it is made.
    if (imported) currentBackStackEntry?.savedStateHandle?.set(IMPORTED, true)
}

/**
 * Pops the destination of [pattern] and everything above it, and nothing else: a second tap
 * during the exit animation, or a screen whose account vanished under a dialog, pops only
 * what is still there.
 */
private fun NavController.leave(pattern: String) {
    if (popBackStack("$pattern/{$CHAIN}/{$ADDRESS}", inclusive = true)) return
    if (!popBackStack("$pattern/{$ADDRESS}", inclusive = true)) popBackStack(pattern, inclusive = true)
}

/** An account as a route carries it, `chainId/address`: identifiers, the address in lower case. */
private val AccountId.route: String
    get() = "${network.chainId}/${address.hex}"

/** The network this entry's route names by its chain id, or null when it names none Serotine knows. */
private fun NavBackStackEntry.network(): Network? =
    savedStateHandle.get<String>(CHAIN)?.toLongOrNull()?.let(Network::ofChainId)

/** The account this entry's route names (`{chain}/{address}`), or null when it names none. */
private fun NavBackStackEntry.account(): AccountId? {
    val network = network() ?: return null
    val address = savedStateHandle.get<String>(ADDRESS)?.let(Address::parse) ?: return null
    return AccountId(network, address)
}

/** A screen of one account, at `pattern/{chain}/{address}`; a route without a valid account leaves at once. */
private fun NavGraphBuilder.withAccount(
    pattern: String,
    navController: NavController,
    content: @Composable (AccountId) -> Unit,
) {
    composable("$pattern/{$CHAIN}/{$ADDRESS}") { entry -> WithAccount(entry, pattern, navController, content) }
}

/** As [withAccount], for a dialog over the screen below it. */
private fun NavGraphBuilder.dialogWithAccount(
    pattern: String,
    navController: NavController,
    content: @Composable (AccountId) -> Unit,
) {
    dialog("$pattern/{$CHAIN}/{$ADDRESS}") { entry -> WithAccount(entry, pattern, navController, content) }
}

@Composable
private fun WithAccount(
    entry: NavBackStackEntry,
    pattern: String,
    navController: NavController,
    content: @Composable (AccountId) -> Unit,
) {
    val account = entry.account()
    if (account != null) {
        content(account)
    } else {
        LaunchedEffect(Unit) { navController.leave(pattern) }
    }
}

private object Destination {
    const val ACCOUNTS = "accounts"
    const val CONNECTIONS = "connections"
    const val PAIR_DAPP = "pairdapp"
    const val DAPP_PROPOSAL = "dappproposal"
    const val DAPP_REQUEST = "dapprequest"
    const val ACCOUNT = "account"
    const val RECEIVE = "receive"
    const val ACTIVITY = "activity"
    const val ACTIVITY_DETAIL = "tx"
    const val WATCH = "watch"
    const val RENAME = "rename"
    const val REMOVE = "remove"
    const val ADD_ON_NETWORK = "add-on-network"
    const val DERIVE = "derive"
    const val SETTINGS = "settings"
    const val DEBUG = "debug"
    const val CURRENCY = "currency"
    const val TOKENS = "tokens"
    const val SECURITY = "security"
    const val ADD_TOKEN = "add-token"
    const val ACTIVITY_ADD_TOKEN = "activity-add-token"
    const val MNEMONIC_IMPORT = "mnemonic-import"
    const val CREATE_ACCOUNT = "create-account"
    const val CREATE_SAFE = "create-safe"
    const val SEND = "send"
    const val SWAP = "swap"
    const val TRANSFER = "transfer"
    const val SAFE = "safe"
    const val SAFE_TX = "tx"
    const val PROPOSE = "propose"
    const val IMPORT = "import"
    const val SAFE_SETTINGS = "settings"
    const val UPDATE_REQUIRED = "update-required"
    const val COW_ORDER = "cow-order"
}

/** The route argument every account destination carries beside [CHAIN], and Add token's contract. */
private const val ADDRESS = "address"

/** The route argument naming an account's network, or a transfer's, by its chain id. */
private const val CHAIN = "chain"

/** Send's and Swap's optional route argument: the contract of the token chosen (sold, for a swap). */
private const val ASSET = "asset"

/** The transfer status's, a Safe transaction's and an Activity detail's route argument: the transaction hash. */
private const val HASH = "hash"
private const val PAIRING = "pairing"
private const val PROPOSAL = "proposal"
private const val DAPP_SESSION = "session"
private const val DAPP_REQUEST_ID = "request"

/** An Activity detail's optional route argument: the log index of an event's log in its block. */
private const val LOG = "log"

/** A Safe's swap's optional route argument: the hash of the queued swap it is proposed again in place of. */
private const val REPLACING = "replacing"

/** Set on a Safe transaction's entry that an import has just queued, until its ViewModel takes it. */
private const val IMPORTED = "imported"

/** Send from an account, with a token chosen or not. */
private const val SEND_ROUTE = "${Destination.SEND}/{$CHAIN}/{$ADDRESS}?$ASSET={$ASSET}"

/** Swap's optional route arguments, all three or none: what it buys, the amount as text, the venue. */
private const val BUY = "buy"
private const val AMOUNT = "amount"
private const val VENUE = "venue"

/** Swap's route argument for a swap whose next step waits, opened from the account's Activity: [CONTINUING]. */
private const val CONTINUE = "continue"
private const val CONTINUING = "waiting"

/** How Swap's route names the network's currency bought, and the two venues. */
private const val NATIVE_PICK = "native"
private const val UNISWAP_VENUE = "uniswap"
private const val COW_VENUE = "cow"

/** An amount as Swap's route carries it: digits, with a decimal point or not. */
private val AMOUNT_TEXT = Regex("^[0-9]+(\\.[0-9]+)?$")

/** Swap from an account, selling a token or the network's currency, filled in or not. */
private const val SWAP_ROUTE =
    "${Destination.SWAP}/{$CHAIN}/{$ADDRESS}?$ASSET={$ASSET}&$BUY={$BUY}&$AMOUNT={$AMOUNT}&$VENUE={$VENUE}" +
        "&$CONTINUE={$CONTINUE}"

/** A CoW order's route argument: its UID. */
private const val UID = "uid"

/** Where a CoW order signed here stands, by its network and its UID. */
private const val COW_ORDER_ROUTE = "${Destination.COW_ORDER}/{$CHAIN}/{$UID}"

/** A CoW order's UID as a route carries it: `0x` and 112 lower-case hex digits. */
private val ORDER_UID = Regex("^0x[0-9a-f]{112}$")

/** The detail of a row of an account's Activity: a transaction by its hash, an event by its log too. */
private const val ACTIVITY_DETAIL_ROUTE =
    "${Destination.ACTIVITY}/{$CHAIN}/{$ADDRESS}/${Destination.ACTIVITY_DETAIL}/{$HASH}?$LOG={$LOG}"

/** A dApp's session proposal, by its pairing's topic and its id there (docs/walletconnect.md, screen D3). */
private const val DAPP_PROPOSAL_ROUTE = "${Destination.DAPP_PROPOSAL}/{$PAIRING}/{$PROPOSAL}"

/** A dApp's request to sign, by its session's topic and its id there (docs/walletconnect.md, screen D4). */
private const val DAPP_REQUEST_ROUTE = "${Destination.DAPP_REQUEST}/{$DAPP_SESSION}/{$DAPP_REQUEST_ID}"

/** A WalletConnect topic: 64 hex digits. */
private val TOPIC = Regex("^[0-9a-f]{64}$")

/** Where a transfer stands, by its network and its hash. */
private const val TRANSFER_ROUTE = "${Destination.TRANSFER}/{$CHAIN}/{$HASH}"

/** A Safe's transaction, by the Safe and the transaction's hash. */
private const val SAFE_TX_ROUTE = "${Destination.SAFE}/{$CHAIN}/{$ADDRESS}/${Destination.SAFE_TX}/{$HASH}"

/** Send from a Safe: a Safe transaction proposed, with a token chosen or not. */
private const val SAFE_PROPOSE_ROUTE =
    "${Destination.SAFE}/{$CHAIN}/{$ADDRESS}/${Destination.PROPOSE}?$ASSET={$ASSET}"

/** Swap from a Safe: its Safe transactions proposed, selling a token or not, or a queued swap again. */
private const val SAFE_SWAP_ROUTE =
    "${Destination.SAFE}/{$CHAIN}/{$ADDRESS}/${Destination.SWAP}?$ASSET={$ASSET}&$REPLACING={$REPLACING}"

/** A Safe's settings, and the changes of them proposed. */
private const val SAFE_SETTINGS_ROUTE = "${Destination.SAFE}/{$CHAIN}/{$ADDRESS}/${Destination.SAFE_SETTINGS}"

/** Import a signing request for a Safe. */
private const val SAFE_IMPORT_ROUTE = "${Destination.SAFE}/{$CHAIN}/{$ADDRESS}/${Destination.IMPORT}"

/** A transaction hash (a Safe transaction's too) as a route carries it: `0x` and 64 lower-case hex digits. */
private val TRANSACTION_HASH = Regex("^0x[0-9a-f]{64}$")

/** From this width on, the tabs are a rail at the side instead of a bar at the bottom. */
private val WIDE_WINDOW = 840.dp
