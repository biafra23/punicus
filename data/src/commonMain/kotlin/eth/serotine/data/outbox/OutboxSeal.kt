package eth.serotine.data.outbox

import eth.serotine.data.keys.EnvelopeCrypto
import eth.serotine.data.keys.HardwareSecret
import eth.serotine.data.keys.Hkdf
import kotlinx.coroutines.CancellationException

/**
 * The outbox's seal (docs/keys-and-signing.md, *The outbox's seal key*): AES-256-GCM under a key
 * derived from [hardware], a hardware key of the outbox's own (never an envelope's), with no
 * password and no biometric prompt, so the outbox sends by itself. A copied file is useless off
 * the device; code inside the app, and on a Mac any program of the same user, can still open it.
 *
 * Sealed bytes are a version byte, a random 12-byte nonce, and the ciphertext with its tag,
 * authenticated with [AAD]. The key is derived for each seal and open and zeroed after it; the
 * plaintext is the caller's to zero. Nothing here throws: a seal that fails is null, and an open
 * says what it found ([Opening]).
 */
internal class OutboxSeal(private val hardware: HardwareSecret) {

    /**
     * Whether the hardware key existed when this seal was made, asked before anything could make
     * one: a sealed file found without it is a lost key (the key store reset, or a copy restored on
     * another device), said, never guessed around. Null when the key store would not say: nothing
     * is concluded from that.
     */
    val keyExistedAtStart: Boolean? = try {
        hardware.isProvisioned()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** [plaintext] sealed, or null when the hardware or the cipher failed. */
    fun seal(plaintext: ByteArray): ByteArray? = withKey { key ->
        val nonce = EnvelopeCrypto.randomBytes(NONCE_BYTES)
        byteArrayOf(VERSION) + nonce + EnvelopeCrypto.aesGcmSeal(key, nonce, plaintext, AAD)
    }

    /**
     * What [sealed] holds ([Opening.Opened]); [Opening.WrongKey] when its tag does not verify
     * (another key, or damage), [Opening.Newer] when a newer version sealed it, [Opening.Failed]
     * when the hardware or the cipher failed, which says nothing about the file.
     */
    fun open(sealed: ByteArray): Opening {
        if (sealed.isNotEmpty() && sealed[0] > VERSION) return Opening.Newer
        if (sealed.size < 1 + NONCE_BYTES + TAG_BYTES || sealed[0] != VERSION) return Opening.WrongKey
        val nonce = sealed.copyOfRange(1, 1 + NONCE_BYTES)
        val ciphertext = sealed.copyOfRange(1 + NONCE_BYTES, sealed.size)
        var tagFailed = false
        val opened = withKey { key ->
            EnvelopeCrypto.aesGcmOpen(key, nonce, ciphertext, AAD).also { if (it == null) tagFailed = true }
        }
        return when {
            opened != null -> Opening.Opened(opened)
            // The key was had and the tag did not verify: not this key's file.
            tagFailed -> Opening.WrongKey
            else -> Opening.Failed
        }
    }

    /** What [open] found. */
    sealed interface Opening {
        /** The [plaintext] (the caller's to zero). */
        class Opened(val plaintext: ByteArray) : Opening

        /** Sealed under another key, or damaged: it will never open here. */
        data object WrongKey : Opening

        /** Sealed by a newer version: it opens there, not here. */
        data object Newer : Opening

        /** The hardware or the cipher failed: it may open later. */
        data object Failed : Opening
    }

    private fun <T> withKey(use: (ByteArray) -> T?): T? {
        var secret: ByteArray? = null
        var key: ByteArray? = null
        return try {
            secret = hardware.mix(MIX_INPUT)
            key = Hkdf.sha256(secret, SALT, INFO, KEY_BYTES)
            use(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            secret?.fill(0)
            key?.fill(0)
        }
    }

    internal companion object {
        const val VERSION: Byte = 1
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val KEY_BYTES = 32
        val AAD = "serotine-outbox-v1".encodeToByteArray()
        private val MIX_INPUT = "serotine outbox seal".encodeToByteArray()
        private val SALT = "serotine-outbox-seal-salt-v1".encodeToByteArray()
        private val INFO = "serotine-outbox-key-v1".encodeToByteArray()
    }
}
