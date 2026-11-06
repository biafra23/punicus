package eth.serotine.data.outbox

import eth.serotine.data.keys.IosSecureEnclaveSecret
import eth.serotine.data.keys.onIosSimulator
import eth.serotine.domain.repository.Outbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.io.files.Path

/**
 * iOS's outbox (docs/keys-and-signing.md, *The outbox's seal key*), in [directory], which must not
 * be the keys' directory: `outbox.json` sealed with a Secure Enclave key of its own, its state in
 * `outbox-enclave.json` beside it, never the envelopes'. The seal makes [directory] private and out
 * of backups. In memory only on the simulator, which has no enclave.
 */
fun iosOutbox(directory: String): Outbox = ChosenOutbox(
    Path(directory, FILE_NAME).toString(),
    if (onIosSimulator) null else { { IosSecureEnclaveSecret(Path(directory, ENCLAVE_STATE_FILE_NAME).toString()) } },
    Dispatchers.IO,
)

private const val FILE_NAME = "outbox.json"

/** The outbox's own enclave state: never the envelopes' (`secure-enclave.json`), so never their secret. */
private const val ENCLAVE_STATE_FILE_NAME = "outbox-enclave.json"
