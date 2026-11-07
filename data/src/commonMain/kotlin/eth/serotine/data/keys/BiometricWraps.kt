package eth.serotine.data.keys

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The biometric path's wraps on disk: one file per account (`<address>.bio`) next to its
 * envelope in [directory], holding its DEK as the [BiometricKey] wrapped it. A wrap is no
 * secret on its own (only the key, and a biometric, open it), and it is written as durably and
 * privately as the envelope. The caller holds the key store's lock.
 */
internal class BiometricWraps(private val directory: String) {

    /** The wrap filed for [address] by a key tagged [keyTag], or null when there is none or it cannot be used. */
    fun read(address: String, keyTag: String): ByteArray? {
        val file = fileOf(address)
        if (!SystemFileSystem.exists(file)) return null
        return try {
            val stored = JSON.decodeFromString(
                StoredWrap.serializer(),
                SystemFileSystem.source(file).buffered().use { it.readString() },
            )
            if (stored.version != VERSION || stored.address != address || stored.key != keyTag) null
            else stored.wrapped.fromHex()
        } catch (e: Exception) {
            report("the biometric wrap at $file cannot be read (${e::class.simpleName})")
            null
        }
    }

    /** Files [wrapped] for [address]; false when it could not be written. */
    fun write(address: String, keyTag: String, wrapped: ByteArray): Boolean = try {
        EnvelopeCrypto.createPrivateDirectory(directory)
        val json = JSON.encodeToString(StoredWrap.serializer(), StoredWrap(VERSION, address, keyTag, wrapped.toHex()))
        EnvelopeCrypto.writeDurably(fileOf(address).toString(), json.encodeToByteArray())
        true
    } catch (e: Exception) {
        report("could not store the biometric wrap of $address (${e::class.simpleName})")
        false
    }

    /** Deletes the wrap of [address], if there is one; false when it could not be deleted. */
    fun delete(address: String): Boolean = deleteFile(fileOf(address))

    /** Deletes every wrap; false when one could not be deleted. */
    fun deleteAll(): Boolean {
        val dir = Path(directory)
        if (!SystemFileSystem.exists(dir)) return true
        return SystemFileSystem.list(dir).filter { it.name.endsWith(SUFFIX) }.map(::deleteFile).all { it }
    }

    private fun deleteFile(file: Path): Boolean = try {
        SystemFileSystem.delete(file, mustExist = false)
        true
    } catch (e: Exception) {
        report("could not delete $file (${e::class.simpleName})")
        false
    }

    private fun fileOf(address: String) = Path(directory, "$address$SUFFIX")

    @Serializable
    private class StoredWrap(val version: Int, val address: String, val key: String, val wrapped: String)

    private companion object {
        const val VERSION = 1
        const val SUFFIX = ".bio"
        val JSON = Json

        fun report(what: String) = println("Punicus: $what")
    }
}
