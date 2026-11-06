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
 * plaintext is the caller's to zero. Nothing here throws: a seal that fails is null, and so is an
 * open whose tag does not verify (another key, or a damaged file).
 */
internal class OutboxSeal(private val hardware: HardwareSecret) {

    /**
     * Whether the hardware key existed when this seal was made, asked before anything could make
     * one: a sealed file found without it is a lost key (the key store reset, or a copy restored on
     * another device), said, never guessed around.
     */
    val keyExistedAtStart: Boolean = try {
        hardware.isProvisioned()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** [plaintext] sealed, or null when the hardware or the cipher failed. */
    fun seal(plaintext: ByteArray): ByteArray? = withKey { key ->
        val nonce = EnvelopeCrypto.randomBytes(NONCE_BYTES)
        byteArrayOf(VERSION) + nonce + EnvelopeCrypto.aesGcmSeal(key, nonce, plaintext, AAD)
    }

    /** What [sealed] holds, or null when it does not open: another key, a damaged file, or a failure. */
    fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size < 1 + NONCE_BYTES + TAG_BYTES || sealed[0] != VERSION) return null
        val nonce = sealed.copyOfRange(1, 1 + NONCE_BYTES)
        val ciphertext = sealed.copyOfRange(1 + NONCE_BYTES, sealed.size)
        return withKey { key -> EnvelopeCrypto.aesGcmOpen(key, nonce, ciphertext, AAD) }
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
