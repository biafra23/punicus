package eth.serotine.data.walletconnect

/**
 * The primitives WalletConnect v2 is built from (docs/walletconnect.md, *Architecture*), all from
 * libsodium, the same library the key envelope uses: X25519 key agreement, ChaCha20-Poly1305 in its
 * IETF form (a 12-byte nonce, the 16-byte tag appended), and Ed25519 for the relay's auth token.
 * Android and the desktop have them in jvmSharedMain, over JNA; iOS in iosMain, over cinterop.
 *
 * None of these keys is an account key: they protect a session's messages, and none can move funds
 * (owner ruling, 2026-11-03, #22, ruling 8). They are still never logged.
 */
internal expect object WcCrypto {

    /** Whether libsodium works on this device. Every other call needs it. */
    fun available(): Boolean

    /** The X25519 public key of the 32-byte [privateKey] (`crypto_scalarmult_curve25519_base`). */
    fun x25519PublicKey(privateKey: ByteArray): ByteArray

    /**
     * The X25519 shared secret of [privateKey] and [publicKey], both 32 bytes, or null when it is
     * all zeros: a low-order [publicKey], which libsodium refuses (RFC 7748, section 6.1).
     */
    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray?

    /** ChaCha20-Poly1305 (IETF) of [plaintext] under the 32-byte [key] and 12-byte [nonce]; the tag is appended. */
    fun chacha20Poly1305Seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /** The plaintext of [ciphertext] (tag appended), or null when it does not authenticate under [key] and [aad]. */
    fun chacha20Poly1305Open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray?

    /** The 32-byte Ed25519 public key of the 32-byte [seed] (`crypto_sign_ed25519_seed_keypair`). */
    fun ed25519PublicKey(seed: ByteArray): ByteArray

    /** The 64-byte Ed25519 signature of [message] by the key of the 32-byte [seed]. */
    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray

    /** Whether [signature] is [publicKey]'s Ed25519 signature of [message]. */
    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/** The size of every key, seed and symmetric key here. */
internal const val WC_KEY_BYTES = 32

/** ChaCha20-Poly1305's nonce in its IETF form. */
internal const val WC_NONCE_BYTES = 12

/** Poly1305's tag. */
internal const val WC_TAG_BYTES = 16

/** An Ed25519 signature. */
internal const val WC_SIGNATURE_BYTES = 64

internal fun requireKey(bytes: ByteArray, what: String) {
    require(bytes.size == WC_KEY_BYTES) { "$what must be $WC_KEY_BYTES bytes" }
}

internal fun requireAead(key: ByteArray, nonce: ByteArray) {
    requireKey(key, "the key")
    require(nonce.size == WC_NONCE_BYTES) { "the nonce must be $WC_NONCE_BYTES bytes" }
}
