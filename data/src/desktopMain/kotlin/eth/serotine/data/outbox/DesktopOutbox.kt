package eth.serotine.data.outbox

import eth.serotine.data.keys.MacSecureEnclaveSecret
import eth.serotine.data.keys.MacSecurity
import eth.serotine.domain.repository.Outbox
import kotlinx.coroutines.Dispatchers
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The desktop's outbox (docs/keys-and-signing.md, *The outbox's seal key*), in [directory]: on a
 * Mac with a Secure Enclave, `outbox.json` sealed with an enclave key of its own, its state in
 * `outbox-enclave.json` beside it, never the envelopes' (whether or not they use the enclave), so
 * [directory] must not be the keys' directory; in memory only on
 * Windows and Linux, a TPM present or not, and on a Mac without one. The host keeps [directory] out
 * of Time Machine as it keeps the envelopes' directory.
 *
 * Its enclave state found there but never opening here (copied from another Mac by a migration or a
 * restore, or damaged; or no enclave usable on this Mac at all) is a lost key: what `outbox.json`
 * held is said as lost and deleted, and the state with it, so a new key is made for the next
 * transaction queued. A framework that does not load says nothing about the state: the outbox is
 * unreadable until it does.
 */
fun desktopOutbox(directory: String): Outbox {
    val state = Path(directory, ENCLAVE_STATE_FILE_NAME)
    return ChosenOutbox(
        Path(directory, FILE_NAME).toString(),
        if (MacSecurity.onMac) {
            { enclaveSecret(state) }
        } else {
            null
        },
        Dispatchers.IO,
        forgetKey = { SystemFileSystem.delete(state, mustExist = false) },
    )
}

/** The outbox's own enclave secret, its state at [state]; [OutboxKeyGone] when that state never opens here. */
private fun enclaveSecret(state: Path): MacSecureEnclaveSecret {
    checkNotNull(MacSecurity.instance) { "Security.framework is not available" }
    val stateThere = SystemFileSystem.exists(state)
    if (!MacSecureEnclaveSecret.usable()) {
        if (stateThere) throw OutboxKeyGone("no Secure Enclave on this Mac opens its seal key")
        error("no Secure Enclave")
    }
    val secret = MacSecureEnclaveSecret(state.toString())
    if (stateThere) {
        try {
            // Unwraps the state's secret with this Mac's enclave, and drops it.
            secret.protection
        } catch (e: Exception) {
            throw OutboxKeyGone("this Mac's Secure Enclave does not open its seal key", e)
        }
    }
    return secret
}

private const val FILE_NAME = "outbox.json"

/** The outbox's own enclave state: never the envelopes' (`secure-enclave.json`), so never their secret. */
private const val ENCLAVE_STATE_FILE_NAME = "outbox-enclave.json"
