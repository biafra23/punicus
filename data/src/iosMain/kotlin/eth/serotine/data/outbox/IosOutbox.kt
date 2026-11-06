package eth.serotine.data.outbox

import eth.serotine.data.keys.IosSecureEnclaveSecret
import eth.serotine.data.keys.onIosSimulator
import eth.serotine.domain.repository.Outbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.io.files.Path

/**
 * iOS's outbox (docs/keys-and-signing.md, *The outbox's seal key*), in [directory]: `outbox.json`
 * sealed with a Secure Enclave key of its own, kept in that directory beside it, which is out of
 * backups as every directory the key store makes is; in memory only on the simulator, which has no
 * enclave.
 */
fun iosOutbox(directory: String): Outbox = ChosenOutbox(
    Path(directory, FILE_NAME).toString(),
    if (onIosSimulator) null else { { IosSecureEnclaveSecret(Path(directory, IosSecureEnclaveSecret.STATE_FILE_NAME).toString()) } },
    Dispatchers.IO,
)

private const val FILE_NAME = "outbox.json"
