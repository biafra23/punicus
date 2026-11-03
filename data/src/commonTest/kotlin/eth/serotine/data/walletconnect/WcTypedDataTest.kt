package eth.serotine.data.walletconnect

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.TypedDataMeaning
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.DappAsk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * `eth_signTypedData_v4` read strictly from its JSON, and hashed. The digests are an independent
 * implementation's: eth-account 0.13.4's `encode_typed_data`.
 */
class WcTypedDataTest {

    private val account = Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")

    private val arrays = """{"types": {"EIP712Domain": [{"name": "name", "type": "string"}, {"name": "version", "type": "string"}, {"name": "chainId", "type": "uint256"}, {"name": "verifyingContract", "type": "address"}], "Person": [{"name": "name", "type": "string"}, {"name": "wallets", "type": "address[]"}], "Mail": [{"name": "from", "type": "Person"}, {"name": "to", "type": "Person[]"}, {"name": "contents", "type": "string"}]}, "primaryType": "Mail", "domain": {"name": "Ether Mail", "version": "1", "chainId": 11155111, "verifyingContract": "0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"}, "message": {"from": {"name": "Cow", "wallets": ["0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826", "0xDeaDbeefdEAdbeefdEadbEEFdeadbeEFdEaDbeeF"]}, "to": [{"name": "Bob", "wallets": ["0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB", "0xB0BdaBea57B0BDABeA57b0bdABEA57b0BDabEa57", "0xB0B0b0b0b0b0B000000000000000000000000000"]}], "contents": "Hello, Bob!"}}"""
    private val atoms = """{"types": {"EIP712Domain": [{"name": "name", "type": "string"}, {"name": "version", "type": "string"}, {"name": "chainId", "type": "uint256"}, {"name": "verifyingContract", "type": "address"}], "Atoms": [{"name": "flag", "type": "bool"}, {"name": "small", "type": "uint8"}, {"name": "neg", "type": "int256"}, {"name": "neg8", "type": "int8"}, {"name": "blob", "type": "bytes"}, {"name": "word", "type": "bytes32"}, {"name": "four", "type": "bytes4"}, {"name": "grid", "type": "uint16[2][]"}, {"name": "big", "type": "uint256"}]}, "primaryType": "Atoms", "domain": {"name": "Atoms", "version": "2", "chainId": 11155111, "verifyingContract": "0x0000000000000000000000000000000000000001"}, "message": {"flag": true, "small": 255, "neg": -1, "neg8": -128, "blob": "0xdeadbeef", "word": "0x1111111111111111111111111111111111111111111111111111111111111111", "four": "0xcafebabe", "grid": [[1, 2], [3, 65535]], "big": "115792089237316195423570985008687907853269984665640564039457584007913129639935"}}"""
    private val permit = """{"types": {"EIP712Domain": [{"name": "name", "type": "string"}, {"name": "version", "type": "string"}, {"name": "chainId", "type": "uint256"}, {"name": "verifyingContract", "type": "address"}], "Permit": [{"name": "owner", "type": "address"}, {"name": "spender", "type": "address"}, {"name": "value", "type": "uint256"}, {"name": "nonce", "type": "uint256"}, {"name": "deadline", "type": "uint256"}]}, "primaryType": "Permit", "domain": {"name": "USD Coin", "version": "2", "chainId": 11155111, "verifyingContract": "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"}, "message": {"owner": "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "spender": "0x000000000022D473030F116dDEE9F6B43aC78BA3", "value": "1000000", "nonce": 0, "deadline": 1793703600}}"""
    private val permit2 = """{"types": {"EIP712Domain": [{"name": "name", "type": "string"}, {"name": "chainId", "type": "uint256"}, {"name": "verifyingContract", "type": "address"}], "PermitSingle": [{"name": "details", "type": "PermitDetails"}, {"name": "spender", "type": "address"}, {"name": "sigDeadline", "type": "uint256"}], "PermitDetails": [{"name": "token", "type": "address"}, {"name": "amount", "type": "uint160"}, {"name": "expiration", "type": "uint48"}, {"name": "nonce", "type": "uint48"}]}, "primaryType": "PermitSingle", "domain": {"name": "Permit2", "chainId": 11155111, "verifyingContract": "0x000000000022D473030F116dDEE9F6B43aC78BA3"}, "message": {"details": {"token": "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", "amount": "1461501637330902918203684832716283019655932542975", "expiration": "1796295600", "nonce": "0"}, "spender": "0x3fC91A3afd70395Cd496C647d5a6CC9D4B2b7FAD", "sigDeadline": "1793705400"}}"""
    private val cow = """{"types": {"EIP712Domain": [{"name": "name", "type": "string"}, {"name": "version", "type": "string"}, {"name": "chainId", "type": "uint256"}, {"name": "verifyingContract", "type": "address"}], "Order": [{"name": "sellToken", "type": "address"}, {"name": "buyToken", "type": "address"}, {"name": "receiver", "type": "address"}, {"name": "sellAmount", "type": "uint256"}, {"name": "buyAmount", "type": "uint256"}, {"name": "validTo", "type": "uint32"}, {"name": "appData", "type": "bytes32"}, {"name": "feeAmount", "type": "uint256"}, {"name": "kind", "type": "string"}, {"name": "partiallyFillable", "type": "bool"}, {"name": "sellTokenBalance", "type": "string"}, {"name": "buyTokenBalance", "type": "string"}]}, "primaryType": "Order", "domain": {"name": "Gnosis Protocol", "version": "v2", "chainId": 11155111, "verifyingContract": "0x9008D19f58AAbD9eD0D60971565AA8510560ab41"}, "message": {"sellToken": "0xfFf9976782d46CC05630D1f6eBAb18b2324d6B14", "buyToken": "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", "receiver": "0x0000000000000000000000000000000000000000", "sellAmount": "1000000000000000000", "buyAmount": "2500000000", "validTo": 1793703600, "appData": "0x0000000000000000000000000000000000000000000000000000000000000000", "feeAmount": "0", "kind": "sell", "partiallyFillable": false, "sellTokenBalance": "erc20", "buyTokenBalance": "erc20"}}"""

