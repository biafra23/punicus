package eth.serotine.data.walletconnect

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class WcPairingUriTest {

    private val topic = "7f6e504bfad60b485450578e05678ed3e8e8c4751d3c6160be17160d63ec90f9"
    private val key = "587d5484ce2a2a6ee3ba1962fdd7e8588e06200c46823bd18fbd67def96ad303"

    private fun parsed(text: String): WcPairingUri = assertIs<WcPairingUri.Parse.Parsed>(WcPairingUri.parse(text)).uri

    @Test
    fun aReferenceClientsUriIsRead() {
        val uri = parsed(
            "wc:$topic@2?relay-protocol=irn&symKey=$key&expiryTimestamp=1705321340" +
                "&methods=%5Bwc_sessionPropose%5D%2C%5Bwc_authRequest%2Cwc_authBatchRequest%5D",
        )
        assertEquals(topic, uri.topic)
        assertContentEquals(WcEncoding.hexOrNull(key), uri.symKey)
        assertEquals(1_705_321_340, uri.expirySeconds)
        assertEquals(listOf("wc_sessionPropose", "wc_authRequest", "wc_authBatchRequest"), uri.methods)
    }

    @Test
    fun theOptionalParametersMayBeMissingAndHexIsLowerCased() {
        val uri = parsed("wc:${topic.uppercase()}@2?symKey=$key&relay-protocol=irn")
        assertEquals(topic, uri.topic)
        assertNull(uri.expirySeconds)
        assertEquals(emptyList(), uri.methods)
    }

    @Test
    fun theKeyNeverAppearsInToString() {
        assertFalse(key in parsed("wc:$topic@2?relay-protocol=irn&symKey=$key").toString())
    }

    @Test
    fun aV1UriIsAnUnsupportedVersion() {
        assertEquals(
            WcPairingUri.Parse.UnsupportedVersion("1"),
            WcPairingUri.parse("wc:8a5e5bdc-a0e4-4702-ba63-8f1a5655744f@1?bridge=https%3A%2F%2Fbridge.walletconnect.org&key=41791"),
        )
    }

    @Test
    fun aCustomRelayOrAnUnknownParameterIsRefusedNotIgnored() {
        assertEquals(
            WcPairingUri.Parse.UnsupportedParameter("relay-data"),
            WcPairingUri.parse("wc:$topic@2?relay-protocol=irn&symKey=$key&relay-data=abc"),
        )
        assertEquals(
            WcPairingUri.Parse.UnsupportedParameter("future"),
            WcPairingUri.parse("wc:$topic@2?relay-protocol=irn&symKey=$key&future=1"),
        )
    }

    @Test
    fun anythingOutOfShapeIsMalformed() {
        val malformed = listOf(
            "",
            "https://example.org",
            "wc:$topic@2",
            "wc:$topic@2?relay-protocol=waku&symKey=$key",
            "wc:$topic@2?symKey=$key",
            "wc:$topic@2?relay-protocol=irn",
            "wc:${topic.dropLast(2)}@2?relay-protocol=irn&symKey=$key",
            "wc:$topic@2?relay-protocol=irn&symKey=${key}00",
            "wc:$topic@2?relay-protocol=irn&symKey=$key&symKey=$key",
            "wc:$topic@2?relay-protocol=irn&symKey=$key&expiryTimestamp=soon",
            "wc:$topic@2?relay-protocol=irn&symKey=$key&methods=%5Bwc_session%3BPropose%5D",
            "wc:$topic@2?relay-protocol=irn&symKey=%zz",
            "wc:$topic@2?relay-protocol=irn&symKey=$key" + "&x".repeat(WcPairingUri.MAX_CHARS),
        )
        for (text in malformed) {
            assertIs<WcPairingUri.Parse>(WcPairingUri.parse(text))
            assertFalse(WcPairingUri.parse(text) is WcPairingUri.Parse.Parsed, text)
        }
    }
}
