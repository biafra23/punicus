package eth.serotine.ios

import eth.serotine.data.account.FileAccountRepository
import eth.serotine.data.cow.FileCowOrders
import eth.serotine.data.cow.HttpCowApi
import eth.serotine.data.engine.MyotisHost
import eth.serotine.data.handoff.NoWalletHandoff
import eth.serotine.data.history.FileHistoryStarts
import eth.serotine.data.keys.EnvelopeKeyStore
import eth.serotine.data.keys.IosBiometricKey
import eth.serotine.data.keys.PasswordOnlyTier
import eth.serotine.data.keys.Secp256k1Recovery
import eth.serotine.data.keys.iosHardwareSecret
import eth.serotine.data.logs.MemoryDebugLog
import eth.serotine.data.network.IosConnectionMeter
import eth.serotine.data.networks.FileNetworkSettings
import eth.serotine.data.nfts.FileAddedNfts
import eth.serotine.data.nfts.FileSeenEnsNames
import eth.serotine.data.outbox.iosOutbox
import eth.serotine.data.safe.FileSafeTxQueue
import eth.serotine.data.safe.JsonSigningRequestCodec
import eth.serotine.data.safe.PinnedSafeReleases
import eth.serotine.data.settings.FileAmountsHidden
import eth.serotine.data.settings.FileDebugScreen
import eth.serotine.data.settings.FileDisplayCurrency
import eth.serotine.data.settings.FileInterfaceMode
import eth.serotine.data.settings.FileMeteredDownloads
import eth.serotine.data.settings.FileSwapVenue
import eth.serotine.data.settings.FileSyncPolicy
import eth.serotine.data.swaps.FileWaitingSwaps
import eth.serotine.data.tokens.BuiltInTokenCatalog
import eth.serotine.data.tokens.FileUserTokens
import eth.serotine.data.transfers.FileSentTransfers
import eth.serotine.data.transfers.FileTransactionHistory
import eth.serotine.data.upgrade.FileKnownUpgradeStore
import eth.serotine.data.walletconnect.WalletConnectDapps
import eth.serotine.domain.model.Network
import eth.serotine.domain.usecase.AnswerDappRequestsAtOnce
import eth.serotine.domain.usecase.CheckHeldSignature
import eth.serotine.domain.usecase.CheckQueued
import eth.serotine.domain.usecase.FollowAccountLogs
import eth.serotine.domain.usecase.FollowDappAccounts
import eth.serotine.domain.usecase.FollowDebugScreen
import eth.serotine.domain.usecase.FollowInterfaceMode
import eth.serotine.domain.usecase.FollowNetworks
import eth.serotine.domain.usecase.FollowOutbox
import eth.serotine.domain.usecase.FollowSafeDeployments
import eth.serotine.domain.usecase.FollowSentTransactions
import eth.serotine.domain.usecase.FollowUpgradeReports
import eth.serotine.domain.usecase.HistoryNotingAccounts
import eth.serotine.domain.usecase.HistoryRecordingSentTransfers
import eth.serotine.domain.usecase.KeepTransactionInputs
import eth.serotine.domain.usecase.ListDeployedSafe
import eth.serotine.domain.usecase.NetworkDemand
import eth.serotine.domain.usecase.ObserveDebugTab
import eth.serotine.domain.usecase.ObserveHeavyDownloads
import eth.serotine.domain.usecase.ObserveSafe
import eth.serotine.domain.usecase.OutboxLock
import eth.serotine.domain.usecase.ProveCowOrderEnds
import eth.serotine.domain.usecase.QuoteSafeApproval
import eth.serotine.domain.usecase.QuoteSafeExecution
import eth.serotine.domain.usecase.QuoteTransfer
import eth.serotine.domain.usecase.UpgradeNotices
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.AppGraph
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillTerminateNotification
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * The iOS composition root: the process's one wallet, which the Swift app creates once, before
 * its first frame, and hands to [MainViewController]. It builds the [graph] from this platform's
 * adapters around the Myotis engine (docs/embedding-myotis.md), on the main thread as the desktop
 * does before its window: nothing here waits on the network, and only a simulator build may ask
 * the Secure Enclave anything ([iosHardwareSecret]). Beside the graph, on the same adapters, it
 * runs the work no screen owns: the engine's network-upgrade reports followed into
 * notifications, the Safes this wallet deployed listed once the light client proves them, and
 * how the transactions sent from here end recorded. That work runs while the app is in the
 * foreground and for a grace period after it ([GRACE_PERIOD]), so a quick switch to another app
 * comes back to synced light clients; then, in the background, the engine's networking is paused
 * (a suspended app keeps no sockets open) and resumed on return, and a reminder due meanwhile
 * comes as the notification [IosUpgradeNotifier] scheduled. The grace period and the pause run as
 * a background task, so iOS keeps the app awake for them and lets it close the engine's
 * connections before it suspends the app. An app terminated while running shuts the
 * engine down, waiting for that only briefly ([TERMINATE_WAIT]); an app killed while suspended
 * runs no code, which the engine survives as any mobile app's state must.
 */
