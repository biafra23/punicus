package eth.serotine.data.keys

import eth.serotine.domain.repository.KeyProtection
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json

/**
 * The envelope's hardware factor on a Mac with a Secure Enclave, built as docs/keys-and-signing.md
 * builds it for iOS (*The envelope*): `HW_pw` is a P-256 key made inside the enclave, with no
 * user-authentication requirement, and `S_hw` is 32 random bytes encrypted to it with ECIES.
 * Both are kept in [stateFile]: the key as the enclave's own encrypted handle, which only this
 * Mac's enclave can use, and `S_hw` only in its encrypted form. [mix] has the enclave decrypt
 * `S_hw`, so a copied envelope, with or without [stateFile], is useless off this Mac.
 *
 * [stateFile] is made once, on the first [mix] of a wallet that has no envelope on the enclave
 * yet, and never replaced. When it is gone while such envelopes exist, the secret they were
 * sealed with is gone with it: [mix] and [protection] fail, and the key store reports itself
 * unavailable instead of sealing under a new secret.
 *
 * The enclave has no HMAC, so `S_hw` is the same for every guess
 * ([everyGuessNeedsDevice] is false): code that compromises the app can unwrap it once, and
 * Argon2id carries the load (rule 2). The handle carries no caller identity, so it is bound
 * to this Mac but not to the app: any program running as the same user can read [stateFile]
 * and use the enclave with it, without a permission prompt. Binding it to the app would take
 * a different design, the key in the app's own keychain access group, which needs a signed
 * app (the owner's ruling of 2026-10-16 accepts the unsigned binding for now).
 */
class MacSecureEnclaveSecret(private val stateFile: String) : HardwareSecret {

    override val tag: String = TAG

    /**
     * [KeyProtection.HARDWARE], once the enclave answers: an existing secret is unwrapped
     * (and dropped) to prove it, so a broken enclave or a lost state shows up front, as the
     * key store being unavailable, not after an attempt's Argon2id run.
     */
    override val protection: KeyProtection
        @Synchronized get() {
            val security = checkNotNull(MacSecurity.instance) { "Security.framework is not available" }
            when {
                SystemFileSystem.exists(Path(stateFile)) -> unwrap(security).fill(0)
                stateLost() -> error(STATE_LOST)
            }
            return KeyProtection.HARDWARE
        }

    override val everyGuessNeedsDevice: Boolean = false

    override fun isProvisioned(): Boolean = SystemFileSystem.exists(Path(stateFile))

    /** `S_hw`, whatever [input] is: decrypted by the enclave, or made and stored on a wallet's first use. */
    @Synchronized
    override fun mix(input: ByteArray): ByteArray {
        val security = checkNotNull(MacSecurity.instance) { "Security.framework is not available" }
        return when {
            SystemFileSystem.exists(Path(stateFile)) -> unwrap(security)
            stateLost() -> error(STATE_LOST)
            else -> create(security)
        }
    }

    /** Whether envelopes sealed with this secret exist although its state does not. */
    private fun stateLost(): Boolean = Path(stateFile).parent?.let { TAG in envelopeTagsIn(it.toString()) } == true

    private fun unwrap(security: MacSecurity): ByteArray {
        val state = SystemFileSystem.source(Path(stateFile)).buffered().use { source ->
            JSON.decodeFromString(EnclaveState.serializer(), source.readString())
        }
        check(state.version == VERSION) { "unknown enclave state version ${state.version}" }
        val key = security.restoreEnclaveKey(state.key.fromHex())
        try {
            val secret = security.eciesDecrypt(key, state.secret.fromHex())
            if (secret.size != SECRET_BYTES) {
                secret.fill(0)
                error("the enclave's secret has the wrong length")
            }
            return secret
        } finally {
            security.release(key)
        }
    }

    private fun create(security: MacSecurity): ByteArray {
        val key = security.createEnclaveKey()
        val secret = EnvelopeCrypto.randomBytes(SECRET_BYTES)
        try {
            val state = EnclaveState(
                version = VERSION,
                key = security.tokenObjectIdOf(key).toHex(),
                secret = security.eciesEncrypt(key, secret).toHex(),
            )
            // Stored before the secret is used, so an envelope sealed under it opens again; and
            // never over an existing state, which envelopes may still need.
            Path(stateFile).parent?.let { EnvelopeCrypto.createPrivateDirectory(it.toString()) }
            val json = JSON.encodeToString(EnclaveState.serializer(), state)
            EnvelopeCrypto.writeDurably(stateFile, json.encodeToByteArray(), replace = false)
            return secret
        } catch (e: Throwable) {
            secret.fill(0)
            throw e
        } finally {
            security.release(key)
        }
    }

    companion object {
        /** The envelopes' hardware tag for this secret. */
        const val TAG = "macos-secure-enclave-ecies-v1"

        /** The name of the state file next to the envelopes. */
        const val STATE_FILE_NAME = "secure-enclave.json"

        private const val STATE_LOST =
            "the Secure Enclave's state is gone, and the envelopes sealed with it cannot be opened"
        private const val VERSION = 1
        private const val SECRET_BYTES = 32
        private val JSON = Json

        /**
         * Whether this Mac's Secure Enclave makes and serves a key for this process now: an
         * ephemeral key is created, its handle restored and used once, and all of it dropped.
         * False off a Mac, on a Mac without an enclave, or when anything of it fails.
         */
        fun usable(): Boolean {
            val security = MacSecurity.instance ?: return false
            return try {
                val key = security.createEnclaveKey()
                try {
                    val restored = security.restoreEnclaveKey(security.tokenObjectIdOf(key))
                    try {
                        val probe = ByteArray(SECRET_BYTES) { it.toByte() }
                        security.eciesDecrypt(restored, security.eciesEncrypt(key, probe)).contentEquals(probe)
                    } finally {
                        security.release(restored)
                    }
                } finally {
                    security.release(key)
                }
            } catch (e: Throwable) {
                // Throwable: JNA and the frameworks fail with Errors too.
                println("Punicus: the Secure Enclave cannot be used (${e.message ?: e::class.simpleName})")
                false
            }
        }
    }
}
