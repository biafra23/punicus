package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.DappTransactionRequest
import eth.serotine.domain.model.DappCallMeaning
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.model.Eip712
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.TypedDataMeaning
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.EncodedTypedData
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.SignInMessage
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.DappAnswer
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappRequest
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappRequests
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/** The CAIP-2 id of the one chain a dApp session may name for now (docs/walletconnect.md, ruling 9). */
internal val SEPOLIA_CHAIN: String = "eip155:${Network.SEPOLIA.chainId}"

/**
 * Why a message cannot be signed (docs/walletconnect.md, *Signing in*): shown, and the request can
 * only be refused, with [error].
 */
enum class DappSignRefusal(val error: DappRequestError) {
    /** It names an account the session does not give the dApp. */
    NOT_SESSION_ACCOUNT(DappRequestError.UNAUTHORIZED),

    /** It names a chain other than Sepolia. */
    OTHER_CHAIN(DappRequestError.UNAUTHORIZED),

    /** The account is no longer in the wallet. */
    NOT_IN_WALLET(DappRequestError.UNAUTHORIZED),

    /** The account list cannot be read, so the account cannot be checked. */
    ACCOUNTS_UNREADABLE(DappRequestError.UNAUTHORIZED),

    /** The account is watched with no key on this device. */
    NO_KEY(DappRequestError.UNAUTHORIZED),

    /** Sepolia is off. */
    SEPOLIA_OFF(DappRequestError.UNAUTHORIZED),

    /** A Safe: its messages (EIP-1271) come later, and until then it signs none. */
    SAFE(DappRequestError.UNSUPPORTED_METHOD),

    /** A sign-in for another address than the account asked to sign it. */
    SIGN_IN_OTHER_ADDRESS(DappRequestError.INVALID_PARAMS),

    /** A sign-in for another chain than the account's. */
    SIGN_IN_OTHER_CHAIN(DappRequestError.INVALID_PARAMS),

    /** A sign-in past its expiration time. */
    SIGN_IN_EXPIRED(DappRequestError.INVALID_PARAMS),

    /** A sign-in before its not-before time. */
    SIGN_IN_NOT_YET_VALID(DappRequestError.INVALID_PARAMS),

    /** Typed data whose domain names another chain than the account's. */
    TYPED_OTHER_CHAIN(DappRequestError.INVALID_PARAMS),

    /** An ERC-2612 permit of another owner than the account asked to sign it. */
    PERMIT_OTHER_OWNER(DappRequestError.INVALID_PARAMS),

    /** A transaction to the zero address: nobody holds its key, so what it sends is lost for good. */
    TX_TO_ZERO_ADDRESS(DappRequestError.INVALID_PARAMS),

    /** Typed data with more values than D5 shows ([MAX_SHOWN_VALUES]): what would not be shown is not signed. */
    TYPED_TOO_LARGE(DappRequestError.LIMIT_EXCEEDED),
}

/** The most values of typed data's message screen D5 shows, every one: more is refused, never cut. */
const val MAX_SHOWN_VALUES: Int = 200

/** What a message to sign is warned about: it may be signed, after the warning. */
enum class DappSignWarning {
    /** A sign-in whose domain is not the host of the URL the dApp stated: it may be a site posing as another. */
    DOMAIN_NOT_DAPP_URL,

    /** Not a sign-in (EIP-4361): the app cannot say what signing it commits to. */
    NOT_A_SIGN_IN,

    /** Typed data whose domain names no chain: a signature that may be used on any chain. */
    NO_CHAIN_ID,

    /** Typed data of a struct this app does not know: shown field by field, its meaning not decoded. */
    NOT_DECODED,
}

/** What a request asks to have signed: a message, or typed data. */
sealed interface DappSignContent {
    /** A `personal_sign` [message], and the sign-in it spells when it is one. */
    data class Message(val message: PersonalMessage, val signIn: SignInMessage?) : DappSignContent

    /**
     * `eth_signTypedData_v4`'s [typed] data, what it lets someone do when this app knows its struct
     * ([meaning]), and the built-in tokens it names ([tokens], by address): the list's own claim of their
     * symbol and decimals, pinned.
     */
    data class Typed(
        val typed: EncodedTypedData,
        val meaning: TypedDataMeaning?,
        val tokens: Map<Address, Token>,
    ) : DappSignContent

