package eth.serotine.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eth.serotine.data.account.FileAccountRepository
import eth.serotine.data.cow.FileCowOrders
import eth.serotine.data.cow.HttpCowApi
import eth.serotine.data.engine.MyotisHost
import eth.serotine.data.history.FileHistoryStarts
import eth.serotine.data.keys.EnvelopeKeyStore
import eth.serotine.data.keys.Secp256k1Recovery
import eth.serotine.data.keys.desktopBiometricKey
import eth.serotine.data.keys.desktopHardwareSecret
import eth.serotine.data.keys.desktopPasswordOnlyProtection
import eth.serotine.data.logs.MemoryDebugLog
import eth.serotine.data.networks.FileNetworkSettings
import eth.serotine.data.safe.FileSafeTxQueue
import eth.serotine.data.safe.JsonSigningRequestCodec
import eth.serotine.data.safe.PinnedSafeReleases
import eth.serotine.data.settings.FileDebugScreen
import eth.serotine.data.settings.FileDisplayCurrency
import eth.serotine.data.settings.FileAmountsHidden
import eth.serotine.data.settings.FileInterfaceMode
import eth.serotine.data.settings.FileSwapVenue
import eth.serotine.data.swaps.FileWaitingSwaps
import eth.serotine.data.walletconnect.WalletConnectDapps
import eth.serotine.data.tokens.BuiltInTokenCatalog
import eth.serotine.data.tokens.FileUserTokens
import eth.serotine.data.transfers.FileSentTransfers
import eth.serotine.data.transfers.FileTransactionHistory
import eth.serotine.data.upgrade.FileKnownUpgradeStore
import eth.serotine.domain.model.Network
import eth.serotine.domain.usecase.FollowAccountLogs
import eth.serotine.domain.usecase.AnswerDappRequestsAtOnce
import eth.serotine.domain.usecase.FollowDappAccounts
import eth.serotine.domain.usecase.FollowDebugScreen
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
import eth.serotine.domain.usecase.ProveCowOrderEnds
import eth.serotine.domain.usecase.UpgradeNotices
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.App
import eth.serotine.ui.AppGraph
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.IOException
import java.lang.ref.Reference
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.exitProcess

/**
 * The desktop composition root: starts the process's one Myotis engine
 * (docs/embedding-myotis.md), builds the [AppGraph] from this platform's adapters and opens
 * the one window on the shared [App], and for as long as the app runs follows the engine's
 * network-upgrade reports into tray notices and lists the Safes this wallet deployed once the
 * light client proves them: the work no screen owns, run here beside the graph, on the same
 * adapters. The window provides the ViewModelStoreOwner the
 * navigation back stack keeps its screens' ViewModels in. Closing it ends the application
 * loop, and the engine is stopped after that, on the main thread: never on the Swing event
 * thread, which a slow network shutdown would freeze. One instance per user: a second one
 * finds the [InstanceLock] taken, says so and ends, so two never write the account list, the
 * keys or the engine's state at once.
 */
fun main() {
    useBundledEngineLibrary()
    val dataDir = appDataDir()
    val instance = try {
        InstanceLock.acquire(dataDir.resolve("serotine.lock"))
    } catch (e: IOException) {
        tellAndExit(
            "Serotine cannot use its data directory $dataDir: ${e.message ?: e::class.simpleName}",
            JOptionPane.ERROR_MESSAGE,
        )
    }
    if (instance == null) {
        tellAndExit("Serotine is already running, or still closing.", JOptionPane.INFORMATION_MESSAGE)
    }
    try {
        openWallet(dataDir)
    } finally {
        // Never released by hand: the lock ends with the process, which openWallet() ends;
        // until then it must stay reachable, or the garbage collector would close it.
        Reference.reachabilityFence(instance)
    }
}

/**
 * Points the Myotis engine at the host library the app's resources carry
 * (desktop-app/build.gradle.kts, `stageMyotisLibrary`), before anything loads it: the
 * packaged app's bundle and a development run's prepared resources both have it, on macOS
 * and Linux. A library already named (`-PmyotisLibraryDir` on the run task) stays. Where the
 * resources have none (Windows, for now), the engine finds none and the balances say so.
 */
private fun useBundledEngineLibrary() {
    if (!System.getProperty(ENGINE_LIBRARY_PROPERTY).isNullOrBlank()) return
    // Blank would resolve against the working directory.
    val resources = System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() } ?: return
    val mac = "mac" in System.getProperty("os.name").lowercase()
    val library = File(resources, if (mac) "libmyotis_engine.dylib" else "libmyotis_engine.so")
    if (library.isFile) System.setProperty(ENGINE_LIBRARY_PROPERTY, library.absolutePath)
}

/** The engine library's override of where its host library is loaded from. */
private const val ENGINE_LIBRARY_PROPERTY = "myotis.engine.lib"

