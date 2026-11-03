package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.DappAnswer
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappRequests
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.TransactionHistory
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * A dApp's transaction quoted for review (screen D6): the request as reviewed, and the transaction
 * that makes it, quoted as every transaction from an account this wallet holds is ([call]: the
 * verified nonce behind the account's own transactions not mined yet, the fees at its level, the gas,
 * and the call run as a verified call from the account at the head, which must not revert). Only
 * [QuoteDappTransaction] makes one, and what [SendDappTransaction] signs is its [call]'s transaction.
 */
@ConsistentCopyVisibility
data class DappTransactionQuote internal constructor(
    val review: DappMessageReview,
    val call: CallQuote,
) {
    val id: DappRequestId get() = review.request.id
}

/** What [QuoteDappTransaction] came to. */
sealed interface DappTransactionQuoting {
    /** [quote] is ready for review. */
    data class Quoted(val quote: DappTransactionQuote) : DappTransactionQuoting

    /** It cannot be sent: [refusal]; the request can only be refused, with its error. */
    data class Refused(val refusal: DappSignRefusal) : DappTransactionQuoting

    /** Not quoted for now: [refusal] (a balance short, a revert, no verified head yet). */
    data class NotQuoted(val refusal: CallRefusal) : DappTransactionQuoting

    /** The gas limit the dApp asks for is below [estimate], the verified estimate: it would run out of gas. */
    data class GasTooLow(val asked: Long, val estimate: Long) : DappTransactionQuoting

    /**
     * It is signed already: [answer], recorded as the answer to this request. Never signed again: one
     * maybe sent is retried, byte for byte; one a peer took answers the dApp ([AnswerDappRequestsAtOnce]).
     */
    data class AlreadySigned(val answer: SignedAnswer) : DappTransactionQuoting

    /** The dApp stopped waiting. */
    data object Expired : DappTransactionQuoting

    /** The request is no longer waiting, or is no transaction. */
    data object Gone : DappTransactionQuoting
}

/**
 * Quotes a dApp's `eth_sendTransaction` from an account whose key is on this device (docs/walletconnect.md,
 * *Transactions*), on Sepolia, as Send quotes a transfer: the request reviewed again now
 * ([reviewDappMessage]), then its value and call data from its account to its recipient at [FeeLevel.NORMAL]'s
 * fees, run as a verified call that must not revert. The gas limit is the verified estimate, or the dApp's
 * own when it asks for one at least that large; one below it is refused, never raised. Nothing is quoted on
 * anything less than verified state. [clock] is in unix seconds.
 */
