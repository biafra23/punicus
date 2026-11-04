package eth.serotine.data.settings

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.repository.AmountsHiddenSetting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [AmountsHiddenSetting] kept in the file at [path] (a [VersionedJsonFile]): whether the user closed
 * the eye that hides amounts (docs/wallet-ux.md, *Hiding amounts*). No file, a damaged one (set
 * aside), one that cannot be read and one a newer build wrote all show amounts, the default.
 */
class FileAmountsHidden(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AmountsHiddenSetting {

    // Wrapped, never null: the file reads a null value as damaged.
    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "hidden amounts",
        formatVersion = FORMAT_VERSION,
        empty = Saved(false),
        decode = { _, text -> Saved(JSON.decodeFromString(StoredHidden.serializer(), text).hidden) },
        encode = { saved -> JSON.encodeToString(StoredHidden.serializer(), StoredHidden(FORMAT_VERSION, saved.hidden)) },
    )

    override fun observe(): Flow<Boolean> = file.observe().map { read ->
        (read as? VersionedJsonFile.Read.Loaded)?.value?.hidden ?: false
    }

    override suspend fun set(hidden: Boolean): Boolean =
        file.change(notSaved = false) { _ -> Decision(true, next = Saved(hidden)) }

    /** What the file holds. */
    private data class Saved(val hidden: Boolean)

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredHidden(val version: Int, val hidden: Boolean)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json
    }
}
