package eth.serotine.data.walletconnect

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.caip2
import eth.serotine.domain.repository.dappNetworkOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The parts of WalletConnect's Sign protocol v2 the wallet role uses: its methods, their relay
 * tags and lifetimes, the error codes it answers with, and the rules of docs/walletconnect.md that
 * apply to every session: the chains it may hold, Ethereum and Gnosis Chain together or Sepolia alone
 * (ruling 9 as amended, owner ruling, 2026-11-05, on #22), and the closed list of methods (ruling 6).
 */
internal object WcProtocol {

    /** Sepolia's chain: what a session stored before the chains were kept holds ([WcStoredSession.chains]). */
    const val SEPOLIA = "eip155:11155111"

    /** The chains a dApp session may hold, each a [Network]'s: of one kind of funds per session ([fundsOf]). */
    val CHAINS: Set<String> = Network.entries.map { it.caip2 }.toSet()

    /** Whether [chain] is a testnet's (true), real funds' (false), or none Punicus serves (null). */
    fun fundsOf(chain: String): Boolean? = dappNetworkOf(chain)?.testnet

    /**
     * The CAIP-10 account [account] (`eip155:1:0x…`, the address in lower case) as its chain and address, or
     * null when it is out of shape or on a chain Punicus does not serve.
     */
    fun accountOf(account: String): Pair<String, String>? {
        val chain = account.substringBeforeLast(':')
        val address = account.substringAfterLast(':')
        if (chain !in CHAINS || !WcWallet.isAddress(address) || address != address.lowercase()) return null
        return chain to address
    }

    /** The methods a dApp may call (ruling 6). `eth_sign` is never among them. */
    val METHODS: Set<String> = setOf(
        "eth_sendTransaction",
        "personal_sign",
        "eth_signTypedData_v4",
        "wallet_switchEthereumChain",
    )

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

    /**
     * Proposals a pairing may have waiting at once. One more is answered with [Error.LIMIT_EXCEEDED], as
     * a request past [PENDING_REQUESTS_PER_SESSION] is: a pairing's key cannot grow the state file either.
     */
    const val PENDING_PROPOSALS_PER_PAIRING = 5

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

        /**
         * EIP-1474: the session has [PENDING_REQUESTS_PER_SESSION] requests waiting already, or the pairing
         * [PENDING_PROPOSALS_PER_PAIRING] proposals.
         */
        LIMIT_EXCEEDED(-32005, "Too many requests waiting."),

        /** EIP-1193: the user declined. */
        PROVIDER_USER_REJECTED(4001, "User rejected the request."),

        /** EIP-1193: a method outside the closed list (ruling 6), `eth_sign` among them. */
        PROVIDER_UNSUPPORTED_METHOD(4200, "Unsupported method."),

        /** EIP-1193: an account or a chain the session does not give the dApp, or one that cannot sign here. */
        PROVIDER_UNAUTHORIZED(4100, "Unauthorized."),

        /** EIP-3326: a chain `wallet_switchEthereumChain` cannot switch to. */
        PROVIDER_UNRECOGNIZED_CHAIN(4902, "Unrecognized chain ID."),

        /** JSON-RPC: parameters that are not the method's. */
        INVALID_PARAMS(-32602, "Invalid params."),
    }

    /** Why a proposal's chains make it one to reject. */
    enum class ChainRefusal {
        /** A required chain Punicus does not serve. */
        UNSUPPORTED,

        /** Required chains of both kinds of funds: a session holds one (ruling 9). */
        MIXED,

        /** No chain Punicus serves, required or optional. */
        NONE,
    }

    /**
     * What a proposal asks of a session, read from its required and optional namespaces, and why it
     * cannot be approved, when it cannot ([refusal], with [chainRefusal] when its chains are why). The
     * rules (docs/walletconnect.md, *Pairing and the session proposal*): a required namespace naming a chain
     * Punicus does not serve, chains of both kinds of funds, any namespace but `eip155`, a method outside
     * the closed list or an event outside [EVENTS], or no chain Punicus serves asked for at all, makes the
     * proposal one to reject. An optional namespace adds its methods and events where it names one of the
     * session's chains ([chains]).
     */
    class Ask(
        val requiredChains: Set<String>,
        val optionalChains: Set<String>,
        /**
         * The chains a session with it may hold: those it asks for that Punicus serves, of its required
         * chains' kind of funds when it requires any.
         */
        val chains: Set<String>,
        /** The methods requested (required, and optional where they name one of [chains]) that a session may carry. */
        val methods: Set<String>,
        /** The events requested, the same way, that a session may carry. */
        val events: Set<String>,
        val refusal: Error?,
        val chainRefusal: ChainRefusal? = null,
    )

    /**
     * Reads [required] and [optional], or null when either is not namespaces at all. With [sessionChains], the
     * chains a session is approved with, an optional namespace adds its methods and events only where it names
     * one of them.
     */
    fun ask(required: JsonObject, optional: JsonObject, sessionChains: Set<String>? = null): Ask? {
        val req = namespaces(required) ?: return null
        val opt = namespaces(optional) ?: return null
        val requiredChains = req.flatMap { it.chains }.toSet()
        val optionalChains = opt.flatMap { it.chains }.toSet()
        val requiredMethods = req.flatMap { it.methods }.toSet()
        val requiredEvents = req.flatMap { it.events }.toSet()
        val requiredFunds = requiredChains.mapNotNull(::fundsOf).toSet()
        val asked = (requiredChains + opt.filter { it.key == "eip155" }.flatMap { it.chains }).filter { it in CHAINS }
        val chains = asked.filter { requiredFunds.isEmpty() || fundsOf(it) in requiredFunds }.toSet()
        val chainRefusal = when {
            requiredChains.any { it !in CHAINS } -> ChainRefusal.UNSUPPORTED
            requiredFunds.size > 1 -> ChainRefusal.MIXED
            chains.isEmpty() -> ChainRefusal.NONE
            else -> null
        }
        val refusal = when {
            req.any { it.key != "eip155" } -> Error.UNSUPPORTED_NAMESPACE_KEY
            chainRefusal == ChainRefusal.UNSUPPORTED || chainRefusal == ChainRefusal.MIXED -> Error.UNSUPPORTED_CHAINS
            requiredMethods.any { it !in METHODS } -> Error.UNSUPPORTED_METHODS
            requiredEvents.any { it !in EVENTS } -> Error.UNSUPPORTED_EVENTS
            chainRefusal == ChainRefusal.NONE -> Error.UNSUPPORTED_CHAINS
            else -> null
        }
        // An optional namespace counts only where it names a chain of the session: what it asks of other chains
        // is not what this session carries.
        val held = sessionChains ?: chains
        val adding = req + opt.filter { it.key == "eip155" && it.chains.any { chain -> chain in held } }
        return Ask(
            requiredChains = requiredChains,
            optionalChains = optionalChains,
            chains = chains,
            methods = adding.flatMap { it.methods }.filter { it in METHODS }.toSet(),
            events = adding.flatMap { it.events }.filter { it in EVENTS }.toSet(),
            refusal = refusal,
            chainRefusal = chainRefusal.takeIf { refusal == Error.UNSUPPORTED_CHAINS },
        )
    }

    private class Namespace(
        val key: String,
        val chains: List<String>,
        val methods: List<String>,
        val events: List<String>,
    )

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
