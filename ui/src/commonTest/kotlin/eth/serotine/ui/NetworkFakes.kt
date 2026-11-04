package eth.serotine.ui

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.usecase.ObserveUpgradeWarnings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * A [NetworkSettings] in memory, Sepolia alone on (a debug build's default, what the tests' accounts
 * are on) unless a test sets [on]; [failSaves] makes it fail.
 */
internal class MemoryNetworkSettings(initial: Set<Network> = setOf(Network.SEPOLIA)) : NetworkSettings {
    val on = MutableStateFlow(initial)
    var failSaves = false

    override fun observe(): Flow<Set<Network>> = on

    override suspend fun setEnabled(network: Network, on: Boolean): Boolean {
        if (failSaves) return false
        this.on.value = if (on) this.on.value + network else this.on.value - network
        return true
    }
}

/** Peers' upgrade reports, per network: none until a test sets one in [reports]. */
internal class FakeUpgradeReports : NetworkUpgrades {
    val reports = Network.entries.associateWith { MutableStateFlow<NetworkUpgrade?>(null) }

    override fun observeUpgrade(network: Network): Flow<NetworkUpgrade?> = reports.getValue(network)
}

/** No upgrade recorded on any network. */
internal object NoKnownUpgrades : KnownUpgradeStore {
    override fun observe(network: Network): Flow<KnownUpgrade?> = flowOf(null)

    override suspend fun save(network: Network, record: KnownUpgrade): Boolean = error("not used by these tests")
}

/**
 * [ObserveUpgradeWarnings] over [reports], with no record, every network's light client [status]
 * (synced by default), and [settings] and [accounts] for the networks followed.
 */
internal fun upgradeWarnings(
    settings: NetworkSettings,
    accounts: AccountRepository,
    reports: FakeUpgradeReports = FakeUpgradeReports(),
    status: Flow<LightClientStatus> = flowOf(LightClientStatus.Synced(9, null, finalized = null)),
) = ObserveUpgradeWarnings(
    reports,
    NoKnownUpgrades,
    object : LightClient {
        override val datesFinalizedBlocks = true

        override fun observeStatus(network: Network): Flow<LightClientStatus> = status
    },
    settings,
    accounts,
)
