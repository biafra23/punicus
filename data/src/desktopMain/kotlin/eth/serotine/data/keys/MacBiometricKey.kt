package eth.serotine.data.keys

import com.sun.jna.Pointer
import eth.serotine.domain.repository.BiometricUnavailable
import eth.serotine.domain.repository.BiometricUnusable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/**
 * The biometric path's key on a Mac with Touch ID (docs/keys-and-signing.md, `HW_bio`, rules 3
 * and 4), built as iOS's: a P-256 key made inside the Secure Enclave whose private half works
 * only after Touch ID of the fingers enrolled now (`.biometryCurrentSet`, no password fallback),
 * on a Mac with a login password, on this Mac only ([MacSecurity.createBiometricEnclaveKey]). It
 * is a key of its own, apart from the password path's [MacSecureEnclaveSecret], and works
 * whichever factor the password path stands on.
 *
 * It is kept only as the enclave's encrypted handle, in [stateFile], with the fingerprint of the
 * enrolled set it was made under (`LAContext.evaluatedPolicyDomainState`): deleting the file
 * deletes the key, and a set that changed since says the key is invalidated, which the enclave
 * enforces too. As with [MacSecureEnclaveSecret], the handle carries no caller identity, so
 * another program running as the same user could use the key with it; it would still need the
 * user's finger, in a prompt naming that program.
 *
 * Wrapping a DEK is ECIES to the public half, which needs no biometric. Unwrapping has the
 * enclave decrypt through an `LAContext` ([MacLocalAuthentication]) that carries the caller's
 * reason, so the system shows its Touch ID prompt for that one use. On macOS 15 its one way
 * out is Cancel, and the signing screen then offers the wallet password; the context also names
 * "Use the password" as the fallback, which reads as a dismissal where macOS shows it.
 */
class MacBiometricKey(private val stateFile: String) : BiometricKey {

    override val tag: String = "macos-secure-enclave-biometric-ecies-v1"

    override fun unavailable(): BiometricUnavailable? {
        if (MacSecurity.instance == null) return BiometricUnavailable.NOT_IN_SECURE_HARDWARE
        val authentication = MacLocalAuthentication.instance ?: return BiometricUnavailable.NO_HARDWARE
        return authentication.withNewContext { context ->
            when (val code = authentication.canEvaluate(context)) {
                null -> null
                LaError.BIOMETRY_LOCKOUT -> BiometricUnavailable.LOCKED_OUT
                LaError.BIOMETRY_NOT_ENROLLED -> BiometricUnavailable.NOT_ENROLLED
                LaError.PASSCODE_NOT_SET -> BiometricUnavailable.NO_PASSCODE
                // No Touch ID, a Touch ID keyboard not paired or not connected, or the lid closed:
                // a Mac has no setting that turns Touch ID off for one app.
                LaError.BIOMETRY_NOT_AVAILABLE, LaError.BIOMETRY_NOT_PAIRED, LaError.BIOMETRY_DISCONNECTED ->
                    BiometricUnavailable.NO_HARDWARE
                else -> BiometricUnavailable.NO_HARDWARE.also {
                    println("Punicus: biometrics cannot be evaluated ($code)")
                }
            }
        }
    }

    override fun exists(): Boolean = SystemFileSystem.exists(Path(stateFile))

    override fun isInvalidated(): Boolean {
        val state = read() ?: return false
        return enrolledNow().changedSince(state)
    }

    @Synchronized
    override fun create(): Boolean {
        delete()
        val security = MacSecurity.instance ?: return false
        val enrolled = (enrolledNow() as? Enrolled.Known)?.state ?: return false
        val key = try {
            security.createBiometricEnclaveKey()
        } catch (e: IllegalStateException) {
            println("Punicus: no biometric key in the Secure Enclave (${e.message})")
            return false
        }
        try {
            val state = BiometricKeyState(VERSION, security.tokenObjectIdOf(key).toHex(), enrolled.toHex())
            Path(stateFile).parent?.let { EnvelopeCrypto.createPrivateDirectory(it.toString()) }
            val json = Json.encodeToString(BiometricKeyState.serializer(), state)
            EnvelopeCrypto.writeDurably(stateFile, json.encodeToByteArray())
            return true
        } finally {
            security.release(key)
        }
    }

    override fun wrap(dek: ByteArray): ByteArray {
        val security = checkNotNull(MacSecurity.instance) { "Security.framework is not available" }
        val state = checkNotNull(read()) { "no biometric key" }
        val key = security.restoreEnclaveKey(state.key.fromHex())
        try {
            return security.eciesEncrypt(key, dek)
        } finally {
            security.release(key)
        }
    }