class SerotineWallet {

    /** When the wallet began to be built: a debug build says on the console how long the start took. */
    private val began = TimeSource.Monotonic.markNow()

    /**
     * The app's own Application Support directory: in its sandbox, never shown to the user. It keeps
     * the app's former name, Serotine: renaming it would leave an installed wallet's accounts and keys
     * behind.
     */
    private val dataDir: String = run {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: error("no Application Support directory")
        "$base/Serotine"
    }

    // Before the graph: on the simulator, a wallet with no keys yet asks whether the Secure
    // Enclave works (iosHardwareSecret says when). The key store creates the directory and
    // keeps it out of backups (docs/keys-and-signing.md, rule 8).
    private val keys = "$dataDir/keys"
    private val hardware = iosHardwareSecret(keys)

    /** The Debug tab's log: in memory for the life of the process, never in a file. */
    private val debugLog = MemoryDebugLog()

    /** Whether the user turned the Debug tab on, beside the other settings: [debugLog] records only then. */
    private val debugScreen = FileDebugScreen("$dataDir/debug-screen.json")

    /** The view the user chose, simple mode or Detailed view (docs/simple-ui.md), beside the other settings. */
    private val interfaceMode = FileInterfaceMode("$dataDir/ui-mode.json")
    private val amountsHidden = FileAmountsHidden("$dataDir/amounts-hidden.json")

    /** Whether a transaction may be signed while the light client syncs (docs/lenient-mode.md), beside the other settings. */
    private val syncPolicy = FileSyncPolicy("$dataDir/sync-policy.json")

    /**
     * Whether the heavy downloads may run now: the user's choice for a metered connection, opt-in
     * (owner ruling, 2026-10-20), and what iOS says the current path costs. One of each, shared by
     * the graph and the accounts' log followers.
     */
    private val connectionMeter = IosConnectionMeter()
    private val meteredDownloads = FileMeteredDownloads("$dataDir/metered-downloads.json")

    // One engine, one light client per network that is on and needed: FollowNetworks starts with
    // the wallet and runs for the life of the process (docs/networks.md, *The engine host*). The
    // engine's state can be fetched again, so it stays out of backups. The balances last verified
    // are kept beside it, to be shown as such after a restart. The adapters' diagnostics and the
    // engine's own log go to the device's log in a debug build only: NSLog, not println, whose
    // stdout reaches no one on a device unless Xcode launched the app. Each request asked of the
    // engine goes to the Debug tab's log ([debugLog]), in memory, in any build.
    @OptIn(ExperimentalNativeApi::class)
    private val node: MyotisHost = run {
        val engineDir = "$dataDir/myotis"
        createDirectoryOutOfBackups(engineDir)
        // The line is NSLog's format, its `%` escaped, so nothing crosses NSLog's C varargs: with
        // NSLog("%@", line) the debug app died at launch on the simulator (#129's CI).
        val debugLog: ((String) -> Unit)? =
            if (Platform.isDebugBinary) { line -> NSLog(line.replace("%", "%%")) } else null
        MyotisHost.start(
            engineDir,
            lastBalances = "$dataDir/last-balances.json",
            debugLog = debugLog,
            apiLog = this.debugLog,
            keptInputs = "$dataDir/kept-inputs.json",
            keptSafes = "$dataDir/kept-safes.json",
        )
    }

    // One record per process, read by the screens and written by the notices; this version's only.
    private val knownUpgrades = FileKnownUpgradeStore("$dataDir/upgrade.json", APP_VERSION)

