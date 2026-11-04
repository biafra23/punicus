package eth.serotine.data.networks

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.NetworkSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [NetworkSettings] kept in the file at [path] (`networks.json`, a [VersionedJsonFile]): for each
 * network the wallet has decided about, whether it is on (docs/networks.md, *Networks on and
 * off*). [defaultOn] is the build's default for a network the file does not mention: the
 * composition root's to say (a release build and a debug build differ), never this adapter's to
 * guess. The first [observe] records the default of every network the file does not mention yet,
 * so a fresh install, an install updated from before the file existed and an update bringing a
 * network the file has not seen all get the defaults for what is new to them, and the user's
 * choice stands from then on. A file that cannot be read is answered with the defaults, nothing
 * recorded, until it can be read; a damaged one is set aside, and the defaults recorded anew. An
 * entry of a network this version does not know is passed over and the file kept aside as it was.
 */
class FileNetworkSettings(
    path: String,
    private val defaultOn: (Network) -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NetworkSettings {

    private val file = VersionedJsonFile<Map<Network, Boolean>>(
        path,
        dispatcher,
        what = "network settings",
        formatVersion = FORMAT_VERSION,
        empty = emptyMap(),
        decode = { _, text ->
            JSON.decodeFromString(StoredNetworks.serializer(), text).networks
                .mapNotNull { entry -> Network.ofChainId(entry.chain)?.let { it to entry.on } }
                .toMap()
        },
        encode = { recorded ->
            val stored = StoredNetworks(
                FORMAT_VERSION,
                recorded.map { (network, on) -> StoredNetwork(network.chainId, on) },
            )
            JSON.encodeToString(StoredNetworks.serializer(), stored)
        },
        incomplete = { text, recorded ->
            JSON.decodeFromString(StoredNetworks.serializer(), text).networks.size > recorded.size
        },
    )

    override fun observe(): Flow<Set<Network>> = file.observe()
        .onStart { recordDefaults() }
        .map { read ->
            val recorded = (read as? VersionedJsonFile.Read.Loaded)?.value.orEmpty()
            Network.entries.filterTo(LinkedHashSet()) { recorded[it] ?: defaultOn(it) }
        }
        .distinctUntilChanged()

    override suspend fun setEnabled(network: Network, on: Boolean): Boolean = file.change(
        notSaved = false,
    ) { recorded ->
        withDefaults(recorded).let { all ->
            if (all[network] == on && recorded.keys.containsAll(Network.entries)) {
                Decision(true)
            } else {
                Decision(true, all + (network to on))
            }
        }
    }

    /** Records the build's default of each network the file does not mention yet; nothing when it cannot be read. */
    private suspend fun recordDefaults() {
        file.change(notSaved = Unit) { recorded ->
            if (recorded.keys.containsAll(Network.entries)) Decision(Unit) else Decision(Unit, withDefaults(recorded))
        }
    }

    private fun withDefaults(recorded: Map<Network, Boolean>): Map<Network, Boolean> =
        Network.entries.associateWith { recorded[it] ?: defaultOn(it) }

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredNetworks(val version: Int, val networks: List<StoredNetwork>)

    /** Whether the network of [chain] is [on]. */
    @Serializable
    private class StoredNetwork(val chain: Long, val on: Boolean)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json
    }
}
