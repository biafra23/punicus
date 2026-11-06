package eth.serotine.data.outbox

import eth.serotine.data.keys.MacSecureEnclaveSecret
import eth.serotine.data.keys.MacSecurity
import eth.serotine.domain.repository.Outbox
import kotlinx.coroutines.Dispatchers
import kotlinx.io.files.Path

/**
 * The desktop's outbox (docs/keys-and-signing.md, *The outbox's seal key*), in [directory]: on a
 * Mac with a Secure Enclave, `outbox.json` sealed with an enclave key of its own, kept in that
 * directory beside it (whether or not the wallet's envelopes use the enclave); in memory only on
 * Windows and Linux, a TPM present or not, and on a Mac without one. The host keeps [directory] out
 * of Time Machine as it keeps the envelopes' directory.
 */
fun desktopOutbox(directory: String): Outbox = ChosenOutbox(
    Path(directory, FILE_NAME).toString(),
    if (MacSecurity.onMac) {
        {
            check(MacSecureEnclaveSecret.usable()) { "no Secure Enclave" }
            MacSecureEnclaveSecret(Path(directory, MacSecureEnclaveSecret.STATE_FILE_NAME).toString())
        }
    } else {
        null
    },
    Dispatchers.IO,
)

private const val FILE_NAME = "outbox.json"
