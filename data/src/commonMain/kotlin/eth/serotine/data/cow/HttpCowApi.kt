package eth.serotine.data.cow

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowAppData
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CowApi
import eth.serotine.domain.repository.CowOrderLife
import eth.serotine.domain.repository.CowPlacement
import eth.serotine.domain.repository.CowQuoteReading
import eth.serotine.domain.repository.CowRefusal
import eth.serotine.domain.repository.CowUnavailable
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * [CowApi] over HTTP, CoW Protocol's order API (`api.cow.fi`, its OpenAPI spec
 * `crates/orderbook/openapi.yml` in `cowprotocol/services`), on the client [engine] each platform
 * gives ([create]). Three calls, and only for a swap the user sends to CoW (CLAUDE.md, *Data
 * sources*): `POST quote`, `POST orders`, `GET orders/{UID}`.
 *
 * - **Requests carry only the fields the API requires**, and the app data is the empty document
 *   ([CowAppData]): nothing about the device or the app beyond what CoW must have.
 * - **Answers are decoded leniently and checked strictly** (docs/swaps.md, *How often CoW's API
 *   changes*): unknown fields are ignored, the few read are required, and each is checked against
 *   the request (the tokens, the amounts adding up, the UID computed here). An answer that does not
 *   decode or check is [CowUnavailable.UNDECODABLE], never a guess.
 * - **No request is retried** by the client: no retry plugin, a CIO engine connecting once (the
 *   platforms' [engine]), and no redirect followed. A POST's answer is the one that happened, so
 *   an order whose POST failed stays live (docs/swaps.md, *A signed order is live until it is
 *   proven dead*).
 * - Every call ends within [TIMEOUT_MILLIS]: no answer by then is [CowUnavailable.TIMEOUT].
 *
 * Never throws, but for the caller's cancellation.
 */
class HttpCowApi internal constructor(
    engine: HttpClientEngine,
    private val baseUrl: (Network) -> String? = ::baseUrlOf,
) : CowApi {

    private val client = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = TIMEOUT_MILLIS
            connectTimeoutMillis = TIMEOUT_MILLIS
            socketTimeoutMillis = TIMEOUT_MILLIS
        }
    }

    override suspend fun quote(
        network: Network,
        sellToken: Address,
        buyToken: Address,
        sellAmount: UInt256,
        from: Address,
    ): CowQuoteReading {
        val base = baseUrl(network) ?: return CowQuoteReading.Unavailable(CowUnavailable.NOT_PINNED)
        val body = buildJsonObject {
            put("sellToken", sellToken.hex)
            put("buyToken", buyToken.hex)
            put("from", from.hex)
            put("kind", "sell")
            put("sellAmountBeforeFee", sellAmount.toDecimal())
        }
        return when (val answer = exchange { client.post("$base/quote") { json(body) } }) {
            is Answer.Failed -> CowQuoteReading.Unavailable(answer.why)
            is Answer.Status -> when {
                answer.code == OK -> quotedOf(answer.json, sellToken, buyToken, sellAmount)
                    ?: CowQuoteReading.Unavailable(CowUnavailable.UNDECODABLE)
                answer.code in REFUSALS -> refusalOf(answer.json)
                    ?.let { (why, errorType) -> CowQuoteReading.Refused(why, errorType) }
                    ?: CowQuoteReading.Unavailable(unusable(answer))
                else -> CowQuoteReading.Unavailable(unusable(answer))
            }
        }
    }

    override suspend fun place(
        network: Network,
        order: CowOrder,
        owner: Address,
        signature: Signature,
        uid: CowOrderUid,
    ): CowPlacement {
        val base = baseUrl(network) ?: return CowPlacement.Unclear(CowUnavailable.NOT_PINNED)
        val body = buildJsonObject {
            put("sellToken", order.sellToken.hex)
            put("buyToken", order.buyToken.hex)
            put("receiver", order.receiver.hex)
            put("sellAmount", order.sellAmount.toDecimal())
            put("buyAmount", order.buyAmount.toDecimal())
            put("validTo", order.validTo)
            put("appData", CowAppData.DOCUMENT)
            put("appDataHash", CowAppData.HASH)
            put("feeAmount", "0")
            put("kind", "sell")
            put("partiallyFillable", false)
            put("sellTokenBalance", "erc20")
            put("buyTokenBalance", "erc20")
            put("signingScheme", "eip712")
            put("signature", signatureHex(signature))
            put("from", owner.hex)
        }
        return when (val answer = exchange { client.post("$base/orders") { json(body) } }) {
            is Answer.Failed -> CowPlacement.Unclear(answer.why)
            is Answer.Status -> when {
                answer.code == CREATED -> (answer.json as? JsonPrimitive)?.contentOrNull
                    ?.let(CowOrderUid::parse)
                    ?.takeIf { it == uid }
                    ?.let { CowPlacement.Accepted(it) }
                    ?: CowPlacement.Unclear(CowUnavailable.UNDECODABLE)
                answer.code in REFUSALS -> when (val refusal = refusalOf(answer.json)) {
                    null -> CowPlacement.Unclear(unusable(answer))
                    // CoW holds this very order already: as good as taken.
                    else -> if (refusal.second == DUPLICATED) {
                        CowPlacement.Accepted(uid)
                    } else {
                        CowPlacement.Refused(refusal.first, refusal.second)
                    }
                }
                else -> CowPlacement.Unclear(unusable(answer))
            }
        }
    }

    override suspend fun life(network: Network, uid: CowOrderUid): CowOrderLife {
        val base = baseUrl(network) ?: return CowOrderLife.Unavailable(CowUnavailable.NOT_PINNED)
        return when (val answer = exchange { client.get("$base/orders/${uid.hex}") }) {
            is Answer.Failed -> CowOrderLife.Unavailable(answer.why)
            is Answer.Status -> when {
                answer.code == OK -> lifeOf(answer.json, uid) ?: CowOrderLife.Unavailable(CowUnavailable.UNDECODABLE)
                // CoW's own "not found", not a gateway's page.
                answer.code == NOT_FOUND && refusalOf(answer.json) != null -> CowOrderLife.Unknown
                else -> CowOrderLife.Unavailable(unusable(answer))
            }
        }
    }

    /**
     * Why [answer], neither the call's success nor a refusal of CoW's, is no usable answer. A server
     * error, or a client error without CoW's own error body (a gateway's page, a rate limit), is
     * [CowUnavailable.SERVER_ERROR], as the port defines it. Anything else, a success of another
     * shape, a redirect (none is followed) or CoW's error body without its `errorType`, is
     * [CowUnavailable.UNDECODABLE]: CoW's API may have changed.
     */
    private fun unusable(answer: Answer.Status): CowUnavailable = when {
        answer.code >= SERVER_ERRORS -> CowUnavailable.SERVER_ERROR
        answer.code >= CLIENT_ERRORS && (answer.code !in REFUSALS || answer.json !is JsonObject) ->
            CowUnavailable.SERVER_ERROR
        else -> CowUnavailable.UNDECODABLE
    }

    /** One request, made once: its status and its body as JSON (null when it is none), or why there is none. */
    private suspend fun exchange(request: suspend () -> HttpResponse): Answer = try {
        val response = request()
        val text = response.bodyAsText()
        val json = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            null
        }
        Answer.Status(response.status.value, json)
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpRequestTimeoutException) {
        Answer.Failed(CowUnavailable.TIMEOUT)
    } catch (e: ConnectTimeoutException) {
        Answer.Failed(CowUnavailable.TIMEOUT)
    } catch (e: SocketTimeoutException) {
        Answer.Failed(CowUnavailable.TIMEOUT)
    } catch (e: IOException) {
        Answer.Failed(CowUnavailable.NO_CONNECTION)
    } catch (e: Exception) {
        // Anything else the engine throws (a TLS failure, a malformed answer) is no usable answer.
        Answer.Failed(CowUnavailable.NO_CONNECTION)
    }

    private sealed interface Answer {
        data class Status(val code: Int, val json: JsonElement?) : Answer
        data class Failed(val why: CowUnavailable) : Answer
    }

    companion object {
        /** No answer within ten seconds is none (docs/swaps.md, *The user's choice of venue*). */
        const val TIMEOUT_MILLIS: Long = 10_000

        private const val OK = 200
        private const val CREATED = 201
        private const val NOT_FOUND = 404
        private const val CLIENT_ERRORS = 400
        private const val SERVER_ERRORS = 500
        private val REFUSALS = setOf(400, 403, 404)
        private const val DUPLICATED = "DuplicatedOrder"

        /** CoW's API on the platform's own HTTP client. */
        fun create(): HttpCowApi = HttpCowApi(cowHttpEngine())
    }
}