    /**
     * Which networks are on (docs/networks.md, *Networks on and off*). A network the file does not
     * mention yet gets this build's default: the release binary turns on the real networks and
     * leaves Sepolia off, the debug binary turns on Sepolia alone.
     */
    @OptIn(ExperimentalNativeApi::class)
    private val networkSettings =
        FileNetworkSettings("$dataDir/networks.json", defaultOn = { it.testnet == Platform.isDebugBinary })
    /**
     * dApp sessions over WalletConnect (docs/walletconnect.md), with this binary's project ID: the
     * debug one's or the release one's; off without one. Their state is in a directory of its own,
     * which the store keeps owner-only and out of iCloud and computer backups (ruling 8).
     */
    @OptIn(ExperimentalNativeApi::class)
    private val dappProjectId = WalletConnectDapps.projectIdFor(debug = Platform.isDebugBinary)
    private val dapps = WalletConnectDapps("$dataDir/walletconnect/state.json", dappProjectId)

    /** Each network's notifications, apart: held here, as the first one is the notification center's delegate. */
    private val notifiers = Network.entries.associateWith(::IosUpgradeNotifier)

    /** What the user hears about each network's upgrades outside the app, apart (docs/networks.md). */
    private val upgradeNotices =
        notifiers.mapValues { (network, notifier) -> UpgradeNotices(network, knownUpgrades, notifier) }

    /** The screens' holds on a network with no account on it yet (Watch, Add token). */
    private val networkDemand = NetworkDemand()

    // One instance each, which the graph and the background work share. Each account added is
    // noted where its history starts (issue #85): addresses with a block or a time, public data only.
    private val historyStarts = FileHistoryStarts("$dataDir/history-starts.json")
    private val accounts = HistoryNotingAccounts(FileAccountRepository("$dataDir/accounts.json"), historyStarts)

    // The transactions signed here, with their signed bytes (public once broadcast), and every one
    // of them kept for good with how it ended (issue #85): each record goes into both.
    private val sentRecord = FileSentTransfers("$dataDir/sent-transfers.json")
    private val transactionHistory = FileTransactionHistory("$dataDir/transaction-history.json")

    /** The CoW orders signed here, each live until its end is proven (docs/swaps.md): public data only. */
    private val cowOrders = FileCowOrders("$dataDir/cow-orders.json")
    private val sentTransfers = HistoryRecordingSentTransfers(sentRecord, transactionHistory)

    /**
     * Lenient mode's outbox (docs/lenient-mode.md): sealed with a Secure Enclave key of its own, in
     * a directory out of backups; in memory only on the simulator. One instance, which the graph and
     * the follower share, with the one lock they share too.
     */
    private val outbox = iosOutbox("$dataDir/outbox")
    private val outboxLock = OutboxLock()

    /**
     * The Safe transactions waiting for their owners, with the signatures collected (public), and who
     * made a co-signer's signature: one instance each, which the graph and the outbox's follower share.
     */
    private val safeTxQueue = FileSafeTxQueue("$dataDir/safe-tx-queue.json")
    private val signatureRecovery = Secp256k1Recovery()

