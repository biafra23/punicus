package eth.serotine.data.walletconnect

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The relay's auth token: a JWT signed with this client's Ed25519 key, whose public half names the
 * client as a `did:key` (WalletConnect's *Relay Client Auth*). The relay learns the key, and with
 * it can tell one connection of this device from the next: the key is kept with the session state
 * (docs/walletconnect.md, ruling 8), so it is one identity for as long as that file lives. It
 * authenticates the connection, never a message, and it cannot move funds.
 */
internal object WcRelayAuth {

    /** The relay's audience, as its URL without the query. */
    const val DEFAULT_AUDIENCE = "wss://relay.walletconnect.org"

    /** A token lives a day, as the reference clients make it; a new one is made for each connection. */
    const val TTL_SECONDS = 86_400L

    /** The multicodec prefix of an Ed25519 public key (`ed25519-pub`, 0xed as a varint). */
    private val ED25519_MULTICODEC = byteArrayOf(0xed.toByte(), 0x01)

    /** `did:key:z…`: [publicKey], with its multicodec prefix, in base58btc. */
    fun didKey(publicKey: ByteArray): String {
        requireKey(publicKey, "an Ed25519 public key")
        return "did:key:z" + WcEncoding.base58(ED25519_MULTICODEC + publicKey)
    }

    /**
     * The token for a connection to [audience] by the key of [seed], made at [issuedAtSeconds]:
     * `base64url(header).base64url(claims).base64url(signature)`, the claims `iss` (this client's
     * `did:key`), `sub` ([subject], random per token), `aud`, `iat` and `exp`.
     */
    fun token(seed: ByteArray, audience: String, subject: String, issuedAtSeconds: Long): String {
        val header = buildJsonObject {
            put("alg", "EdDSA")
            put("typ", "JWT")
        }
        val claims = buildJsonObject {
            put("iss", didKey(WcCrypto.ed25519PublicKey(seed)))
            put("sub", subject)
            put("aud", audience)
            put("iat", issuedAtSeconds)
            put("exp", issuedAtSeconds + TTL_SECONDS)
        }
        val signingInput = WcEncoding.base64Url(header.toString().encodeToByteArray()) + "." +
            WcEncoding.base64Url(claims.toString().encodeToByteArray())
        val signature = WcCrypto.ed25519Sign(seed, signingInput.encodeToByteArray())
        return signingInput + "." + WcEncoding.base64Url(signature)
    }
}
