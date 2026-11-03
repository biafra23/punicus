package eth.serotine.data.walletconnect

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [WcCrypto] against the RFCs' own test vectors: RFC 7748 (X25519), RFC 8439 (the AEAD), RFC 8032 (Ed25519). */
class WcCryptoTest {

    private fun hex(text: String) = checkNotNull(WcEncoding.hexOrNull(text))

    private val alicePrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    private val alicePublic = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
    private val bobPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    private val bobPublic = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
    private val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

    @Test
    fun libsodiumLoads() {
        assertTrue(WcCrypto.available())
    }

    @Test
    fun x25519MatchesRfc7748() {
        assertContentEquals(alicePublic, WcCrypto.x25519PublicKey(alicePrivate))
        assertContentEquals(bobPublic, WcCrypto.x25519PublicKey(bobPrivate))
        assertContentEquals(shared, WcCrypto.x25519(alicePrivate, bobPublic))
        assertContentEquals(shared, WcCrypto.x25519(bobPrivate, alicePublic))
    }

    @Test
    fun aLowOrderPublicKeyGivesNoSharedSecret() {
        assertNull(WcCrypto.x25519(alicePrivate, ByteArray(32)))
    }

    @Test
    fun chacha20Poly1305MatchesRfc8439() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, " +
            "sunscreen would be it.").encodeToByteArray()
        val expected = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b" +
                "1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116" + "1ae10b594f09e26a7e902ecbd0600691",
        )
        val sealed = WcCrypto.chacha20Poly1305Seal(key, nonce, plaintext, aad)
        assertContentEquals(expected, sealed)
        assertContentEquals(plaintext, WcCrypto.chacha20Poly1305Open(key, nonce, sealed, aad))
    }

    @Test
    fun aTamperedCiphertextOrWrongAadDoesNotOpen() {
        val key = ByteArray(32) { 7 }
        val nonce = ByteArray(12)
        val sealed = WcCrypto.chacha20Poly1305Seal(key, nonce, "hi".encodeToByteArray(), ByteArray(0))
        val tampered = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(WcCrypto.chacha20Poly1305Open(key, nonce, tampered, ByteArray(0)))
        assertNull(WcCrypto.chacha20Poly1305Open(key, nonce, sealed, byteArrayOf(1)))
        assertNull(WcCrypto.chacha20Poly1305Open(key, nonce, ByteArray(15), ByteArray(0)))
    }

    @Test
    fun anEmptyPlaintextRoundTrips() {
        val key = ByteArray(32) { 3 }
        val nonce = ByteArray(12) { 1 }
        val sealed = WcCrypto.chacha20Poly1305Seal(key, nonce, ByteArray(0), ByteArray(0))
        assertEquals(16, sealed.size)
        assertContentEquals(ByteArray(0), WcCrypto.chacha20Poly1305Open(key, nonce, sealed, ByteArray(0)))
    }

    @Test
    fun ed25519MatchesRfc8032TestOne() {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val publicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46b" +
                "d25bf5f0595bbe24655141438e7a100b",
        )
        assertContentEquals(publicKey, WcCrypto.ed25519PublicKey(seed))
        assertContentEquals(signature, WcCrypto.ed25519Sign(seed, ByteArray(0)))
        assertTrue(WcCrypto.ed25519Verify(publicKey, ByteArray(0), signature))
        assertFalse(WcCrypto.ed25519Verify(publicKey, byteArrayOf(0), signature))
    }
}
