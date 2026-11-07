package eth.serotine.data.keys

import eth.serotine.domain.repository.BiometricUnusable
import kotlinx.serialization.Serializable

/**
 * What iOS's IosBiometricKey and the Mac's MacBiometricKey share: both are a Secure Enclave key
 * under `.biometryCurrentSet`, asked through LocalAuthentication, which answers in [LaError]'s
 * codes on both.
 */
internal object LaError {
    // LAError's codes (LocalAuthentication/LAError.h).
    const val AUTHENTICATION_FAILED = -1L
    const val USER_CANCEL = -2L
    const val USER_FALLBACK = -3L
    const val SYSTEM_CANCEL = -4L
    const val PASSCODE_NOT_SET = -5L
    const val BIOMETRY_NOT_AVAILABLE = -6L
    const val BIOMETRY_NOT_ENROLLED = -7L
    const val BIOMETRY_LOCKOUT = -8L
    const val APP_CANCEL = -9L

    /** A Mac's Touch ID keyboard was never paired with it. */
    const val BIOMETRY_NOT_PAIRED = -12L

    /** A Mac's Touch ID keyboard is not connected now. */
    const val BIOMETRY_DISCONNECTED = -13L
    const val NOT_INTERACTIVE = -1004L

    /** What a biometric prompt that ended in [code] means for the unwrap it was shown for. */
    fun unwrapFailure(code: Long): BiometricUnwrap = when (code) {
        USER_CANCEL, USER_FALLBACK, SYSTEM_CANCEL, APP_CANCEL -> BiometricUnwrap.Cancelled
        // The face or finger was not recognised, try after try: not a dismissal.
        AUTHENTICATION_FAILED -> BiometricUnwrap.Failed
        BIOMETRY_LOCKOUT -> BiometricUnwrap.Unusable(BiometricUnusable.LOCKED_OUT)
        NOT_INTERACTIVE -> BiometricUnwrap.Unusable(BiometricUnusable.NO_PROMPT)
        BIOMETRY_NOT_ENROLLED, PASSCODE_NOT_SET -> BiometricUnwrap.Unusable(BiometricUnusable.INVALIDATED)
        BIOMETRY_NOT_AVAILABLE, BIOMETRY_NOT_PAIRED, BIOMETRY_DISCONNECTED ->
            BiometricUnwrap.Unusable(BiometricUnusable.SENSOR_UNAVAILABLE)
        else -> BiometricUnwrap.Failed.also { println("Punicus: the biometric prompt failed ($code)") }
    }

    /**
     * What a check of the biometrics now ([canEvaluate]'s code, null when they can be evaluated)
     * says of the enrolled set, whose fingerprint (`evaluatedPolicyDomainState`) [domainState]
     * reads where there is one.
     */
    fun enrolled(canEvaluate: Long?, domainState: () -> ByteArray?): Enrolled = when (canEvaluate) {
        null, BIOMETRY_LOCKOUT -> domainState()?.let { Enrolled.Known(it) } ?: Enrolled.Unknown
        BIOMETRY_NOT_ENROLLED, PASSCODE_NOT_SET -> Enrolled.None
        else -> Enrolled.Unknown
    }
}

/** What an Apple biometric key can tell of the biometrics enrolled now. */
internal sealed interface Enrolled {
    /** The set's fingerprint. */
    class Known(val state: ByteArray) : Enrolled

    /** None enrolled, or no passcode, without which none can be. */
    data object None : Enrolled

    /** Not now: biometrics cannot be evaluated at the moment. */
    data object Unknown : Enrolled

    /**
     * Whether this is not the set [state]'s key was made under: one was added or removed, or
     * none is left. Not when that cannot be told now (a lockout, a sensor in use): the enclave
     * refuses an invalidated key anyway.
     */
    fun changedSince(state: BiometricKeyState): Boolean = when (this) {
        is Known -> this.state.toHex() != state.enrolled
        None -> true
        Unknown -> false
    }
}

/** An Apple biometric key's state file: the enclave key's handle and the enrolled set's fingerprint, both hex. */
@Serializable
internal class BiometricKeyState(val version: Int, val key: String, val enrolled: String)
