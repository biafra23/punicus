package eth.serotine.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import eth.serotine.domain.repository.ConnectionCost
import eth.serotine.domain.repository.ConnectionMeter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * [ConnectionMeter] over Android's ConnectivityManager: the default network is
 * [ConnectionCost.UNMETERED] when it has `NET_CAPABILITY_NOT_METERED`, what
 * `isActiveNetworkMetered()` reads too, so a VPN that does not declare itself unmetered counts as
 * metered, as it does for every other app. No network, or capabilities that cannot be read, are
 * [ConnectionCost.METERED]: a read error never lets a heavy download run. Followed with a default
 * network callback while collected, and unregistered when collection stops. Needs
 * ACCESS_NETWORK_STATE.
 */
class AndroidConnectionMeter(context: Context) : ConnectionMeter {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun observe(): Flow<ConnectionCost> = callbackFlow {
        val connectivity = connectivity
        if (connectivity == null) {
            send(ConnectionCost.METERED)
            awaitClose()
            return@callbackFlow
        }
        trySend(costNow(connectivity))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(costOf(capabilities))
            }

            // Called only once no default network is left: none is metered, as a read error is.
            override fun onLost(network: Network) {
                trySend(ConnectionCost.METERED)
            }
        }
        val registered = try {
            connectivity.registerDefaultNetworkCallback(callback)
            true
        } catch (e: RuntimeException) {
            // Too many callbacks, or the permission missing: a change would go unseen, so metered.
            trySend(ConnectionCost.METERED)
            false
        }
        awaitClose { if (registered) connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun costNow(connectivity: ConnectivityManager): ConnectionCost = try {
        costOf(connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities))
    } catch (e: RuntimeException) {
        ConnectionCost.METERED
    }

    private fun costOf(capabilities: NetworkCapabilities?): ConnectionCost =
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) {
            ConnectionCost.UNMETERED
        } else {
            ConnectionCost.METERED
        }
}
