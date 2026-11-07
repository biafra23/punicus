package eth.serotine.data.network

import eth.serotine.domain.repository.ConnectionCost
import eth.serotine.domain.repository.ConnectionMeter
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * [ConnectionMeter] over iOS's Network framework (`NWPathMonitor`): the current path is
 * [ConnectionCost.UNMETERED] when it is up and neither expensive (cellular, a personal hotspot)
 * nor constrained (Low Data Mode), what iOS itself counts as data the user pays for. No path up,
 * or none reported yet, is [ConnectionCost.METERED]: a read error never lets a heavy download
 * run. Followed with a path monitor while collected, cancelled when collection stops.
 */
class IosConnectionMeter : ConnectionMeter {

    @OptIn(ExperimentalForeignApi::class)
    override fun observe(): Flow<ConnectionCost> = callbackFlow {
        // Until the monitor answers: metered, as the port says of a cost that cannot be read.
        trySend(ConnectionCost.METERED)
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { path ->
            val unmetered = path != null &&
                nw_path_get_status(path) == nw_path_status_satisfied &&
                !nw_path_is_expensive(path) &&
                !nw_path_is_constrained(path)
            trySend(if (unmetered) ConnectionCost.UNMETERED else ConnectionCost.METERED)
        }
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("eth.serotine.connection-meter", null))
        nw_path_monitor_start(monitor)
        awaitClose { nw_path_monitor_cancel(monitor) }
    }.distinctUntilChanged()
}
