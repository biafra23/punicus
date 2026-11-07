package eth.serotine.data.keys

import eth.serotine.domain.repository.KeyProtection
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import platform.Foundation.NSRecursiveLock

/**
 * The envelope's hardware factor on iOS (docs/keys-and-signing.md, *The envelope*): `HW_pw` is a
 * P-256 key made inside the Secure Enclave, with no user-authentication requirement, and `S_hw`
 * is 32 random bytes encrypted to it with ECIES. The iOS twin of the desktop's
 * MacSecureEnclaveSecret, with the same state file: the key as the enclave's own encrypted
 * handle, which only this device's enclave can use, and `S_hw` only in its encrypted form. [mix]
 * has the enclave decrypt `S_hw`, so a copied envelope, with or without [stateFile], is useless
 * off this device.
 *
 * [stateFile] is made once, on the first [mix] of a wallet that has no envelope on the enclave
 * yet, and never replaced. When it is gone while such envelopes exist, the secret they were
 * sealed with is gone with it: [mix] and [protection] fail, and the key store reports itself
 * unavailable instead of sealing under a new secret.
 *
 * The enclave has no HMAC, so `S_hw` is the same for every guess ([everyGuessNeedsDevice] is
 * false): code that compromises the app can unwrap it once, and Argon2id carries the load
 * (rule 2). The handle is bound to this device, and the app's sandbox keeps other apps from it.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSecureEnclaveSecret(private val stateFile: String) : HardwareSecret {

    // Kotlin/Native has no @Synchronized: the same serialization the Mac's secret has, by hand.
    private val lock = NSRecursiveLock()

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    override val tag: String = TAG

    /**
     * [KeyProtection.HARDWARE], once the enclave answers: an existing secret is unwrapped (and
     * dropped) to prove it, so a broken enclave or a lost state shows up front, as the key store
     * being unavailable, not after an attempt's Argon2id run.
     */
    override val protection: KeyProtection
        get() = locked {
            when {
                SystemFileSystem.exists(Path(stateFile)) -> unwrap().fill(0)
                stateLost() -> error(STATE_LOST)
            }
            KeyProtection.HARDWARE
        }

    override val everyGuessNeedsDevice: Boolean = false

    override fun isProvisioned(): Boolean = SystemFileSystem.exists(Path(stateFile))

    /** `S_hw`, whatever [input] is: decrypted by the enclave, or made and stored on a wallet's first use. */
    override fun mix(input: ByteArray): ByteArray = locked {
        when {
            SystemFileSystem.exists(Path(stateFile)) -> unwrap()
            stateLost() -> error(STATE_LOST)
            else -> create()
        }
    }

    /** Whether envelopes sealed with this secret exist although its state does not. */
    private fun stateLost(): Boolean = Path(stateFile).parent?.let { TAG in envelopeTagsIn(it.toString()) } == true

    private fun unwrap(): ByteArray {
        val state = SystemFileSystem.source(Path(stateFile)).buffered().use { source ->
            Json.decodeFromString(EnclaveState.serializer(), source.readString())
        }
        check(state.version == VERSION) { "unknown enclave state version ${state.version}" }
        val key = IosSecurity.restoreEnclaveKey(state.key.fromHex())
        try {
            val secret = IosSecurity.eciesDecrypt(key, state.secret.fromHex())
            if (secret.size != SECRET_BYTES) {
                secret.fill(0)
                error("the enclave's secret has the wrong length")
            }
            return secret
        } finally {
            IosSecurity.release(key)
        }
    }

    private fun create(): ByteArray {
        val key = IosSecurity.createEnclaveKey()
        val secret = EnvelopeCrypto.randomBytes(SECRET_BYTES)
        try {
            val state = EnclaveState(
                version = VERSION,
                key = IosSecurity.tokenObjectIdOf(key).toHex(),
                secret = IosSecurity.eciesEncrypt(key, secret).toHex(),
            )
            // Stored before the secret is used, so an envelope sealed under it opens again; and
            // never over an existing state, which envelopes may still need.
            Path(stateFile).parent?.let { EnvelopeCrypto.createPrivateDirectory(it.toString()) }
            val json = Json.encodeToString(EnclaveState.serializer(), state)
            EnvelopeCrypto.writeDurably(stateFile, json.encodeToByteArray(), replace = false)
            return secret
        } catch (e: Throwable) {
            secret.fill(0)
            throw e
        } finally {
            IosSecurity.release(key)
        }
    }

    companion object {
        /** The envelopes' hardware tag for this secret. */
        const val TAG = "ios-secure-enclave-ecies-v1"

        /** The name of the state file next to the envelopes. */
        const val STATE_FILE_NAME = "secure-enclave.json"

        private const val STATE_LOST =
            "the Secure Enclave's state is gone, and the envelopes sealed with it cannot be opened"
        private const val VERSION = 1
        private const val SECRET_BYTES = 32

        /**
         * Whether this device's Secure Enclave makes and serves a key for this process now: an
         * ephemeral key is created, its handle restored and used once, and all of it dropped.
         * False on the simulator, which has no enclave, or when anything of it fails.
         */
        fun usable(): Boolean = try {
            val key = IosSecurity.createEnclaveKey()
            try {
                val restored = IosSecurity.restoreEnclaveKey(IosSecurity.tokenObjectIdOf(key))
                try {
                    val probe = ByteArray(SECRET_BYTES) { it.toByte() }
                    IosSecurity.eciesDecrypt(restored, IosSecurity.eciesEncrypt(key, probe)).contentEquals(probe)
                } finally {
                    IosSecurity.release(restored)
                }
            } finally {
                IosSecurity.release(key)
            }
        } catch (e: Throwable) {
            println("Punicus: the Secure Enclave cannot be used (${e.message ?: e::class.simpleName})")
            false
        }
    }
}