    /**
     * `eth_sendTransaction`'s [transaction], what its call does ([DappTransactionRequest.meaning]),
     * and the built-in token it calls when it is one ([tokens]). Quoted and sent by
     * [QuoteDappTransaction] and [SendDappTransaction], never signed as a message.
     */
    data class Transaction(
        val transaction: DappTransactionRequest,
        val tokens: Map<Address, Token>,
    ) : DappSignContent
}

/**
 * A dApp's request to sign, reviewed (screens D4 and D5): who asks, which account is to sign, and what:
 * a message as the dApp sent it, and the sign-in it spells when it is one; or typed data, with what it
 * lets someone do when this app knows its struct. [refusal] when it cannot be signed; the [warnings]
 * otherwise. Nothing in it is read from the chain: signing reads nothing.
 */
data class DappMessageReview(
    val request: DappRequest,
    /** The account asked to sign. */
    val address: Address,
    /** The wallet's account at [address] on Sepolia, when there is one. */
    val account: Account?,
    val content: DappSignContent,
    val refusal: DappSignRefusal?,
    val warnings: Set<DappSignWarning>,
) {
    /** The message, when [content] is one. */
    val message: PersonalMessage? get() = (content as? DappSignContent.Message)?.message

    /** The sign-in the message spells, when it is one. */
    val signIn: SignInMessage? get() = (content as? DappSignContent.Message)?.signIn

    /**
     * The digest signing it signs, computed here: EIP-191's for a message, EIP-712's for typed data;
     * null for a transaction, which is quoted and signed as one.
     */
    val digest: Digest?
        get() = when (content) {
            is DappSignContent.Message -> content.message.digest()
            is DappSignContent.Typed -> content.typed.digest
            is DappSignContent.Transaction -> null
        }
}

/**
 * Reviews [request], when it asks to have something signed (`personal_sign`, `eth_signTypedData_v4`;
 * null otherwise), against the wallet's [accounts] (null: the list cannot be read), the networks
 * [enabled] and the built-in [tokens] of Sepolia, at [nowSeconds] (docs/walletconnect.md, *Signing in*
 * and *Typed data*).
 */
fun reviewDappMessage(
    request: DappRequest,
    accounts: List<Account>?,
    enabled: Set<Network>,
    nowSeconds: Long,
    tokens: List<Token> = emptyList(),
): DappMessageReview? {
    val (signer, content) = when (val ask = request.ask) {
        is DappAsk.PersonalSign ->
            ask.account to DappSignContent.Message(ask.message, ask.message.text?.let(SignInMessage::parse))
        is DappAsk.SignTypedData -> {
            val meaning = TypedDataMeaning.of(ask.typed)
            val named = tokensOf(meaning)
            val pinned = tokens.filter { it.address in named }.associateBy { it.address }
            ask.account to DappSignContent.Typed(ask.typed, meaning, pinned)
        }
        is DappAsk.SendTransaction -> {
            val token = ask.transaction.to
            val pinned = tokens.filter { it.address == token }.associateBy { it.address }
            ask.transaction.from to DappSignContent.Transaction(ask.transaction, pinned)
        }
        else -> return null
    }
    val account = accounts?.firstOrNull { it.network == Network.SEPOLIA && it.address == signer }
    val refusal = accountRefusal(request, signer, accounts, account, enabled) ?: when (content) {
        is DappSignContent.Message -> signInRefusal(content.signIn, signer, nowSeconds)
        is DappSignContent.Typed -> typedRefusal(content, signer)
        is DappSignContent.Transaction ->
            DappSignRefusal.TX_TO_ZERO_ADDRESS.takeIf { content.transaction.to == Address.ZERO }
    }
    val warnings = buildSet {
        when (content) {
            is DappSignContent.Message -> {
                val signIn = content.signIn
                if (signIn == null) add(DappSignWarning.NOT_A_SIGN_IN)
                if (signIn != null && !sameSite(signIn, request.dapp.url)) add(DappSignWarning.DOMAIN_NOT_DAPP_URL)
            }
            is DappSignContent.Typed -> {
                if ("chainId" !in content.typed.data.domain) add(DappSignWarning.NO_CHAIN_ID)
                if (content.meaning == null) add(DappSignWarning.NOT_DECODED)
            }
            is DappSignContent.Transaction -> {
                if (content.transaction.meaning is DappCallMeaning.NotDecoded) add(DappSignWarning.NOT_DECODED)
            }
        }
    }
    return DappMessageReview(request, signer, account, content, refusal, warnings)
}