    internal val graph: AppGraph = run {
        // This device's Secure Enclave; the password alone on a simulator without one, its Argon2id
        // tier sized by the memory the simulator reports (the Mac's). One instance is both the key
        // store and the signer, so that their calls take turns.
        val keyStore = EnvelopeKeyStore(
            keys,
            hardware,
            // Face ID or Touch ID, the user's opt-in in Settings → Security (rule 3).
            biometricKey = IosBiometricKey("$keys/${IosBiometricKey.STATE_FILE_NAME}"),
            passwordOnlyTier = PasswordOnlyTier.forDevice(
                NSProcessInfo.processInfo.physicalMemory.toLong(),
                lowRamDevice = false,
            ),
        )
        AppGraph(
            accounts = accounts,
            keys = keyStore,
            nativeBalances = node.nativeBalances,
            tokenCatalog = BuiltInTokenCatalog(),
            tokenBalances = node.tokenBalances,
            tokenMetadata = node.tokenMetadata,
            userTokens = FileUserTokens("$dataDir/tokens.json"),
            priceFeeds = node.priceFeeds,
            displayCurrency = FileDisplayCurrency("$dataDir/display-currency.json"),
            addressKinds = node.addressKinds,
            lightClient = node.lightClient,
            staleAnchorConsent = node,
            networkFreshness = node,
            networkUpgrades = node.networkUpgrades,
            knownUpgrades = knownUpgrades,
            transactionInputs = node.transactionInputs,
            broadcaster = node.broadcaster,
            receipts = node.receipts,
            blockTransactions = node.blockTransactions,
            // iOS hands no payment link over yet (#255): several apps may claim `ethereum:`, the one
            // opened is undefined, and the engine's iOS reads have no verified block body.
            walletHandoff = NoWalletHandoff,
            names = node.names,
            signer = keyStore,
            sentTransfers = sentTransfers,
            transactionHistory = transactionHistory,
            historyStarts = historyStarts,
            logIndex = node.logIndex,
            nfts = node.nfts,
            ensNfts = node.ensNfts,
            // The NFTs added by contract and id, beside tokens.json (issue #246).
            addedNfts = FileAddedNfts("$dataDir/nfts.json"),
            seenEnsNames = FileSeenEnsNames("$dataDir/ens-names.json"),
            // The Safe transactions waiting for their owners: the follower's one instance.
            safeTxQueue = safeTxQueue,
            signingRequests = JsonSigningRequestCodec(),
            signatureRecovery = signatureRecovery,
            safeApprovals = node.safeApprovals,
            // Safe 1.4.1's singleton, factory and proxy code, pinned in code: what a new Safe is created from.
            safeReleases = PinnedSafeReleases(),
            // Uniswap v3 on each network that has it pinned, its contracts proven by their code before any quote.
            uniswap = node.uniswap,
            // CoW Protocol: its pinned contracts proven by their code, and its order API, an off-chain
            // service used only for a swap the user sends to CoW (CLAUDE.md, *Data sources*). Until
            // Myotis's iOS module reads a block by number, no finalized block's own time is known here,
            // so no CoW order is offered on iOS (docs/swaps.md, *Proving an order's end*).
            cowChain = node.cow,
            cowApi = HttpCowApi.create(),
            cowOrders = cowOrders,
            outbox = outbox,
            // The follower's own lock, the inputs kept for a lenient draft, and Sign while syncing.
            outboxLock = outboxLock,
            keptTransactionInputs = node.keptTransactionInputs,
            keptSafes = node.keptSafes,
            syncPolicy = syncPolicy,
            connectionMeter = connectionMeter,
            meteredDownloads = meteredDownloads,
            swapVenue = FileSwapVenue("$dataDir/swap-venue.json"),
            waitingSwaps = FileWaitingSwaps("$dataDir/waiting-swaps.json"),
            debugLog = debugLog,
            debugScreen = debugScreen,
            interfaceMode = interfaceMode,
            amountsHidden = amountsHidden,
            // iOS blocks no capture, so there is nothing for a debug switch to lift.
            screenCaptureDebugSwitch = null,
            networkDemand = networkDemand,
            networkSettings = networkSettings,
            dappSessions = dapps,
            dappRequests = dapps.requests,
            dappConnectionsOff = dappProjectId.isEmpty(),
        )
    }

    /**
     * The background work's scope. A failure in it is reported and ends that one piece of work,
     * never the wallet: the next return to the foreground runs it again.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            println("Punicus: the app's background work stopped: $e")
        },
    )

    /** The work running while the app is in the foreground; main thread only. */
    private var foregroundWork: List<Job> = emptyList()

    /** The wait before the pause, while the app is in the background and still awake; main thread only. */
    private var gracePeriod: Job? = null

