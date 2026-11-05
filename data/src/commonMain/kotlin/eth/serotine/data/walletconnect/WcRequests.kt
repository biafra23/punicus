package eth.serotine.data.walletconnect

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DappTransactionRequest
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Eip712
import eth.serotine.domain.model.TypedData
import eth.serotine.domain.model.TypedField
import eth.serotine.domain.model.TypedValue
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.Signature
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.dappNetworkOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * A dApp's request, decoded strictly from its JSON into what it asks (docs/walletconnect.md, *The
 * methods*). Nothing is repaired: parameters out of shape are [DappAsk.Malformed], answered as invalid.
 */
internal object WcRequests {

    /** What [method] with [params] asks, on [chain], the CAIP-2 chain its request names. */
    fun decode(method: String, params: JsonElement, chain: String): DappAsk = when (method) {
        "personal_sign" -> personalSign(params) ?: DappAsk.Malformed(method)
        "wallet_switchEthereumChain" -> switchChain(params) ?: DappAsk.Malformed(method)
        "eth_signTypedData_v4" -> signTypedData(params) ?: DappAsk.Malformed(method)
        "eth_sendTransaction" -> sendTransaction(params, chain) ?: DappAsk.Malformed(method)
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

    /**
     * `eth_signTypedData_v4`'s `[address, typedData]`, exactly two: the typed data as a JSON string, as
     * EIP-712 and wallets take it, or as a JSON object, at most [MAX_TYPED_DATA_CHARS] long. It must be
     * exactly `{types, primaryType, domain, message}`, each member of a type exactly `{name, type}`, and
     * every number an integer; then [Eip712.encode] checks it against EIP-712 and computes its digest.
     */
    private fun signTypedData(params: JsonElement): DappAsk.SignTypedData? {
        val array = params as? JsonArray ?: return null
        if (array.size != 2) return null
        val address = (array[0] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        val account = address.takeIf(WcWallet::isAddress)?.let(Address::parse) ?: return null
        val second = array[1]
        val json = when {
            // Its nesting is bounded already, by the envelope's reading (WcJson): measuring it recurses safely.
            second is JsonObject -> second.takeIf { it.toString().length <= MAX_TYPED_DATA_CHARS } ?: return null
            second is JsonPrimitive && second.isString -> {
                val text = second.content.takeIf { it.length <= MAX_TYPED_DATA_CHARS } ?: return null
                WcJson.parse(text) as? JsonObject ?: return null
            }
            else -> return null
        }
        // Bounded before anything walks it: a tree nested deeper than EIP-712 allows is refused here.
        if (depthOf(json) > Eip712.MAX_DEPTH + 2) return null
        val typed = typedData(json) ?: return null
        return DappAsk.SignTypedData(account, Eip712.encode(typed) ?: return null)
    }

    private fun typedData(json: JsonObject): TypedData? {
        if (json.keys != setOf("types", "primaryType", "domain", "message")) return null
        val types = (json["types"] as? JsonObject)?.mapValues { (_, fields) ->
            (fields as? JsonArray)?.map { field ->
                val member = field as? JsonObject ?: return null
                if (member.keys != setOf("name", "type")) return null
                TypedField(member.text("name") ?: return null, member.text("type") ?: return null)
            } ?: return null
        } ?: return null
        val primaryType = (json["primaryType"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val domain = (json["domain"] as? JsonObject)?.let(::struct) ?: return null
        val message = (json["message"] as? JsonObject)?.let(::struct) ?: return null
        return TypedData(types, primaryType, domain.fields, message.fields)
    }

    private fun struct(json: JsonObject): TypedValue.Struct? =
        TypedValue.Struct(json.mapValues { (_, value) -> value(value) ?: return null })

    private fun value(json: JsonElement): TypedValue? = when (json) {
        is JsonObject -> struct(json)
        is JsonArray -> TypedValue.Items(json.map { value(it) ?: return null })
        is JsonNull -> null
        is JsonPrimitive -> when {
            json.isString -> TypedValue.Text(json.content)
            json.booleanOrNull != null -> TypedValue.Bool(json.boolean)
            INTEGER.matches(json.content) -> TypedValue.Number(json.content)
            else -> null
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * `eth_sendTransaction`'s `[transaction]`, exactly one object, each field applied or refused
     * (docs/walletconnect.md, *Transactions*; Myotis #314):
     * - applied: `from` and `to` (required; no contract creation), `value` (hex, 0 without one),
     *   `data` or `input` (hex; the same when both), `gas` or `gasLimit` (hex; the same when both), the
     *   limit the transaction is signed with when it covers the verified estimate;
     * - checked: `chainId` the request's [chain]'s, `type` `0x2`, an empty `accessList`;
     * - refused: `nonce` (the wallet takes the account's next one), `gasPrice`, `maxFeePerGas` and
     *   `maxPriorityFeePerGas` (the fees are the wallet's own, from the verified fee history), and any
     *   other field.
     */
    private fun sendTransaction(params: JsonElement, chain: String): DappAsk.SendTransaction? {
        val fields = (params as? JsonArray)?.singleOrNull() as? JsonObject ?: return null
        if (!TRANSACTION_FIELDS.containsAll(fields.keys)) return null
        // A field given as anything but a string reads as "", which no check below takes.
        fun text(key: String): String? =
            fields[key]?.let { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: "" }
        fun address(key: String): Address? = text(key)?.takeIf(WcWallet::isAddress)?.let(Address::parse)
        fun quantity(key: String): UInt256? = text(key)?.takeIf(QUANTITY::matches)?.substring(2)
            ?.let { digits -> WcEncoding.hexOrNull(digits.padStart(64, '0'))?.let(UInt256::fromBigEndian) }
        val from = address("from") ?: return null
        val to = address("to") ?: return null
        val value = if ("value" in fields) quantity("value") ?: return null else UInt256.ZERO
        val data = listOfNotNull(text("data"), text("input")).distinct()
        if (data.size > 1) return null
        val bytes = data.singleOrNull()?.let { hex ->
            hex.takeIf { it.startsWith("0x") && it.length <= 2 + 2 * DappTransactionRequest.MAX_DATA_BYTES }
                ?.substring(2)?.let(WcEncoding::hexOrNull) ?: return null
        } ?: ByteArray(0)
        val gases = listOf("gas", "gasLimit").filter { it in fields }.map { quantity(it) ?: return null }.distinct()
        if (gases.size > 1) return null
        val gas = gases.singleOrNull()?.let { limit ->
            limit.toLongOrNull()?.takeIf { it in 1..MAX_GAS } ?: return null
        }
        // The transaction's own chain, where it names one, is the request's: never signed for another.
        val chainId = dappNetworkOf(chain)?.chainId ?: return null
        if ("chainId" in fields && quantity("chainId") != UInt256.of(chainId)) return null
        if ("type" in fields && quantity("type") != UInt256.of(2)) return null
        if ("accessList" in fields && (fields["accessList"] as? JsonArray)?.isEmpty() != true) return null
        return DappAsk.SendTransaction(DappTransactionRequest(from, to, value, bytes, gas))
    }

    /**
     * How deep [json] nests, counted without recursion, and no further than [MAX_PARAMS_DEPTH] + 1: what
     * keeps a dApp's tree from overflowing the stack of whatever walks it next.
     */
    fun depthOf(json: JsonElement): Int {
        val waiting = ArrayDeque(listOf(json to 1))
        var deepest = 0
        while (waiting.isNotEmpty()) {
            val (element, depth) = waiting.removeLast()
            deepest = maxOf(deepest, depth)
            if (deepest > MAX_PARAMS_DEPTH) return deepest
            when (element) {
                is JsonObject -> element.values.forEach { waiting.addLast(it to depth + 1) }
                is JsonArray -> element.forEach { waiting.addLast(it to depth + 1) }
                else -> Unit
            }
        }
        return deepest
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
        DappRequestError.LIMIT_EXCEEDED -> WcProtocol.Error.LIMIT_EXCEEDED
    }

    /** The fields a transaction may name; any other is refused. */
    private val TRANSACTION_FIELDS =
        setOf("from", "to", "value", "data", "input", "gas", "gasLimit", "chainId", "type", "accessList")

    /**
     * The largest gas limit a dApp may ask for, on every network: 2^24, EIP-7825's cap on a transaction's gas
     * (Ethereum and Sepolia, since Fusaka), and above Gnosis Chain's block gas limit too. A larger one could
     * never be included.
     */
    private const val MAX_GAS = 16_777_216L

    /** A JSON-RPC quantity: `0x` and at most 64 hex digits. */
    private val QUANTITY = Regex("^0x[0-9a-fA-F]{1,64}$")

    /** The deepest a request's parameters may nest: deeper ones are answered as invalid, never kept. */
    const val MAX_PARAMS_DEPTH = 32

    /** The longest typed data a dApp may ask to have signed, as JSON. */
    const val MAX_TYPED_DATA_CHARS = 64 * 1024

    private val INTEGER = Regex("^-?(0|[1-9][0-9]*)$")

    // At most 15 hex digits: a chain id fits a Long, and no real one is longer.
    private val CHAIN_ID = Regex("^0x[1-9a-fA-F][0-9a-fA-F]{0,14}$")
}