/** Why [signer] cannot sign [request] here, whatever it asks; null when it can. */
private fun accountRefusal(
    request: DappRequest,
    signer: Address,
    accounts: List<Account>?,
    account: Account?,
    enabled: Set<Network>,
): DappSignRefusal? = when {
    request.chain != SEPOLIA_CHAIN -> DappSignRefusal.OTHER_CHAIN
    signer !in request.sessionAccounts -> DappSignRefusal.NOT_SESSION_ACCOUNT
    accounts == null -> DappSignRefusal.ACCOUNTS_UNREADABLE
    account == null -> DappSignRefusal.NOT_IN_WALLET
    account.kind == AccountKind.SAFE -> DappSignRefusal.SAFE
    account.custody == AccountCustody.Watched -> DappSignRefusal.NO_KEY
    Network.SEPOLIA !in enabled -> DappSignRefusal.SEPOLIA_OFF
    else -> null
}

private fun signInRefusal(signIn: SignInMessage?, signer: Address, nowSeconds: Long): DappSignRefusal? = when {
    signIn == null -> null
    signIn.address != signer -> DappSignRefusal.SIGN_IN_OTHER_ADDRESS
    signIn.chainId != Network.SEPOLIA.chainId -> DappSignRefusal.SIGN_IN_OTHER_CHAIN
    signIn.expiresAtSeconds != null && signIn.expiresAtSeconds <= nowSeconds -> DappSignRefusal.SIGN_IN_EXPIRED
    signIn.notBeforeSeconds != null && signIn.notBeforeSeconds > nowSeconds -> DappSignRefusal.SIGN_IN_NOT_YET_VALID
    else -> null
}

private fun typedRefusal(content: DappSignContent.Typed, signer: Address): DappSignRefusal? {
    val chainId = content.typed.data.domain["chainId"]?.let(Eip712::unsignedOf)
    return when {
        Eip712.count(content.typed.data.message.values) > MAX_SHOWN_VALUES -> DappSignRefusal.TYPED_TOO_LARGE
        chainId != null && chainId != UInt256.of(Network.SEPOLIA.chainId) -> DappSignRefusal.TYPED_OTHER_CHAIN
        (content.meaning as? TypedDataMeaning.Permit)?.owner?.let { it != signer } == true ->
            DappSignRefusal.PERMIT_OTHER_OWNER
        else -> null
    }
}

/** The tokens [meaning] names. */
private fun tokensOf(meaning: TypedDataMeaning?): Set<Address> = when (meaning) {
    is TypedDataMeaning.Permit -> setOf(meaning.token)
    is TypedDataMeaning.Permit2Allowance -> setOf(meaning.token)
    is TypedDataMeaning.Permit2Transfer -> setOf(meaning.token)
    is TypedDataMeaning.CowOrder -> setOf(meaning.sellToken, meaning.buyToken)
    null -> emptySet()
}

/**
 * Whether [signIn]'s domain is the host and port of [url], the URL the dApp stated for the session.
 * Both are the dApp's claim: equal, they say nothing of which site it is; different, the message was
 * made for another site than the one connected.
 */
internal fun sameSite(signIn: SignInMessage, url: String): Boolean {
    val stated = url.substringAfter("://", missingDelimiterValue = "")
    if (stated.isEmpty()) return false
    val scheme = url.substringBefore("://").lowercase()
    val authority = stated.substringBefore('/').substringBefore('?').substringBefore('#')
    val connected = site(authority, scheme) ?: return false
    return connected == site(signIn.domain, signIn.scheme?.lowercase() ?: "https")
}

