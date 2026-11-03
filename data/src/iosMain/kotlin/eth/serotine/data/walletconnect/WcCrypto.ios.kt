package eth.serotine.data.walletconnect

import eth.serotine.data.keys.sodium.crypto_aead_chacha20poly1305_ietf_decrypt
import eth.serotine.data.keys.sodium.crypto_aead_chacha20poly1305_ietf_encrypt
import eth.serotine.data.keys.sodium.crypto_scalarmult_curve25519
import eth.serotine.data.keys.sodium.crypto_scalarmult_curve25519_base
import eth.serotine.data.keys.sodium.crypto_sign_ed25519_detached
import eth.serotine.data.keys.sodium.crypto_sign_ed25519_seed_keypair
import eth.serotine.data.keys.sodium.crypto_sign_ed25519_verify_detached
import eth.serotine.data.keys.sodium.sodium_init
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

/**
 * [WcCrypto] on iOS: libsodium (the bindings' libsodium.a, 1.0.19, which the key envelope links
 * too), its functions declared in src/nativeInterop/cinterop/sodium.def. The arrays are pinned,
 * not copied, so libsodium reads and writes the caller's own bytes and no native copy is left.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object WcCrypto {

    // 0: initialized now, 1: already initialized; -1: failed.
    private val ready: Boolean by lazy { sodium_init() >= 0 }

    actual fun available(): Boolean = ready

    actual fun x25519PublicKey(privateKey: ByteArray): ByteArray {
        requireKey(privateKey, "an X25519 private key")
        checkReady()
        val q = ByteArray(WC_KEY_BYTES)
        val result = q.withBytes { qPtr ->
            privateKey.withBytes { nPtr -> crypto_scalarmult_curve25519_base(qPtr, nPtr) }
        }
        check(result == 0) { "X25519 failed" }
        return q
    }

    actual fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray? {
        requireKey(privateKey, "an X25519 private key")
        requireKey(publicKey, "an X25519 public key")
        checkReady()
        val q = ByteArray(WC_KEY_BYTES)
        val result = q.withBytes { qPtr ->
            privateKey.withBytes { nPtr ->
                publicKey.withBytes { pPtr -> crypto_scalarmult_curve25519(qPtr, nPtr, pPtr) }
            }
        }
        // -1: the shared secret is all zeros (a low-order public key).
        return if (result == 0) q else null
    }

    actual fun chacha20Poly1305Seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        requireAead(key, nonce)
        checkReady()
        val out = ByteArray(plaintext.size + WC_TAG_BYTES)
        val result = out.withBytes { outPtr ->
            plaintext.withBytes { inPtr ->
                aad.withBytes { aadPtr ->
                    nonce.withBytes { noncePtr ->
                        key.withBytes { keyPtr ->
                            crypto_aead_chacha20poly1305_ietf_encrypt(
                                outPtr, null,
                                inPtr, plaintext.size.convert(),
                                aadPtr, aad.size.convert(),
                                null, noncePtr, keyPtr,
                            )
                        }
                    }
                }
            }
        }
        check(result == 0) { "ChaCha20-Poly1305 sealing failed" }
        return out
    }

    actual fun chacha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray? {
        requireAead(key, nonce)
        checkReady()
        if (ciphertext.size < WC_TAG_BYTES) return null
        val out = ByteArray(ciphertext.size - WC_TAG_BYTES)
        val result = out.withBytes { outPtr ->
            ciphertext.withBytes { inPtr ->
                aad.withBytes { aadPtr ->
                    nonce.withBytes { noncePtr ->
                        key.withBytes { keyPtr ->
                            crypto_aead_chacha20poly1305_ietf_decrypt(
                                outPtr, null,
                                null,
                                inPtr, ciphertext.size.convert(),
                                aadPtr, aad.size.convert(),
                                noncePtr, keyPtr,
                            )
                        }
                    }
                }
            }
        }
        return if (result == 0) out else null
    }

    actual fun ed25519PublicKey(seed: ByteArray): ByteArray {
        requireKey(seed, "an Ed25519 seed")
        checkReady()
        val pk = ByteArray(WC_KEY_BYTES)
        val sk = ByteArray(ED25519_SECRET_BYTES)
        try {
            val result = pk.withBytes { pkPtr ->
                sk.withBytes { skPtr ->
                    seed.withBytes { seedPtr -> crypto_sign_ed25519_seed_keypair(pkPtr, skPtr, seedPtr) }
                }
            }
            check(result == 0) { "Ed25519 failed" }
            return pk
        } finally {
            sk.fill(0)
        }
    }

    actual fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        requireKey(seed, "an Ed25519 seed")
        checkReady()
        val pk = ByteArray(WC_KEY_BYTES)
        val sk = ByteArray(ED25519_SECRET_BYTES)
        try {
            val derived = pk.withBytes { pkPtr ->
                sk.withBytes { skPtr ->
                    seed.withBytes { seedPtr -> crypto_sign_ed25519_seed_keypair(pkPtr, skPtr, seedPtr) }
                }
            }
            check(derived == 0) { "Ed25519 failed" }
            val signature = ByteArray(WC_SIGNATURE_BYTES)
            val result = signature.withBytes { sigPtr ->
                message.withBytes { mPtr ->
                    sk.withBytes { skPtr ->
                        crypto_sign_ed25519_detached(sigPtr, null, mPtr, message.size.convert(), skPtr)
                    }
                }
            }
            check(result == 0) { "Ed25519 signing failed" }
            return signature
        } finally {
            sk.fill(0)
        }
    }

    actual fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != WC_KEY_BYTES || signature.size != WC_SIGNATURE_BYTES) return false
        checkReady()
        val result = signature.withBytes { sigPtr ->
            message.withBytes { mPtr ->
                publicKey.withBytes { pkPtr ->
                    crypto_sign_ed25519_verify_detached(sigPtr, mPtr, message.size.convert(), pkPtr)
                }
            }
        }
        return result == 0
    }

    private fun checkReady() = check(ready) { "libsodium is not available" }

    /** libsodium's Ed25519 secret key: the seed and the public key. */
    private const val ED25519_SECRET_BYTES = 64
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <R> ByteArray.withBytes(block: (CPointer<UByteVar>?) -> R): R =
    if (isEmpty()) block(null) else usePinned { block(it.addressOf(0).reinterpret()) }
