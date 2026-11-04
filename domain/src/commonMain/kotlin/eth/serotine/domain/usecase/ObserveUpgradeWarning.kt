package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.NetworkUpgrades
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/**
 * What the app must tell the user about a network upgrade this version does not support:
 * the "update the app" capability. It joins what peers report now, the upgrade recorded
 * from earlier reports (so an app opened after the upgrade knows it before peers report it
 * again) and the light client's own state, and re-decides when a known activation time
 * passes, with [clock] (unix seconds) as now.
 *
 * Peers' reports are not verified, so on their own they never make the app claim it can no
 * longer verify: [UpgradeWarning.UpdateRequired], the state in which the app shows nothing
 * but "update", needs an activation peers confirmed (reported now, or recorded as active)
 * AND a light client whose own state agrees that it is not verifying (not synced, or synced
 * with a stale head) — the rule Myotis sets for every host of its upgrade advisory. A
 * recorded time that has merely passed on the clock is no such confirmation, since a fork
 * can move: that is [UpgradeWarning.Activated], a banner, as is an activation the light
 * client does not contradict. A record no peer reports any more says nothing once the light
 * client verifies after it: this version follows the network.
 */
class ObserveUpgradeWarning(
    private val network: Network,
    private val upgrades: NetworkUpgrades,
    private val known: KnownUpgradeStore,
    private val lightClient: LightClient,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<UpgradeWarning> =
        combine(
            upgrades.observeUpgrade(network),
            known.observe(network),
            lightClient.observeStatus(network),
        ) { report, record, status ->
            Inputs(report, record?.upgrade, status)
        }.transformLatest { inputs ->
            while (true) {
                val now = clock()
                emit(warningOf(inputs, now))
                // Nothing upstream changes when an activation time passes on the clock alone.
                val next = listOfNotNull(inputs.report, inputs.recorded)
                    .mapNotNull { it.activationTime }
                    .filter { it > now }
                    .minOrNull() ?: break
                delay((next - now).seconds)
            }
        }.distinctUntilChanged()

    private data class Inputs(
        val report: NetworkUpgrade?,
        val recorded: NetworkUpgrade?,
        val status: LightClientStatus,
    )

    private fun warningOf(inputs: Inputs, now: Long): UpgradeWarning {
        val (report, recorded, status) = inputs
        val upgrade = when {
            report == null -> recorded
            // The record may know a time the report does not: peers already on the new rules
            // do not say when they began.
            recorded != null && recorded.isSameUpgradeAs(report) -> recorded.updatedBy(report)
            else -> report
        } ?: return UpgradeWarning.None
        val confirmed = report != null || upgrade.phase == NetworkUpgrade.Phase.ACTIVE
        return when {
            !upgrade.hasActivated(now) -> UpgradeWarning.Ahead(upgrade)
            confirmed && status.agreesItIsNotVerifying -> UpgradeWarning.UpdateRequired(upgrade)
            report == null && status.verifies -> UpgradeWarning.None
            else -> UpgradeWarning.Activated(upgrade)
        }
    }
}

/** Whether the light client verifies fresh heads: synced, with a head young enough to read at. */
private val LightClientStatus.verifies: Boolean
    get() = this is LightClientStatus.Synced || this is LightClientStatus.WaitingForProofPeers

/**
 * Whether the light client's own state agrees that it is not verifying the chain: not synced
 * yet, or synced with a head that has gone stale. Asleep (networking off on purpose), a network
 * turned off or idle, and no light client at all say nothing either way.
 */
private val LightClientStatus.agreesItIsNotVerifying: Boolean
    get() = when (this) {
        LightClientStatus.Starting,
        is LightClientStatus.Bootstrapping,
        is LightClientStatus.CatchingUp,
        is LightClientStatus.WaitingForHead,
        is LightClientStatus.StaleAnchor,
        -> true
        is LightClientStatus.Synced,
        is LightClientStatus.WaitingForProofPeers,
        LightClientStatus.Sleeping,
        LightClientStatus.Unavailable,
        LightClientStatus.Off,
        LightClientStatus.Idle,
        -> false
    }

/** What [ObserveUpgradeWarning] emits. */
sealed interface UpgradeWarning {

    /** No upgrade this version does not support is reported or recorded. */
    data object None : UpgradeWarning

    /** [upgrade] lies ahead, at its activation time or one not known yet: update before then. */
    data class Ahead(val upgrade: NetworkUpgrade) : UpgradeWarning

    /**
     * [upgrade] has activated, as peers report or by its announced time on the clock, and the
     * light client's own state does not show this version cut off: update before it is.
     */
    data class Activated(val upgrade: NetworkUpgrade) : UpgradeWarning

    /**
     * [upgrade] has activated and the light client is not verifying: this version can no
     * longer follow the network. The app shows nothing but "update".
     */
    data class UpdateRequired(val upgrade: NetworkUpgrade) : UpgradeWarning
}

/**
 * Every network's [UpgradeWarning] at once (docs/networks.md, *Upgrades and the update screen*):
 * each one's from [ObserveUpgradeWarning], with the networks the wallet follows, those that are on
 * ([settings]) and have accounts ([accounts]); in a wallet with no account (or whose list cannot
 * be read), those that are on. The update screen, which replaces every other, is for when
 * **every** network followed can no longer be followed ([UpgradeWarnings.updateRequired]); a
 * network blocked alone is that network's to say, in its own section. A network that is off, or
 * without accounts beside one with them, never counts towards it.
 */
class ObserveUpgradeWarnings(
    private val upgrades: NetworkUpgrades,
    private val known: KnownUpgradeStore,
    private val lightClient: LightClient,
    private val settings: NetworkSettings,
    private val accounts: AccountRepository,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    operator fun invoke(): Flow<UpgradeWarnings> {
        val each = Network.entries.map { network ->
            ObserveUpgradeWarning(network, upgrades, known, lightClient, clock)().map { network to it }
        }
        val warnings = combine(each) { pairs -> pairs.toMap() }
        // Networks with accounts; none while the list cannot be read, when every one that is on is followed.
        val held = accounts.observeAccounts().map { list ->
            (list as? AccountList.Loaded)?.accounts?.mapTo(HashSet()) { it.network }.orEmpty()
        }
        return combine(warnings, settings.observe(), held) { byNetwork, on, withAccounts ->
            val followed = Network.entries.filterTo(LinkedHashSet()) {
                it in on && (withAccounts.isEmpty() || it in withAccounts)
            }
            UpgradeWarnings(byNetwork, followed)
        }.distinctUntilChanged()
    }
}

/**
 * Each network's upgrade warning ([byNetwork]), and the networks the wallet [followed]: on, with
 * accounts.
 */
data class UpgradeWarnings(val byNetwork: Map<Network, UpgradeWarning>, val followed: Set<Network>) {

    /**
     * What makes the update screen: every network followed, at least one, can no longer be
     * followed, each with its upgrade; empty otherwise.
     */
    val updateRequired: Map<Network, UpgradeWarning.UpdateRequired>
        get() {
            val blocked = followed.associateWith { byNetwork[it] as? UpgradeWarning.UpdateRequired }
            return if (blocked.isNotEmpty() && blocked.values.all { it != null }) {
                blocked.mapValues { (_, warning) -> checkNotNull(warning) }
            } else {
                emptyMap()
            }
        }

    /** [network]'s warning; [UpgradeWarning.None] for one not reported. */
    fun of(network: Network): UpgradeWarning = byNetwork[network] ?: UpgradeWarning.None
}