    private fun decode(typed: String, asString: Boolean = true): DappAsk {
        val second = if (asString) JsonPrimitive(typed) else Json.parseToJsonElement(typed)
        return WcRequests.decode("eth_signTypedData_v4", JsonArray(listOf(JsonPrimitive(account.hex), second)))
    }

    private fun digestOf(typed: String, asString: Boolean = true): Digest =
        assertIs<DappAsk.SignTypedData>(decode(typed, asString)).typed.digest

    @Test
    fun theDigestsAreAnIndependentImplementations() {
        assertEquals(Digest.of("ea37d2c7e934e62ee87034b2304098326dfd5c3d36402030e107f60818d9c8ea"), digestOf(arrays))
        assertEquals(Digest.of("aa34fc8b8995531a5c67b62f17f226b51b8c4396fc7ccbfb4a5a822bb37f9c4c"), digestOf(atoms))
        assertEquals(Digest.of("231cd5c8abb3c215e707a434e00e76fdf9e08f40ca277bf491e25c421710c92f"), digestOf(permit))
        assertEquals(Digest.of("913330419c1bf4a07809ffc622602d696652e51f9764961d0f390a56d041ab87"), digestOf(permit2))
        assertEquals(Digest.of("2618632ca5cec3353ab2aac7b338294161f54926bc87148ea8b00ecfa2286b22"), digestOf(cow))
        // The typed data as a JSON object, not a string, reads the same.
        assertEquals(Digest.of("ea37d2c7e934e62ee87034b2304098326dfd5c3d36402030e107f60818d9c8ea"), digestOf(arrays, asString = false))
    }

    @Test
    fun theStructsThisAppKnowsAreDecoded() {
        fun meaning(typed: String) = TypedDataMeaning.of(assertIs<DappAsk.SignTypedData>(decode(typed)).typed)
        val permit = assertIs<TypedDataMeaning.Permit>(meaning(permit))
        assertEquals(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), permit.token)
        val allowance = assertIs<TypedDataMeaning.Permit2Allowance>(meaning(permit2))
        assertEquals(Address.of("0x3fc91a3afd70395cd496c647d5a6cc9d4b2b7fad"), allowance.spender)
        assertEquals(UInt256.of(1_796_295_600), allowance.expiration)
        val order = assertIs<TypedDataMeaning.CowOrder>(meaning(cow))
        assertEquals("sell", order.kind)
        assertEquals(UInt256.of("1000000000000000000"), order.sellAmount)
        assertNull(meaning(arrays))
        // Only GPv2's own kinds and ERC-20 balances: another, or a Balancer vault balance, is not decoded.
        assertNull(meaning(cow.replace("\"kind\": \"sell\"", "\"kind\": \"SELL\"")))
        assertNull(meaning(cow.replace("\"sellTokenBalance\": \"erc20\"", "\"sellTokenBalance\": \"internal\"")))
        // The same struct for another verifying contract is not Permit2's, nor CoW's.
        assertNull(meaning(permit2.replace("0x000000000022D473030F116dDEE9F6B43aC78BA3", "0x000000000022D473030F116dDEE9F6B43aC78BA4")))
        assertNull(meaning(cow.replace("0x9008D19f58AAbD9eD0D60971565AA8510560ab41", "0x9008D19f58AAbD9eD0D60971565AA8510560ab42")))
    }

    @Test
    fun anythingNotExactlyTypedDataIsMalformed() {
        val malformed = DappAsk.Malformed("eth_signTypedData_v4")
        fun json(text: String) = Json.parseToJsonElement(text).jsonObject
        assertEquals(malformed, decode(permit.replace("\"nonce\": 0", "\"nonce\": 0.5")))
        assertEquals(malformed, decode(permit.replace("\"nonce\": 0", "\"nonce\": null")))
        assertEquals(malformed, decode(permit.dropLast(1) + ", \"extra\": 1}"))
        assertEquals(malformed, decode(permit.replace("\"type\": \"address\"}", "\"type\": \"address\", \"x\": 1}")))
        assertEquals(malformed, decode("not json"))
        assertEquals(malformed, decode("[]"))
        assertEquals(malformed, decode(permit.replace("\"deadline\": 1793703600", "\"deadline\": 1793703600, \"memo\": \"x\"")))
        val padded = json(permit).toString().dropLast(1) + "," + "\"pad\":\"" + "x".repeat(WcRequests.MAX_TYPED_DATA_CHARS) + "\"}"
        assertEquals(malformed, decode(padded))
        // Nested deeper than EIP-712 allows: refused before anything walks it, with no stack overflow.
        val deep = "[".repeat(20_000) + "]".repeat(20_000)
        assertEquals(malformed, decode(permit.replace("\"nonce\": 0", "\"nonce\": $deep")))
        assertEquals(20_000, WcJson.depthOf(deep))
        assertNull(WcJson.parse(deep))
        // Brackets inside a string are no nesting.
        assertEquals(1, WcJson.depthOf("[\"[[[{\\\"[\"]"))
        // Exactly two parameters, the account first.
        assertEquals(malformed, WcRequests.decode("eth_signTypedData_v4", JsonArray(listOf(JsonPrimitive(permit), JsonPrimitive(account.hex)))))
    }
}
