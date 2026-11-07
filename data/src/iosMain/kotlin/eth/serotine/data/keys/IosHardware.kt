package eth.serotine.data.keys

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The hardware secret for the iOS key store whose envelopes live in [keysDirectory]
 * (docs/keys-and-signing.md, *The envelope*): this device's Secure Enclave
 * ([IosSecureEnclaveSecret]). Every iPhone this app runs on has one, so a device build never
 * stands on the password alone, nor asks whether the enclave works: it is the factor, and an
 * enclave that fails makes sealing fail and the key store unavailable. Not asking also keeps a
 * locked device, whose enclave refuses the key until it is unlocked, from deciding anything.
 *
 * The simulator has no enclave, and only a simulator build ([onIosSimulator], fixed at compile
 * time) lets a wallet stand on the password alone, so the app can be developed and tried there;
 * its envelopes say so in their hardware tag, as the desktop's do. Its factor sticks to the
 * wallet as on the desktop: once the enclave holds a wallet's secret it is used again, and only
 * a wallet without envelopes asks whether the enclave works.
 */
fun iosHardwareSecret(keysDirectory: String): HardwareSecret? = try {
    val stateFile = Path(keysDirectory, IosSecureEnclaveSecret.STATE_FILE_NAME)
    val factor = chooseIosFactor(
        onSimulator = onIosSimulator,
        enclaveStateExists = SystemFileSystem.exists(stateFile),
        envelopeTags = envelopeTagsIn(keysDirectory),
        enclaveUsable = IosSecureEnclaveSecret::usable,
    )
    when (factor) {
        IosFactor.SECURE_ENCLAVE -> IosSecureEnclaveSecret(stateFile.toString())
        IosFactor.PASSWORD_ONLY -> null
        IosFactor.UNAVAILABLE -> UnavailableHardware("the wallet's envelopes are not on this device's Secure Enclave")
    }
} catch (e: Throwable) {
    // Not knowing the wallet's factor is no reason to guess one: the key store reports itself
    // unavailable. Said here, where the cause is known.
    val reason = "the hardware factor cannot be determined (${e.message ?: e::class.simpleName})"
    println("Punicus: $reason")
    UnavailableHardware(reason)
}

/** Whether this is a build for the iOS simulator: its own source set says, not the environment. */
internal expect val onIosSimulator: Boolean

internal enum class IosFactor { SECURE_ENCLAVE, PASSWORD_ONLY, UNAVAILABLE }

/**
 * [iosHardwareSecret]'s rule, on its inputs: [enclaveUsable] is asked only on the simulator,
 * and only for a wallet without envelopes. Off the simulator nothing but the enclave is chosen.
 */
internal fun chooseIosFactor(
    onSimulator: Boolean,
    enclaveStateExists: Boolean,
    envelopeTags: Set<String>,
    enclaveUsable: () -> Boolean,
): IosFactor = when {
    enclaveStateExists || IosSecureEnclaveSecret.TAG in envelopeTags -> IosFactor.SECURE_ENCLAVE
    !onSimulator -> if (envelopeTags.isEmpty()) IosFactor.SECURE_ENCLAVE else IosFactor.UNAVAILABLE
    envelopeTags.isNotEmpty() -> IosFactor.PASSWORD_ONLY
    enclaveUsable() -> IosFactor.SECURE_ENCLAVE
    else -> IosFactor.PASSWORD_ONLY
}
