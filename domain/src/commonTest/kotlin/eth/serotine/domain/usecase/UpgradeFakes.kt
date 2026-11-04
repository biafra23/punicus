package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.UpgradeNotice
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.repository.UpgradeNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Sepolia's Glamsterdam, as its fork id and activation time were published. */
internal val glamsterdam = NetworkUpgrade(NetworkUpgrade.Phase.SCHEDULED, 1_791_294_816, "0x6c1d9423", 5)

internal const val DAY = 24 * 60 * 60L

internal class FakeNetworkUpgrades : NetworkUpgrades {
    val report = MutableStateFlow<NetworkUpgrade?>(null)
    override fun observeUpgrade(network: Network): Flow<NetworkUpgrade?> = report
}

internal class FakeKnownUpgradeStore : KnownUpgradeStore {
    val record = MutableStateFlow<KnownUpgrade?>(null)
    var saves = 0
    var failing = false

    /**
     * The caller is cancelled while the record is written, and this store surfaces it as the
     * save returns, the record already written: what a store written as a bare
     * `withContext(Dispatchers.IO)` does. FileKnownUpgradeStore, under NonCancellable, returns
     * normally instead; UpgradeNotices must hold up under either.
     */
    var cancelledDuringSave = false

    override fun observe(network: Network): Flow<KnownUpgrade?> = record

    override suspend fun save(network: Network, record: KnownUpgrade): Boolean {
        if (failing) return false
        saves++
        this.record.value = record
        if (cancelledDuringSave) throw CancellationException("the check was cancelled during the write")
        return true
    }
}

internal class FakeLightClient : LightClient {
    val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)
    override var datesFinalizedBlocks: Boolean = true
    override fun observeStatus(network: Network): Flow<LightClientStatus> = status
}

internal class FakeNotifier : UpgradeNotifier {
    val shown = mutableListOf<UpgradeNotice>()
    val reminders = mutableListOf<Long?>()

    override fun show(notice: UpgradeNotice) {
        shown += notice
    }

    override fun remindAt(at: Long?) {
        reminders += at
    }
}
