package eth.serotine.domain.repository

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.UpgradeNotice
import kotlinx.coroutines.flow.Flow

/**
 * The port for what this version of the app last learned about an upgrade it does not
 * support, one record per network, kept across restarts: so reminders fall due while the app is not running, a
 * notice is shown once, and an app opened after the upgrade knows it at once. :data keeps it
 * in a file that belongs to one app version: a record written by another version reads as
 * none, since an update is what the record asks for.
 */
interface KnownUpgradeStore {
    /**
     * [network]'s record, or null when there is none; re-emitted on each [save]. Never fails: a
     * record that cannot be read is none.
     */
    fun observe(network: Network): Flow<KnownUpgrade?>

    /** Replaces [network]'s record with [record]; false when it could not be stored. Never throws. */
    suspend fun save(network: Network, record: KnownUpgrade): Boolean
}

/** An [upgrade] peers reported, and the notices about it the user has been [shown] already. */
data class KnownUpgrade(val upgrade: NetworkUpgrade, val shown: Set<UpgradeNotice.Kind>)