/** CoW's order API on [network], pinned: none for a network CoW is not offered on. */
internal fun baseUrlOf(network: Network): String? = when (network) {
    Network.ETHEREUM, Network.GNOSIS -> null
    Network.SEPOLIA -> "https://api.cow.fi/sepolia/api/v1"
}

private fun HttpRequestBuilder.json(body: JsonObject) {
    contentType(ContentType.Application.Json)
    setBody(body.toString())
}

/** The signature as GPv2's `eip712` scheme takes it: `r ‖ s ‖ v`, 65 bytes, `v` 27 or 28. */
internal fun signatureHex(signature: Signature): String =
    "0x" + signature.r + signature.s + (27 + signature.recoveryId).toString(16)

/**
 * CoW's quote in [json], checked against the request: the tokens as asked, a sell quote, and the
 * amount it sells after its fee plus that fee adding up to [sellAmount]; null for anything else.
 */
internal fun quotedOf(
    json: JsonElement?,
    sellToken: Address,
    buyToken: Address,
    sellAmount: UInt256,
): CowQuoteReading? {
    val quote = (json as? JsonObject)?.get("quote") as? JsonObject ?: return null
    if (quote.address("sellToken") != sellToken || quote.address("buyToken") != buyToken) return null
    if (quote.text("kind") != "sell") return null
    val sold = quote.amount("sellAmount") ?: return null
    val fee = quote.amount("feeAmount") ?: return null
    val bought = quote.amount("buyAmount") ?: return null
    if (sold.plusOrNull(fee) != sellAmount) return null
    return CowQuoteReading.Quoted(buyAmount = bought, feeAmount = fee)
}

