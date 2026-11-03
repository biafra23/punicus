package eth.serotine.data.walletconnect

import eth.serotine.data.keys.EnvelopeCrypto
import eth.serotine.data.keys.Hkdf
import org.kotlincrypto.hash.sha2.SHA256

/**
 * A WalletConnect v2 envelope: how every message on a topic is sealed (the Sign protocol's
 * *Crypto* section). Base64 of
 * - type 0: `0x00 ‖ nonce (12) ‖ ChaCha20-Poly1305(symKey, nonce, message)`, under the topic's
 *   symmetric key;
 * - type 1: `0x01 ‖ sender's X25519 public key (32) ‖ nonce (12) ‖ sealed`, under the key the
 *   receiver derives from its own private key and that public key ([WcKeys.symKey]): for a peer that
 *   does not hold the topic's key yet.
 *
 * No additional data is authenticated, as in the reference clients. A sealed message authenticates
 * its content, not its delivery: the relay can deliver it again, and only the receiver's record of
 * the ids it has seen stops a replay (docs/walletconnect.md, *What the relay learns*).
 */
internal object WcEnvelope {

    /** What [parse] found in an envelope; its plaintext comes from [open]. */
    sealed interface Parsed {
        val nonce: ByteArray
        val sealed: ByteArray

        /** Sealed under the topic's own symmetric key. */
        class Type0(override val nonce: ByteArray, override val sealed: ByteArray) : Parsed

        /** Sealed under a key derived with [senderPublicKey]. */
        class Type1(val senderPublicKey: ByteArray, override val nonce: ByteArray, override val sealed: ByteArray) :
            Parsed
    }

    private const val TYPE_0: Byte = 0
    private const val TYPE_1: Byte = 1

    /** The largest message read: the relay's own limit is far below it, and nothing Sign sends comes near. */
    const val MAX_MESSAGE_CHARS = 512 * 1024

    /** [plaintext] sealed as a type 0 envelope under [symKey], with a fresh random nonce unless one is given. */
    fun sealType0(symKey: ByteArray, plaintext: ByteArray, nonce: ByteArray = randomNonce()): String {
        val sealed = WcCrypto.chacha20Poly1305Seal(symKey, nonce, plaintext, EMPTY)
        return WcEncoding.base64(byteArrayOf(TYPE_0) + nonce + sealed)
    }

    /** [plaintext] sealed as a type 1 envelope under [symKey], naming [senderPublicKey]. */
    fun sealType1(
        symKey: ByteArray,
        senderPublicKey: ByteArray,
        plaintext: ByteArray,
        nonce: ByteArray = randomNonce(),
    ): String {
        requireKey(senderPublicKey, "the sender's public key")
        val sealed = WcCrypto.chacha20Poly1305Seal(symKey, nonce, plaintext, EMPTY)
        return WcEncoding.base64(byteArrayOf(TYPE_1) + senderPublicKey + nonce + sealed)
    }

    /**
     * The parts of the envelope [message], or null when it is not one: not base64, too long, of
     * another type (type 2, the unencrypted one, is never read), or too short to hold a tag.
     */
    fun parse(message: String): Parsed? {
        if (message.length > MAX_MESSAGE_CHARS) return null
        val bytes = WcEncoding.base64OrNull(message) ?: return null
        if (bytes.isEmpty()) return null
        return when (bytes[0]) {
            TYPE_0 -> {
                if (bytes.size < 1 + WC_NONCE_BYTES + WC_TAG_BYTES) return null
                Parsed.Type0(
                    nonce = bytes.copyOfRange(1, 1 + WC_NONCE_BYTES),
                    sealed = bytes.copyOfRange(1 + WC_NONCE_BYTES, bytes.size),
                )
            }
            TYPE_1 -> {
                val start = 1 + WC_KEY_BYTES
                if (bytes.size < start + WC_NONCE_BYTES + WC_TAG_BYTES) return null
                Parsed.Type1(
                    senderPublicKey = bytes.copyOfRange(1, start),
                    nonce = bytes.copyOfRange(start, start + WC_NONCE_BYTES),
                    sealed = bytes.copyOfRange(start + WC_NONCE_BYTES, bytes.size),
                )
            }
            else -> null
        }
    }

    /** The plaintext of [parsed] under [symKey], or null when it does not authenticate. */
    fun open(symKey: ByteArray, parsed: Parsed): ByteArray? =
        WcCrypto.chacha20Poly1305Open(symKey, parsed.nonce, parsed.sealed, EMPTY)

    private fun randomNonce(): ByteArray = EnvelopeCrypto.randomBytes(WC_NONCE_BYTES)

    private val EMPTY = ByteArray(0)
}

/** WalletConnect's key derivations (the Sign protocol's *Crypto* section). */
internal object WcKeys {

    /** A fresh X25519 private key: 32 random bytes, which libsodium clamps when it uses them. */
    fun newPrivateKey(): ByteArray = EnvelopeCrypto.randomBytes(WC_KEY_BYTES)

    /** A fresh symmetric key, for a pairing this side starts. */
    fun newSymKey(): ByteArray = EnvelopeCrypto.randomBytes(WC_KEY_BYTES)

    /** A fresh Ed25519 seed, for the relay's auth key. */
    fun newSeed(): ByteArray = EnvelopeCrypto.randomBytes(WC_KEY_BYTES)

    /**
     * The symmetric key two peers share: HKDF-SHA256 of their X25519 shared secret, with no salt
     * (so RFC 5869's zero salt) and no info, 32 bytes. Null when [peerPublicKey] is low-order.
     */
    fun symKey(selfPrivateKey: ByteArray, peerPublicKey: ByteArray): ByteArray? {
        val shared = WcCrypto.x25519(selfPrivateKey, peerPublicKey) ?: return null
        try {
            return Hkdf.sha256(shared, ByteArray(0), ByteArray(0), WC_KEY_BYTES)
        } finally {
            shared.fill(0)
        }
    }

    /** The topic a symmetric key's messages are published on: SHA-256 of the key, in hex. */
    fun topicOf(symKey: ByteArray): String {
        requireKey(symKey, "a symmetric key")
        return WcEncoding.hex(SHA256().digest(symKey))
    }
}
