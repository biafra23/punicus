package eth.serotine.data.walletconnect

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The parts of WalletConnect's Sign protocol v2 the wallet role uses: its methods, their relay
 * tags and lifetimes, the error codes it answers with, and the rules of docs/walletconnect.md that
 * apply to every session: Sepolia alone (owner ruling, 2026-11-03, #22, ruling 9) and the closed list
 * of methods (ruling 6).
 */
internal object WcProtocol {

    /** The one chain a dApp session may name for now (ruling 9). */
    const val SEPOLIA = "eip155:11155111"

    /** The methods a dApp may call (ruling 6). `eth_sign` is never among them. */
    val METHODS: Set<String> = setOf("eth_sendTransaction", "personal_sign", "eth_signTypedData_v4", "wallet_switchEthereumChain")

    /** The events a session may carry. */
    val EVENTS: Set<String> = setOf("accountsChanged", "chainChanged")

    /** How long a settled session lives, and the longest an extension may make it: seven days. */
    const val SESSION_SECONDS = 7L * 24 * 60 * 60

    /** How long a pairing lives once a session came of it: thirty days, as the reference clients keep it. */
    const val ACTIVE_PAIRING_SECONDS = 30L * 24 * 60 * 60

    /** How long a pairing whose URI names no expiry waits for a proposal: five minutes. */
    const val INACTIVE_PAIRING_SECONDS = 5L * 60

    /** How long a request without its own expiry waits for its answer: the five minutes the relay holds it. */
    const val REQUEST_SECONDS = 5L * 60

    /** The longest a dApp may make a request wait: a session's lifetime. */
    const val MAX_REQUEST_SECONDS = SESSION_SECONDS

    /** Request ids remembered per topic, against replays (docs/walletconnect.md, *What the relay learns*). */
    const val SEEN_IDS_PER_TOPIC = 2_000

    /**
     * Requests a session may have waiting at once. One more is answered with [Error.LIMIT_EXCEEDED]:
     * a dApp cannot grow the state file, which every message rewrites, without bound.
     */
    const val PENDING_REQUESTS_PER_SESSION = 20

    /** A Sign method's relay tag for its request, its response, and how long the relay holds it. */
    class Method(val name: String, val requestTag: Int, val responseTag: Int, val ttlSeconds: Long)

    val SESSION_PROPOSE = Method("wc_sessionPropose", 1100, 1101, 300)
    val SESSION_SETTLE = Method("wc_sessionSettle", 1102, 1103, 300)
    val SESSION_UPDATE = Method("wc_sessionUpdate", 1104, 1105, 86_400)
    val SESSION_EXTEND = Method("wc_sessionExtend", 1106, 1107, 86_400)
    val SESSION_REQUEST = Method("wc_sessionRequest", 1108, 1109, 300)
    val SESSION_EVENT = Method("wc_sessionEvent", 1110, 1111, 300)
    val SESSION_DELETE = Method("wc_sessionDelete", 1112, 1113, 86_400)
    val SESSION_PING = Method("wc_sessionPing", 1114, 1115, 30)
    val PAIRING_DELETE = Method("wc_pairingDelete", 1000, 1001, 86_400)
    val PAIRING_PING = Method("wc_pairingPing", 1002, 1003, 30)

    /** The tag of a rejected proposal's answer. */
    const val SESSION_PROPOSE_REJECT_TAG = 1120

    /** The answer to a method the other side may not call, or one this build does not serve, by name. */
    val BY_NAME: Map<String, Method> = listOf(
        SESSION_PROPOSE, SESSION_SETTLE, SESSION_UPDATE, SESSION_EXTEND, SESSION_REQUEST,
        SESSION_EVENT, SESSION_DELETE, SESSION_PING, PAIRING_DELETE, PAIRING_PING,
    ).associateBy { it.name }

    /** A JSON-RPC error a wallet answers with: the Sign protocol's own codes, and EIP-1193's for a dApp's method. */
    enum class Error(val code: Int, val message: String) {
        USER_REJECTED(5000, "User rejected."),
        UNSUPPORTED_CHAINS(5100, "Unsupported chains."),
        UNSUPPORTED_METHODS(5101, "Unsupported methods."),
        UNSUPPORTED_EVENTS(5102, "Unsupported events."),
        UNSUPPORTED_ACCOUNTS(5103, "Unsupported accounts."),
        UNSUPPORTED_NAMESPACE_KEY(5104, "Unsupported namespace key."),
        UNAUTHORIZED_EVENT(3002, "Unauthorized event."),
        UNAUTHORIZED_UPDATE_REQUEST(3003, "Unauthorized update request."),
        UNAUTHORIZED_EXTEND_REQUEST(3004, "Unauthorized extend request."),
        USER_DISCONNECTED(6000, "User disconnected."),
        SESSION_SETTLEMENT_FAILED(7000, "Session settlement failed."),
        REQUEST_EXPIRED(8000, "Request expired."),
        WC_METHOD_UNSUPPORTED(10001, "Unsupported wc_ method."),

        /** EIP-1474: the session has [PENDING_REQUESTS_PER_SESSION] requests waiting already. */
        LIMIT_EXCEEDED(-32005, "Too many requests waiting."),

        /** EIP-1193: the user declined. */
        PROVIDER_USER_REJECTED(4001, "User rejected the request."),

        /** EIP-1193: a method outside the closed list (ruling 6), `eth_sign` among them. */
        PROVIDER_UNSUPPORTED_METHOD(4200, "Unsupported method."),
    }

    /**
     * What a proposal asks of a session, read from its required and optional namespaces, and why it
     * cannot be approved, when it cannot ([refusal]). The rules (docs/walletconnect.md, *Pairing and
     * the session proposal*): a required namespace naming any chain but Sepolia, any namespace but
     * `eip155`, a method outside the closed list or an event outside [EVENTS] makes the proposal one
     * to reject; an optional one is approved with what of it is Sepolia and on the lists.
     */
    class Ask(
        val requiredChains: Set<String>,
        val optionalChains: Set<String>,
        /** The methods requested (required and optional) that a session may carry. */
        val methods: Set<String>,
        /** The events requested that a session may carry. */
        val events: Set<String>,
        val refusal: Error?,
    )

    /** Reads [required] and [optional], or null when either is not namespaces at all. */
    fun ask(required: JsonObject, optional: JsonObject): Ask? {
        val req = namespaces(required) ?: return null
        val opt = namespaces(optional) ?: return null
        val requiredChains = req.flatMap { it.chains }.toSet()
        val optionalChains = opt.flatMap { it.chains }.toSet()
        val requiredMethods = req.flatMap { it.methods }.toSet()
        val requiredEvents = req.flatMap { it.events }.toSet()
        val refusal = when {
            req.any { it.key != "eip155" } -> Error.UNSUPPORTED_NAMESPACE_KEY
            requiredChains.any { it != SEPOLIA } -> Error.UNSUPPORTED_CHAINS
            requiredMethods.any { it !in METHODS } -> Error.UNSUPPORTED_METHODS
            requiredEvents.any { it !in EVENTS } -> Error.UNSUPPORTED_EVENTS
            SEPOLIA !in requiredChains && opt.none { it.key == "eip155" && SEPOLIA in it.chains } -> Error.UNSUPPORTED_CHAINS
            else -> null
        }
        val asked = req + opt.filter { it.key == "eip155" }
        return Ask(
            requiredChains = requiredChains,
            optionalChains = optionalChains,
            methods = asked.flatMap { it.methods }.filter { it in METHODS }.toSet(),
            events = asked.flatMap { it.events }.filter { it in EVENTS }.toSet(),
            refusal = refusal,
        )
    }

    private class Namespace(val key: String, val chains: List<String>, val methods: List<String>, val events: List<String>)

    /**
     * The namespaces of [value]: each key a namespace (`eip155`, its chains listed) or a chain
     * (`eip155:11155111`, which is its own chain). Null when one is out of shape.
     */
    private fun namespaces(value: JsonObject): List<Namespace>? = value.map { (key, body) ->
        val fields = body as? JsonObject ?: return null
        val listed = strings(fields["chains"]) ?: return null
        val chains = when {
            ':' in key -> if (listed.isEmpty() || listed == listOf(key)) listOf(key) else return null
            else -> listed
        }
        Namespace(
            key = key.substringBefore(':'),
            chains = chains,
            methods = strings(fields["methods"]) ?: return null,
            events = strings(fields["events"]) ?: return null,
        )
    }

    /** The strings of a JSON array; empty when absent; null when it holds anything else. */
    private fun strings(value: JsonElement?): List<String>? {
        if (value == null) return emptyList()
        val array = value as? JsonArray ?: return null
        return array.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull ?: return null }
    }
}