/** [authority] as host and port, the port the [scheme]'s default where it names none; null when unreadable. */
private fun site(authority: String, scheme: String): Pair<String, Int>? {
    val hostPort = authority.substringAfterLast('@').lowercase()
    if (hostPort.isEmpty()) return null
    val (host, port) = if (hostPort.startsWith("[")) {
        val end = hostPort.indexOf(']').takeIf { it > 0 } ?: return null
        hostPort.substring(0, end + 1) to hostPort.substring(end + 1).removePrefix(":").ifEmpty { null }
    } else {
        hostPort.substringBefore(':') to hostPort.substringAfter(':', missingDelimiterValue = "").ifEmpty { null }
    }
    if (host.isEmpty()) return null
    val number = when {
        port != null -> port.toIntOrNull() ?: return null
        scheme == "http" -> 80
        scheme == "https" -> 443
        else -> -1
    }
    return host to number
}

/**
 * The `personal_sign` requests waiting, each reviewed ([reviewDappMessage]), oldest first (screens D1
 * and D4). Reviewed again every [tickSeconds] as well as on each change, so a sign-in whose expiry or
 * not-before time passes says so. [clock] is now in unix seconds.
 */
class ObserveDappMessages(
    private val requests: DappRequests,
    private val accounts: AccountRepository,
    private val networks: NetworkSettings,
    /** The built-in tokens, which name the tokens typed data refers to. */
    private val tokens: TokenCatalog,
    private val clock: () -> Long = ::epochSecondsNow,
    private val tickSeconds: Long = 15,
) {
    operator fun invoke(): Flow<List<DappMessageReview>> {
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(tickSeconds.seconds)
            }
        }
        val accountLists = accounts.observeAccounts()
        val known = tokens.observeTokens(Network.SEPOLIA)
        val enabled = networks.observe()
        return combine(requests.observe(), accountLists, enabled, known, ticks) { waiting, list, on, pinned, _ ->
            val loaded = (list as? AccountList.Loaded)?.accounts
            val now = clock()
            waiting.mapNotNull { request -> reviewDappMessage(request, loaded, on, now, pinned) }
        }.distinctUntilChanged()
    }
}

/** What [SignDappMessage] came to. */
sealed interface DappMessageSigning {
    /**
     * Signed and answered; [delivered] false while the relay has not taken the answer: it is sent
     * again, never lost.
     */
    data class Signed(val delivered: Boolean) : DappMessageSigning

    /** Not signed, for the reason [signing] gives: nothing left the key store. */
    data class NotSigned(val signing: Signing) : DappMessageSigning

    /** Not signed: [refusal]. */
    data class Refused(val refusal: DappSignRefusal) : DappMessageSigning

    /** The dApp stopped waiting: nothing is answered but that it expired. */
    data object Expired : DappMessageSigning

    /** The request is no longer waiting: answered already, or its session ended. */
    data object Gone : DappMessageSigning

    /** Signed, but the answer could not be saved: nothing was sent, and the request still waits. */
    data object NotSaved : DappMessageSigning
}

/**
 * Signs a dApp's request to sign (screens D4 and D5) and answers it with the signature. The request
 * is reviewed again first, now ([reviewDappMessage]): one that cannot be signed is refused, and one
 * the dApp stopped waiting for is not signed at all. The digest is computed here, EIP-191's for a
 * message and EIP-712's for typed data; the signer signs it under [unlock] as given
 * (docs/walletconnect.md, *Signing in* and *Typed data*).
 */
