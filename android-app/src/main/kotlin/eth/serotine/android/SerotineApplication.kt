package eth.serotine.android

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import eth.serotine.android.keys.ForegroundActivity
import eth.serotine.android.keys.PlatformBiometricPrompter
import eth.serotine.android.upgrade.AndroidUpgradeNotifier
import eth.serotine.android.upgrade.UpgradeCheckJob
import eth.serotine.android.widget.RateRefreshJob
import eth.serotine.android.widget.RateWidget
import eth.serotine.android.widget.RateWidgetPairs
import eth.serotine.data.account.FileAccountRepository
import eth.serotine.data.cow.FileCowOrders
import eth.serotine.data.cow.HttpCowApi
import eth.serotine.data.walletconnect.WalletConnectDapps
import eth.serotine.data.engine.AndroidDnsServers
import eth.serotine.data.engine.MyotisHost
import eth.serotine.data.history.FileHistoryStarts
import eth.serotine.data.keys.AndroidBiometricKey
import eth.serotine.data.keys.AndroidKeystoreSecret
import eth.serotine.data.keys.EnvelopeKeyStore
import eth.serotine.data.keys.PasswordOnlyTier
import eth.serotine.data.keys.Secp256k1Recovery
import eth.serotine.data.logs.MemoryDebugLog
import eth.serotine.data.networks.FileNetworkSettings
import eth.serotine.data.prices.PinnedFeedTable
import eth.serotine.data.rates.FileLastExchangeRate
import eth.serotine.data.safe.FileSafeTxQueue
import eth.serotine.data.safe.JsonSigningRequestCodec
import eth.serotine.data.safe.PinnedSafeReleases
import eth.serotine.data.settings.FileDebugScreen
import eth.serotine.data.settings.FileDisplayCurrency
import eth.serotine.data.settings.FileAmountsHidden
import eth.serotine.data.settings.FileInterfaceMode
import eth.serotine.data.settings.FileSwapVenue
import eth.serotine.data.swaps.FileWaitingSwaps
import eth.serotine.data.tokens.BuiltInTokenCatalog
import eth.serotine.data.tokens.FileUserTokens
import eth.serotine.data.transfers.FileSentTransfers
import eth.serotine.data.transfers.FileTransactionHistory
import eth.serotine.data.upgrade.FileKnownUpgradeStore
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.RatePair
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.usecase.FollowAccountLogs
import eth.serotine.domain.usecase.AnswerDappRequestsAtOnce
import eth.serotine.domain.usecase.FollowDappAccounts
import eth.serotine.domain.usecase.FollowDebugScreen
import eth.serotine.domain.usecase.FollowExchangeRate
import eth.serotine.domain.usecase.FollowInterfaceMode
import eth.serotine.domain.usecase.FollowNetworks
import eth.serotine.domain.usecase.FollowSafeDeployments
import eth.serotine.domain.usecase.FollowSentTransactions
import eth.serotine.domain.usecase.FollowUpgradeReports
import eth.serotine.domain.usecase.HistoryNotingAccounts
import eth.serotine.domain.usecase.HistoryRecordingSentTransfers
import eth.serotine.domain.usecase.ListDeployedSafe
import eth.serotine.domain.usecase.NetworkDemand
import eth.serotine.domain.usecase.ObserveDebugTab
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ProveCowOrderEnds
import eth.serotine.domain.usecase.ReadExchangeRate
import eth.serotine.domain.usecase.RateWidgetChoices
import eth.serotine.domain.usecase.RefreshExchangeRate
import eth.serotine.domain.usecase.UpgradeNotices
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.AppGraph
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.platform.ScreenCaptureDebugSwitch
import eth.serotine.ui.ratewidget.WidgetPairUiState
import eth.serotine.ui.ratewidget.widgetBaseUiState
import eth.serotine.ui.ratewidget.widgetNetworkUiState
import eth.serotine.ui.ratewidget.widgetQuoteUiState
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Android composition root: the one place that knows which adapters implement the
 * domain's ports on this platform, and the owner of the process's one Myotis engine
 * (docs/embedding-myotis.md). Built once per process; activities read [graph]. The engine's
 * networking is on while something holds it ([holdEngine]: a visible activity, the daily
 * upgrade check), and while it is, the engine's upgrade reports are followed into
 * notifications ([upgradeNotices]), the Safes this wallet deployed are listed once the light
 * client proves them ([FollowSafeDeployments]), the engine's log index follows the accounts
 * ([FollowAccountLogs]) and, while an activity is visible and an exchange-rate widget is
 * placed, its rate is kept current ([FollowExchangeRate]): the work no screen owns, run here
 * beside the [graph], on the same adapters, and never from the shared UI. The widget's refresh
 * while the app is closed ([RateRefreshJob]) wakes the engine alone, without that work
 * ([refreshRateInBackground]).
 */
class SerotineApplication : Application() {

