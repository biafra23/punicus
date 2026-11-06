package eth.serotine.data.outbox

import eth.serotine.data.keys.AndroidKeystoreSecret
import eth.serotine.domain.repository.Outbox
import kotlinx.coroutines.Dispatchers

/**
 * Android's outbox (docs/keys-and-signing.md, *The outbox's seal key*): the file at [path], sealed
 * with an HMAC key of its own in the Android Keystore, with no user-authentication requirement;
 * in memory only where the Keystore puts that key in software (an emulator, some devices). The
 * manifest keeps the app's files out of backups and device transfers.
 */
fun androidOutbox(path: String): Outbox =
    ChosenOutbox(path, { AndroidKeystoreSecret(alias = OUTBOX_KEY_ALIAS) }, Dispatchers.IO)

/** The Keystore alias of the outbox's own key: never an envelope's. */
private const val OUTBOX_KEY_ALIAS = "serotine.outbox.seal.v1"