class QuoteDappTransaction(
    private val requests: DappRequests,
    private val accounts: AccountRepository,
    private val networks: NetworkSettings,
    private val tokens: TokenCatalog,
    private val nativeBalances: NativeBalances,
    inputs: TransactionInputs,
    private val sentTransfers: SentTransfers,
    private val history: TransactionHistory,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val network = Network.SEPOLIA
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock)

    suspend operator fun invoke(id: DappRequestId, level: FeeLevel = FeeLevel.NORMAL): DappTransactionQuoting {
        val request = requests.observe().first().firstOrNull { it.id == id } ?: return DappTransactionQuoting.Gone
        val list = (accounts.observeAccounts().first() as? AccountList.Loaded)?.accounts
        val pinned = tokens.observeTokens(network).first()
        val review = reviewDappMessage(request, list, networks.observe().first(), clock(), pinned)
            ?: return DappTransactionQuoting.Gone
        val transaction = (review.content as? DappSignContent.Transaction)?.transaction
            ?: return DappTransactionQuoting.Gone
        // A Safe's is a Safe transaction (ProposeDappSafeTx), never quoted as an account's own.
        if (review.account?.kind == AccountKind.SAFE) return DappTransactionQuoting.Gone
        // First: one signed already is that transaction, whatever the review says now (a key gone, Sepolia
        // off): it needs nothing more to be retried, and must never be refused while it may be mined.
        signedFor(sentTransfers, history, id)?.let { return DappTransactionQuoting.AlreadySigned(it) }
        review.refusal?.let { return DappTransactionQuoting.Refused(it) }
        if (request.expiresAtSeconds <= clock()) return DappTransactionQuoting.Expired

        quoter.heldBack(transaction.from)?.let { return DappTransactionQuoting.NotQuoted(it) }
        val native = when (val read = NativeRead.of(nativeBalances.observeBalance(network, transaction.from).first())) {
            is NativeRead.Before -> read
            is NativeRead.NotRead -> return DappTransactionQuoting.NotQuoted(read.refusal)
        }
        val call = when (
            val quoting = quoter.quote(
                transaction.from,
                transaction.to,
                transaction.value,
                transaction.data,
                level,
                native,
            )
        ) {
            is CallQuoting.Quoted -> quoting.quote
            is CallQuoting.Refused -> return DappTransactionQuoting.NotQuoted(quoting.refusal)
        }
        val asked = transaction.gas ?: return DappTransactionQuoting.Quoted(DappTransactionQuote(review, call))
        val estimate = call.transaction.gasLimit
        if (asked < estimate) return DappTransactionQuoting.GasTooLow(asked, estimate)
        return withGas(call, asked)?.let { DappTransactionQuoting.Quoted(DappTransactionQuote(review, it)) }
            ?: DappTransactionQuoting.NotQuoted(CallRefusal.InsufficientFunds)
    }

    /** [call] with [gas] as its limit, its most fee and the balance it leaves worked out again; null when short. */
    private fun withGas(call: CallQuote, gas: Long): CallQuote? {
        val transaction = call.transaction
        val maxFee = transaction.maxFeePerGas.timesOrNull(UInt256.of(gas)) ?: return null
        val cost = maxFee.plusOrNull(transaction.value) ?: return null
        val left = call.nativeBefore.minusOrNull(call.pendingCost)?.minusOrNull(cost) ?: return null
        return call.copy(
            transaction = eth.serotine.domain.model.Eip1559Transaction(
                chainId = transaction.chainId,
                nonce = transaction.nonce,
                maxPriorityFeePerGas = transaction.maxPriorityFeePerGas,
                maxFeePerGas = transaction.maxFeePerGas,
                gasLimit = gas,
                to = transaction.to,
                value = transaction.value,
                data = transaction.data,
            ),
            maxFee = maxFee,
            nativeAfterAtLeast = left,
        )
    }
}

/** Where a transaction signed as a request's answer stands. */
enum class SignedState {
    /** No peer was seen to take it, and no block to include it: it may still be on its way; Try again sends it. */
    ON_ITS_WAY,

    /** A peer took it: the dApp is answered with its hash. */
    SENT,

    /** A verified block includes it: the dApp is answered with its hash. */
    MINED,

    /** Another transaction of the account took its nonce: it can never be mined. The request can only be refused. */
    DROPPED,
}

/** The transaction [transfer] signed as a request's answer, and where it stands ([state]). */
data class SignedAnswer(val transfer: SentTransfer, val state: SignedState)

/**
 * The transaction signed as the answer to request [id], when there is one: in the sent record, or, once
 * the record has let it go, in the [history], which keeps every one with its verified outcome. So a
 * request is signed once, whatever was pruned since.
 */
internal suspend fun signedFor(
    sentTransfers: SentTransfers,
    history: TransactionHistory,
    id: DappRequestId,
): SignedAnswer? {
    fun answers(transfer: SentTransfer) = (transfer.action as? SentAction.DappCall)?.request == id
    val recorded = (sentTransfers.observe().first() as? SentTransferList.Loaded)?.transfers?.firstOrNull(::answers)
    val entry = (history.observe().first() as? HistoryList.Loaded)?.entries?.firstOrNull { answers(it.transfer) }
    val transfer = recorded ?: entry?.transfer ?: return null
    return SignedAnswer(transfer, stateOf(recorded, entry))
}