    // A light client per network that is on and needed (CLAUDE.md, *Data sources*). The engine boots in the background
    // and keeps its state under the app's private files; on an ABI the engine library does
    // not ship (it has arm64-v8a and x86_64), balances say so. The balances last verified are
    // kept with the other files (none of which leave the device), to be shown as such after a
    // restart until a head can be read. The adapters' diagnostics and the engine's own log go to
    // logcat in the debug app only (debuggable below); each request asked of the engine goes to
    // the Debug tab's log, in memory, in either app.
    // One engine, one light client per network that is on and needed: FollowNetworks starts with
    // the host and runs for the life of the process (docs/networks.md, *The engine host*).
    private val node: MyotisHost by lazy {
        MyotisHost.start(
            filesDir.resolve("myotis"),
            AndroidDnsServers(this),
            lastBalances = filesDir.resolve("last-balances.json").path,
            debugLog = if (debuggable) { line -> Log.d(LOG_TAG, line) } else null,
            apiLog = debugLog,
        ).also { host ->
            scope.launch { followNetworks(host) }
            // Values proven on demand in simple mode, at every head in Detailed view (docs/simple-ui.md).
            scope.launch { followInterfaceMode(host) }
        }
    }

    /** Keeps the proving of values to the view the user chose, for the life of the process. */
    private suspend fun followInterfaceMode(host: MyotisHost) {
        try {
            FollowInterfaceMode(interfaceMode, host)()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Serotine: following the view stopped: ${e::class.simpleName}")
        }
    }

    /**
     * Keeps each network's light client running while it is on and needed, for the life of the
     * process. A failure ends it and is reported: the light clients keep their last state.
     */
    private suspend fun followNetworks(host: MyotisHost) {
        try {
            FollowNetworks(networkSettings, accounts, networkDemand, host)()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Serotine: following the networks stopped: ${e::class.simpleName}")
        }
    }

    /** The screens' holds on a network with no account on it yet (Watch, Add token), and the widget's read's. */
    private val networkDemand = NetworkDemand()

    /** The Debug tab's log: in memory for the life of the process, never in a file. */
    private val debugLog by lazy { MemoryDebugLog() }

    /** Whether the user turned the Debug tab on, beside the other settings: [debugLog] records only then. */
    private val debugScreen by lazy { FileDebugScreen(filesDir.resolve("debug-screen.json").path) }

    /** The view the user chose, simple mode or Detailed view (docs/simple-ui.md), beside the other settings. */
    private val interfaceMode by lazy { FileInterfaceMode(filesDir.resolve("ui-mode.json").path) }
    private val amountsHidden by lazy { FileAmountsHidden(filesDir.resolve("amounts-hidden.json").path) }

    /** eth.serotine.debug is debuggable, the release app is not: what the debug app's extras hang on. */
    private val debuggable by lazy { applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }

    /**
     * The upgrade record, for this app version only: one instance, which the screens read and
     * the notices write. Peer reports, no keys; kept out of backups with the rest of the files.
     */
    private val knownUpgrades by lazy { FileKnownUpgradeStore(filesDir.resolve("upgrade.json").path, APP_VERSION) }

    /**
     * Which networks are on (docs/networks.md, *Networks on and off*), beside the other settings.
     * A network the file does not mention yet gets this app's default: the release app turns on
     * the real networks and leaves Sepolia off, the debug app turns on Sepolia alone.
     */
    private val networkSettings by lazy {
        FileNetworkSettings(filesDir.resolve("networks.json").path, defaultOn = { it.testnet == debuggable })
    }

    /**
     * What the user hears about each network's upgrades outside the app, apart (docs/networks.md,
     * *Upgrades and the update screen*). Needs no engine, so a reminder never starts one.
     */
    private val upgradeNotices by lazy {
        Network.entries.associateWith { UpgradeNotices(it, knownUpgrades, AndroidUpgradeNotifier(this, it)) }
    }

    /**
     * Where each account's history starts (issue #85), beside the account list: addresses with a
     * block or the time each was added, public data only.
     */
    private val historyStarts by lazy { FileHistoryStarts(filesDir.resolve("history-starts.json").path) }

    /**
     * The account list in the app's private files, which the manifest keeps out of backups and
     * device transfers; addresses and labels only, no keys. Each account added is noted in
     * [historyStarts] as it is. One instance, which the graph and the followers share.
     */
    private val accounts by lazy {
        HistoryNotingAccounts(FileAccountRepository(filesDir.resolve("accounts.json").path), historyStarts)
    }

    /**
     * The transactions signed here, with their signed bytes (public once broadcast, no key in
     * them), beside the account list. One instance, which the graph and the deployments' follower
     * share.
     */
    private val sentRecord by lazy { FileSentTransfers(filesDir.resolve("sent-transfers.json").path) }

    /**
     * Every transaction sent from here, kept for good with how each ended (issue #85): what an
     * account's Activity lists. Public data only, as [sentRecord]'s, beside it.
     */
    private val transactionHistory by lazy {
        FileTransactionHistory(filesDir.resolve("transaction-history.json").path)
    }

    /** [sentRecord], each of whose records also goes into [transactionHistory]: what all that sends is handed. */
    private val sentTransfers by lazy { HistoryRecordingSentTransfers(sentRecord, transactionHistory) }

