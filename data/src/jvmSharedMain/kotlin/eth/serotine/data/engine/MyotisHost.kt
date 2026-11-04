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
import io.myotis.api.BeaconState
import io.myotis.api.ChainHandle
import io.myotis.api.EngineConfig
import io.myotis.api.EnsRoot
import io.myotis.api.LifecycleState
import io.myotis.api.MyotisEngine
import io.myotis.api.ports.DnsServers
import io.myotis.api.ports.EnginePorts
import io.myotis.engine.Myotis
import io.myotis.engine.ports.DefaultEnginePorts
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * The embedded Myotis engine of this process (docs/embedding-myotis.md; docs/networks.md, *The
 * engine host*): one engine, which hosts one light client per network, each a [ChainNode] that
 * runs only while [set] asks it to (`FollowNetworks`: the network is on, and an account or a
 * screen needs it). A host's composition root creates it with [start] and hands its ports to the
 * AppGraph: [nativeBalances], [tokenBalances], [tokenMetadata], [priceFeeds], [addressKinds],
 * [lightClient], [networkUpgrades], and for sending [transactionInputs], [broadcaster],
 * [receipts] and [names], for a Safe's transactions [safeApprovals], for swaps [uniswap] and [cow], for the
 * accounts' histories [logIndex], for a home-screen widget's daily change [priceHistory]; each
 * port routes a call to the light client of the network it names ([NetworkRoutedPorts]). The host
 * owns it for the life of the process: [pause] and [resume] every running light client around
 * the app being in the background, [shutdown] on exit. Only `io.myotis.api` and the library's
 * default ports are used; nothing below them.
 *
 * Every start, stop, pause and resume runs on one control thread, in the order asked: the last
 * one asked for a network is what it ends in. The engine itself is created by the first start,
 * and without its library (or with one that fails) every network reads as without a light
 * client ([EngineSnapshot.Down], final). A network whose start fails reads the same way
 * ([EngineSnapshot.Failed]), and is tried again the next time it is asked to run.
 */