/** CoW's refusal in [json] (`errorType`), in the kinds the screens tell apart, with CoW's own word; null when none. */
internal fun refusalOf(json: JsonElement?): Pair<CowRefusal, String>? {
    val errorType = (json as? JsonObject)?.text("errorType")?.takeIf { it.isNotBlank() } ?: return null
    val why = when (errorType) {
        "NoLiquidity", "InsufficientLiquidity" -> CowRefusal.NO_LIQUIDITY
        "UnsupportedToken", "TokenTemporarilySuspended", "UnsupportedBuyTokenDestination",
        "UnsupportedSellTokenSource", "InvalidNativeSellToken", "TradingOutsideAllowedWindow",
        -> CowRefusal.UNSUPPORTED_TOKEN
        "SellAmountDoesNotCoverFee", "ZeroAmount" -> CowRefusal.TOO_SMALL
        "Forbidden" -> CowRefusal.FORBIDDEN
        "InsufficientBalance", "InsufficientAllowance" -> CowRefusal.FUNDS
        else -> CowRefusal.OTHER
    }
    return why to errorType
}

/** How CoW's order book reports the order in [json], when it names [uid]; null for anything else. */
internal fun lifeOf(json: JsonElement?, uid: CowOrderUid): CowOrderLife? {
    val order = json as? JsonObject ?: return null
    if (order.text("uid")?.let(CowOrderUid::parse) != uid) return null
    return when (order.text("status")) {
        "open", "presignaturePending" -> CowOrderLife.Open
        "expired" -> CowOrderLife.Expired
        "cancelled" -> CowOrderLife.Cancelled
        // Fulfilled by CoW's report: whether it filled is the chain's to say.
        "fulfilled" -> CowOrderLife.Closed
        else -> null
    }
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.address(key: String): Address? = text(key)?.let(Address::parse)

private fun JsonObject.amount(key: String): UInt256? = text(key)?.let(UInt256::parse)
