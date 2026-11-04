package eth.serotine.data.safe

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.repository.SigningRequestCodec
import eth.serotine.domain.repository.SigningRequestDecoding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * [SigningRequestCodec] as compact JSON (docs/wallet-ux.md, *Co-signing a Safe transaction*),
 * for requests on every network the app knows ([Network]):
 *
 * ```
 * {"v":1,"chainId":11155111,"safe":"0x…","tx":{"to":"0x…","value":"0","data":"0x…","operation":0,
 *  "safeTxGas":"0","baseGas":"0","gasPrice":"0","gasToken":"0x…","refundReceiver":"0x…","nonce":"4"},
 *  "signatures":[{"owner":"0x…","signature":"0x<r ‖ s ‖ v>"}]}
 * ```
 *
 * One line, no spaces, the keys in this order, the signatures in ascending owner order, every
 * value in its one form ([WireRequest]). Decoding takes nothing else: the text, less the
 * whitespace a share sheet may add around it, must be exactly what [encode] makes of the
 * request it decodes to, so each request has one text and no text carries anything beside its
 * fields (a second value of a key, a comment, a field this version does not know). `v` is read
 * first: any version but 1 is [SigningRequestDecoding.UnknownVersion]; a chain that is no
 * [Network], [SigningRequestDecoding.UnknownChain]. Whether the request is for the Safe it is
 * imported to, on that Safe's network, is the importing use case's to check. Text longer than [MAX_LENGTH] is refused
 * before it is parsed.
 */
class JsonSigningRequestCodec : SigningRequestCodec {

    override fun encode(request: SigningRequest): String =
        JSON.encodeToString(WireRequest.serializer(), WireRequest.of(request))

    override fun decode(text: String): SigningRequestDecoding = try {
        decodeOrThrow(text.trim())
    } catch (e: Exception) {
        // Whatever kotlinx-serialization throws for text that is no JSON of this shape.
        SigningRequestDecoding.Malformed
    }

    private fun decodeOrThrow(text: String): SigningRequestDecoding {
        // Before the parser sees it: kotlinx's reads nested arrays recursively, and a few
        // thousand brackets in a QR code would end the app with a StackOverflowError.
        if (text.length > MAX_LENGTH || !nestsAtMost(text, MAX_DEPTH)) return SigningRequestDecoding.Malformed
        val json = JSON.parseToJsonElement(text) as? JsonObject ?: return SigningRequestDecoding.Malformed
        val version = (json["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: return SigningRequestDecoding.Malformed
        if (version != WireRequest.VERSION) return SigningRequestDecoding.UnknownVersion(version)
        val wire = JSON.decodeFromJsonElement(WireRequest.serializer(), json)
        val network = Network.ofChainId(wire.chainId) ?: return SigningRequestDecoding.UnknownChain(wire.chainId)
        val request = wire.toRequest(network) ?: return SigningRequestDecoding.Malformed
        if (encode(request) != text) return SigningRequestDecoding.Malformed
        return SigningRequestDecoding.Decoded(request)
    }

    companion object {
        /**
         * The longest text decoded: room for 64 KiB of call data, far more than one QR code holds
         * (under 3 KB) and more than any transaction this wallet makes or shows.
         */
        const val MAX_LENGTH = 140_000

        /** How deep a request nests: the request, its signatures, each signature. */
        private const val MAX_DEPTH = 3

        private val JSON = Json

        /** Whether [text] nests objects and arrays no deeper than [max], outside its strings. */
        private fun nestsAtMost(text: String, max: Int): Boolean {
            var depth = 0
            var inString = false
            var escaped = false
            for (char in text) {
                when {
                    escaped -> escaped = false
                    inString && char == '\\' -> escaped = true
                    char == '"' -> inString = !inString
                    inString -> Unit
                    char == '{' || char == '[' -> if (++depth > max) return false
                    char == '}' || char == ']' -> depth--
                }
            }
            return true
        }
    }
}