class MyotisHost private constructor(
    private val dataDir: File,
    private val dnsServers: DnsServers?,
    lastBalancesPath: String?,
    private val debugLog: ((String) -> Unit)?,
    private val apiLog: LogSink?,
) : LightClientControl, StaleAnchorConsent, NetworkFreshness {

    /** The engine of this process: none until a first start, [Absent] when its library does not run here. */
    private sealed interface EngineState {
        data object NotCreated : EngineState
        data object Absent : EngineState
        class Created(val engine: MyotisEngine) : EngineState
    }

    /** The control thread's: the engine, created at the first start. */
    private var engine: EngineState = EngineState.NotCreated

    /** Whether the host last asked for networking off; every light client started after follows it. */
    @Volatile
    private var pauseWanted = false

    @Volatile
    private var shutDown = false

    /** Runs every start, stop, pause and resume, which block while the engine works. */
    private val control = Executors.newSingleThreadExecutor { task ->
        Thread(task, "myotis-control").apply { isDaemon = true }
    }

    /** Where the engine's own log is drained into [debugLog] while the engine runs. */
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Where each network's finalized block's header is read, off the status poll ([FinalizedTimes]). */
    private val headerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /** Each network's light client, for the user to watch. */
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

    /**
     * CoW Protocol's settlement contracts on each network, proven by their code
     * (docs/swaps.md, *CoW Protocol*).
     */
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
        onControlThread {
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
        onControlThread { node.acceptStaleAnchor() }
    }

    override fun observeLive(network: Network): Flow<Boolean> {
        val node = nodes[network] ?: return flowOf(false)
        return flow {
            while (true) {
                val live = try {
                    node.live()
                } catch (e: Throwable) {
                    // Throwable: the engine's state crosses JNA. A cancellation of this poll ends it.
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
     * Turns every running light client's networking off, keeping its state, for while the app is
     * in the background. Returns at once. One started meanwhile starts paused.
     */
    fun pause() {
        pauseWanted = true
        onControlThread { nodes.values.forEach { it.pause() } }
    }

    /** Turns networking back on after [pause]; a warm start. Returns at once. */
    fun resume() {
        pauseWanted = false
        onControlThread { nodes.values.forEach { it.resume() } }
    }

    private fun onControlThread(action: () -> Unit) {
        try {
            control.execute {
                if (shutDown) return@execute
                try {
                    action()
                } catch (e: Throwable) {
                    // Throwable: the calls cross JNA. Uncaught on this thread it would end the
                    // process; the light clients keep their last state instead.
                    System.err.println("Serotine: the engine could not be switched: $e")
                }
            }
        } catch (e: RejectedExecutionException) {
            // Shut down already: nothing left to start or stop.
        }
    }

    /**
     * Stops every light client and the engine and releases the files they hold; idempotent. A
     * start in progress finishes first, waited for up to [SHUTDOWN_WAIT_MS], so a process that
     * exits right after does not cut it off mid-start. Blocks: never call it on a UI thread.
     */
    fun shutdown() {
        if (shutDown) return
        val stopped = try {
            control.submit {
                shutDown = true
                nodes.values.forEach { it.release() }
                (engine as? EngineState.Created)?.engine?.let { created ->
                    try {
                        created.shutdownAll()
                    } catch (e: Throwable) {
                        System.err.println("Serotine: the engine did not shut down cleanly: $e")
                    }
                }
                engine = EngineState.Absent
            }
        } catch (e: RejectedExecutionException) {
            null
        }
        try {
            stopped?.get(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // A start that did not end in time, or a stop that failed: the process ends anyway.
        }
        shutDown = true
        logScope.cancel()
        headerScope.cancel()
        // In a debug build, what the engine said while stopping, which the pump no longer drains.
        debugLog?.let { EngineLogPump(Myotis::drainLogs, it).drainNow() }
        control.shutdown()
    }

    /** The engine, created on the control thread at the first start; null without one on this device. */
    private fun engine(): MyotisEngine? {
        when (val current = engine) {
            is EngineState.Created -> return current.engine
            EngineState.Absent -> return null
            EngineState.NotCreated -> Unit
        }
        if (!Myotis.isAvailable()) {
            report("no engine library loads on this device")
            engine = EngineState.Absent
            return null
        }
        val created = try {
            Myotis.newEngine()
        } catch (e: Throwable) {
            // A native library that loads but cannot run (a missing symbol, an ABI mismatch).
            report("the engine failed to load: $e")
            engine = EngineState.Absent
            return null
        }
        engine = EngineState.Created(created)
        // The engine's own log, in a debug build: the one account of why a sync stalls.
        if (debugLog != null) logScope.launch { EngineLogPump(Myotis::drainLogs, debugLog).run(ENGINE_LOG_INTERVAL) }
        return created
    }

    /** One network's light client in the host's engine: its state, its handle while it runs, and its adapters. */
    private sealed interface ChainState {
        data object Booting : ChainState
        class Stopped(val off: Boolean) : ChainState
        class Running(
            val handle: ChainHandle,
            val ports: DefaultEnginePorts,
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
     * its state is the control thread's; the adapters read it from theirs.
     */
    private inner class ChainNode(private val network: Network) {

        /** Starting, until `FollowNetworks` says what the network is to do: a moment, at the app's start. */
        @Volatile
        var state: ChainState = ChainState.Booting

        /** The handle, unwrapped: what [activity] reads (in memory, so neither logged nor shared). */
        private val handleChain = HandleChain()

        /** The handle, each request written to [apiLog] when there is one, its status reads shared. */
        private val chain: EngineChain = handleChain.let { handle ->
            val logged = apiLog?.let { LoggedChain(handle, it) } ?: handle
            SharedSnapshots(FinalizedHeaders(logged, FinalizedTimes(headerScope)), SNAPSHOT_REUSE)
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
            lightClient = EngineLightClient(network, chain, Dispatchers.IO, PROGRESS_POLL_INTERVAL),
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

        /** Starts the light client, unless it runs already. Control thread; blocks while the engine starts it. */
        fun run() {
            if (state is ChainState.Running) return
            state = ChainState.Booting
            val engine = engine() ?: run {
                state = ChainState.Down
                return
            }
            state = boot(engine) ?: ChainState.Failed
            if (pauseWanted) pause()
        }

        /**
         * Stops the light client, keeping its files, and says why: [off], or idle. Control thread. A
         * device with no engine keeps saying so when the network goes idle, rather than promising a
         * start it cannot make; turned off, it reads as off, as the user asked.
         */
        fun stop(off: Boolean) {
            if (state == ChainState.Down && !off) return
            val running = state as? ChainState.Running
            // Said before the handle goes: a read in flight answers "off" rather than an engine error.
            state = ChainState.Stopped(off)
            running?.let { stopEngineChain(it) }
        }

        /** Stops it for good: the host shuts down. Control thread. */
        fun release() {
            val running = state as? ChainState.Running
            state = ChainState.Down
            running?.let { stopEngineChain(it) }
        }

        fun pause() = switch("pause") { it.pause() }

        fun resume() = switch("resume") { it.resume() }

        /**
         * Whether the user let this network sync past a stale anchor in this run: armed again on
         * each chain started later in the run, before its start (the engine's pre-consent), as the
         * engine keeps it per chain. Control thread.
         */
        private var staleAnchorConsented = false

        fun acceptStaleAnchor() {
            staleAnchorConsented = true
            switch("sync past its stale anchor") { it.acceptStaleAnchor() }
        }

        // Caught here, not around the loop over the networks, so one failing does not leave the
        // others' networking as it was. Throwable: the calls cross JNA.
        private inline fun switch(what: String, call: (ChainHandle) -> Unit) {
            switch((state as? ChainState.Running)?.handle ?: return, what, call)
        }

        private inline fun switch(handle: ChainHandle, what: String, call: (ChainHandle) -> Unit) {
            try {
                call(handle)
            } catch (e: Throwable) {
                System.err.println("Serotine: ${network.displayName}'s light client did not $what: $e")
            }
        }

        private fun stopEngineChain(running: ChainState.Running) {
            try {
                // Stops and unregisters the chain, so that a later start creates it anew.
                (engine as? EngineState.Created)?.engine?.stop(network.engineName())
            } catch (e: Throwable) {
                System.err.println("Serotine: ${network.displayName}'s light client did not stop cleanly: $e")
            } finally {
                running.ports.close() // the cache writers close whatever the engine does
            }
        }

        /** Creates and starts the network's chain in [engine]; null when it cannot, said why. */
        private fun boot(engine: MyotisEngine): ChainState.Running? {
            val name = network.engineName()
            dataDir.mkdirs()
            val dir = dataDir.toPath()
            // One directory for every network's default ports and the engine: the ports name each
            // network's peer caches and sync snapshot apart (`peers-gnosis.cache`).
            val ports = Myotis.defaultPorts(engine, dir, dir, name, dnsServers)
            // No CCIP-Read gateway: an off-chain ENS answer (EIP-3668) comes from an HTTP gateway the
            // resolver names, an off-chain source no ruling allows, and the request would tell it the
            // name and this device's address. With none, the engine refuses such a name, and says so.
            val defaults = ports.ports()
            val enginePorts = EnginePorts(
                defaults.keyStore(),
                defaults.peerCache(),
                defaults.clPeerCache(),
                defaults.dnsServers(),
                null,
                defaults.logger(),
                defaults.clock(),
            )
            val config = EngineConfig(
                name,
                0,
                0,
                0, // the network's default EL and discv5 ports; rpcPort is unused, nothing listens
                ports.syncSnapshot().toString(),
                TARGET_SNAP_PEERS,
                true, // strict state freshness
                dataDir.path,
            )
            val handle = try {
                engine.create(config, enginePorts)
            } catch (e: Throwable) {
                // An engine that refuses the config (an anchor mismatch, a bad data directory)
                // still holds the ports' cache writers: release them before giving up.
                ports.close()
                report("the engine refused ${network.displayName}: $e")
                return null
            }
            return try {
                val info = engine.availableNetworks().firstOrNull { it.name() == engine.canonicalNetworkName(name) }
                val running = ChainState.Running(handle, ports, info?.clGenesisTime() ?: 0, info?.secondsPerSlot() ?: 0)
                // Its own catch: one that throws leaves this chain to park and ask again, not the boot failed.
                if (staleAnchorConsented) switch(handle, "sync past its stale anchor") { it.acceptStaleAnchor() }
                if (handle.start()) {
                    running
                } else {
                    // The engine says why only in its own log, which is drained, not printed.
                    val refusal = EngineLogPump.lastLines(Myotis::drainLogs, ENGINE_LOG_LINES)
                    stopEngineChain(running)
                    report("the engine refused to start $name:\n$refusal")
                    null
                }
            } catch (e: Throwable) {
                try {
                    engine.stop(name)
                } catch (t: Throwable) {
                    e.addSuppressed(t)
                }
                ports.close()
                report("${network.displayName}'s light client failed to start: $e")
                null
            }
        }

        private inner class HandleChain : EngineChain, ActivityChain {

            // Each call a status read; SharedSnapshots shares one among the pollers.
            override fun snapshot(): EngineSnapshot {
                val running = when (val current = state) {
                    ChainState.Booting -> return EngineSnapshot.Booting
                    ChainState.Down -> return EngineSnapshot.Down
                    ChainState.Failed -> return EngineSnapshot.Failed
                    is ChainState.Stopped -> return EngineSnapshot.Stopped(current.off)
                    is ChainState.Running -> current
                }
                val handle = running.handle
                val status = handle.status() ?: return EngineSnapshot.Booting
                // The head block's time from its slot, the consensus rule for an execution
                // payload's timestamp (genesis + slot × seconds per slot): verified data only. The
                // optimistic slot is the head block's own, not the later signature slot (Myotis #658).
                val slot = handle.beaconStatus()?.optimisticSlot() ?: 0
                val headTimestamp = if (slot > 0 && running.genesisTime > 0 && running.secondsPerSlot > 0) {
                    running.genesisTime + slot * running.secondsPerSlot
                } else {
                    null
                }
                return EngineSnapshot.Running(
                    phase = when (status.beaconState()) {
                        BeaconState.SYNCING -> EngineSnapshot.Phase.SYNCING
                        BeaconState.CATCHING_UP -> EngineSnapshot.Phase.CATCHING_UP
                        BeaconState.SYNCED -> EngineSnapshot.Phase.SYNCED
                        BeaconState.STALE_ANCHOR -> EngineSnapshot.Phase.STALE_ANCHOR
                        BeaconState.STARTING, null -> EngineSnapshot.Phase.STARTING
                    },
                    paused = status.lifecycle() == LifecycleState.PAUSED,
                    syncStartPeriod = status.syncStartPeriod(),
                    syncCurrentPeriod = status.syncCurrentPeriod(),
                    syncTargetPeriod = status.syncTargetPeriod(),
                    huntingLightClientServers = status.lcHunting(),
                    proofPeers = status.snapServingPeers(),
                    headBlock = status.optimisticBlockNumber(),
                    headTimestamp = headTimestamp,
                    headAgeMs = status.verifiedHeadAgeMs(),
                    wsBoundPeriods = status.wsBoundPeriods(),
                    upgrade = status.upgradeAdvisory()?.toNetworkUpgrade(),
                    finalizedBlock = status.finalizedBlockNumber(),
                    // Filled by FinalizedHeaders, which reads the header through this chain.
                    finalizedHeader = null,
                )
            }

            // requestAccount, not VerifiedReads.getBalance: it returns the verdict and the block
            // the proof anchors to, so a reading is labelled with where it was proven. (A block
            // number passed to getBalance is answered from the current head anyway.)
            override fun proveBalance(address: Address): ProvenBalance? {
                val handle = (state as? ChainState.Running)?.handle ?: return null
                val result = handle.requestAccount(address.hex)
                if (result.failReason() != null || result.verifyMethod() == null) return null
                // A proven absence is a proven zero.
                return ProvenBalance(
                    if (result.exists()) result.balanceWei() ?: return null else "0",
                    result.blockNumber(),
                    result.blockHashHex()?.lowercase(),
                )
            }

            // The code hash comes with the account proof, labelled with its block; the code itself
            // from the verified reads; provenCode decides what of it is proven.
            override fun proveCode(address: Address): ProvenCode? {
                val handle = (state as? ChainState.Running)?.handle ?: return null
                val account = handle.requestAccount(address.hex)
                if (account.failReason() != null || account.verifyMethod() == null) return null
                val anchor = accountProofAnchor(
                    account.verifyMethod(),
                    account.matchedBeaconSlot(),
                    account.blockNumber(),
                )
                return provenCode(account.exists(), account.codeHashHex(), account.blockNumber(), anchor) {
                    handle.reads().getCode(address.hex.removePrefix("0x").fromHex(), "latest")
                }
            }

            override fun call(to: Address, data: ByteArray): CallOutcome? {
                val handle = (state as? ChainState.Running)?.handle ?: return null
                val contract = to.hex.removePrefix("0x").fromHex()
                return callOutcome(handle.reads().callDetailed(null, contract, data, null, "latest", null))
            }

            // Accepted only with a verified storage proof tied to the beacon chain and a verdict, as
            // balances are; a proven empty slot is a proven zero. Labelled as storageProofLabel says:
            // shown with the block the proof anchors to once it matched an attested block's slot from
            // the finalized one up to the head the reads resolved, or that a header-chain walk proved;
            // and anchored to that attested slot, which is what one reading's proofs must share.
            override fun proveSlot(address: Address, slot: Long, holder: Address?): ProvenSlot? {
                val handle = (state as? ChainState.Running)?.handle ?: return null
                val result = handle.getStorageProof(address.hex, slot, holder?.hex)
                val verified = result.storageProofValid() && result.beaconChainVerified()
                if (result.failReason() != null || !verified) return null
                val label = storageProofLabel(
                    verifyMethod = result.verifyMethod(),
                    matchedBeaconSlot = result.matchedBeaconSlot(),
                    finalizedSlot = result.finalizedSlot(),
                    optimisticSlot = result.optimisticSlot(),
                    peerBlockNumber = result.peerBlockNumber(),
                    finalizedBlockNumber = result.finalizedBlockNumber(),
                ) ?: return null
                val value = if (result.exists()) result.valueHex()?.removePrefix(
                        "0x",
                    )?.lowercase() ?: return null else ""
                return value.padStart(WORD_HEX_DIGITS, '0').takeIf { it.length == WORD_HEX_DIGITS }
                    ?.let { ProvenSlot(it, label.blockNumber, label.anchor) }
            }

            override fun storageAt(address: Address, slot: ByteArray): String? {
                val handle = (state as? ChainState.Running)?.handle ?: return null
                val contract = address.hex.removePrefix("0x").fromHex()
                val word = handle.reads().getStorageAt(contract, slot, "latest") ?: return null
                return word.toHex().padStart(WORD_HEX_DIGITS, '0').takeIf { it.length == WORD_HEX_DIGITS }
            }

            override fun blockByNumber(number: Long): String? {
                val handle = (state as? ChainState.Running)?.handle ?: return null
                return handle.reads().getBlockByNumber("0x" + number.toString(16), false)
            }

            // "pending": the verified count plus the sends this node made that are not mined yet,
            // so a second transfer right after a first does not reuse its nonce.
            override fun pendingNonce(address: Address): Long? {
                val handle = (state as? ChainState.Running)?.handle ?: return null
                return handle.reads().getTransactionCount(address.bytes(), "pending")
            }

            override fun minedNonce(address: Address): Long? {
                val handle = (state as? ChainState.Running)?.handle ?: return null
                return handle.reads().getTransactionCount(address.bytes(), "latest")
            }

            override fun feeHistory(blocks: Int, percentiles: DoubleArray): String? {
                val handle = (state as? ChainState.Running)?.handle ?: return null
                return handle.reads().feeHistory(blocks.toLong(), "latest", percentiles)
            }

            override fun estimateGas(from: Address, to: Address, valueWei: String, data: ByteArray): EstimateAnswer {
                val handle = (state as? ChainState.Running)?.handle ?: return EstimateAnswer.Unavailable(
                    "no engine running",
                )
                return estimateAnswer(handle.reads().estimateGasDetailed(from.bytes(), to.bytes(), data, valueWei))
            }

            override fun callFrom(from: Address, to: Address, valueWei: String, data: ByteArray): CallOutcome? {
                val handle = (state as? ChainState.Running)?.handle ?: return null
                val result = handle.reads().callDetailed(from.bytes(), to.bytes(), data, valueWei, "latest", null)
                return callOutcome(result)
            }

            override fun sendRaw(raw: ByteArray): SendAnswer {
                val handle = (state as? ChainState.Running)?.handle
                    ?: return SendAnswer.Unavailable("the light client is not running")
                return sendAnswer(handle.reads().sendRawTransactionDetailed(raw))
            }

            override fun receipt(hash: String): String? {
                val handle = (state as? ChainState.Running)?.handle ?: return null
                return handle.reads().getTransactionReceipt(hash.removePrefix("0x").fromHex())
            }

            override fun setLogIndexConfig(configJson: String): Boolean {
                val handle = (state as? ChainState.Running)?.handle ?: return false
                return handle.setLogIndexConfig(configJson)
            }

            override fun logIndexStatus(): String? {
                val handle = (state as? ChainState.Running)?.handle ?: return null
                return handle.logIndexStatusJson()
            }

            override fun logs(filterJson: String): String? {
                val handle = (state as? ChainState.Running)?.handle ?: return null
                return handle.reads().getLogs(filterJson)
            }

            override fun setActivityWatch(configJson: String): Boolean {
                val handle = (state as? ChainState.Running)?.handle ?: return false
                return handle.setActivityWatch(configJson)
            }

            override fun addressActivity(afterBlock: Long): String? {
                val handle = (state as? ChainState.Running)?.handle ?: return null
                return handle.addressActivityJson(afterBlock)
            }

            // FINALIZED only: PEER_HEAD would be a peer's claim, and AUTO falls back to it.
            override fun resolveName(name: String): NameAnswer {
                val handle = (state as? ChainState.Running)?.handle
                    ?: return NameAnswer(null, -1, verified = false, error = "the light client is not running")
                return nameAnswerOf { handle.ens().resolveAddress(name, EnsRoot.FINALIZED) }
            }
        }
    }

    companion object {
        /** How often the verified reads poll the engine; internal for `SafeBudgetsTest`. */
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
        private const val SHUTDOWN_WAIT_MS = 15_000L

        /** Snap peers the engine keeps for state proofs; the library guide's mobile setting. */
        private const val TARGET_SNAP_PEERS = 3

        /**
         * A host for the networks' light clients with their state under [dataDir]. Nothing runs
         * until [set] starts a network, on the control thread: loading the native library,
         * creating the engine and starting a chain all block, and none of it may run on a
         * caller's main thread. [dnsServers] returns the host's resolver IPs for EIP-1459 peer
         * discovery, which a mobile host must supply (null where the engine reads the system's);
         * a plain function, so no Myotis type leaves this module. [debugLog] takes the adapters'
         * diagnostics and, while the engine runs, the engine's own log ([EngineLogPump]): a debug
         * build's log, null in a release build. Public chain data only; no key material is on any
         * path that writes to it. [lastBalances] is the file the balances last verified are kept
         * in ([LastBalancesFile]); null keeps them for the process only. [apiLog] takes each
         * request the adapters make of the engine ([LoggedChain]): the Debug tab's log, in memory
         * only; null records none.
         *
         * A device without an engine — an Android ABI the library does not ship, a desktop whose
         * resources carry no host library (Windows, for now) — or one whose engine fails to start
         * gets light clients that say so.
         */
        fun start(
            dataDir: File,
            dnsServers: (() -> List<String>)? = null,
            lastBalances: String? = null,
            debugLog: ((String) -> Unit)? = null,
            apiLog: LogSink? = null,
        ): MyotisHost = MyotisHost(
            dataDir,
            dnsServers?.let { servers -> DnsServers { servers() } },
            lastBalances,
            debugLog,
            apiLog,
        )
    }
}

/**
 * Why there is no light client, on stderr (logcat's System.err on Android): the one fact a
 * user's "No light client" cannot tell a developer. Engine state only; no key material is
 * anywhere near this path.
 */
private fun report(why: String) = System.err.println("Serotine: no light client: $why")
