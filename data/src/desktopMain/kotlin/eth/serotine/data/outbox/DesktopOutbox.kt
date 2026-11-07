package eth.serotine.data.outbox

import eth.serotine.data.keys.EnvelopeCrypto
import eth.serotine.data.keys.HardwareSecret
import eth.serotine.data.keys.MacSecureEnclaveSecret
import eth.serotine.data.keys.MacSecurity
import eth.serotine.domain.repository.Outbox
import kotlinx.coroutines.Dispatchers
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import java.security.MessageDigest

/**
 * The desktop's outbox (docs/keys-and-signing.md, *The outbox's seal key*), in [directory]: on a
 * Mac with a Secure Enclave, `outbox.json` sealed with an enclave key of its own, its state in
 * `outbox-enclave.json` beside it, never the envelopes' (whether or not they use the enclave), so
 * [directory] must not be the keys' directory; in memory only on
 * Windows and Linux, a TPM present or not, and on a Mac without one. The host keeps [directory] out
 * of Time Machine as it keeps the envelopes' directory.
 *
 * Its enclave state made on another Mac (copied here by a migration or a restore), as
 * `outbox-mac` beside it says, and not opening here, is a lost key. What `outbox.json` held
 * is said as lost and deleted, and the state with it, so a new key is made for the next
 * transaction queued. Any other failure, of the enclave, the framework or the state, says nothing
 * about the key: the outbox is unreadable until it opens, since it may hold a transaction already
 * on the network.
 */
fun desktopOutbox(directory: String): Outbox {
    val state = Path(directory, ENCLAVE_STATE_FILE_NAME)
    val madeOn = Path(directory, MADE_ON_FILE_NAME)
    return ChosenOutbox(
        Path(directory, FILE_NAME).toString(),
        if (MacSecurity.onMac) {
            {
                val security = checkNotNull(MacSecurity.instance) { "Security.framework is not available" }
                enclaveSecret(
                    state,
                    madeOn,
                    thisMac = security.platformUuid()?.let(::macId),
                    usable = MacSecureEnclaveSecret::usable,
                    secretAt = ::MacSecureEnclaveSecret,
                )
            }
        } else {
            null
        },
        Dispatchers.IO,
        forgetKey = {
            SystemFileSystem.delete(state, mustExist = false)
            SystemFileSystem.delete(madeOn, mustExist = false)
        },
    )
}

/**
 * The outbox's own enclave secret, its state at [state] ([secretAt]). [OutboxKeyGone] only when
 * the state is known to be another Mac's: [madeOn] names a Mac other than [thisMac], both read,
 * and the state does not open here. A state that opens here is this Mac's, whatever [madeOn] said,
 * and [madeOn] is made to say so. With no state yet, [madeOn] is written first, so the state made
 * next is never taken for another Mac's. Anything else that fails here is this time's failure.
 */
internal fun enclaveSecret(
    state: Path,
    madeOn: Path,
    thisMac: String?,
    usable: () -> Boolean,
    secretAt: (String) -> HardwareSecret,
): HardwareSecret {
    if (!SystemFileSystem.exists(state)) {
        // Whatever it said is about no state now: an unknown Mac is said as unknown, never as another one.
        SystemFileSystem.delete(madeOn, mustExist = false)
        if (thisMac != null) record(madeOn, thisMac)
        check(usable()) { "no Secure Enclave usable now" }
        return secretAt(state.toString())
    }
    val secret = secretAt(state.toString())
    val recorded = recordedMac(madeOn)
    if (thisMac == null || recorded == thisMac) {
        check(usable()) { "no Secure Enclave usable now" }
        return secret
    }
    // Recorded as another Mac's, or not at all: only this Mac's enclave opens a state made on it.
    if (usable() && opens(secret)) {
        try {
            record(madeOn, thisMac)
        } catch (e: Exception) {
            // The record is asked again at the next start; the state opens here either way.
        }
        return secret
    }
    if (recorded != null) throw OutboxKeyGone("its seal key was made on another Mac, whose enclave alone opens it")
    error("the outbox's enclave state does not open now")
}

/** Whether [secret] opens its state here: its secret unwrapped by this Mac's enclave, and dropped. */
private fun opens(secret: HardwareSecret): Boolean = try {
    secret.protection
    true
} catch (e: Exception) {
    false
}

private fun record(madeOn: Path, mac: String) {
    madeOn.parent?.let { EnvelopeCrypto.createPrivateDirectory(it.toString()) }
    EnvelopeCrypto.writeDurably(madeOn.toString(), mac.encodeToByteArray())
}

/**
 * The Mac [madeOn] names ([macId]), or null when it names none, cannot be read, or holds anything
 * else: unknown, never another Mac.
 */
private fun recordedMac(madeOn: Path): String? = try {
    if (SystemFileSystem.exists(madeOn)) {
        SystemFileSystem.source(madeOn).buffered().use { it.readString() }.trim().takeIf(MAC_ID::matches)
    } else {
        null
    }
} catch (e: Exception) {
    null
}

/** What `outbox-mac` keeps of [platformUuid]: a hash, so the file names this Mac without its hardware id. */
internal fun macId(platformUuid: String): String = MessageDigest.getInstance("SHA-256")
    .digest("serotine outbox seal key, made on $platformUuid".encodeToByteArray())
    .joinToString("") { "%02x".format(it) }

private const val FILE_NAME = "outbox.json"

/** The outbox's own enclave state: never the envelopes' (`secure-enclave.json`), so never their secret. */
private const val ENCLAVE_STATE_FILE_NAME = "outbox-enclave.json"

/** Which Mac made the outbox's enclave state ([macId]). */
private const val MADE_ON_FILE_NAME = "outbox-mac"

/** What [macId] gives: 32 bytes, in lower-case hex. */
private val MAC_ID = Regex("[0-9a-f]{64}")