    /**
     * The display currency, one instance, which Settings writes and the exchange-rate widget
     * reads, so the widget follows a change at once.
     */
    private val displayCurrency by lazy { FileDisplayCurrency(filesDir.resolve("display-currency.json").path) }

    /** The CoW orders signed here, each live until its end is proven (docs/swaps.md): public data only. */
    private val cowOrders by lazy { FileCowOrders(filesDir.resolve("cow-orders.json").path) }

    /** The venue last chosen on Swap, compose, which the next swap starts at. */
    private val swapVenue by lazy { FileSwapVenue(filesDir.resolve("swap-venue.json").path) }
    private val waitingSwaps by lazy { FileWaitingSwaps(filesDir.resolve("waiting-swaps.json").path) }

    /**
     * dApp sessions over WalletConnect (docs/walletconnect.md), with this build type's project ID:
     * off without one. Their state is in the app's files, which stay out of backups and device
     * transfers (data_extraction_rules.xml), as ruling 8 asks.
     */
    private val dapps by lazy {
        WalletConnectDapps(
            filesDir.resolve("walletconnect/state.json").path,
            WalletConnectDapps.projectIdFor(debuggable),
        )
    }

    /**
     * The exchange rate last verified, for the widget, beside the other files: a price and its
     * times, public data only. Read without the engine, so drawing a widget never starts it.
     */
    private val lastRate by lazy { FileLastExchangeRate(filesDir.resolve("last-exchange-rate.json").path) }

    /** What a widget's rate is read with through the engine: the display currency, and the reads. */
    private val rateReads by lazy {
        ObserveDisplayCurrencies(node.priceFeeds, displayCurrency, accounts) to
            ReadExchangeRate(node.priceFeeds, node.priceHistory)
    }

    /**
     * Reads [pair]'s widget rate, and the one of a day before, through the engine, and keeps it in
     * [lastRate]: one per pair, each widget showing one (docs/wallet-ux.md, *Home-screen widget*).
     */
    private fun refreshRate(pair: RatePair): RefreshExchangeRate {
        val (currencies, read) = rateReads
        return RefreshExchangeRate(currencies, read, lastRate, pair)
    }

    /**
     * What a widget being placed may show, from the pinned tables alone: choosing starts no
     * engine (docs/wallet-ux.md, *Home-screen widget*).
     */
    private val widgetChoices by lazy { RateWidgetChoices(tokenCatalog, PinnedFeedTable()) }

    /** The built-in token list, which the screens and the widget's configuration share. */
    private val tokenCatalog by lazy { BuiltInTokenCatalog() }

    /**
     * Draws every placed widget from the rates kept for their pairs, saying so where a network
     * is off, without the engine; [refreshing] as [RateWidget.draw] takes it.
     */
    private suspend fun drawWidgets(refreshing: Boolean? = null) {
        val pairs = RateWidget.pairs(this)
        val rates = pairs.associateWith { lastRate.load(it) }
        // Each (network, base) once: widgets of the same base in different currencies share it.
        val iconOf = pairs.map { it.network to it.base }.distinct()
            .associateWith { (network, base) -> widgetChoices.icon(network, base) }
        val icons = pairs.associateWith { iconOf[it.network to it.base] }
        RateWidget.draw(this, rates, icons, networkSettings.observe().first(), refreshing)
    }

    /**
     * The step of a widget's configuration with [network] and [base] chosen so far (null: not
     * yet): the networks it may show (docs/networks.md, *The widget*), then what its rate may be
     * of on [network], then the currencies [base] may be shown in.
     */
    suspend fun widgetPairStep(network: Network?, base: PriceId?): WidgetPairUiState = when {
        network == null -> widgetNetworkChoices()
        base == null -> widgetBaseUiState(network, widgetChoices.bases(network))
        else -> widgetQuoteUiState(network, base.id, widgetChoices.quotes(network, base))
    }

    /** The networks a widget being placed may show. */
    private suspend fun widgetNetworkChoices(): WidgetPairUiState.Networks {
        val on = networkSettings.observe().first()
        val listed = accounts.observeAccounts().first() as? AccountList.Loaded
        return widgetNetworkUiState(
            Network.entries.map(NetworkLabel::of),
            on.mapTo(HashSet()) { it.chainId },
            listed?.accounts?.mapTo(HashSet()) { it.network.chainId },
        )
    }

    /**
     * The widget [id] was placed showing [pair]: recorded, drawn, its rate read soon, and
     * followed while the app is open. False when the choice could not be stored. Called on the
     * main thread; the choice is written off it.
     */
    suspend fun rateWidgetConfigured(id: Int, pair: RatePair): Boolean {
        if (!RateWidgetPairs(this).set(id, pair)) return false
        // On the scheduling lane, so a cancel asked for by a removal meanwhile never lands before it.
        scope.launch(jobScheduling) {
            drawWidgets()
            RateRefreshJob.schedule(this@SerotineApplication, now = true)
        }
        rateWidgetsChanged()
        return true
    }

