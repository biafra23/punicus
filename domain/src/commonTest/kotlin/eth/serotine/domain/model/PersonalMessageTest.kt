package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PersonalMessageTest {

    // Independent vectors: ethers' hashMessage, and pycryptodome's Keccak-256 over the EIP-191 prefix.
    @Test
    fun theDigestIsEip191sOverTheMessagesBytes() {
        assertEquals(
            Digest.of("d9eba16ed0ecae432b71fe008c98cc872bb4cc214d3220a36f365326cf807d68"),
            assertNotNull(PersonalMessage.of("hello world".encodeToByteArray())).digest(),
        )
        assertEquals(
            Digest.of("5f35dce98ba4fba25530a026ed80b2cecdaa31091ba4958b99b52ea1d068adad"),
            assertNotNull(PersonalMessage.ofHex("")).digest(),
        )
        assertEquals(
            Digest.of("d1c7f1a06a4f9a535077e50ad23244ce2c6ae443fcd412965226f3df5d28eaaa"),
            assertNotNull(PersonalMessage.ofHex("DEADbeef")).digest(),
        )
    }

    @Test
    fun textIsShownAsTextAndAnythingElseAsHex() {
        assertEquals("Sign in\n\tplease", PersonalMessage.of("Sign in\n\tplease".encodeToByteArray())?.text)
        assertEquals("héllo ✓", PersonalMessage.of("héllo ✓".encodeToByteArray())?.text)
        // Not UTF-8.
        assertNull(PersonalMessage.ofHex("deadbeef")?.text)
        assertEquals("deadbeef", PersonalMessage.ofHex("DEADBEEF")?.hex)
        // A control character, a right-to-left override, a zero-width space: shown as hex.
        assertNull(PersonalMessage.of("a\u0000b".encodeToByteArray())?.text)
        assertNull(PersonalMessage.of("pay \u202Eevil".encodeToByteArray())?.text)
        assertNull(PersonalMessage.of("ex\u200Bample.com".encodeToByteArray())?.text)
        assertNull(PersonalMessage.of("line\u2028break".encodeToByteArray())?.text)
    }

    @Test
    fun aMessageTooLongOrNotHexIsRefused() {
        assertNotNull(PersonalMessage.of(ByteArray(PersonalMessage.MAX_BYTES)))
        assertNull(PersonalMessage.of(ByteArray(PersonalMessage.MAX_BYTES + 1)))
        assertNull(PersonalMessage.ofHex("abc"))
        assertNull(PersonalMessage.ofHex("zz"))
        assertNull(PersonalMessage.ofHex("00".repeat(PersonalMessage.MAX_BYTES + 1)))
    }

    @Test
    fun aMessageIsItsBytes() {
        val bytes = byteArrayOf(1, 2, 3)
        val message = assertNotNull(PersonalMessage.of(bytes))
        bytes[0] = 9
        assertEquals("010203", message.hex)
        assertEquals(PersonalMessage.ofHex("010203"), message)
        assertEquals("PersonalMessage(3 bytes)", message.toString())
    }
}
