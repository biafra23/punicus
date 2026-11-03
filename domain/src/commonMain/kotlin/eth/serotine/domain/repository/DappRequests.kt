package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DappTransactionRequest
import eth.serotine.domain.model.EncodedTypedData
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.Signature
import kotlinx.coroutines.flow.Flow

/** What names a dApp's request: its session and its id there (ids are unique per session only). */
data class DappRequestId(val session: DappSessionId, val id: Long)

/**
 * What a dApp asks for, decoded by the adapter from its JSON, strictly and with size limits
 * (docs/walletconnect.md, *The methods*). Untrusted input, every field of it: the use cases check it
 * against the session and the wallet before anything is shown as signable.
 */
sealed interface DappAsk {

    /** `personal_sign`: [account] to sign [message], as the dApp sent it. */
    data class PersonalSign(val account: Address, val message: PersonalMessage) : DappAsk

    /**
     * `eth_signTypedData_v4`: [account] to sign [typed], the typed data the dApp sent, checked against
     * EIP-712 and its digest computed here ([eth.serotine.domain.model.Eip712]).
     */
    data class SignTypedData(val account: Address, val typed: EncodedTypedData) : DappAsk

    /**
     * `eth_sendTransaction`: [transaction], its fields applied as docs/walletconnect.md sets out (the
     * others were refused by the adapter, never accepted and ignored).
     */
    data class SendTransaction(val transaction: DappTransactionRequest) : DappAsk

    /** `wallet_switchEthereumChain` to [chainId]: answered at once, Sepolia alone being a session's chain. */
    data class SwitchChain(val chainId: Long) : DappAsk

    /**
     * A method on the closed list that this version does not answer yet ([method]): answered at once
     * with the unsupported-method error, never left waiting.
     */
    data class NotAnsweredYet(val method: String) : DappAsk

    /** A [method] on the closed list whose parameters are not what it takes: answered at once as invalid. */
    data class Malformed(val method: String) : DappAsk
}

/** A dApp's request waiting for its answer. */
data class DappRequest(
    val id: DappRequestId,
    val dapp: DappMetadata,
    /** The accounts the session gives the dApp: a request naming another is refused. */
    val sessionAccounts: List<Address>,
    /** The chain it names, as CAIP-2 (`eip155:11155111`): the adapter keeps only Sepolia's. */
    val chain: String,
    val ask: DappAsk,
    /** When the dApp stops waiting for an answer, in unix seconds. */
    val expiresAtSeconds: Long,
)

/** The error a request is answered with, as EIP-1193 names them. */
enum class DappRequestError {
    /** 4001: the user declined. */
    USER_REJECTED,

    /** 4100: it names an account, or a chain, the session does not give the dApp; or one that cannot sign here. */
    UNAUTHORIZED,

    /** 4200: a method this version does not answer. */
    UNSUPPORTED_METHOD,

    /** 4902: a chain the session cannot switch to (Sepolia alone, for now). */
    UNRECOGNIZED_CHAIN,

    /** -32602: its parameters are not the method's. */
    INVALID_PARAMS,

    /** -32005 (EIP-1474): more than this app takes, though the method's. */
    LIMIT_EXCEEDED,
}

/** What answering a request came to. */
enum class DappAnswer {
    /** Answered, and the relay took the answer. */
    DELIVERED,

    /** Answered and saved, but the relay has not taken it yet: it is sent again, never lost. */
    NOT_DELIVERED,

    /** No such request is waiting (answered already, or its session ended). */
    NO_SUCH_REQUEST,

    /** The dApp stopped waiting: it is told the request expired, never handed a late answer. */
    EXPIRED,

    /** The state could not be saved: nothing was answered, and the request is still waiting. */
    NOT_SAVED,

    /** dApp connections are switched off in this build. */
    OFF,
}

/**
 * The requests dApps send over their sessions (docs/walletconnect.md, *Direction 1*), and their answers.
 * Only the closed list of methods reaches this port, on Sepolia alone (owner rulings, 2026-11-03, #22):
 * the adapter answers every other request itself. No key reaches it: a signature comes from the signer
 * port, made over a digest computed in `:domain`. Never throws.
 */
interface DappRequests {

    /** The requests waiting, oldest first, on each change; none while dApp connections are off. */
    fun observe(): Flow<List<DappRequest>>

    /** Answers [request] with [signature], as the 65 bytes `r ‖ s ‖ v`, `v` being 27 or 28. */
    suspend fun answerSignature(request: DappRequestId, signature: Signature): DappAnswer

    /** Answers [request] with [hash], `0x` and 64 hex digits: the transaction it asked for, sent. */
    suspend fun answerTransaction(request: DappRequestId, hash: String): DappAnswer

    /** Answers [request] with JSON's null: what `wallet_switchEthereumChain` answers when it succeeds. */
    suspend fun answerNull(request: DappRequestId): DappAnswer

    /** Answers [request] with the error [why]. */
    suspend fun refuse(request: DappRequestId, why: DappRequestError): DappAnswer
}
