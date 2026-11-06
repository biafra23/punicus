package eth.serotine.data.settings

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.SyncPolicy
import eth.serotine.domain.repository.SyncPolicySetting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [SyncPolicySetting] kept in the file at [path] (a [VersionedJsonFile]): whether the user chose
 * *Sign while syncing* in Settings → Advanced (docs/lenient-mode.md). No file, a damaged one (set
 * aside), one that cannot be read and a policy this build does not know, written by a newer one,
 * are all strict mode, the default in every build (owner rulings, 2026-11-06).
 */
class FileSyncPolicy(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SyncPolicySetting {

    // Wrapped, never null: the file reads a null value as damaged.
    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "sync policy",
        formatVersion = FORMAT_VERSION,
        empty = Saved(SyncPolicy.STRICT),
        decode = { _, text ->
            val stored = JSON.decodeFromString(StoredPolicy.serializer(), text)
            Saved(SyncPolicy.entries.firstOrNull { it.code == stored.policy } ?: SyncPolicy.STRICT)
        },
        encode = { saved -> JSON.encodeToString(StoredPolicy.serializer(), StoredPolicy(FORMAT_VERSION, saved.policy.code)) },
    )

    override fun observe(): Flow<SyncPolicy> = file.observe().map { read ->
        (read as? VersionedJsonFile.Read.Loaded)?.value?.policy ?: SyncPolicy.STRICT
    }

    override suspend fun set(policy: SyncPolicy): Boolean =
        file.change(notSaved = false) { _ -> Decision(true, next = Saved(policy)) }

    /** What the file holds. */
    private data class Saved(val policy: SyncPolicy)

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredPolicy(val version: Int, val policy: String)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json

        /** How a policy is written: a name of its own, not the enum's, so a rename changes no file. */
        val SyncPolicy.code: String
            get() = when (this) {
                SyncPolicy.STRICT -> "strict"
                SyncPolicy.LENIENT -> "lenient"
            }
    }
}