    init {
        // At launch the app is in the foreground: the work starts with the wallet.
        startForegroundWork()
        // The debug log records while the Debug tab is on, for the life of the process.
        scope.launch { FollowDebugScreen(ObserveDebugTab(debugScreen, interfaceMode), debugLog)() }
        // Each network's light client runs while it is on and needed (docs/networks.md, *The engine
        // host*); following the setting also records each network's default. A failure ends it,
        // never the wallet: the light clients keep their last state.
        scope.launch {
            reportingFailure("following the networks") {
                FollowNetworks(networkSettings, accounts, networkDemand, node)()
            }
        }
        // Values proven on demand in simple mode, at every head in Detailed view (docs/simple-ui.md).
        scope.launch {
            reportingFailure("following the view") { FollowInterfaceMode(interfaceMode, node)() }
        }
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
            pauseAfterGracePeriod()
        }
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) {
            // Back within the grace period: nothing was paused, and the light clients are still synced.
            gracePeriod?.cancel()
            gracePeriod = null
            node.resume()
            startForegroundWork()
        }
        center.addObserverForName(UIApplicationWillTerminateNotification, null, NSOperationQueue.mainQueue) {
            // The shutdown blocks, so it runs off the main thread; the process ends when this
            // returns, so it is waited for, but only as long as the system's watchdog allows.
            val shutdown = scope.launch(Dispatchers.IO) { node.shutdown() }
            runBlocking { withTimeoutOrNull(TERMINATE_WAIT) { shutdown.join() } }
        }
        // One permission for every network's notices: asked once, by one of them.
        notifiers.values.first().askPermission()
        startupTime("wallet built")
    }

    /** Says on the console, in a debug build, how long after the wallet began [what] came. */
    @OptIn(ExperimentalNativeApi::class)
    internal fun startupTime(what: String) {
        if (Platform.isDebugBinary) println("Punicus: $what ${began.elapsedNow()} after launch")
    }

    /**
     * Keeps the engine's networking and the foreground work on for [GRACE_PERIOD] after the app
     * went to the background, so a user who switches away for a moment, to copy an address say,
     * finds the light clients still synced on return; then stops the work and pauses the engine.
     * iOS keeps the app awake for this as a background task, which it limits to about 30 s: the
     * wait is cut short to leave [PAUSE_MARGIN] for the pause within the time iOS says is left,
     * and a task iOS ends early pauses at once. Main thread.
     */
    @OptIn(ExperimentalAtomicApi::class)
    private fun pauseAfterGracePeriod() {
        gracePeriod?.cancel()
        val app = UIApplication.sharedApplication
        val paused = AtomicBoolean(false)
        val ended = AtomicBoolean(false)
        var task = UIBackgroundTaskInvalid
        val end = {
            if (ended.compareAndSet(false, true) && task != UIBackgroundTaskInvalid) app.endBackgroundTask(task)
        }
        // Once per grace period, on the main thread: the foreground work stops, and the pause runs.
        val pause = {
            if (paused.compareAndSet(false, true)) {
                foregroundWork.forEach { it.cancel() }
                foregroundWork = emptyList()
                node.pause()
            } else {
                null
            }
        }
        var waiting: Job? = null
        task = app.beginBackgroundTaskWithName("keep the network on briefly, then pause the engine") {
            // iOS ends the task: the pause cannot be waited for any more, only asked for.
            waiting?.cancel()
            if (gracePeriod === waiting) gracePeriod = null
            pause()
            end()
        }
        // In the background, the time iOS still gives the task; without a task, none.
        val left = if (task == UIBackgroundTaskInvalid) Duration.ZERO else app.backgroundTimeRemaining.seconds
        val wait = minOf(GRACE_PERIOD, left - PAUSE_MARGIN).coerceAtLeast(Duration.ZERO)
        val waited = scope.launch(Dispatchers.Main) {
            delay(wait)
            pause()?.join()
        }
        // Ended by the pause, by a return to the foreground (nothing paused) or by iOS.
        waited.invokeOnCompletion { end() }
        waiting = waited
        gracePeriod = waited
    }

    /**
     * Starts the foreground work unless it runs: iOS posts the will-enter-foreground notice at
     * launch too, after the wallet has started it, and a second set would outlive the first's
     * cancellation in the background.
     */
    private fun startForegroundWork() {
        if (foregroundWork.isNotEmpty()) return
        foregroundWork = upgradeNotices.map { (network, notices) ->
            // Each network's upgrade reports, while its light client runs (one off or idle reports none).
            scope.launch {
                reportingFailure("upgrade notices of ${network.displayName}") {
                    FollowUpgradeReports(network, node.networkUpgrades, notices)()
                }
            }
        } + listOf(
            scope.launch {
                // What is queued is checked at a fresh head and sent while the app is in front (docs/lenient-mode.md).
                reportingFailure("sending what is queued") {
                    FollowOutbox(
                        outbox,
                        CheckQueued(
                            { network ->
                                QuoteTransfer(
                                    node.transactionInputs,
                                    node.nativeBalances,
                                    node.tokenBalances,
                                    sentTransfers,
                                    cowOrders,
                                    node.cow,
                                    node.lightClient,
                                    network,
                                    outbox,
                                )
                            },
                            // A Safe's approval or execution, queued while syncing: its strict quote again.
                            { network ->
                                QuoteSafeApproval(
                                    ObserveSafe(node.addressKinds, accounts),
                                    node.safeApprovals,
                                    node.nativeBalances,
                                    node.transactionInputs,
                                    sentTransfers,
                                    network,
                                    outbox,
                                )
                            },
                            { network ->
                                QuoteSafeExecution(
                                    ObserveSafe(node.addressKinds, accounts),
                                    signatureRecovery,
                                    node.safeApprovals,
                                    node.nativeBalances,
                                    node.transactionInputs,
                                    sentTransfers,
                                    network,
                                    outbox,
                                )
                            },
                        ),
                        node.lightClient,
                        node.broadcaster,
                        sentTransfers,
                        IosOutboxNotifier(),
                        outboxLock,
                        // A Safe signature held while syncing: checked at a fresh head, then saved to the one queue.
                        CheckHeldSignature(ObserveSafe(node.addressKinds, accounts), signatureRecovery),
                        safeTxQueue,
                    )()
                }
            },
            scope.launch {
                // What a lenient draft is built on, kept recent while the light clients verify (docs/lenient-mode.md).
                reportingFailure("keeping the transaction inputs") {
                    KeepTransactionInputs(syncPolicy, accounts, node.lightClient, node.transactionInputs)()
                }
            },
            scope.launch {
                // The Safes this wallet deployed are listed whatever screen is open, and again after a
                // restart (docs/wallet-ux.md, screen 15).
                val kinds = node.addressKinds
                reportingFailure("following Safe deployments") {
                    FollowSafeDeployments(
                        sentTransfers,
                        node.receipts,
                        accounts,
                        ListDeployedSafe(kinds, WatchAddress(accounts, kinds)),
                    )()
                }
            },
            scope.launch {
                // The relay connection while the app is in front: dApp messages arrive while it is open.
                reportingFailure("the WalletConnect relay connection") { dapps.run() }
            },
            scope.launch {
                // Each session's accounts kept to those still in the wallet (accountsChanged).
                reportingFailure("following the dApp sessions' accounts") {
                    FollowDappAccounts(dapps, accounts, networkSettings)()
                }
            },
            scope.launch {
                // The requests that need no one's decision, answered at once.
                reportingFailure("answering the dApps' requests") {
                    AnswerDappRequestsAtOnce(dapps.requests, sentTransfers, transactionHistory, networkSettings)()
                }
            },
            scope.launch {
                // How each transaction sent from here ends is recorded whatever screen is open (issue #85).
                reportingFailure("following sent transactions") {
                    FollowSentTransactions(transactionHistory, sentRecord, node.receipts, node.transactionInputs)()
                }
            },
        ) + Network.entries.flatMap { network ->
            listOf(
                scope.launch {
                    // Each network's log index follows its accounts from where each history starts (issue #85).
                    reportingFailure("following the accounts' logs on ${network.displayName}") {
                        FollowAccountLogs(
                            accounts,
                            historyStarts,
                            transactionHistory,
                            node.lightClient,
                            node.logIndex,
                            ObserveHeavyDownloads(connectionMeter, meteredDownloads),
                            network,
                        )()
                    }
                },
                scope.launch {
                    // Each CoW order's end is proven from its network's log-index entries (docs/swaps.md).
                    reportingFailure("proving the CoW orders' ends on ${network.displayName}") {
                        ProveCowOrderEnds(cowOrders, node.logIndex, node.lightClient, accounts, network)()
                    }
                },
            )
        }
    }

    /** Runs [work]; a failure ends it, said on the console with its kind only, never the wallet. */
    private suspend fun reportingFailure(what: String, work: suspend () -> Unit) {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Punicus: $what stopped: ${e::class.simpleName}")
        }
    }

    private companion object {
        /** How long a termination waits for the engine's shutdown; iOS allows about five seconds. */
        val TERMINATE_WAIT = 3.seconds

        /**
         * How long the networking stays on after the app went to the background: within the about
         * 30 s iOS gives a background task.
         */
        val GRACE_PERIOD = 25.seconds

        /** What the pause is left of the background task's time, to close the engine's connections. */
        val PAUSE_MARGIN = 3.seconds
    }
}

/**
 * Creates [path] and keeps it out of iCloud and computer backups: what can be fetched again
 * does not belong in a backup. Best effort; a failure is said and the engine still starts.
 */
private fun createDirectoryOutOfBackups(path: String) {
    val created = NSFileManager.defaultManager.createDirectoryAtPath(
        path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    val excluded = created && NSURL.fileURLWithPath(path, isDirectory = true)
        .setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    if (!excluded) println("Punicus: the engine's directory is not kept out of backups")
}