    /** The widgets placed, or their pairs, changed: their rates are followed anew while the app is open. */
    fun rateWidgetsChanged() {
        rateFollowing?.cancel()
        rateFollowing = null
        followRateForWidgets()
    }

    val graph: AppGraph by lazy {
        // Imported seeds in envelopes under the app's private files, the hardware factor a
        // Keystore HMAC key (docs/keys-and-signing.md); kept out of backups and transfers. The
        // biometric path's key prompts on the activity in front. One instance is both the key
        // store and the signer, so that their calls take turns. Where the password must stand
        // alone (a key store outside secure hardware), its Argon2id tier is sized by this
        // device's memory (the owner's ruling of 2026-10-18).
        val keyStore = EnvelopeKeyStore(
            filesDir.resolve("keys").path,
            AndroidKeystoreSecret(),
            biometricKey = AndroidBiometricKey(this, PlatformBiometricPrompter { foreground.current }),
            passwordOnlyTier = passwordOnlyTier(),
        )
        AppGraph(
            accounts = accounts,
            keys = keyStore,
            nativeBalances = node.nativeBalances,
            tokenCatalog = tokenCatalog,
            tokenBalances = node.tokenBalances,
            tokenMetadata = node.tokenMetadata,
            userTokens = FileUserTokens(filesDir.resolve("tokens.json").path),
            priceFeeds = node.priceFeeds,
            displayCurrency = displayCurrency,
            addressKinds = node.addressKinds,
            lightClient = node.lightClient,
            staleAnchorConsent = node,
            networkFreshness = node,
            networkUpgrades = node.networkUpgrades,
            knownUpgrades = knownUpgrades,
            transactionInputs = node.transactionInputs,
            broadcaster = node.broadcaster,
            receipts = node.receipts,
            names = node.names,
            signer = keyStore,
            sentTransfers = sentTransfers,
            transactionHistory = transactionHistory,
            historyStarts = historyStarts,
            logIndex = node.logIndex,
            nfts = node.nfts,
            // The Safe transactions waiting for their owners, with the signatures collected (public
            // data, no key in them), beside the account list in the app's private files.
            safeTxQueue = FileSafeTxQueue(filesDir.resolve("safe-tx-queue.json").path),
            signingRequests = JsonSigningRequestCodec(),
            signatureRecovery = Secp256k1Recovery(),
            safeApprovals = node.safeApprovals,
            // Safe 1.4.1's singleton, factory and proxy code, pinned in code: what a new Safe is created from.
            safeReleases = PinnedSafeReleases(),
            // Uniswap v3 on each network that has it pinned, its contracts proven by their code before any quote.
            uniswap = node.uniswap,
            // CoW Protocol: its pinned contracts proven by their code, and its order API, an off-chain
            // service used only for a swap the user sends to CoW (CLAUDE.md, *Data sources*).
            cowChain = node.cow,
            cowApi = HttpCowApi.create(),
            cowOrders = cowOrders,
            swapVenue = swapVenue,
            waitingSwaps = waitingSwaps,
            debugLog = debugLog,
            debugScreen = debugScreen,
            interfaceMode = interfaceMode,
            amountsHidden = amountsHidden,
            // The debug app only: Settings may lift FLAG_SECURE for a screen recording, until the
            // process ends. The release app has no switch (docs/keys-and-signing.md, rule 8).
            screenCaptureDebugSwitch = if (debuggable) ScreenCaptureDebugSwitch() else null,
            networkDemand = networkDemand,
            networkSettings = networkSettings,
            dappSessions = dapps,
            dappRequests = dapps.requests,
            dappConnectionsOff = WalletConnectDapps.projectIdFor(debuggable).isEmpty(),
        )
    }

    /**
     * The process's own work: following upgrade reports, showing reminders and listing the
     * Safes deployed. A failure in it is reported and ends that one piece of work, never the
     * wallet: the next hold, alarm or start runs it again.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            System.err.println("Serotine: the app's background work stopped: $e")
        },
    )

    /**
     * Where the widget's refresh is scheduled and cancelled: one at a time, in the order asked,
     * so a cancel asked for before a schedule never lands after it.
     */
    private val jobScheduling = Dispatchers.Default.limitedParallelism(1)

    // The holds on the engine, and what they keep running: main thread only. [holds] counts every
    // hold, [appHolds] those that run the app's background work too (all but the widget's
    // refresh), [visible] the activities started, while which a placed widget's rate is followed.
    private val handler = Handler(Looper.getMainLooper())
    private var holds = 0
    private var appHolds = 0
    private var visible = 0
    private var following: Job? = null
    private var deployments: Job? = null
    private var sentTransactions: Job? = null
    private var accountLogs: Job? = null
    private var cowOrderEnds: Job? = null
    private var rateFollowing: Job? = null
    private var dappRelay: Job? = null
    private val sleep = Runnable { node.pause() }
    private val stopAppWork = Runnable {
        following?.cancel()
        following = null
        deployments?.cancel()
        deployments = null
        sentTransactions?.cancel()
        sentTransactions = null
        accountLogs?.cancel()
        accountLogs = null
        cowOrderEnds?.cancel()
        cowOrderEnds = null
        dappRelay?.cancel()
        dappRelay = null
    }