class SignDappMessage(
    private val requests: DappRequests,
    private val accounts: AccountRepository,
    private val networks: NetworkSettings,
    private val signer: Signer,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(id: DappRequestId, unlock: Unlock): DappMessageSigning {
        val request = requests.observe().first().firstOrNull { it.id == id } ?: return DappMessageSigning.Gone
        val list = (accounts.observeAccounts().first() as? AccountList.Loaded)?.accounts
        val review = reviewDappMessage(request, list, networks.observe().first(), clock())
            ?: return DappMessageSigning.Gone
        review.refusal?.let { return DappMessageSigning.Refused(it) }
        // A transaction is quoted and sent as one (SendDappTransaction), never signed as a message.
        val digest = review.digest ?: return DappMessageSigning.Gone
        if (request.expiresAtSeconds <= clock()) return DappMessageSigning.Expired
        val signature = when (val signing = signer.sign(review.address, digest, unlock)) {
            is Signing.Signed -> signing.signature
            else -> return DappMessageSigning.NotSigned(signing)
        }
        return when (requests.answerSignature(id, signature)) {
            DappAnswer.DELIVERED -> DappMessageSigning.Signed(delivered = true)
            DappAnswer.NOT_DELIVERED -> DappMessageSigning.Signed(delivered = false)
            DappAnswer.EXPIRED -> DappMessageSigning.Expired
            DappAnswer.NOT_SAVED -> DappMessageSigning.NotSaved
            DappAnswer.NO_SUCH_REQUEST, DappAnswer.OFF -> DappMessageSigning.Gone
        }
    }
}

/**
 * Refuses a dApp's request (screen D4): with "user rejected" when the user declined one that could be
 * signed, or, reviewed now ([reviewDappMessage]), with its [DappSignRefusal]'s error when it cannot be.
 */
class RefuseDappRequest(
    private val requests: DappRequests,
    private val accounts: AccountRepository,
    private val networks: NetworkSettings,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(id: DappRequestId): DappAnswer {
        val request = requests.observe().first().firstOrNull { it.id == id } ?: return DappAnswer.NO_SUCH_REQUEST
        val list = (accounts.observeAccounts().first() as? AccountList.Loaded)?.accounts
        val refusal = reviewDappMessage(request, list, networks.observe().first(), clock())?.refusal
        return requests.refuse(id, refusal?.error ?: DappRequestError.USER_REJECTED)
    }
}

/**
 * Answers at once the requests that need no one's decision (docs/walletconnect.md, *The methods*):
 * `wallet_switchEthereumChain` to Sepolia with null and to any other chain with the unrecognized-chain
 * error; a method this version does not answer yet with the unsupported-method error; and parameters
 * that are not the method's as invalid. So no request is left unanswered while its session lives: an
 * answer that could not be saved is tried again every [retrySeconds] until it is, or the request goes.
 * Returns only by cancellation; the host runs it while dApp messages arrive.
 */
class AnswerDappRequestsAtOnce(private val requests: DappRequests, private val retrySeconds: Long = 30) {
    suspend operator fun invoke() {
        requests.observe().collectLatest { waiting ->
            var current = waiting
            // Each new list starts over. One answered already has left it; answered again from an older
            // list, it is no longer waiting, and nothing is sent.
            while (answerAll(current)) {
                delay(retrySeconds.seconds)
                current = requests.observe().first()
            }
        }
    }

    /** Answers those of [waiting] that need no decision; true when one of the answers could not be saved. */
    private suspend fun answerAll(waiting: List<DappRequest>): Boolean {
        var notSaved = false
        for (request in waiting) {
            // Each answer runs to its end, its send included: a new list cancels only what follows it.
            val answer = withContext(NonCancellable) { answerAtOnce(request) } ?: continue
            if (answer == DappAnswer.NOT_SAVED) notSaved = true
        }
        return notSaved
    }

    /** The answer [request] needs no one's decision for, given; null for one to sign, which waits for the user. */
    private suspend fun answerAtOnce(request: DappRequest): DappAnswer? = when (val ask = request.ask) {
        is DappAsk.SwitchChain -> if (ask.chainId == Network.SEPOLIA.chainId) {
            requests.answerNull(request.id)
        } else {
            requests.refuse(request.id, DappRequestError.UNRECOGNIZED_CHAIN)
        }
        is DappAsk.NotAnsweredYet -> requests.refuse(request.id, DappRequestError.UNSUPPORTED_METHOD)
        is DappAsk.Malformed -> requests.refuse(request.id, DappRequestError.INVALID_PARAMS)
        is DappAsk.PersonalSign, is DappAsk.SignTypedData, is DappAsk.SendTransaction -> null
    }
}