/** Where a transaction stands, from its [recorded] entry (null: let go) and its [entry] in the history. */
internal fun stateOf(recorded: SentTransfer?, entry: HistoryEntry?): SignedState = when (entry?.outcome) {
    is SentOutcome.Included, is SentOutcome.Finalized -> SignedState.MINED
    SentOutcome.NonceUsed -> SignedState.DROPPED
    else -> if ((recorded ?: entry?.transfer)?.maybeSent == true) SignedState.ON_ITS_WAY else SignedState.SENT
}

/** What [SendDappTransaction] came to. */
sealed interface DappTransactionSending {
    /** Sent: [hash], and the dApp answered with it ([answer]: delivered, or saved and sent again). */
    data class Sent(val hash: String, val answer: DappAnswer) : DappTransactionSending

    /** Not signed or not sent: [sending] says why, its signed bytes kept for a retry where there are some. */
    data class NotSent(val sending: TransferSending) : DappTransactionSending

    /** Not signed: reviewed again, now, it cannot be sent ([refusal]). */
    data class Refused(val refusal: DappSignRefusal) : DappTransactionSending

    /** Not signed again: [answer] is signed already as the answer to this request. */
    data class AlreadySigned(val answer: SignedAnswer) : DappTransactionSending

    /** The dApp stopped waiting before it was signed: nothing was signed. */
    data object Expired : DappTransactionSending

    /** The request is no longer waiting: nothing was signed. */
    data object Gone : DappTransactionSending
}

/**
 * Signs a reviewed [DappTransactionQuote] and sends it (screen D6), as Send sends a transfer
 * ([signAndSend]: the EIP-1559 signing digest signed by the account's key under the unlock, the
 * signed bytes broadcast, recorded once a peer took them), and answers the dApp with its hash: only
 * an Ethereum transaction hash, and only once a peer took it (docs/walletconnect.md, *Transactions*).
 * One not sent is kept for a retry ([retry]: the same bytes, never a new signature), which answers the
 * dApp once it is sent. A request the dApp stopped waiting for is not signed at all. [clock] is in unix
 * seconds.
 */
class SendDappTransaction(
    private val requests: DappRequests,
    private val accounts: AccountRepository,
    private val networks: NetworkSettings,
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val history: TransactionHistory,
    receipts: ReceiptTracker,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val rebroadcast = RebroadcastTransfer(broadcaster, sentTransfers, receipts, clock)

    suspend operator fun invoke(quote: DappTransactionQuote, unlock: Unlock): DappTransactionSending {
        val request = requests.observe().first().firstOrNull { it.id == quote.id } ?: return DappTransactionSending.Gone
        // Reviewed again, now, as a message is before it is signed: an account removed, a key gone or
        // Sepolia turned off since the quote refuses it.
        signedFor(sentTransfers, history, quote.id)?.let { return DappTransactionSending.AlreadySigned(it) }
        val list = (accounts.observeAccounts().first() as? AccountList.Loaded)?.accounts
        val review = reviewDappMessage(request, list, networks.observe().first(), clock())
        review?.refusal?.let { return DappTransactionSending.Refused(it) }
        if (review?.account?.kind == AccountKind.SAFE) return DappTransactionSending.Gone
        if (request.expiresAtSeconds <= clock()) return DappTransactionSending.Expired
        val action = SentAction.DappCall(
            quote.call.transaction.to,
            quote.call.transaction.value,
            request.dapp.name,
            quote.id,
        )
        val sending =
            signAndSend(signer, broadcaster, sentTransfers, Network.SEPOLIA, quote.call, action, clock, unlock)
        return answered(quote.id, sending)
    }

    /** Sends [transfer], signed for request [id] but not sent, again, byte for byte. */
    suspend fun retry(id: DappRequestId, transfer: SentTransfer): DappTransactionSending =
        answered(id, rebroadcast(transfer))

    private suspend fun answered(id: DappRequestId, sending: TransferSending): DappTransactionSending =
        when (sending) {
            is TransferSending.Sent -> withContext(NonCancellable) {
                DappTransactionSending.Sent(sending.hash, requests.answerTransaction(id, sending.hash))
            }
            else -> DappTransactionSending.NotSent(sending)
        }
}