/**
 * Says [message] (a dialog of [kind], a [JOptionPane] message type, where there is a screen;
 * stderr always) and ends the process with status 1.
 */
private fun tellAndExit(message: String, kind: Int): Nothing {
    System.err.println(message)
    if (!GraphicsEnvironment.isHeadless()) {
        try {
            SwingUtilities.invokeAndWait { JOptionPane.showMessageDialog(null, message, "Serotine", kind) }
        } catch (e: Throwable) {
            // No usable window server after all: stderr has the message, and the exit below stands.
        }
    }
    exitProcess(1)
}

private fun openWallet(dataDir: File) {
    val keys = dataDir.resolve("keys")
    excludeFromBackups(keys)
    // Before the window: on a Mac with no wallet yet this asks the Secure Enclave once.
    val hardware = desktopHardwareSecret(keys.path)
    // A light client per network that is on and needed (CLAUDE.md, *Data sources*). The engine boots in the
    // background; without its host library (useBundledEngineLibrary), balances say so. The adapters'
    // diagnostics and the engine's own log go to stderr only in a development run
    // (`:desktop-app:run` sets the property; no installer does): the desktop's debug build.
    val debugLog: ((String) -> Unit)? =
        if (System.getProperty(DEBUG_LOG_PROPERTY) == "true") { line -> System.err.println(line) } else null
    // The balances last verified are kept beside it, to be shown as such after a restart. Each
    // request asked of the engine goes to the Debug tab's log, in memory, in any run, while the
    // user has that tab turned on (debugScreen, followed below).
    val apiLog = MemoryDebugLog()
    val debugScreen = FileDebugScreen(dataDir.resolve("debug-screen.json").path)
    // The view the user chose, simple mode or Detailed view (docs/simple-ui.md); the Debug tab is the latter's.
    val interfaceMode = FileInterfaceMode(dataDir.resolve("ui-mode.json").path)
    val amountsHidden = FileAmountsHidden(dataDir.resolve("amounts-hidden.json").path)
    // One engine, one light client per network that is on and needed (FollowNetworks, below).
    val node = MyotisHost.start(
        dataDir.resolve("myotis"),
        lastBalances = dataDir.resolve("last-balances.json").path,
        debugLog = debugLog,
        apiLog = apiLog,
    )
    // One record per process, read by the screens and written by the notices; this version's only.
    val knownUpgrades = FileKnownUpgradeStore(dataDir.resolve("upgrade.json").path, APP_VERSION)
    // Each network's upgrades are told apart: its own notices and record.
    val upgradeNotices = Network.entries.associateWith { UpgradeNotices(it, knownUpgrades, TrayUpgradeNotifier(it)) }
    // One instance each, which the graph and the followers share. Each account added is noted
    // where its history starts (issue #85): addresses with a block or a time, public data only.
    val historyStarts = FileHistoryStarts(dataDir.resolve("history-starts.json").path)
    val accounts =
        HistoryNotingAccounts(FileAccountRepository(dataDir.resolve("accounts.json").path), historyStarts)
    // The transactions signed here, with their signed bytes (public once broadcast), and every one
    // of them kept for good with how it ended (issue #85): each record goes into both.
    val sentRecord = FileSentTransfers(dataDir.resolve("sent-transfers.json").path)
    val transactionHistory = FileTransactionHistory(dataDir.resolve("transaction-history.json").path)
    // The CoW orders signed here, each live until its end is proven (docs/swaps.md): public data only.
    val cowOrders = FileCowOrders(dataDir.resolve("cow-orders.json").path)
    val sentTransfers = HistoryRecordingSentTransfers(sentRecord, transactionHistory)
    // Which networks are on (docs/networks.md, *Networks on and off*). A network the file does not
    // mention yet gets this build's default: an installed app turns on the real networks and leaves
    // Sepolia off, a development run (its debug build, the property's) turns on Sepolia alone.
    val development = System.getProperty(DEBUG_LOG_PROPERTY) == "true"
    val networkSettings =
        FileNetworkSettings(dataDir.resolve("networks.json").path, defaultOn = { it.testnet == development })
    // The screens' holds on a network with no account on it yet (Watch, Add token).
    val networkDemand = NetworkDemand()
    // dApp sessions over WalletConnect (docs/walletconnect.md), with the project ID of this build: a
    // development run's (the debug one), an installed app's (the release one); off without one.
    val dappProjectId = WalletConnectDapps.projectIdFor(debug = development)
    val dapps = WalletConnectDapps(dataDir.resolve("walletconnect/state.json").path, dappProjectId)
    application(exitProcessOnExit = false) {
        LaunchedEffect(Unit) {
            // The relay connection while the app runs: dApp messages arrive while it is open. A failure
            // ends it alone.
            try {
                dapps.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Serotine: the WalletConnect relay connection stopped: ${e::class.simpleName}")
            }
        }
        LaunchedEffect(Unit) {
            // Each session's accounts kept to those a dApp may still be offered (accountsChanged). A
            // failure ends it alone, never the relay connection.
            try {
                FollowDappAccounts(dapps, accounts, networkSettings)()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Serotine: following the dApp sessions' accounts stopped: ${e::class.simpleName}")
            }
        }
        LaunchedEffect(Unit) {
            // The requests that need no one's decision, answered at once. A failure ends it alone.
            try {
                AnswerDappRequestsAtOnce(dapps.requests)()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Serotine: answering the dApps' requests stopped: ${e::class.simpleName}")
            }
        }
        LaunchedEffect(Unit) {
            // Each network's light client runs while it is on and needed (docs/networks.md, *The
            // engine host*); following the setting also records each network's default. A failure
            // ends it, never the wallet: the light clients keep their last state.
            try {
                FollowNetworks(networkSettings, accounts, networkDemand, node)()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Serotine: following the networks stopped: ${e::class.simpleName}")
            }
        }
        LaunchedEffect(Unit) {
            // Values proven on demand in simple mode, at every head in Detailed view (docs/simple-ui.md).
            try {
                FollowInterfaceMode(interfaceMode, node)()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Serotine: following the view stopped: ${e::class.simpleName}")
            }
        }
        // Each network's upgrade reports, while its light client runs (a network that is off or
        // idle reports none).
        Network.entries.forEach { network ->
            LaunchedEffect(network) {
                // A failure in the notices ends them, never the wallet (as on Android): the window
                // still shows every warning, and the next start follows the reports again.
                try {
                    FollowUpgradeReports(network, node.networkUpgrades, upgradeNotices.getValue(network))()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println("Serotine: upgrade notices of ${network.displayName} stopped: $e")
                }
            }
        }
        LaunchedEffect(Unit) {
            // The debug log records while the Debug tab is on. A failure ends it, never the wallet.
            try {
                FollowDebugScreen(ObserveDebugTab(debugScreen, interfaceMode), apiLog)()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Serotine: following the Debug tab's switch stopped: ${e::class.simpleName}")
            }
        }
        LaunchedEffect(Unit) {
            // The Safes this wallet deployed are listed whatever screen is open, and again after a
            // restart (docs/wallet-ux.md, screen 15). A failure ends it, never the wallet.
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
        LaunchedEffect(Unit) {
            // How each transaction sent from here ends is recorded whatever screen is open, while
            // the app runs (issue #85). A failure ends it, never the wallet.
            try {
                FollowSentTransactions(transactionHistory, sentRecord, node.receipts, node.transactionInputs)()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Serotine: following sent transactions stopped: ${e::class.simpleName}")
            }
        }
        // Each network's log index follows that network's accounts from where each history starts,
        // while the app runs (issue #85). A failure ends it, never the wallet.
        Network.entries.forEach { network ->
            LaunchedEffect(network) {
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
        // Each network's CoW orders' ends are proven from its accounts' log-index entries, while the
        // app runs (docs/swaps.md, *Proving an order's end*), after the ended ones signed over a week
        // ago are dropped. A failure ends it, never the wallet.
        Network.entries.forEach { network ->
            LaunchedEffect(network) {
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
        val graph = remember {
            // A Mac's Secure Enclave where the wallet uses it or can start to, the password
            // alone otherwise, and on Windows and Linux saying that their TPM is not used yet
            // (the owner's rulings of 2026-10-15 and -16); Touch ID on a Mac, the user's opt-in
            // in Settings → Security (rule 3). One instance is both the key store and the
            // signer, so that their calls take turns.
            val keyStore = EnvelopeKeyStore(
                keys.path,
                hardware,
                withoutHardware = desktopPasswordOnlyProtection(),
                biometricKey = desktopBiometricKey(keys.path),
            )
            AppGraph(
                accounts = accounts,
                keys = keyStore,
                nativeBalances = node.nativeBalances,
                tokenCatalog = BuiltInTokenCatalog(),
                tokenBalances = node.tokenBalances,
                tokenMetadata = node.tokenMetadata,
                userTokens = FileUserTokens(dataDir.resolve("tokens.json").path),
                priceFeeds = node.priceFeeds,
                displayCurrency = FileDisplayCurrency(dataDir.resolve("display-currency.json").path),
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
                // The Safe transactions waiting for their owners, with the signatures collected (public).
                safeTxQueue = FileSafeTxQueue(dataDir.resolve("safe-tx-queue.json").path),
                signingRequests = JsonSigningRequestCodec(),
                signatureRecovery = Secp256k1Recovery(),
                safeApprovals = node.safeApprovals,
                // Safe 1.4.1's singleton, factory and proxy code, pinned in code: what a new Safe is created from.
                safeReleases = PinnedSafeReleases(),
                // Uniswap v3 on each network that has it pinned, its contracts proven by their code before any quote.
                uniswap = node.uniswap,
                // CoW Protocol: its pinned contracts proven by their code, and its order API, an
                // off-chain service used only for a swap the user sends to CoW (CLAUDE.md, *Data sources*).
                cowChain = node.cow,
                cowApi = HttpCowApi.create(),
                cowOrders = cowOrders,
                swapVenue = FileSwapVenue(dataDir.resolve("swap-venue.json").path),
                waitingSwaps = FileWaitingSwaps(dataDir.resolve("waiting-swaps.json").path),
                debugLog = apiLog,
                debugScreen = debugScreen,
                interfaceMode = interfaceMode,
                amountsHidden = amountsHidden,
                // The desktop keeps no window out of screen capture, so there is nothing to lift.
                screenCaptureDebugSwitch = null,
                networkDemand = networkDemand,
                networkSettings = networkSettings,
                dappSessions = dapps,
                dappRequests = dapps.requests,
                dappConnectionsOff = dappProjectId.isEmpty(),
            )
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Serotine",
            // The title bar's and taskbar's icon; an installed app's dock and launcher icon is
            // the installer's (iconFile, desktop-app/build.gradle.kts).
            icon = remember { AppIcon.image?.let { BitmapPainter(it.toComposeImageBitmap()) } },
            state = rememberWindowState(width = 480.dp, height = 760.dp),
        ) {
            App(graph)
        }
    }
    // The engine's network shutdown can be slow, and this process holds the instance lock
    // until it exits: bounded, so a closed window never leaves a process without one that
    // refuses the next launch for long. exitProcess in finally, so a shutdown that throws
    // still ends the process.
    try {
        thread(name = "myotis-shutdown", isDaemon = true) { node.shutdown() }.join(ENGINE_SHUTDOWN_WAIT_MS)
    } finally {
        exitProcess(0)
    }
}

/**
 * How long a closing app waits for the engine to stop before it exits anyway: past the 15 s
 * MyotisHost.shutdown() itself gives a start in progress, so that boot is not cut off mid-start.
 */
private const val ENGINE_SHUTDOWN_WAIT_MS = 20_000L

/** The system property, `true` in a development run only, that turns the adapters' diagnostics on. */
private const val DEBUG_LOG_PROPERTY = "serotine.debugLog"

/**
 * Keeps [directory], the key store's envelopes, out of backups (docs/keys-and-signing.md,
 * rule 8) where this OS backs application data up on its own. On macOS that is Time Machine,
 * told through its sticky exclusion, which needs no administrator rights and moves with the
 * directory. On Linux it is GNOME's Backups (Déjà Dup), whose default takes the whole home
 * directory and skips a directory holding a `.deja-dup-ignore` file. Windows' File History
 * leaves application data out unless the user adds it. Best effort, off the main thread; a
 * failure is reported.
 */
private fun excludeFromBackups(directory: File) {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    if ("linux" in os) {
        thread(isDaemon = true, name = "serotine-backup-exclusion") {
            try {
                directory.mkdirs()
                directory.resolve(".deja-dup-ignore").createNewFile()
            } catch (e: Exception) {
                println("Serotine: could not mark $directory for Déjà Dup (${e::class.simpleName})")
            }
        }
        return
    }
    if ("mac" !in os) return
    thread(isDaemon = true, name = "serotine-backup-exclusion") {
        try {
            directory.mkdirs()
            val process = ProcessBuilder("/usr/bin/tmutil", "addexclusion", directory.path)
                .redirectErrorStream(true)
                .start()
            process.inputStream.use { it.readBytes() }
            val exit = process.waitFor()
            if (exit != 0) println("Serotine: Time Machine did not exclude $directory (exit $exit)")
        } catch (e: Exception) {
            println("Serotine: Time Machine did not exclude $directory (${e::class.simpleName})")
        }
    }
}

/**
 * Where this OS keeps an application's own data: the account list, the keys, the engine's
 * state and the instance lock.
 */
private fun appDataDir(): File {
    val os = System.getProperty("os.name").lowercase()
    val home = File(System.getProperty("user.home"))
    return when {
        "mac" in os -> home.resolve("Library/Application Support/Serotine")
        // Not %LOCALAPPDATA%\Serotine: the per-user msi installs the app there, and the keys
        // must not share a directory an uninstaller, an upgrade or a user cleaning up owns.
        "win" in os -> (System.getenv("LOCALAPPDATA")?.let(::File) ?: home.resolve("AppData/Local"))
            .resolve("eth.serotine")
        else -> (System.getenv("XDG_DATA_HOME")?.let(::File) ?: home.resolve(".local/share")).resolve("serotine")
    }
}
