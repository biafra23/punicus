package eth.serotine.data.walletconnect

import com.sun.jna.Memory
import eth.serotine.data.keys.Sodium

/**
 * [WcCrypto] on Android and the desktop: libsodium through JNA, the binaries the key envelope
 * loads ([Sodium]). Every input and output goes through native buffers of our own, zeroed and
 * freed after the call, as the envelope's Argon2id does: JNA's own copies of byte arrays are
 * freed without being zeroed.
 */
internal actual object WcCrypto {

    actual fun available(): Boolean = Sodium.library != null

    actual fun x25519PublicKey(privateKey: ByteArray): ByteArray {
        requireKey(privateKey, "an X25519 private key")
        return native { buffers ->
            val n = buffers.of(privateKey)
            val q = buffers.out(WC_KEY_BYTES)
            check(sodium().crypto_scalarmult_curve25519_base(q, n) == 0) { "X25519 failed" }
            q.getByteArray(0, WC_KEY_BYTES)
        }
    }

    actual fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray? {
        requireKey(privateKey, "an X25519 private key")
        requireKey(publicKey, "an X25519 public key")
        return native { buffers ->
            val n = buffers.of(privateKey)
            val p = buffers.of(publicKey)
            val q = buffers.out(WC_KEY_BYTES)
            // -1: the shared secret is all zeros (a low-order public key).
            if (sodium().crypto_scalarmult_curve25519(q, n, p) != 0) null else q.getByteArray(0, WC_KEY_BYTES)
        }
    }

    actual fun chacha20Poly1305Seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        requireAead(key, nonce)
        return native { buffers ->
            val out = buffers.out(plaintext.size + WC_TAG_BYTES)
            val result = sodium().crypto_aead_chacha20poly1305_ietf_encrypt(
                out, null,
                buffers.ofOrNull(plaintext), plaintext.size.toLong(),
                buffers.ofOrNull(aad), aad.size.toLong(),
                null, buffers.of(nonce), buffers.of(key),
            )
            check(result == 0) { "ChaCha20-Poly1305 sealing failed" }
            out.getByteArray(0, plaintext.size + WC_TAG_BYTES)
        }
    }

    actual fun chacha20Poly1305Open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray? {
        requireAead(key, nonce)
        if (ciphertext.size < WC_TAG_BYTES) return null
        val length = ciphertext.size - WC_TAG_BYTES
        return native { buffers ->
            val out = if (length == 0) null else buffers.out(length)
            val result = sodium().crypto_aead_chacha20poly1305_ietf_decrypt(
                out, null,
                null,
                buffers.of(ciphertext), ciphertext.size.toLong(),
                buffers.ofOrNull(aad), aad.size.toLong(),
                buffers.of(nonce), buffers.of(key),
            )
            when {
                result != 0 -> null
                out == null -> ByteArray(0)
                else -> out.getByteArray(0, length)
            }
        }
    }

    actual fun ed25519PublicKey(seed: ByteArray): ByteArray {
        requireKey(seed, "an Ed25519 seed")
        return native { buffers ->
            val pk = buffers.out(WC_KEY_BYTES)
            val sk = buffers.out(ED25519_SECRET_BYTES)
            check(sodium().crypto_sign_ed25519_seed_keypair(pk, sk, buffers.of(seed)) == 0) { "Ed25519 failed" }
            pk.getByteArray(0, WC_KEY_BYTES)
        }
    }

    actual fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        requireKey(seed, "an Ed25519 seed")
        return native { buffers ->
            val pk = buffers.out(WC_KEY_BYTES)
            val sk = buffers.out(ED25519_SECRET_BYTES)
            check(sodium().crypto_sign_ed25519_seed_keypair(pk, sk, buffers.of(seed)) == 0) { "Ed25519 failed" }
            val sig = buffers.out(WC_SIGNATURE_BYTES)
            val result = sodium().crypto_sign_ed25519_detached(
                sig, null, buffers.ofOrNull(message), message.size.toLong(), sk,
            )
            check(result == 0) { "Ed25519 signing failed" }
            sig.getByteArray(0, WC_SIGNATURE_BYTES)
        }
    }

    actual fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != WC_KEY_BYTES || signature.size != WC_SIGNATURE_BYTES) return false
        return native { buffers ->
            sodium().crypto_sign_ed25519_verify_detached(
                buffers.of(signature), buffers.ofOrNull(message), message.size.toLong(), buffers.of(publicKey),
            ) == 0
        }
    }

    private fun sodium(): Sodium.Library = checkNotNull(Sodium.library) { "libsodium is not available" }

    /** Runs [block] with native buffers that are all zeroed and freed when it returns. */
    private inline fun <R> native(block: (Buffers) -> R): R {
        val buffers = Buffers()
        try {
            return block(buffers)
        } finally {
            buffers.release()
        }
    }

    private class Buffers {
        private val held = ArrayList<Memory>()

        /** A native copy of [bytes], which must not be empty (JNA's Memory cannot be). */
        fun of(bytes: ByteArray): Memory = out(bytes.size).also { it.write(0, bytes, 0, bytes.size) }

        /** A native copy of [bytes], or null when it is empty (libsodium reads no byte of it then). */
        fun ofOrNull(bytes: ByteArray): Memory? = if (bytes.isEmpty()) null else of(bytes)

        fun out(size: Int): Memory = Memory(size.toLong()).also(held::add)

        fun release() {
            for (memory in held) {
                memory.clear()
                memory.close()
            }
            held.clear()
        }
    }

    /** libsodium's Ed25519 secret key: the seed and the public key. */
    private const val ED25519_SECRET_BYTES = 64
}