    /** The activity in front, for the biometric prompt. */
    private val foreground = ForegroundActivity()

    /**
     * This device's password-only Argon2id tier, from its total memory and whether the system
     * calls it a low-RAM device ([PasswordOnlyTier.forDevice]); the full tier when the system
     * does not say.
     */
    private fun passwordOnlyTier(): PasswordOnlyTier {
        val manager = getSystemService(ActivityManager::class.java) ?: return PasswordOnlyTier.FULL
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return PasswordOnlyTier.forDevice(memory.totalMem, manager.isLowRamDevice)
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(foreground)
        registerActivityLifecycleCallbacks(HoldWhileVisible())
        scope.launch { UpgradeCheckJob.schedule(this@SerotineApplication) }
        // The debug log records while the Debug tab is on, for the life of the process: it needs
        // no engine, and a request made while the app is in the background is one too.
        scope.launch { FollowDebugScreen(ObserveDebugTab(debugScreen, interfaceMode), debugLog)() }
        // Records each network's default the first time it is found unrecorded, before any engine starts.
        scope.launch { networkSettings.observe().first() }
        // Kept scheduled while a widget is placed, whatever cancelled it (a widget restored by a
        // launcher, say): the job's own period is kept.
        scope.launch(jobScheduling) {
            val app = this@SerotineApplication
            if (RateWidget.placed(app)) {
                RateRefreshJob.schedule(app, now = false)
                // From the file: a spinner a process that ended left on the widget stops.
                drawWidgets()
            }
        }
    }

    /**
     * Keeps the engine's networking on, its upgrade reports followed into notifications, the
     * Safes this wallet deployed listed once proven, how the transactions sent from here end
     * recorded, and the accounts followed by the engine's log index, until the matching
     * [releaseEngine]. Main thread.
     */
    fun holdEngine() {
        wakeEngine()
        if (appHolds++ > 0) return
        handler.removeCallbacks(stopAppWork)
        // Not active: never started, stopped by the last stopAppWork, or ended with an engine that is down.
        if (following?.isActive != true) {
            // Each network's reports into its own notices; a network that is off or idle reports none.
            following = scope.launch {
                upgradeNotices.forEach { (network, notices) ->
                    launch { FollowUpgradeReports(network, node.networkUpgrades, notices)() }
                }
            }
        }
        if (deployments?.isActive != true) deployments = scope.launch { followSafeDeployments() }
        if (sentTransactions?.isActive != true) sentTransactions = scope.launch { followSentTransactions() }
        if (accountLogs?.isActive != true) accountLogs = scope.launch { followAccountLogs() }
        if (cowOrderEnds?.isActive != true) cowOrderEnds = scope.launch { proveCowOrderEnds() }
        // The relay connection while the app holds its work: dApp messages arrive while it is open.
        if (dappRelay?.isActive != true) dappRelay = scope.launch { runDappRelay() }
    }

    /**
     * Keeps WalletConnect's relay connection (docs/walletconnect.md), and each session's accounts to
     * those a dApp may still be offered (accountsChanged), and answers at once the requests that need
     * no one's decision, until stopped. A failure ends that one alone.
     */
    private suspend fun runDappRelay() = coroutineScope {
        launch {
            reportingFailure("following the dApp sessions' accounts") {
                FollowDappAccounts(dapps, accounts, networkSettings)()
            }
        }
        launch {
            reportingFailure("answering the dApps' requests") {
                AnswerDappRequestsAtOnce(dapps.requests, sentTransfers, transactionHistory)()
            }
        }
        reportingFailure("the WalletConnect relay connection") { dapps.run() }
    }

