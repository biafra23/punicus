package eth.serotine.data.settings

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.repository.MeteredDownloadsChoice
import eth.serotine.domain.repository.MeteredDownloadsSetting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [MeteredDownloadsSetting] kept in the file at [path] (a [VersionedJsonFile]). No file is "not
 * allowed": the heavy downloads are opt-in on a metered connection. A damaged file is set aside,
 * which reads as not allowed and says so until another choice is made
 * ([MeteredDownloadsChoice.Chosen.setAside]), and one that cannot be read is
 * [MeteredDownloadsChoice.Unreadable].
 */
class FileMeteredDownloads(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MeteredDownloadsSetting {

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "metered downloads setting",
        formatVersion = FORMAT_VERSION,
        empty = Saved(allowed = null),
        decode = { _, text -> Saved(JSON.decodeFromString(StoredChoice.serializer(), text).allowedOnMetered) },
        encode = { saved ->
            // Only a choice is ever saved, so there is no null to write.
            JSON.encodeToString(StoredChoice.serializer(), StoredChoice(FORMAT_VERSION, saved.allowed == true))
        },
    )

    override fun observe(): Flow<MeteredDownloadsChoice> = file.observe().map { read ->
        when (read) {
            // Said until a choice is made again: then nothing is missing any more.
            is VersionedJsonFile.Read.Loaded -> {
                val allowed = read.value.allowed
                MeteredDownloadsChoice.Chosen(allowed == true, setAside = read.damagedSetAside && allowed == null)
            }
            is VersionedJsonFile.Read.Unreadable -> MeteredDownloadsChoice.Unreadable
        }
    }

    override suspend fun allow(allowed: Boolean): Boolean =
        file.change(notSaved = false) { _ -> Decision(true, next = Saved(allowed)) }

    /** What the file holds: whether the heavy downloads are [allowed] on a metered connection, null for no choice. */
    private data class Saved(val allowed: Boolean?)

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredChoice(val version: Int, val allowedOnMetered: Boolean)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json
    }
}
