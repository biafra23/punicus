package eth.serotine.data.walletconnect

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.Signature
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappRequestError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A dApp's request, decoded strictly from its JSON into what it asks (docs/walletconnect.md, *The
 * methods*). Nothing is repaired: parameters out of shape are [DappAsk.Malformed], answered as invalid.
 */
internal object WcRequests {

    /** What [method] with [params] asks. */
    fun decode(method: String, params: JsonElement): DappAsk = when (method) {
        "personal_sign" -> personalSign(params) ?: DappAsk.Malformed(method)
        "wallet_switchEthereumChain" -> switchChain(params) ?: DappAsk.Malformed(method)
        else -> DappAsk.NotAnsweredYet(method)
    }

    /**
     * `personal_sign`'s `[message, address]`, exactly two strings. The message is the bytes its `0x`
     * hex spells, as EIP-1474 has it; a message that is not `0x` and hex is the text's UTF-8 bytes, as
     * wallets read it. Either way the bytes are what is shown and signed.
     */
    private fun personalSign(params: JsonElement): DappAsk.PersonalSign? {
        val (message, address) = strings(params, count = 2) ?: return null
        val account = address.takeIf(WcWallet::isAddress)?.let(Address::parse) ?: return null
        val hex = message.takeIf { it.startsWith("0x") }?.substring(2)
        val bytes = hex?.let(PersonalMessage::ofHex) ?: PersonalMessage.of(message.encodeToByteArray()) ?: return null
        return DappAsk.PersonalSign(account, bytes)
    }

    /** `wallet_switchEthereumChain`'s `[{"chainId": "0x…"}]`, the chain id in hex with no leading zero. */
    private fun switchChain(params: JsonElement): DappAsk.SwitchChain? {
        val fields = (params as? JsonArray)?.singleOrNull() as? JsonObject ?: return null
        if (fields.keys != setOf("chainId")) return null
        val text = (fields["chainId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        if (!CHAIN_ID.matches(text)) return null
        return DappAsk.SwitchChain(text.substring(2).toLong(16))
    }

    private fun strings(params: JsonElement, count: Int): List<String>? {
        val array = params as? JsonArray ?: return null
        if (array.size != count) return null
        return array.map { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        }
    }

    /** [signature] as `personal_sign` answers it: `0x`, `r`, `s`, and `v` as 27 or 28. */
    fun signatureHex(signature: Signature): String =
        "0x" + signature.r + signature.s + (27 + signature.recoveryId).toString(16)

    /** The JSON-RPC error [why] is answered with. */
    fun error(why: DappRequestError): WcProtocol.Error = when (why) {
        DappRequestError.USER_REJECTED -> WcProtocol.Error.PROVIDER_USER_REJECTED
        DappRequestError.UNAUTHORIZED -> WcProtocol.Error.PROVIDER_UNAUTHORIZED
        DappRequestError.UNSUPPORTED_METHOD -> WcProtocol.Error.PROVIDER_UNSUPPORTED_METHOD
        DappRequestError.UNRECOGNIZED_CHAIN -> WcProtocol.Error.PROVIDER_UNRECOGNIZED_CHAIN
        DappRequestError.INVALID_PARAMS -> WcProtocol.Error.INVALID_PARAMS
    }

    // At most 15 hex digits: a chain id fits a Long, and no real one is longer.
    private val CHAIN_ID = Regex("^0x[1-9a-fA-F][0-9a-fA-F]{0,14}$")
}
