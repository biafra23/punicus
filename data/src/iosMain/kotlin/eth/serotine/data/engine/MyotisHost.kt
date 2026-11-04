package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.data.keys.toHex
import eth.serotine.data.logs.LogSink
import eth.serotine.data.prices.ChainlinkFeeds
import eth.serotine.data.prices.FeedsThenPools
import eth.serotine.data.prices.UniswapPools
import eth.serotine.data.safe.SafeDeployments
import eth.serotine.data.swaps.UniswapDeployments
import eth.serotine.data.tokens.BuiltInTokens
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientControl
import eth.serotine.domain.repository.LightClientRun
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkFreshness
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.PriceHistory
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SafeApprovals
import eth.serotine.domain.repository.StaleAnchorConsent
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TokenMetadata
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.UniswapV3Chain
import io.myotis.engine.ios.BeaconState
import io.myotis.engine.ios.ChainHandle
import io.myotis.engine.ios.EngineConfig
import io.myotis.engine.ios.EnsRoot
import io.myotis.engine.ios.LifecycleState
import io.myotis.engine.ios.Myotis
import io.myotis.engine.ios.MyotisEngine
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog

/**
 * The embedded Myotis engine of this process on iOS (docs/embedding-myotis.md; docs/networks.md,
 * *The engine host*): one engine, which hosts one light client per network, each a [ChainNode]
 * that runs only while [set] asks it to (`FollowNetworks`: the network is on, and an account or a
 * screen needs it). The twin of the JVM targets' `MyotisHost` (jvmSharedMain), over Myotis's iOS
 * module (`io.myotis.engine.ios`, which carries `io.myotis.api`'s names and meanings over the
 * engine's C ABI): the same adapters, wired to the same [EngineChain] seam per network, routed
 * by [NetworkRoutedPorts], and the same lifecycle. The iOS host's composition root creates it
 * with [start], hands its ports to the AppGraph and owns it for the life of the process: [pause]
 * and [resume] every running light client around the app being in the background, [shutdown] on
 * exit, as far as iOS lets an app's exit run code at all. Nothing below the iOS module's typed
 * API is used.
 *
 * Every start, stop, pause and resume runs on one control dispatcher, in the order asked: the
 * last one asked for a network is what it ends in. The engine itself is created by the first
 * start; one that cannot be created leaves every network without a light client
 * ([EngineSnapshot.Down], final). A network whose start fails reads as [EngineSnapshot.Failed],
 * and is tried again the next time it is asked to run. No light client here dates a finalized
 * block: Myotis's iOS module reads no block by number yet, so no CoW order is offered on iOS.
 */
