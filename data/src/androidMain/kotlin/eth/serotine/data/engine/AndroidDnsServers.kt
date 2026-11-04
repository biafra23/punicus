package eth.serotine.data.engine

import android.content.Context
import android.net.ConnectivityManager

/**
 * The DNS servers of the active network, for the engine's EIP-1459 peer discovery: Android
 * has no resolver configuration the engine could read itself, so the host supplies it to
 * [MyotisHost.start] (docs/android-library.md in Myotis). Read on each call, so a network
 * change is picked up. Needs ACCESS_NETWORK_STATE.
 */
class AndroidDnsServers(context: Context) : () -> List<String> {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun invoke(): List<String> {
        val network = connectivity?.activeNetwork ?: return emptyList()
        return connectivity.getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
    }
}