    // No withContext of its own: its exit would drop a finished Dek, un-zeroed, on a cancel. The
    // store calls this on its dispatcher and opens the Dek before its next suspension point.
    override suspend fun unwrap(wrapped: ByteArray, reason: String): BiometricUnwrap {
        val security = MacSecurity.instance ?: return BiometricUnwrap.Failed
        val authentication = MacLocalAuthentication.instance ?: return BiometricUnwrap.Failed
        val state = try {
            read()
        } catch (e: Exception) {
            println("Punicus: the biometric key's state cannot be read (${e.message ?: e::class.simpleName})")
            null
        } ?: return BiometricUnwrap.Unusable(BiometricUnusable.OFF)
        return try {
            if (enrolledNow().changedSince(state)) return BiometricUnwrap.Unusable(BiometricUnusable.INVALIDATED)
            prompted(security, authentication, state, wrapped, reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable: JNA and the frameworks fail with Errors too. Never thrown on (the port).
            notOpened(e)
        }
    }

    /** [wrapped] decrypted once the system's prompt, saying [reason], passed; a cancelled caller dismisses it. */
    private suspend fun prompted(
        security: MacSecurity,
        authentication: MacLocalAuthentication,
        state: BiometricKeyState,
        wrapped: ByteArray,
        reason: String,
    ): BiometricUnwrap {
        val context = authentication.newContext()
        // A cancel invalidates the context from another thread: never after it is released.
        val guard = Any()
        var released = false
        try {
            authentication.setReason(context, reason)
            authentication.setFallbackTitle(context, USE_THE_PASSWORD)
            return suspendCancellableCoroutine { continuation ->
                // A cancelled caller dismisses the prompt, which ends the blocked decryption below.
                continuation.invokeOnCancellation {
                    synchronized(guard) { if (!released) authentication.invalidate(context) }
                }
                val outcome = authentication.autoreleasing { decrypt(security, state, context, wrapped) }
                continuation.resume(outcome) { (outcome as? BiometricUnwrap.Dek)?.bytes?.fill(0) }
            }
        } finally {
            synchronized(guard) {
                released = true
                authentication.release(context)
            }
        }
    }

    @Synchronized
    override fun delete() {
        // The handle is the key's one copy: with the file the key is gone.
        SystemFileSystem.delete(Path(stateFile), mustExist = false)
    }

    /** [wrapped] decrypted by the enclave once the system's prompt on [context] passed; blocks while it is up. */
    private fun decrypt(
        security: MacSecurity,
        state: BiometricKeyState,
        context: Pointer,
        wrapped: ByteArray,
    ): BiometricUnwrap = try {
        val key = security.restoreEnclaveKey(state.key.fromHex(), context)
        try {
            BiometricUnwrap.Dek(security.eciesDecrypt(key, wrapped))
        } finally {
            security.release(key)
        }
    } catch (e: SecurityCallFailed) {
        e.localAuthentication?.let(LaError::unwrapFailure) ?: notOpened(e)
    } catch (e: Throwable) {
        // Throwable: JNA and the frameworks fail with Errors too.
        notOpened(e)
    }

    private fun notOpened(e: Throwable): BiometricUnwrap {
        println("Punicus: the biometric key did not open its wrap (${e.message ?: e::class.simpleName})")
        return BiometricUnwrap.Failed
    }

    private fun read(): BiometricKeyState? {
        if (!SystemFileSystem.exists(Path(stateFile))) return null
        val state = SystemFileSystem.source(Path(stateFile)).buffered().use { source ->
            Json.decodeFromString(BiometricKeyState.serializer(), source.readString())
        }
        check(state.version == VERSION) { "unknown biometric key state version ${state.version}" }
        return state
    }

    companion object {
        /** The name of the state file next to the envelopes. */
        const val STATE_FILE_NAME = "biometric-key.json"

        private const val VERSION = 1
        private const val USE_THE_PASSWORD = "Use the password"

        /** The fingerprint of the fingers enrolled now (`evaluatedPolicyDomainState`), as far as it can be told. */
        private fun enrolledNow(): Enrolled {
            val authentication = MacLocalAuthentication.instance ?: return Enrolled.Unknown
            return authentication.withNewContext { context ->
                LaError.enrolled(authentication.canEvaluate(context)) { authentication.domainState(context) }
            }
        }

        private fun <T> MacLocalAuthentication.withNewContext(block: (Pointer) -> T): T {
            val context = newContext()
            try {
                return block(context)
            } finally {
                release(context)
            }
        }
    }
}