class MyotisHost private constructor(
    private val dataDir: String,
    lastBalancesPath: String?,
    private val debugLog: ((String) -> Unit)?,
    private val apiLog: LogSink?,
) : LightClientControl, StaleAnchorConsent, NetworkFreshness {

    /** The engine of this process: none until a first start, [Absent] when it cannot be created here. */
    private sealed interface EngineState {
        data object NotCreated : EngineState
        data object Absent : EngineState
        class Created(val engine: MyotisEngine) : EngineState
    }

    /** The control dispatcher's: the engine, created at the first start. */
    private var engine: EngineState = EngineState.NotCreated

    /** Whether the host last asked for networking off; every light client started after follows it. */
    @Volatile
    private var pauseWanted = false

    @Volatile
    private var shutDown = false

    /** Every blocking engine call off the caller's thread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Runs every start, stop, pause and resume one at a time, in the order asked: each blocks while it lasts. */
    private val control = Dispatchers.IO.limitedParallelism(1)

    /** The debug build's drain of the engine's own log, while the engine runs. */
    @Volatile
    private var logPump: Job? = null

    /** The balances last verified, kept for after a restart; none kept without a path. Shared by every network. */
    private val lastBalances = lastBalancesPath?.let { LastBalancesFile(it, Dispatchers.IO) }

    /** One light client per network the engine can host. */
    private val nodes: Map<Network, ChainNode> = Network.entries.associateWith { ChainNode(it) }

    private val routed = NetworkRoutedPorts(nodes.mapValues { (_, node) -> node.ports })

    /** Native-currency balances, verified by each network's light client. */
    val nativeBalances: NativeBalances = routed.nativeBalances

    /** What is at an address (an account, a delegated one, a Safe, a contract), verified on its network. */
    val addressKinds: AddressKinds = routed.addressKinds

    /** What a token contract says about itself, as verified calls. */
    val tokenMetadata: TokenMetadata = routed.tokenMetadata

    /** Prices: each a verified call of a pinned Chainlink feed of the network, or of a pinned pool where none is. */
    val priceFeeds: PriceFeeds = routed.priceFeeds

    /** What the same feeds answered some time ago, from their round histories, as verified calls. */
    val priceHistory: PriceHistory = routed.priceHistory

    /** ERC-20 balances, each a verified call of the token's `balanceOf`. */
    val tokenBalances: TokenBalances = routed.tokenBalances

    /** Each network's light client, for the user to watch; none dates a finalized block here. */
    val lightClient: LightClient = routed.lightClient

    /** Network upgrades this build does not support, as peers report them to each light client. */
    val networkUpgrades: NetworkUpgrades = routed.networkUpgrades

    /** A transaction's nonce, fees, gas and verified simulation, from its network's verified reads. */
    val transactionInputs: TransactionInputs = routed.transactionInputs

    /** Gossips a signed transaction to its network's peers (the engine never signs). */
    val broadcaster: Broadcaster = routed.broadcaster

    /** A Safe's owners' approvals on chain (`approvedHashes`), each a verified call. */
    val safeApprovals: SafeApprovals = routed.safeApprovals

    /** Uniswap v3 on each network: the pinned deployment once its code is proven, and verified calls. */
    val uniswap: UniswapV3Chain = routed.uniswap

    /** CoW Protocol's settlement contracts on each network, proven by their code (docs/swaps.md, *CoW Protocol*). */
    val cow: CowChain = routed.cow

    /** A sent transaction's verified receipt and its block's finality. */
    val receipts: ReceiptTracker = routed.receipts

    /** ENS names, resolved against beacon-finalized state. */
    val names: NameResolver = routed.names

    /** The engine's verified log index of each network: what it follows of the accounts, and the logs it holds. */
    val logIndex: LogIndex = routed.logIndex

    /** Starts [network]'s light client, or stops it ([LightClientRun.IDLE], [LightClientRun.OFF]). Returns at once. */
    override fun set(network: Network, run: LightClientRun) {
        val node = nodes[network] ?: return
        onControl {
            when (run) {
                LightClientRun.RUN -> node.run()
                LightClientRun.IDLE -> node.stop(off = false)
                LightClientRun.OFF -> node.stop(off = true)
            }
        }
    }

    /**
     * Lets [network]'s light client sync past its stale anchor for this run (the engine's
     * `acceptStaleAnchor`, which is never persisted). Returns at once.
     */
    override fun syncPastStaleAnchor(network: Network) {
        val node = nodes[network] ?: return
        onControl { node.acceptStaleAnchor() }
    }

    override fun observeLive(network: Network): Flow<Boolean> {
        val node = nodes[network] ?: return flowOf(false)
        return flow {
            while (true) {
                val live = try {
                    node.live()
                } catch (e: Throwable) {
                    // Throwable: the engine's state crosses into native code. A cancellation of this poll ends it.
                    currentCoroutineContext().ensureActive()
                    false
                }
                emit(live)
                delay(POLL_INTERVAL)
            }
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    override fun refresh(network: Network) {
        nodes[network]?.schedule?.refresh()
    }

    override fun setOnDemand(onDemand: Boolean) {
        nodes.values.forEach { it.schedule.setOnDemand(onDemand) }
    }

    /**
     * Turns every running light client's networking off, keeping its state, for while the app is in
     * the background. Returns at once with the job the pause runs in, so the host can keep the app
     * awake until it has finished. One started meanwhile starts paused.
     */
    fun pause(): Job {
        pauseWanted = true
        return onControl { nodes.values.forEach { it.followPauseWanted() } }
    }

    /** Turns networking back on after [pause]; a warm start. Returns at once. */
    fun resume(): Job {
        pauseWanted = false
        return onControl { nodes.values.forEach { it.followPauseWanted() } }
    }

    private fun onControl(action: () -> Unit): Job =
        scope.launch(control) {
            if (shutDown) return@launch
            try {
                action()
            } catch (e: Throwable) {
                // The calls cross the engine's C ABI; the light clients keep their last state.
                log("Serotine: the engine could not be switched: $e")
            }
        }

    /**
     * Stops every light client and the engine; idempotent. A start in progress finishes first,
     * waited for up to [SHUTDOWN_WAIT], so a process that exits right after does not cut it off
     * mid-start. The wait bounds only the queue: once the stop runs, it runs to the end, its
     * engine calls not interruptible, and a stop still queued when the wait ends never runs (the
     * JVM host's runs once the start ends), the process ending with the engine as it is. Blocks:
     * never call it on the main thread, where iOS's watchdog ends an app that blocks; the host
     * calls it off the main thread when the app is terminated while running. An app killed while
     * suspended runs no code at all, so on iOS this is best effort: the engine has to come back
     * from an abrupt end anyway.
     */
    fun shutdown() {
        if (shutDown) return
        runBlocking {
            withTimeoutOrNull(SHUTDOWN_WAIT) {
                withContext(control) {
                    shutDown = true
                    nodes.values.forEach { it.release() }
                    (engine as? EngineState.Created)?.engine?.let { created ->
                        try {
                            created.shutdownAll()
                        } catch (e: Throwable) {
                            log("Serotine: the engine did not shut down cleanly: $e")
                        }
                    }
                    engine = EngineState.Absent
                }
            }
        }
        shutDown = true
        logPump?.cancel()
        // In a debug build, what the engine said while stopping, which the pump no longer drains.
        debugLog?.let { EngineLogPump(Myotis::drainLogs, it).drainNow() }
        scope.cancel()
    }

    /** The engine, created on the control dispatcher at the first start; null when it cannot be. */
    private fun engine(): MyotisEngine? {
        when (val current = engine) {
            is EngineState.Created -> return current.engine
            EngineState.Absent -> return null
            EngineState.NotCreated -> Unit
        }
        val created = try {
            Myotis.newEngine()
        } catch (e: Throwable) {
            report("the engine failed to load: $e")
            engine = EngineState.Absent
            return null
        }
        engine = EngineState.Created(created)
        createDirectory(dataDir)
        // The engine's own log, in a debug build: the one account of why a sync stalls.
        if (debugLog != null) {
            logPump = scope.launch { EngineLogPump(Myotis::drainLogs, debugLog).run(ENGINE_LOG_INTERVAL) }
        }
        return created
    }

    /** One network's light client in the host's engine: its state, and its handle while it runs. */
    private sealed interface ChainState {
        data object Booting : ChainState
        class Stopped(val off: Boolean) : ChainState
        class Running(
            val handle: ChainHandle,
            /** The network's beacon genesis (unix seconds) and slot length, from the engine. */
            val genesisTime: Long,
            val secondsPerSlot: Int,
        ) : ChainState

        /** No engine on this device, or the host shut down: final. */
        data object Down : ChainState

        /** The engine failed to start this network's light client: tried again at the next run. */
        data object Failed : ChainState
    }

    /**
     * [network]'s light client: stopped until [run], and again after [stop]. Its [ports] read
     * through its handle while it runs, and say why there is no answer otherwise. Every change of
     * its state is the control dispatcher's; the adapters read it from theirs.
     */
    private inner class ChainNode(private val network: Network) {

        /** Starting, until `FollowNetworks` says what the network is to do: a moment, at the app's start. */
        @Volatile
        var state: ChainState = ChainState.Booting

        /** Whether this light client's networking is off, as the last switch left it: the control dispatcher's. */
        private var networkingPaused = false

        /** The handle, unwrapped: what [activity] reads (in memory, so neither logged nor shared). */
        private val handleChain = HandleChain()

        /** The handle, each request written to [apiLog] when there is one, its status reads shared. */
        private val chain: EngineChain = handleChain.let { handle ->
            SharedSnapshots(apiLog?.let { LoggedChain(handle, it) } ?: handle, SNAPSHOT_REUSE)
        }

        /** Which accounts the verified blocks touched, so a proof stands until one did (Myotis #691). */
        private val activity = AddressActivity(handleChain)

        /** When this network's balances and prices are proven again: at every head, or on demand in simple mode. */
        val schedule = ProofSchedule(live = activity::hasAccountCoverage)

        /** Whether this network's values update live: a readable verified head with block access lists. */
        fun live(): Boolean {
            val head = (chain.snapshot() as? EngineSnapshot.Running)?.readableHead ?: return false
            return activity.hasAccountCoverage(head)
        }

        /** The network's pinned Chainlink feeds' prices, proven on the network's schedule. */
        private val feeds = VerifiedPriceFeeds(
            network,
            chain,
            Dispatchers.IO,
            POLL_INTERVAL,
            ChainlinkFeeds.of(network),
            activity,
            schedule,
        )

        /** The pinned Uniswap pools' averages, for what no feed prices (owner ruling, 2026-10-29, #205). */
        private val pools = VerifiedPoolPrices(
            network,
            chain,
            Dispatchers.IO,
            POLL_INTERVAL,
            UniswapPools.of(network),
            UniswapDeployments.of(network),
        )

        /** A feed's price where one is pinned, a pool's only where none is: now and a day back alike. */
        private val prices = FeedsThenPools(
            feeds,
            VerifiedPriceHistory(network, chain, Dispatchers.IO, ChainlinkFeeds.of(network)),
            pools,
        )

        val ports = ChainPorts(
            nativeBalances = VerifiedNativeBalances(
                network,
                chain,
                Dispatchers.IO,
                POLL_INTERVAL,
                lastBalances,
                activity,
                schedule,
            ),
            tokenBalances = VerifiedTokenBalances(
                network,
                chain,
                Dispatchers.IO,
                POLL_INTERVAL,
                lastBalances,
                activity,
                gated = BuiltInTokens.of(network).mapTo(HashSet()) { it.address },
                schedule = schedule,
            ),
            tokenMetadata = VerifiedTokenMetadata(network, chain, Dispatchers.IO, POLL_INTERVAL),
            addressKinds = VerifiedAddressKinds(
                network,
                chain,
                Dispatchers.IO,
                POLL_INTERVAL,
                SafeDeployments.of(network),
                debugLog = debugLog,
            ),
            // No finalized block's own time here (the iOS module reads no block by number): no CoW order.
            lightClient = object : LightClient by EngineLightClient(
                network,
                chain,
                Dispatchers.IO,
                PROGRESS_POLL_INTERVAL,
            ) {
                override val datesFinalizedBlocks: Boolean = false
            },
            networkUpgrades = EngineNetworkUpgrades(network, chain, Dispatchers.IO, POLL_INTERVAL),
            priceFeeds = prices,
            priceHistory = prices,
            transactionInputs = EngineTransactionInputs(network, chain, Dispatchers.IO),
            broadcaster = EngineBroadcaster(network, chain, Dispatchers.IO),
            receipts = EngineReceipts(network, chain, Dispatchers.IO, POLL_INTERVAL),
            names = EngineNames(network, chain, Dispatchers.IO),
            safeApprovals = VerifiedSafeApprovals(network, chain, Dispatchers.IO),
            uniswap = VerifiedUniswapV3(chain, Dispatchers.IO, network),
            cow = VerifiedCow(chain, Dispatchers.IO, network),
            logIndex = EngineLogIndex(network, chain, Dispatchers.IO, POLL_INTERVAL),
        )

        /** Starts the light client, unless it runs already. Control dispatcher; blocks while the engine starts it. */
        fun run() {
            if (state is ChainState.Running) return
            state = ChainState.Booting
            val engine = engine() ?: run {
                state = ChainState.Down
                return
            }
            state = boot(engine) ?: ChainState.Failed
            networkingPaused = false
            followPauseWanted()
        }

        /**
         * Stops the light client, keeping its files, and says why: [off], or idle. Control dispatcher.
         * A device with no engine keeps saying so when the network goes idle; turned off, it reads
         * as off, as the user asked.
         */
        fun stop(off: Boolean) {
            if (state == ChainState.Down && !off) return
            val running = state as? ChainState.Running
            // Said before the handle goes: a read in flight answers "off" rather than an engine error.
            state = ChainState.Stopped(off)
            running?.let { stopEngineChain() }
        }

        /** Stops it for good: the host shuts down. Control dispatcher. */
        fun release() {
            val running = state as? ChainState.Running
            state = ChainState.Down
            running?.let { stopEngineChain() }
        }

        /**
         * Brings the light client's networking to what the host last asked for ([pauseWanted]): a
         * switch to the state it is already in is not asked of the engine, which refuses it.
         * Control dispatcher.
         */
        /**
         * Whether the user let this network sync past a stale anchor in this run: armed again on
         * each chain started later in the run, before its start (the engine's pre-consent), as the
         * engine keeps it per chain. Control dispatcher.
         */
        private var staleAnchorConsented = false

        fun acceptStaleAnchor() {
            staleAnchorConsented = true
            acceptStaleAnchor((state as? ChainState.Running)?.handle ?: return)
        }

        /** Arms [handle]'s consent, a failure logged: the chain then parks and asks again, never failed. */
        private fun acceptStaleAnchor(handle: ChainHandle) {
            val accepted = try {
                handle.acceptStaleAnchor()
            } catch (e: Throwable) {
                log("Serotine: ${network.displayName}'s light client could not sync past its stale anchor: $e")
                return
            }
            if (!accepted) log("Serotine: ${network.displayName}'s light client did not take the stale anchor")
        }

        fun followPauseWanted() {
            val handle = (state as? ChainState.Running)?.handle ?: return
            val wanted = pauseWanted
            if (networkingPaused == wanted) return
            val switched = try {
                if (wanted) handle.pause() else handle.resume()
            } catch (e: Throwable) {
                // Caught here, not around the loop over the networks, so one failing does not
                // leave the others' networking as it was.
                log("Serotine: ${network.displayName}'s light client could not be switched: $e")
                return
            }
            if (switched) {
                networkingPaused = wanted
            } else {
                log("Serotine: ${network.displayName}'s light client did not ${if (wanted) "pause" else "resume"}")
            }
        }

        private fun stopEngineChain() {
            try {
                // Stops and unregisters the chain, so that a later start creates it anew.
                (engine as? EngineState.Created)?.engine?.stop(network.engineName())
            } catch (e: Throwable) {
                log("Serotine: ${network.displayName}'s light client did not stop cleanly: $e")
            }
        }

        /** Creates and starts the network's chain in [engine]; null when it cannot, said why. */
        private fun boot(engine: MyotisEngine): ChainState.Running? {
            val name = network.engineName()
            // The iOS engine keeps each network's peer caches, sync snapshot and log index under the
            // one data directory, named per network, and asks the host for no ports. It has no
            // CCIP-Read gateway either: an off-chain ENS answer (EIP-3668) comes from an HTTP gateway
            // the resolver names, an off-chain source no ruling allows; the engine refuses such a
            // name, and says so. The JVM host's snap-peer target and strict freshness flag have no
            // counterpart here: the Rust engine behind both takes only the name and the data
            // directory, so both run the same.
            val handle = try {
                engine.create(EngineConfig(name, dataDir))
            } catch (e: Throwable) {
                report("the engine refused ${network.displayName}: $e")
                return null
            }
            return try {
                val info = engine.availableNetworks().firstOrNull { it.name == handle.networkName }
                val running = ChainState.Running(handle, info?.clGenesisTime ?: 0, info?.secondsPerSlot ?: 0)
                if (staleAnchorConsented) acceptStaleAnchor(handle)
                if (handle.start()) {
                    running
                } else {
                    // The engine says why only in its own log, at its end, drained before anything stops it.
                    val refusal = EngineLogPump.lastLines(Myotis::drainLogs, ENGINE_LOG_LINES)
                    stopEngineChain()
                    report("the engine refused to start $name:\n$refusal")
                    null
                }
            } catch (e: Throwable) {
                stopEngineChain()
                report("${network.displayName}'s light client failed to start: $e")
                null
            }
        }

        private inner class HandleChain : EngineChain, ActivityChain {

            // Each call a status read; SharedSnapshots shares one among the pollers.
            override fun snapshot(): EngineSnapshot = read()

            private fun read(): EngineSnapshot {
                val running = when (val current = state) {
                    ChainState.Booting -> return EngineSnapshot.Booting
                    ChainState.Down -> return EngineSnapshot.Down
                    ChainState.Failed -> return EngineSnapshot.Failed
                    is ChainState.Stopped -> return EngineSnapshot.Stopped(current.off)
                    is ChainState.Running -> current
                }
                val status = try {
                    running.handle.status()
                } catch (e: Throwable) {
                    // A status out of shape (the iOS module fails closed on drift): no report yet.
                    return EngineSnapshot.Booting
                }
                // The head block's time from its slot, the consensus rule for an execution
                // payload's timestamp (genesis + slot × seconds per slot): verified data only. The
                // optimistic slot is the head block's own (Myotis #658): the iOS module decodes it
                // from the same status JSON key ("optimisticSlot") RustChainHandle.beaconStatus()
                // reads on the JVM, so both nodes date the head alike.
                val slot = status.optimisticSlot
                val headTimestamp = if (slot > 0 && running.genesisTime > 0 && running.secondsPerSlot > 0) {
                    running.genesisTime + slot * running.secondsPerSlot
                } else {
                    null
                }
                return EngineSnapshot.Running(
                    phase = when (status.beaconState) {
                        BeaconState.SYNCING -> EngineSnapshot.Phase.SYNCING
                        BeaconState.CATCHING_UP -> EngineSnapshot.Phase.CATCHING_UP
                        BeaconState.SYNCED -> EngineSnapshot.Phase.SYNCED
                        BeaconState.STALE_ANCHOR -> EngineSnapshot.Phase.STALE_ANCHOR
                        BeaconState.STARTING -> EngineSnapshot.Phase.STARTING
                    },
                    paused = status.lifecycle == LifecycleState.PAUSED,
                    syncStartPeriod = status.syncStartPeriod,
                    syncCurrentPeriod = status.syncCurrentPeriod,
                    syncTargetPeriod = status.syncTargetPeriod,
                    huntingLightClientServers = status.lcHunting,
                    proofPeers = status.snapServingPeers,
                    headBlock = status.optimisticBlockNumber,
                    headTimestamp = headTimestamp,
                    headAgeMs = status.verifiedHeadAgeMs,
                    wsBoundPeriods = status.wsBoundPeriods,
                    upgrade = status.upgradeAdvisory?.toNetworkUpgrade(),
                    finalizedBlock = status.finalizedBlockNumber,
                    // No finalized timestamp on iOS: Myotis 0.1.18's iOS module reads no block by
                    // number (its block reads are internal), and the slot's time is no stand-in for the
                    // header's (docs/swaps.md, *Proving an order's end*). A CoW order is then not
                    // offered here, as it waits for a finalized block's own time.
                    finalizedHeader = null,
                )
            }

            private fun handle(): ChainHandle? = (state as? ChainState.Running)?.handle

            // requestAccount: it returns the verdict and the block the proof anchors to, so a
            // reading is labelled with where it was proven.
            override fun proveBalance(address: Address): ProvenBalance? {
                val result = handle()?.requestAccount(address.hex) ?: return null
                if (result.failReason != null || result.verifyMethod == null) return null
                // A proven absence is a proven zero.
                return ProvenBalance(
                    if (result.exists) result.balanceWei ?: return null else "0",
                    result.blockNumber,
                    result.blockHashHex?.lowercase(),
                )
            }

            // The code hash comes with the account proof, labelled with its block; the code itself
            // from the verified reads; provenCode decides what of it is proven.
            override fun proveCode(address: Address): ProvenCode? {
                val handle = handle() ?: return null
                val account = handle.requestAccount(address.hex)
                if (account.failReason != null || account.verifyMethod == null) return null
                val anchor = accountProofAnchor(account.verifyMethod, account.matchedBeaconSlot, account.blockNumber)
                return provenCode(account.exists, account.codeHashHex, account.blockNumber, anchor) {
                    handle.reads().getCode(address.bytes(), "latest")
                }
            }

            override fun call(to: Address, data: ByteArray): CallOutcome? {
                val handle = handle() ?: return null
                return callOutcome(handle.reads().callDetailed(null, to.bytes(), data, null, "latest", null))
            }

            // Accepted only with a verified storage proof tied to the beacon chain and a verdict, as
            // balances are; a proven empty slot is a proven zero. Labelled as storageProofLabel says.
            override fun proveSlot(address: Address, slot: Long, holder: Address?): ProvenSlot? {
                val result = handle()?.getStorageProof(address.hex, slot, holder?.hex) ?: return null
                if (result.failReason != null || !result.storageProofValid || !result.beaconChainVerified) return null
                val label = storageProofLabel(
                    verifyMethod = result.verifyMethod,
                    matchedBeaconSlot = result.matchedBeaconSlot,
                    finalizedSlot = result.finalizedSlot,
                    optimisticSlot = result.optimisticSlot,
                    peerBlockNumber = result.peerBlockNumber,
                    finalizedBlockNumber = result.finalizedBlockNumber,
                ) ?: return null
                val value = if (result.exists) result.valueHex?.removePrefix("0x")?.lowercase() ?: return null else ""
                return value.padStart(WORD_HEX_DIGITS, '0').takeIf { it.length == WORD_HEX_DIGITS }
                    ?.let { ProvenSlot(it, label.blockNumber, label.anchor) }
            }

            override fun storageAt(address: Address, slot: ByteArray): String? {
                val word = handle()?.reads()?.getStorageAt(address.bytes(), slot, "latest") ?: return null
                return word.toHex().padStart(WORD_HEX_DIGITS, '0').takeIf { it.length == WORD_HEX_DIGITS }
            }

            // "pending": the verified count plus the sends this node made that are not mined yet,
            // so a second transfer right after a first does not reuse its nonce.
            override fun pendingNonce(address: Address): Long? =
                handle()?.reads()?.getTransactionCount(address.bytes(), "pending")

            override fun minedNonce(address: Address): Long? =
                handle()?.reads()?.getTransactionCount(address.bytes(), "latest")

            override fun feeHistory(blocks: Int, percentiles: DoubleArray): String? =
                handle()?.reads()?.feeHistory(blocks.toLong(), "latest", percentiles)

            override fun estimateGas(from: Address, to: Address, valueWei: String, data: ByteArray): EstimateAnswer {
                val handle = handle() ?: return EstimateAnswer.Unavailable("no engine running")
                return estimateAnswer(handle.reads().estimateGasDetailed(from.bytes(), to.bytes(), data, valueWei))
            }

            override fun callFrom(from: Address, to: Address, valueWei: String, data: ByteArray): CallOutcome? {
                val handle = handle() ?: return null
                val outcome = handle.reads().callDetailed(from.bytes(), to.bytes(), data, valueWei, "latest", null)
                return callOutcome(outcome)
            }

            override fun sendRaw(raw: ByteArray): SendAnswer {
                val handle = handle() ?: return SendAnswer.Unavailable("the light client is not running")
                return sendAnswer(handle.reads().sendRawTransactionDetailed(raw))
            }

            override fun receipt(hash: String): String? =
                handle()?.reads()?.getTransactionReceipt(hash.removePrefix("0x").fromHex())

            override fun setLogIndexConfig(configJson: String): Boolean =
                handle()?.setLogIndexConfig(configJson) ?: false

            override fun logIndexStatus(): String? = handle()?.logIndexStatusJson()

            override fun logs(filterJson: String): String? = handle()?.reads()?.getLogs(filterJson)

            override fun setActivityWatch(configJson: String): Boolean = handle()?.setActivityWatch(configJson) ?: false

            override fun addressActivity(afterBlock: Long): String? = handle()?.addressActivityJson(afterBlock)

            // FINALIZED only: PEER_HEAD would be a peer's claim, and AUTO falls back to it.
            override fun resolveName(name: String): NameAnswer {
                val handle = handle()
                    ?: return NameAnswer(null, -1, verified = false, error = "the light client is not running")
                val ens = handle.ens()
                    ?: return NameAnswer(null, -1, verified = false, error = "this network has no name registry")
                return nameAnswer(ens.resolveAddress(name, EnsRoot.FINALIZED))
            }
        }
    }

    companion object {
        /** How often the verified reads poll the engine, as on the JVM targets. */
        internal val POLL_INTERVAL = 4.seconds

        /** A storage word's hex digits. */
        private const val WORD_HEX_DIGITS = 64

        /** How long one status read serves other pollers: well under the fastest poll (1 s). */
        private val SNAPSHOT_REUSE = 500.milliseconds

        /** Faster than balances: a progress display that moves in 4 s steps looks stuck. */
        private val PROGRESS_POLL_INTERVAL = 1.seconds

        /** Engine log lines a failed start reports: the last ones, where it says why. */
        private const val ENGINE_LOG_LINES = 40

        /** How often a debug build drains the engine's log; its ring holds hours of lines. */
        private val ENGINE_LOG_INTERVAL = 1.seconds

        /** How long [shutdown] waits for a start in progress to end. */
        private val SHUTDOWN_WAIT = 15.seconds

        /**
         * A host for the networks' light clients with their state under [dataDir] (a path in the
         * app's own container). Nothing runs until [set] starts a network, on the control
         * dispatcher: creating the engine and starting a chain block, and none of it may run on the
         * main thread. [debugLog] takes the adapters' diagnostics and, while the engine runs, the
         * engine's own log ([EngineLogPump]): a debug build's log, null in a release build, which
         * leaves the engine's log undrained; public chain data only. [lastBalances] is the file the
         * balances last verified are kept in, to be shown as such after a restart until a head can
         * be read ([LastBalancesFile]); null keeps them for the process only. [apiLog] takes each
         * request the adapters make of the engine ([LoggedChain]): the Debug tab's log, in memory
         * only; null records none.
         */
        fun start(
            dataDir: String,
            lastBalances: String? = null,
            debugLog: ((String) -> Unit)? = null,
            apiLog: LogSink? = null,
        ): MyotisHost = MyotisHost(dataDir, lastBalances, debugLog, apiLog)
    }
}

/**
 * Why there is no light client, in the device's log: the one fact a user's "No light client"
 * cannot tell a developer. Engine state only; no key material is anywhere near this path.
 */
private fun report(why: String) = log("Serotine: no light client: $why")

/**
 * [line] in the device's log: NSLog, since println's stdout reaches no one unless Xcode runs the
 * app. The line is the format itself, its `%` escaped, so nothing crosses NSLog's C varargs: with
 * `NSLog("%@", line)` the debug app died at launch on the simulator (#129's CI).
 */
private fun log(line: String) = NSLog(line.replace("%", "%%"))

/** Creates [path] and its parents; an existing directory is fine, a failure the engine's to tell. */
@OptIn(ExperimentalForeignApi::class)
private fun createDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
}
