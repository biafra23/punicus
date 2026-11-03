package eth.serotine.data.walletconnect

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.Signature
import eth.serotine.domain.repository.DappAsk
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class WcRequestsTest {

    private val account = Address.of("0x00000000000000000000000000000000000000a1")

    private fun decode(method: String, params: String): DappAsk = WcRequests.decode(method, Json.parseToJsonElement(params))

    @Test
    fun personalSignTakesTheMessageAndTheAccountInThatOrder() {
        assertEquals(
            DappAsk.PersonalSign(account, PersonalMessage.ofHex("68656c6c6f")!!),
            decode("personal_sign", """["0x68656c6c6f", "${account.hex}"]"""),
        )
        // Not 0x and hex: the text's own bytes, as wallets read it.
        assertEquals(
            DappAsk.PersonalSign(account, PersonalMessage.of("hello".encodeToByteArray())!!),
            decode("personal_sign", """["hello", "${account.hex}"]"""),
        )
        assertEquals(
            DappAsk.PersonalSign(account, PersonalMessage.of("0xnot hex".encodeToByteArray())!!),
            decode("personal_sign", """["0xnot hex", "${account.hex}"]"""),
        )
    }

    @Test
    fun personalSignOutOfShapeIsMalformed() {
        val malformed = DappAsk.Malformed("personal_sign")
        assertEquals(malformed, decode("personal_sign", """["${account.hex}", "0x68656c6c6f"]"""))
        assertEquals(malformed, decode("personal_sign", """["0x68656c6c6f", "${account.hex}", ""]"""))
        assertEquals(malformed, decode("personal_sign", """["0x68656c6c6f"]"""))
        assertEquals(malformed, decode("personal_sign", """[1, "${account.hex}"]"""))
        assertEquals(malformed, decode("personal_sign", """{"message": "0x00"}"""))
        val tooLong = "a".repeat(PersonalMessage.MAX_BYTES + 1)
        assertEquals(malformed, decode("personal_sign", """["$tooLong", "${account.hex}"]"""))
    }

    @Test
    fun switchChainTakesOneChainIdInHex() {
        assertEquals(DappAsk.SwitchChain(11_155_111), decode("wallet_switchEthereumChain", """[{"chainId": "0xaa36a7"}]"""))
        assertEquals(DappAsk.SwitchChain(1), decode("wallet_switchEthereumChain", """[{"chainId": "0x1"}]"""))
        val malformed = DappAsk.Malformed("wallet_switchEthereumChain")
        for (params in listOf("""[{"chainId": "0x01"}]""", """[{"chainId": 1}]""", """[{"chainId": "0x1", "x": 1}]""", "[]")) {
            assertEquals(malformed, decode("wallet_switchEthereumChain", params), params)
        }
    }

    @Test
    fun theOtherMethodsAreNotAnsweredYet() {
        assertEquals(DappAsk.NotAnsweredYet("eth_sendTransaction"), decode("eth_sendTransaction", "[]"))
        assertEquals(DappAsk.NotAnsweredYet("eth_signTypedData_v4"), decode("eth_signTypedData_v4", "[]"))
    }

    @Test
    fun aSignatureIsAnsweredAsRsAndV() {
        val signature = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), 1)
        assertEquals("0x" + "1".padStart(64, '0') + "2".padStart(64, '0') + "1c", WcRequests.signatureHex(signature))
        assertEquals("1b", WcRequests.signatureHex(signature.copy(recoveryId = 0)).takeLast(2))
    }
}