    /** Runs [work]; a failure ends it, said with its kind only, never the wallet. */
    private suspend fun reportingFailure(what: String, work: suspend () -> Unit) {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Serotine: $what stopped: ${e::class.simpleName}")
        }
    }

    /** Turns the engine's networking on for one more hold. Main thread. */
    private fun wakeEngine() {
        if (holds++ > 0) return
        handler.removeCallbacks(sleep)
        node.resume()
    }

    /** Ends a [wakeEngine]: [pauseAfterMs] after the last hold of any kind, networking goes off. Main thread. */
    private fun sleepEngine(pauseAfterMs: Long = PAUSE_DELAY_MS) {
        if (--holds == 0) handler.postDelayed(sleep, pauseAfterMs)
    }

    /**
     * Keeps a placed exchange-rate widget's rate current while an activity is visible, its light
     * client running anyway ([FollowExchangeRate]): read when it is more than
     * [RATE_MAX_AGE_MINUTES] old or in another currency than the one chosen now, and drawn.
     * Never for a hold of the background jobs, which may run on a metered network: the
     * widget's own refresh is [RateRefreshJob]'s. Nothing is read without a widget. Main thread.
     */
    private fun followRateForWidgets() {
        if (visible == 0 || rateFollowing?.isActive == true) return
        rateFollowing = scope.launch {
            val following = this
            // Each pair a widget shows, and only while its network is on: one that is off reads
            // nothing, and one turned on while the app is open is followed from then on. With none
            // on, it waits for the next change of the settings.
            networkSettings.observe().distinctUntilChanged().collectLatest { on ->
                val followed = RateWidget.pairs(this@SerotineApplication).filter { it.network in on }
                if (followed.isEmpty()) return@collectLatest
                coroutineScope {
                    for (pair in followed) launch { followRate(pair) }
                }
                // Every follower ended (each only on a failure): the following itself ends, not this
                // round alone, so the next start of an activity or a placed widget starts it again.
                following.cancel()
            }
        }
    }

    /** Keeps [pair]'s widget rate current while the app is open ([FollowExchangeRate]); a failure ends it alone. */
    private suspend fun followRate(pair: RatePair) {
        try {
            FollowExchangeRate(
                displayCurrency,
                refreshRate(pair),
                maxAgeSeconds = RATE_MAX_AGE_MINUTES * 60,
                checkEvery = RATE_CHECK_MINUTES.minutes,
                readTimeout = RATE_READ_TIMEOUT,
            ).invoke { drawWidgets() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println(
                "Serotine: following a ${pair.network.displayName} widget's rate stopped: ${e::class.simpleName}",
            )
        }
    }

    /** Where a refresh the user asked for (the widget's refresh button) stands. */
    enum class ManualRefresh {
        /** None asked for, or the last one ended: the button shows. */
        NONE,

        /** Tapped, its job not started yet: the spinner shows. */
        REQUESTED,

        /** Its job runs: the spinner shows until its last draw. */
        RUNNING,
    }

    private val manualRefresh = AtomicReference(ManualRefresh.NONE)

    /** Whether a draw shows the spinner in the refresh button's place. Any thread. */
    val refreshShown: Boolean
        get() = manualRefresh.get() != ManualRefresh.NONE

    /**
     * The widget's read in progress, which a read asked for meanwhile joins, with how many wait
     * for it: the last to stop waiting gives it up. Under [readLock].
     */
    private class SharedRead(val read: Deferred<Boolean>) {
        var waiting = 0
    }
    private var sharedRead: SharedRead? = null
    private val readLock = Mutex()

    /**
     * Reads the exchange rate once for the widget, with the engine awake for this read alone and
     * none of the app's other background work, draws it, and then calls [done] on the main
     * thread; a read already in progress for another job is joined, each waiting as long as its
     * own timeout. A [manual] read (the widget's refresh button) shows the spinner from its
     * start and stops it with a last draw before [done], saying so when it ended without a rate
     * unless it was stopped. Without a placed widget nothing wakes: the refresh is cancelled,
     * [done] is called and null returned. Cancelling the job it returns gives the read up;
     * [done] is called then too. Main thread.
     */
    fun refreshRateInBackground(manual: Boolean, done: () -> Unit): Job? {
        val app = this
        if (!RateWidget.placed(app)) {
            scope.launch(jobScheduling) { RateRefreshJob.cancel(app) }
            if (manual) manualRefresh.set(ManualRefresh.NONE)
            handler.post(done)
            return null
        }
        wakeEngine()
        var read = false
        return scope.launch {
            if (manual) {
                // Also in a process started for the job alone, which knows of no tap.
                manualRefresh.set(ManualRefresh.RUNNING)
                drawWidgets()
            }
            read = joinRead(if (manual) MANUAL_READ_TIMEOUT else RATE_READ_TIMEOUT)
            if (!manual && read) drawWidgets()
        }.also { job ->
            // On completion, not in a finally: a job cancelled before it started never runs its
            // body, and the hold must end anyway.
            job.invokeOnCompletion { cause ->
                if (!manual) {
                    handler.post {
                        sleepEngine()
                        done()
                    }
                    return@invokeOnCompletion
                }
                val failed = !read && cause !is CancellationException
                scope.launch(NonCancellable) {
                    try {
                        drawWidgets(refreshing = false)
                    } finally {
                        handler.post {
                            if (failed) toast(R.string.rate_widget_not_refreshed)
                            sleepEngine()
                            // The job ends first: a tap from here on schedules a new one, never
                            // in place of this one while it still counts as running.
                            done()
                            manualRefresh.set(ManualRefresh.NONE)
                        }
                    }
                }
            }
        }
    }

    /**
     * The widgets' read: the one in progress, or a new one given [RATE_READ_TIMEOUT], waited for
     * at most [timeout]; whether any pair's rate was read in that time. It reads, at once, the
     * rate of each pair a placed widget shows whose network is on, waking those networks alone (a
     * network that is off reads nothing; its widgets say so). The last waiter to stop gives it
     * up, so no read outlives everyone who asked for it.
     */
    private suspend fun joinRead(timeout: Duration): Boolean {
        val shared = readLock.withLock {
            val current = sharedRead?.takeIf { it.read.isActive }
                ?: SharedRead(
                    scope.async {
                        val on = networkSettings.observe().first()
                        val byNetwork = RateWidget.pairs(this@SerotineApplication)
                            .filter { it.network in on }
                            .groupBy { it.network }
                        byNetwork.map { (network, pairs) ->
                            async {
                                // The widgets' network runs for as long as this read does, with no account on it too.
                                val hold = networkDemand.hold(network)
                                try {
                                    pairs.map { pair -> async { readWidgetRate(pair) } }.awaitAll().any { it }
                                } finally {
                                    hold.release()
                                }
                            }
                        }.awaitAll().any { it }
                    },
                )
            sharedRead = current
            current.waiting++
            current
        }
        try {
            return withTimeoutOrNull(timeout) { shared.read.await() } ?: false
        } finally {
            withContext(NonCancellable) {
                readLock.withLock { if (--shared.waiting == 0) shared.read.cancel() }
            }
        }
    }

    /**
     * Reads [pair]'s widget rate once, given [RATE_READ_TIMEOUT]; whether it was read. A failure
     * ends this read alone.
     */
    private suspend fun readWidgetRate(pair: RatePair): Boolean = try {
        withTimeoutOrNull(RATE_READ_TIMEOUT) { refreshRate(pair)() } != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The others' reads still count.
        val name = pair.network.displayName
        System.err.println("Serotine: a $name widget's rate was not read: ${e::class.simpleName}")
        false
    }

    /**
     * The widget's refresh button: asks for a refresh now, on any network (the user asked for
     * it), and shows the spinner until it ends. With no network that reaches the internet it
     * says so instead; a tap while one is asked for or runs adds nothing; a refresh Android has
     * not started within [MANUAL_START_TIMEOUT] is given up and said so. Then [done], so the
     * broadcast that brought the tap ends only once this is settled. Main thread.
     */
    fun refreshRateWidgetsNow(done: () -> Unit) {
        if (!online()) {
            toast(R.string.rate_widget_no_network)
            done()
            return
        }
        if (!manualRefresh.compareAndSet(ManualRefresh.NONE, ManualRefresh.REQUESTED)) {
            done()
            return
        }
        val app = this
        scope.launch(jobScheduling) {
            try {
                drawWidgets()
                // Every network the widgets show is off: nothing to read, and the widgets say so.
                val on = networkSettings.observe().first()
                if (RateWidget.pairs(app).none { it.network in on }) {
                    giveUpManualRefresh(R.string.rate_widget_network_off)
                } else if (!RateRefreshJob.refreshNow(app)) {
                    giveUpManualRefresh(R.string.rate_widget_not_started)
                }
            } finally {
                done()
            }
        }
        handler.postDelayed({
            if (manualRefresh.get() == ManualRefresh.REQUESTED) {
                scope.launch(jobScheduling) {
                    RateRefreshJob.cancelRefreshNow(app)
                    giveUpManualRefresh(R.string.rate_widget_not_started)
                }
            }
        }, MANUAL_START_TIMEOUT.inWholeMilliseconds)
    }

    /** Ends a refresh asked for that never started: the button again, and [text] said. */
    private suspend fun giveUpManualRefresh(text: Int) {
        if (!manualRefresh.compareAndSet(ManualRefresh.REQUESTED, ManualRefresh.NONE)) return
        drawWidgets()
        handler.post { toast(text) }
    }

    /** Whether the active network reaches the internet, as the system checked it (no captive portal). */
    private fun online(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Says [text] briefly, from wherever the app is. Main thread. */
    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /** Draws every placed widget from the rate kept, without the engine; then [done]. */
    fun drawRateWidgets(done: () -> Unit) {
        scope.launch {
            try {
                drawWidgets()
            } finally {
                done()
            }
        }
    }

    /** The first widget was placed: schedules its refresh, and follows its rate if the app is open. Main thread. */
    fun rateWidgetPlaced() {
        scope.launch(jobScheduling) { RateRefreshJob.schedule(this@SerotineApplication, now = true) }
        followRateForWidgets()
    }

    /** The last widget was removed: nothing is read for one any more. Main thread. */
    fun rateWidgetsRemoved() {
        rateFollowing?.cancel()
        rateFollowing = null
        scope.launch(jobScheduling) { RateRefreshJob.cancel(this@SerotineApplication) }
    }

    /**
     * Proves the end of each CoW order signed here from the accounts' log-index entries (docs/swaps.md,
     * *Proving an order's end*), after dropping the ended ones signed over a week ago; runs until
     * cancelled. A failure ends it, never the wallet: the next hold proves them again.
     */
    private suspend fun proveCowOrderEnds() = coroutineScope {
        // Each network's orders, at its own finalized blocks; a failure ends that one only.
        Network.entries.forEach { network ->
            launch {
                try {
                    ProveCowOrderEnds(cowOrders, node.logIndex, node.lightClient, accounts, network)()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println(
                        "Serotine: proving the CoW orders' ends on ${network.displayName} stopped: " +
                            "${e::class.simpleName}",
                    )
                }
            }
        }
    }

    /**
     * Keeps the engine's log index following every account from where its history starts (issue
     * #85); runs until cancelled. A failure ends it, never the wallet: the next hold follows them
     * again.
     */
    private suspend fun followAccountLogs() = coroutineScope {
        // Each network's log index follows that network's accounts; a failure ends that one only.
        Network.entries.forEach { network ->
            launch {
                try {
                    FollowAccountLogs(
                        accounts,
                        historyStarts,
                        transactionHistory,
                        node.lightClient,
                        node.logIndex,
                        network,
                    )()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println(
                        "Serotine: following the accounts\' logs on ${network.displayName} stopped: " +
                            "${e::class.simpleName}",
                    )
                }
            }
        }
    }

    /**
     * Records how each transaction sent from here ends, whatever screen is open (issue #85); runs
     * until cancelled. A failure ends it, never the wallet: the next hold follows them again.
     */
    private suspend fun followSentTransactions() {
        try {
            FollowSentTransactions(transactionHistory, sentRecord, node.receipts, node.transactionInputs)()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Serotine: following sent transactions stopped: ${e::class.simpleName}")
        }
    }

    /**
     * Lists every Safe this wallet deployed once the light client proves it, whatever screen is
     * open (docs/wallet-ux.md, screen 15); runs until cancelled. A failure ends it, never the
     * wallet: the next hold follows the deployments again.
     */
    private suspend fun followSafeDeployments() {
        val kinds = node.addressKinds
        try {
            FollowSafeDeployments(
                sentTransfers,
                node.receipts,
                accounts,
                ListDeployedSafe(kinds, WatchAddress(accounts, kinds)),
            )()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Serotine: following Safe deployments stopped: ${e::class.simpleName}")
        }
    }

    /**
     * Ends a [holdEngine]. [pauseAfterMs] after the last one, neither the reports nor the
     * deployments are followed (the reminder alarm covers the reports until the next hold, and
     * a deployment's record keeps it for the next), whatever else still holds the engine, and
     * once nothing does, networking goes off (a backgrounded wallet keeps no sockets or timers
     * open). The delay keeps a rotation from pausing at all; the last screen's release waits
     * [GRACE_PERIOD_MS] instead, so a quick app switch does not either ([GracePeriodJob]). A
     * hold whose end leaves nothing to keep the process from being frozen passes 0. Main thread.
     */
    fun releaseEngine(pauseAfterMs: Long = PAUSE_DELAY_MS) {
        if (--appHolds == 0) handler.postDelayed(stopAppWork, pauseAfterMs)
        sleepEngine(pauseAfterMs)
    }

    /**
     * Calls [then], on the main thread, with whether a background look for upgrades can learn
     * anything: not once all is said about the known one ([UpgradeNotices.allSaid]). Never
     * starts the engine.
     */
    fun whenUpgradeCheckWanted(then: (Boolean) -> Unit) {
        scope.launch {
            val wanted = upgradeNotices.values.any { !it.allSaid() }
            handler.post { then(wanted) }
        }
    }

    /** Shows the upgrade reminder due now, if any, and arms the next; then [done]. Never starts the engine. */
    fun remindOfUpgrades(done: () -> Unit) {
        scope.launch {
            try {
                upgradeNotices.values.forEach { it.remind() }
            } finally {
                done()
            }
        }
    }

    /**
     * Holds the engine, and follows a placed widget's rate, while any activity is visible, and the
     * engine for [GRACE_PERIOD_MS] more once none is: the last one stopped, except for a
     * configuration change, starts a [GracePeriodJob], and the next one started cancels it.
     */
    private inner class HoldWhileVisible : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            holdEngine()
            if (visible++ == 0) scope.launch(jobScheduling) { GracePeriodJob.cancel(this@SerotineApplication) }
            followRateForWidgets()
        }

        override fun onActivityStopped(activity: Activity) {
            if (--visible == 0) {
                rateFollowing?.cancel()
                rateFollowing = null
                if (!activity.isChangingConfigurations) {
                    val until = SystemClock.elapsedRealtime() + GRACE_PERIOD_MS
                    scope.launch(jobScheduling) { GracePeriodJob.start(this@SerotineApplication, until) }
                }
            }
            releaseEngine(pauseAfterMs = GRACE_PERIOD_MS)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        const val PAUSE_DELAY_MS = 30_000L

        /**
         * How long the networking stays on after the app's last screen left the foreground: a
         * quick switch to another app, to copy an address say, comes back to synced light clients.
         */
        const val GRACE_PERIOD_MS = 60_000L

        /** How old the widget's rate may be while the app is open before it is read again. */
        const val RATE_MAX_AGE_MINUTES = 30L

        /** How often the app, while open, looks whether the widget's rate is due. */
        const val RATE_CHECK_MINUTES = 5

        /** How long one read of the widget's rate may wait for the light client. */
        val RATE_READ_TIMEOUT = 4.minutes

        /**
         * The same for a read the user asked for: within an expedited job's few minutes, so the
         * system does not stop it first.
         */
        val MANUAL_READ_TIMEOUT = 150.seconds

        /** How long a refresh the user asked for may wait for Android to start it before it is given up. */
        val MANUAL_START_TIMEOUT = 2.minutes

        /** The logcat tag of the debug app's diagnostics. */
        const val LOG_TAG = "Serotine"
    }
}
