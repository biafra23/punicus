package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientControl
import eth.serotine.domain.repository.LightClientRun
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.TransactionHistory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The networks that are on in this wallet, in [Network] order (the order of every list on
 * screen), re-emitted when one is turned on or off.
 */
class ObserveEnabledNetworks(private val settings: NetworkSettings) {
    operator fun invoke(): Flow<List<Network>> = settings.observe()
        .map { on -> Network.entries.filter { it in on } }
        .distinctUntilChanged()
}

/** Turns a network on or off (docs/networks.md, *Networks on and off*); false when that could not be saved. */
class SetNetworkEnabled(private val settings: NetworkSettings) {
    suspend operator fun invoke(network: Network, on: Boolean): Boolean = settings.setEnabled(network, on)
}

/**
 * The screens that need a network's light client while they are open, though no account of the
 * wallet is on it (Watch checking an address, Add token reading a contract): one per process,
 * shared by the screens and [FollowNetworks]. A hold keeps its network running, if it is on,
 * until it is released.
 */
class NetworkDemand {
    private val holds = MutableStateFlow<Map<Network, Int>>(emptyMap())

    /** The networks some screen holds now, re-emitted when that changes. */
    fun observe(): Flow<Set<Network>> = holds.map { it.filterValues { count -> count > 0 }.keys }.distinctUntilChanged()

    /** Holds [network] until the [NetworkHold] returned is released (once; a second release does nothing). */
    fun hold(network: Network): NetworkHold {
        holds.update { it + (network to (it[network] ?: 0) + 1) }
        return NetworkHold { holds.update { it + (network to ((it[network] ?: 1) - 1)) } }
    }
}

/** A screen's hold on a network ([NetworkDemand.hold]); [release] ends it, once. */
class NetworkHold internal constructor(private val onRelease: () -> Unit) {
    private var released = false

    /** Ends the hold; a second call does nothing. */
    fun release() {
        if (released) return
        released = true
        onRelease()
    }
}

/** Holds a network's light client for a screen while it is open ([NetworkDemand]). */
class HoldNetwork(private val demand: NetworkDemand) {
    operator fun invoke(network: Network): NetworkHold = demand.hold(network)
}

/**
 * Keeps each network's light client doing what the wallet needs of it (docs/networks.md, *The
 * engine host*), for as long as it runs (the hosts run it while the engine does, never `App`):
 *
 * - a network turned off ([NetworkSettings]) is [LightClientRun.OFF] at once;
 * - one that is on runs while an account of the wallet is on it, or a screen holds it
 *   ([NetworkDemand]); an account list that cannot be read counts as having accounts on every
 *   network that is on, as nothing says otherwise;
 * - one that is on and needed by nothing is [LightClientRun.IDLE], [idleAfter] after it last was
 *   needed (an account removed, a screen closed): a screen opened again meanwhile keeps it
 *   running. One never needed since the start is idle at once.
 */
class FollowNetworks(
    private val settings: NetworkSettings,
    private val accounts: AccountRepository,
    private val demand: NetworkDemand,
    private val control: LightClientControl,
    private val idleAfter: Duration = 30.seconds,
) {
    /** Runs until cancelled. */
    suspend operator fun invoke(): Unit = coroutineScope {
        val wanted = combine(settings.observe(), accounts.observeAccounts(), demand.observe()) { on, list, held ->
            Network.entries.associateWith { network ->
                val needed = network in held || when (list) {
                    is AccountList.Loaded -> list.accounts.any { it.network == network }
                    is AccountList.Unreadable -> true
                }
                when {
                    network !in on -> LightClientRun.OFF
                    needed -> LightClientRun.RUN
                    else -> LightClientRun.IDLE
                }
            }
        }
        Network.entries.forEach { network ->
            launch {
                // What the network was last told: it is told again only what changes it.
                var told: LightClientRun? = null
                wanted.map { it.getValue(network) }.distinctUntilChanged().collectLatest { run ->
                    if (run == told) return@collectLatest
                    // Cancelled by the next change: a network needed again within the delay keeps running.
                    if (run == LightClientRun.IDLE && told == LightClientRun.RUN) delay(idleAfter)
                    control.set(network, run)
                    told = run
                }
            }
        }
    }
}

/**
 * Each network as Settings → Networks shows it (docs/networks.md, N16 and N20), in [Network]
 * order: whether it is on, the wallet's accounts on it, its light client, and what waits for it
 * if it is turned off. Re-emitted when any of them changes. [clock] is in unix seconds.
 */
class ObserveNetworkOverview(
    private val settings: NetworkSettings,
    private val accounts: AccountRepository,
    private val lightClient: LightClient,
    private val history: TransactionHistory,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    operator fun invoke(): Flow<List<NetworkOverview>> {
        val statuses = combine(Network.entries.map { lightClient.observeStatus(it) }) { it.toList() }
        return combine(settings.observe(), accounts.observeAccounts(), statuses, history.observe()) {
                on, list, status, sent ->
            val listed = (list as? AccountList.Loaded)?.accounts
            val entries = (sent as? HistoryList.Loaded)?.entries.orEmpty()
            val now = clock()
            // What is still followed, and would wait: a receipt for FOLLOW_SECONDS, a deployment's
            // listing for DEPLOYMENT_LIFETIME_SECONDS (FollowSafeDeployments gives up after that).
            val followed = now - FOLLOW_SECONDS
            val listing = now - DEPLOYMENT_LIFETIME_SECONDS
            Network.entries.mapIndexed { i, network ->
                val onNetwork = entries.filter { it.transfer.network == network }
                NetworkOverview(
                    network = network,
                    on = network in on,
                    accounts = listed?.filter { it.network == network },
                    status = status[i],
                    unconfirmed = onNetwork.count { entry ->
                        entry.outcome == SentOutcome.NotSeen && entry.transfer.sentAt >= followed
                    },
                    unlistedSafes = onNetwork.count { entry ->
                        entry.transfer.sentAt >= listing &&
                            (entry.transfer.action as? SentAction.SafeDeployment)?.let { it.listed == null } == true
                    },
                )
            }
        }.distinctUntilChanged()
    }
}

/**
 * One network as Settings → Networks shows it: whether it is [on]; the wallet's [accounts] on it
 * (null while the list cannot be read); its light client's [status]; and what waits for it if it
 * is turned off: transactions sent from it that no block was seen to take yet ([unconfirmed]), and
 * Safes deployed on it that are not listed yet ([unlistedSafes]), each only while it is still
 * followed. Following them waits until it is on again; the records keep them.
 */
data class NetworkOverview(
    val network: Network,
    val on: Boolean,
    val accounts: List<Account>?,
    val status: LightClientStatus,
    val unconfirmed: Int,
    val unlistedSafes: Int,
)
