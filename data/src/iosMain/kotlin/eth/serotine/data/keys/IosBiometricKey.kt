package eth.serotine.data.keys

import eth.serotine.domain.repository.BiometricUnavailable
import eth.serotine.domain.repository.BiometricUnusable
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSRecursiveLock
import platform.LocalAuthentication.LABiometryType
import platform.LocalAuthentication.LABiometryTypeNone
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import kotlin.coroutines.resume

/**
 * The biometric path's key on iOS (docs/keys-and-signing.md, `HW_bio`, rules 3 and 4): a P-256
 * key made inside the Secure Enclave whose private half works only after Face ID or Touch ID of
 * the set enrolled now (`.biometryCurrentSet`, no passcode fallback), on a device with a
 * passcode, on this device only ([IosSecurity.createBiometricEnclaveKey]). It is a key of its
 * own, apart from the password path's [IosSecureEnclaveSecret].
 *
 * Like that one, it is kept only as the enclave's encrypted handle, in [stateFile], with the
 * fingerprint of the enrolled set it was made under (`LAContext.evaluatedPolicyDomainState`):
 * deleting the file deletes the key, and a set that changed since says the key is invalidated,
 * which the enclave enforces too, since such a key no longer opens anything.
 *
 * Wrapping a DEK is ECIES to the public half, which needs no biometric. Unwrapping asks the
 * system's Face ID prompt, saying the caller's reason, with "Use the password" as the way out,
 * and hands the context the user passed to the enclave for that one decryption. The simulator
 * has no enclave, so the path is not offered there (rule 5).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBiometricKey(private val stateFile: String) : BiometricKey {

    // Kotlin/Native has no @Synchronized: create and delete take turns by hand.
    private val lock = NSRecursiveLock()

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    override val tag: String = "ios-secure-enclave-biometric-ecies-v1"

    override fun unavailable(): BiometricUnavailable? {
        if (onIosSimulator) return BiometricUnavailable.NOT_IN_SECURE_HARDWARE
        return when (val code = canEvaluate(LAContext())) {
            null -> null
            // Until the passcode is entered no key can be made: the enrolled set cannot be read.
            LaError.BIOMETRY_LOCKOUT -> BiometricUnavailable.LOCKED_OUT
            LaError.BIOMETRY_NOT_ENROLLED -> BiometricUnavailable.NOT_ENROLLED
            LaError.PASSCODE_NOT_SET -> BiometricUnavailable.NO_PASSCODE
            // On a device with Face ID, the user declined it for this app or turned it off since.
            LaError.BIOMETRY_NOT_AVAILABLE -> if (biometryType() == LABiometryTypeNone) {
                BiometricUnavailable.NO_HARDWARE
            } else {
                BiometricUnavailable.NOT_ALLOWED
            }
            else -> BiometricUnavailable.NO_HARDWARE.also {
                println("Punicus: biometrics cannot be evaluated ($code)")
            }
        }
    }

    override fun exists(): Boolean = SystemFileSystem.exists(Path(stateFile))

    override fun isInvalidated(): Boolean {
        val state = read() ?: return false
        return enrolledNow().changedSince(state)
    }

    override fun create(): Boolean = locked {
        delete()
        if (onIosSimulator) return@locked false
        val enrolled = (enrolledNow() as? Enrolled.Known)?.state ?: return@locked false
        val key = try {
            IosSecurity.createBiometricEnclaveKey()
        } catch (e: IllegalStateException) {
            println("Punicus: no biometric key in the Secure Enclave (${e.message})")
            return@locked false
        }
        try {
            val state = BiometricKeyState(VERSION, IosSecurity.tokenObjectIdOf(key).toHex(), enrolled.toHex())
            Path(stateFile).parent?.let { EnvelopeCrypto.createPrivateDirectory(it.toString()) }
            val json = Json.encodeToString(BiometricKeyState.serializer(), state)
            EnvelopeCrypto.writeDurably(stateFile, json.encodeToByteArray())
            true
        } finally {
            IosSecurity.release(key)
        }
    }

    override fun wrap(dek: ByteArray): ByteArray {
        val state = checkNotNull(read()) { "no biometric key" }
        val key = IosSecurity.restoreEnclaveKey(state.key.fromHex())
        try {
            return IosSecurity.eciesEncrypt(key, dek)
        } finally {
            IosSecurity.release(key)
        }
    }

    // No withContext of its own: its exit would drop a finished Dek, un-zeroed, on a cancel. The
    // store calls this on its dispatcher and opens the Dek before its next suspension point.
    override suspend fun unwrap(wrapped: ByteArray, reason: String): BiometricUnwrap {
        val state = try {
            read()
        } catch (e: Exception) {
            println("Punicus: the biometric key's state cannot be read (${e.message ?: e::class.simpleName})")
            null
        } ?: return BiometricUnwrap.Unusable(BiometricUnusable.OFF)
        if (enrolledNow().changedSince(state)) return BiometricUnwrap.Unusable(BiometricUnusable.INVALIDATED)
        val context = LAContext()
        context.localizedFallbackTitle = USE_THE_PASSWORD
        try {
            evaluate(context, reason)?.let { code -> return LaError.unwrapFailure(code) }
            return try {
                // The context the user just passed: the enclave decrypts once, without a second prompt.
                val key = IosSecurity.restoreEnclaveKey(state.key.fromHex(), context)
                try {
                    BiometricUnwrap.Dek(IosSecurity.eciesDecrypt(key, wrapped))
                } finally {
                    IosSecurity.release(key)
                }
            } catch (e: Exception) {
                println("Punicus: the biometric key did not open its wrap (${e.message ?: e::class.simpleName})")
                BiometricUnwrap.Failed
            }
        } finally {
            context.invalidate()
        }
    }

    override fun delete() = locked {
        // The handle is the key's one copy: with the file the key is gone.
        SystemFileSystem.delete(Path(stateFile), mustExist = false)
    }

    private fun read(): BiometricKeyState? {
        if (!SystemFileSystem.exists(Path(stateFile))) return null
        val state = SystemFileSystem.source(Path(stateFile)).buffered().use { source ->
            Json.decodeFromString(BiometricKeyState.serializer(), source.readString())
        }
        check(state.version == VERSION) { "unknown biometric key state version ${state.version}" }
        return state
    }

    /**
     * Shows the system's prompt, saying [reason]; null once the user passed it, else LAError's
     * code. A cancelled caller dismisses it.
     */
    private suspend fun evaluate(context: LAContext, reason: String): Long? =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { context.invalidate() }
            context.evaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, reason) { success, error ->
                continuation.resume(if (success) null else error?.code ?: LaError.AUTHENTICATION_FAILED)
            }
        }

    companion object {
        /** The name of the state file next to the envelopes. */
        const val STATE_FILE_NAME = "biometric-key.json"

        private const val VERSION = 1
        private const val USE_THE_PASSWORD = "Use the password"

        /** The kind of sensor the device has, enrolled or allowed or not. */
        private fun biometryType(): LABiometryType = LAContext().let { context ->
            canEvaluate(context)
            context.biometryType
        }

        /** Null when [context] can evaluate the biometric policy now, else LAError's code. */
        private fun canEvaluate(context: LAContext): Long? = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error.ptr)) {
                null
            } else {
                error.value?.code ?: LaError.BIOMETRY_NOT_AVAILABLE
            }
        }

        /** The fingerprint of the biometrics enrolled now (`evaluatedPolicyDomainState`), as far as it can be told. */
        private fun enrolledNow(): Enrolled {
            val context = LAContext()
            return LaError.enrolled(canEvaluate(context)) { context.evaluatedPolicyDomainState?.toByteArray() }
        }

        private fun NSData.toByteArray(): ByteArray {
            val length = length.toInt()
            if (length == 0) return ByteArray(0)
            return checkNotNull(bytes) { "NSData without bytes" }.readBytes(length)
        }
    }
}
