package eth.serotine.data.upgrade

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.UpgradeNotice
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [KnownUpgradeStore] kept in the file at [path], one record per network, for the app version
 * [appVersion]: a record
 * another version wrote reads as none and is replaced by the next save, since updating the
 * app is what the record asks for, and an updated app hears from its own engine whether its
 * build supports the upgrade. Peer reports only (times, fork ids, what the user was told):
 * nothing in it is key material or chain state.
 *
 * Not the account list: a record that cannot be read, or a damaged one, reads as none rather
 * than being kept aside, as losing it costs at most a notice shown again. Written whole, to a
 * temporary file moved over the old one, on [dispatcher]. One process writes it (Android runs
 * one, the desktop holds its instance lock).
 */
class FileKnownUpgradeStore(
    path: String,
    private val appVersion: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : KnownUpgradeStore {

    private val file = Path(path)

    /** Null until the file has been read. */
    private val state = MutableStateFlow<Read?>(null)
    private val lock = Mutex()

    /** What the file holds: each network's [records], none for a network without one. */
    private data class Read(val records: Map<Network, KnownUpgrade>)

    override fun observe(network: Network): Flow<KnownUpgrade?> = state
        .onStart { if (state.value == null) lock.withLock { if (state.value == null) state.value = Read(load()) } }
        .filterNotNull()
        .map { it.records[network] }
        .distinctUntilChanged()

    override suspend fun save(network: Network, record: KnownUpgrade): Boolean = lock.withLock {
        // Not cancellable: a written record must also be what everyone observes, and the other
        // networks' records read first are part of what is written.
        withContext(NonCancellable) {
            val next = (state.value?.records ?: load()) + (network to record)
            write(next).also { if (it) state.value = Read(next) }
        }
    }

    /**
     * Every network's record. A file of another format, or written by another app version,
     * holds none: format 1, from before networks, too, since an update is what it asked for.
     */
    private suspend fun load(): Map<Network, KnownUpgrade> = withContext(dispatcher) {
        if (!SystemFileSystem.exists(file)) return@withContext emptyMap()
        try {
            val text = SystemFileSystem.source(file).buffered().use { it.readString() }
            val stored = JSON.decodeFromString(StoredRecords.serializer(), text)
            if (stored.version != FORMAT_VERSION || stored.appVersion != appVersion) {
                emptyMap()
            } else {
                // A network this version does not know is left out, as is one record of a network twice.
                stored.records.mapNotNull { record ->
                    Network.ofChainId(record.chain)?.let { it to record.toKnownUpgrade() }
                }.toMap()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("the upgrade record at $file cannot be used (${e::class.simpleName}); it is read as none")
            emptyMap()
        }
    }

    private suspend fun write(records: Map<Network, KnownUpgrade>): Boolean = withContext(dispatcher) {
        val temporary = Path("$file.${Random.nextLong().toULong().toString(16)}.tmp")
        try {
            file.parent?.let { SystemFileSystem.createDirectories(it) }
            val stored = StoredRecords(
                FORMAT_VERSION,
                appVersion,
                records.map { (network, record) -> StoredRecord(network, record) },
            )
            val text = JSON.encodeToString(StoredRecords.serializer(), stored)
            SystemFileSystem.sink(temporary).buffered().use { it.writeString(text) }
            SystemFileSystem.atomicMove(temporary, file)
            true
        } catch (e: Exception) {
            report("could not save the upgrade record to $file (${e::class.simpleName})")
            try {
                if (SystemFileSystem.exists(temporary)) SystemFileSystem.delete(temporary)
            } catch (cleanup: Exception) {
                // Nothing more to do: a stray temporary file is harmless.
            }
            false
        }
    }

    /** The file's layout. [version] is the format's, [appVersion] the app's that wrote it. */
    @Serializable
    private class StoredRecords(val version: Int, val appVersion: String, val records: List<StoredRecord>)

    /** One network's record: the network of [chain]'s. */
    @Serializable
    private class StoredRecord(
        val chain: Long,
        val phase: String,
        val activationTime: Long? = null,
        val forkId: String? = null,
        val reportingNetworks: Int,
        val shown: List<String>,
    ) {
        constructor(network: Network, record: KnownUpgrade) : this(
            chain = network.chainId,
            phase = record.upgrade.phase.name,
            activationTime = record.upgrade.activationTime,
            forkId = record.upgrade.forkId,
            reportingNetworks = record.upgrade.reportingNetworks,
            shown = record.shown.map { it.name }.sorted(),
        )

        /** Throws on anything the domain would not accept: the file is then read as none. */
        fun toKnownUpgrade() = KnownUpgrade(
            NetworkUpgrade(NetworkUpgrade.Phase.valueOf(phase), activationTime, forkId, reportingNetworks),
            shown.mapTo(HashSet()) { UpgradeNotice.Kind.valueOf(it) },
        )
    }

    private companion object {
        /** 2 since networks: one record per network, by its chain id. */
        const val FORMAT_VERSION = 2
        val JSON = Json

        fun report(what: String) = println("Serotine: $what")
    }
}
