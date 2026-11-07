package eth.serotine.data.walletconnect

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.DappTransactionRequest
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.Signature
import eth.serotine.domain.repository.DappAsk
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class WcRequestsTest {

    private val account = Address.of("0x00000000000000000000000000000000000000a1")

    private fun decode(method: String, params: String, chain: String = "eip155:11155111"): DappAsk =
        WcRequests.decode(method, Json.parseToJsonElement(params), chain)

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
    fun aMethodOutOfShapeIsMalformed() {
        assertEquals(DappAsk.Malformed("eth_sendTransaction"), decode("eth_sendTransaction", "[]"))
        assertEquals(DappAsk.Malformed("eth_signTypedData_v4"), decode("eth_signTypedData_v4", "[]"))
        assertEquals(DappAsk.NotAnsweredYet("eth_foo"), decode("eth_foo", "[]"))
    }

    private val other = Address.of("0x00000000000000000000000000000000000000b2")

    private fun transaction(fields: String) = decode("eth_sendTransaction", """[{"from": "${account.hex}", "to": "${other.hex}"$fields}]""")

    @Test
    fun aTransactionsFieldsAreAppliedOrRefused() {
        assertEquals(DappAsk.SendTransaction(DappTransactionRequest(account, other, UInt256.ZERO, ByteArray(0), null)), transaction(""))
        assertEquals(
            DappAsk.SendTransaction(DappTransactionRequest(account, other, UInt256.of(255), byteArrayOf(1, 2), 21_000)),
            transaction(""", "value": "0xff", "data": "0x0102", "gas": "0x5208", "chainId": "0xaa36a7", "type": "0x2", "accessList": []"""),
        )
        // input stands for data, gasLimit for gas: the same when both are given.
        assertEquals(transaction(""", "data": "0x0102", "gas": "0x5208""""), transaction(""", "input": "0x0102", "gasLimit": "0x5208""""))
        assertEquals(transaction(""", "data": "0x0102""""), transaction(""", "data": "0x0102", "input": "0x0102""""))
        val malformed = DappAsk.Malformed("eth_sendTransaction")
        for (fields in listOf(
            """, "nonce": "0x1"""",
            """, "gasPrice": "0x1"""",
            """, "maxFeePerGas": "0x1"""",
            """, "maxPriorityFeePerGas": "0x1"""",
            """, "chainId": "0x1"""",
            """, "type": "0x0"""",
            """, "accessList": [{"address": "${other.hex}", "storageKeys": []}]""",
            """, "data": "0x0102", "input": "0x0103"""",
            """, "data": "0x012"""",
            """, "value": "255"""",
            """, "value": 255""",
            """, "gas": "0x0"""",
            """, "from2": "x"""",
        )) {
            assertEquals(malformed, transaction(fields), fields)
        }
        // No contract creation: a recipient is required.
        assertEquals(malformed, decode("eth_sendTransaction", """[{"from": "${account.hex}"}]"""))
    }

    @Test
    fun aTransactionsChainIsTheRequestsAndItsGasFitsATransactionOnEveryNetwork() {
        fun on(chain: String, fields: String) =
            decode("eth_sendTransaction", """[{"from": "${account.hex}", "to": "${other.hex}"$fields}]""", chain)
        val plain = DappAsk.SendTransaction(DappTransactionRequest(account, other, UInt256.ZERO, ByteArray(0), null))
        assertEquals(plain, on("eip155:1", """, "chainId": "0x1""""))
        assertEquals(plain, on("eip155:100", """, "chainId": "0x64""""))
        val malformed = DappAsk.Malformed("eth_sendTransaction")
        // Another chain than the request's is refused, never sent there or here.
        assertEquals(malformed, on("eip155:100", """, "chainId": "0x1""""))
        assertEquals(malformed, on("eip155:1", """, "chainId": "0xaa36a7""""))
        assertEquals(malformed, on("eip155:137", ""), "a chain Punicus does not serve")
        // EIP-7825's cap, 2^24: below it is applied, above it refused.
        assertEquals(16_777_216L, (on("eip155:1", """, "gas": "0x1000000"""") as DappAsk.SendTransaction).transaction.gas)
        assertEquals(malformed, on("eip155:1", """, "gas": "0x1000001""""))
    }

    @Test
    fun aSignatureIsAnsweredAsRsAndV() {
        val signature = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), 1)
        assertEquals("0x" + "1".padStart(64, '0') + "2".padStart(64, '0') + "1c", WcRequests.signatureHex(signature))
        assertEquals("1b", WcRequests.signatureHex(signature.copy(recoveryId = 0)).takeLast(2))
    }
}
