package eth.serotine.data.walletconnect

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The envelopes, the key derivation, base58, `did:key` and the relay's token, against vectors made
 * independently (Python's `cryptography` package: X25519, HKDF-SHA256, ChaCha20-Poly1305 and
 * Ed25519 from OpenSSL) from RFC 7748's keys and RFC 8032's first seed.
 */
class WcEnvelopeTest {

    private fun hex(text: String) = checkNotNull(WcEncoding.hexOrNull(text))

    private val alicePrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    private val alicePublic = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
    private val bobPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    private val bobPublic = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
    private val symKey = hex("ea1d8a20f476d1e1ec952ca42708b8f7161ce7c81eadf97e520e2b40333decd5")
    private val nonce = hex("000102030405060708090a0b")
    private val ping = """{"id":1,"jsonrpc":"2.0","method":"wc_sessionPing","params":{}}""".encodeToByteArray()

    @Test
    fun bothSidesDeriveTheSameSymKeyAndTopic() {
        assertContentEquals(symKey, WcKeys.symKey(alicePrivate, bobPublic))
        assertContentEquals(symKey, WcKeys.symKey(bobPrivate, alicePublic))
        assertEquals("1ca1d70db64cab0f93de5934e27f7114e8e9fd7dd3c7145d81ce7f2dd2cd05c8", WcKeys.topicOf(symKey))
    }

    @Test
    fun aLowOrderPeerKeyDerivesNothing() {
        assertNull(WcKeys.symKey(alicePrivate, ByteArray(32)))
    }

    @Test
    fun aTypeZeroEnvelopeMatchesTheIndependentVector() {
        val expected = "AAABAgMEBQYHCAkKC1NpmVwoe1LoBKBdzMFtbY6qYKa2PRkZ4zOs55VRUm6CaXZJLLvgLytXMlKUqgTVeI0u4hTO8kwfaVwDIERjQ" +
            "orYy/tllqI/rSrjTCb9Cg=="
        assertEquals(expected, WcEnvelope.sealType0(symKey, ping, nonce))
        val parsed = assertIs<WcEnvelope.Parsed.Type0>(WcEnvelope.parse(expected))
        assertContentEquals(ping, WcEnvelope.open(symKey, parsed))
    }

    @Test
    fun aTypeOneEnvelopeNamesItsSender() {
        val expected = "AYUg8AmJMKdUdIt93LQ+91oNvzoNJjga9OukqY6qm05qAAECAwQFBgcICQoLU2mZXCh7UugEoF3MwW1tjqpgprY9GRnjM6znlVFSboJpd" +
            "kksu+AvK1cyUpSqBNV4jS7iFM7yTB9pXAMgRGNCitjL+2WWoj+tKuNMJv0K"
        assertEquals(expected, WcEnvelope.sealType1(symKey, alicePublic, ping, nonce))
        val parsed = assertIs<WcEnvelope.Parsed.Type1>(WcEnvelope.parse(expected))
        assertContentEquals(alicePublic, parsed.senderPublicKey)
        // The receiver derives the key from its own private key and the sender's public key.
        assertContentEquals(ping, WcEnvelope.open(checkNotNull(WcKeys.symKey(bobPrivate, parsed.senderPublicKey)), parsed))
    }

    @Test
    fun anEnvelopeIsReadWithOrWithoutItsPadding() {
        val padded = WcEnvelope.sealType0(symKey, ping, nonce)
        assertTrue(padded.endsWith("="))
        val parsed = checkNotNull(WcEnvelope.parse(padded.trimEnd('=')))
        assertContentEquals(ping, WcEnvelope.open(symKey, parsed))
    }

    @Test
    fun whatIsNotAnEnvelopeIsNotRead() {
        assertNull(WcEnvelope.parse(""))
        assertNull(WcEnvelope.parse("not base64!"))
        assertNull(WcEnvelope.parse(WcEncoding.base64(byteArrayOf(2) + ByteArray(40)))) // type 2: never read
        assertNull(WcEnvelope.parse(WcEncoding.base64(byteArrayOf(0) + ByteArray(27)))) // too short for a tag
        assertNull(WcEnvelope.parse(WcEncoding.base64(byteArrayOf(1) + ByteArray(59))))
        assertNull(WcEnvelope.parse("A".repeat(WcEnvelope.MAX_MESSAGE_CHARS + 4)))
    }

    @Test
    fun anotherKeyDoesNotOpenAnEnvelope() {
        val parsed = checkNotNull(WcEnvelope.parse(WcEnvelope.sealType0(symKey, ping)))
        assertNull(WcEnvelope.open(ByteArray(32), parsed))
    }

    @Test
    fun freshNoncesMakeEachEnvelopeDifferent() {
        assertTrue(WcEnvelope.sealType0(symKey, ping) != WcEnvelope.sealType0(symKey, ping))
    }

    @Test
    fun base58MatchesBitcoinsAlphabet() {
        assertEquals("StV1DL6CwTryKyV", WcEncoding.base58("hello world".encodeToByteArray()))
        assertEquals("115T", WcEncoding.base58(byteArrayOf(0, 0, 1, 2)))
        assertEquals("", WcEncoding.base58(ByteArray(0)))
    }

    @Test
    fun didKeyAndTheRelayTokenMatchTheIndependentVectors() {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val did = "did:key:z6MktwupdmLXVVqTzCw4i46r4uGyosGXRnR3XjN4Zq7oMMsw"
        assertEquals(did, WcRelayAuth.didKey(WcCrypto.ed25519PublicKey(seed)))
        val token = WcRelayAuth.token(
            seed = seed,
            audience = WcRelayAuth.DEFAULT_AUDIENCE,
            subject = "c479fe5dc464e771e78b193d239a65b58d278cad1c34bfb0b5716e5bb514928e",
            issuedAtSeconds = 1_700_000_000,
        )
        val expected = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9." +
            "eyJpc3MiOiJkaWQ6a2V5Ono2TWt0d3VwZG1MWFZWcVR6Q3c0aTQ2cjR1R3lvc0dYUm5SM1hqTjRacTdvTU1zdyIsInN1YiI6ImM0NzlmZT" +
            "VkYzQ2NGU3NzFlNzhiMTkzZDIzOWE2NWI1OGQyNzhjYWQxYzM0YmZiMGI1NzE2ZTViYjUxNDkyOGUiLCJhdWQiOiJ3c3M6Ly9yZWxheS53" +
            "YWxsZXRjb25uZWN0Lm9yZyIsImlhdCI6MTcwMDAwMDAwMCwiZXhwIjoxNzAwMDg2NDAwfQ." +
            "P9xjASpr8cmbOwaOFhT8agfmJ96iS-46mep2HkV1I1PENGXAMrJwzlld0d4drHpjtVq1eKBpK50hkwhzBDrgCQ"
        assertEquals(expected, token)
    }
}
