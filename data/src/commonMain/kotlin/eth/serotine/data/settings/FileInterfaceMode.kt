package eth.serotine.data.settings

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.repository.InterfaceModeSetting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [InterfaceModeSetting] kept in the file at [path] (a [VersionedJsonFile]): the view the user
 * chose in Settings → Advanced. No file, a damaged one (set aside), one that cannot be read and a
 * view this build does not know, written by a newer one, are all simple mode, the default in
 * every build (docs/simple-ui.md).
 */
class FileInterfaceMode(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : InterfaceModeSetting {

    // Wrapped, never null: the file reads a null value as damaged.
    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "interface mode",
        formatVersion = FORMAT_VERSION,
        empty = Saved(InterfaceMode.SIMPLE),
        decode = { _, text ->
            val stored = JSON.decodeFromString(StoredMode.serializer(), text)
            Saved(InterfaceMode.entries.firstOrNull { it.code == stored.mode } ?: InterfaceMode.SIMPLE)
        },
        encode = { saved -> JSON.encodeToString(StoredMode.serializer(), StoredMode(FORMAT_VERSION, saved.mode.code)) },
    )

    override fun observe(): Flow<InterfaceMode> = file.observe().map { read ->
        (read as? VersionedJsonFile.Read.Loaded)?.value?.mode ?: InterfaceMode.SIMPLE
    }

    override suspend fun set(mode: InterfaceMode): Boolean =
        file.change(notSaved = false) { _ -> Decision(true, next = Saved(mode)) }

    /** What the file holds. */
    private data class Saved(val mode: InterfaceMode)

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredMode(val version: Int, val mode: String)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json

        /** How a view is written: a name of its own, not the enum's, so a rename changes no file. */
        val InterfaceMode.code: String
            get() = when (this) {
                InterfaceMode.SIMPLE -> "simple"
                InterfaceMode.DETAILED -> "detailed"
            }
    }
}
