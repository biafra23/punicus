package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DappTransactionRequest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappRequests
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.SafeTxQueue
import eth.serotine.domain.repository.SafeTxQueueList
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.repository.TransactionHistory
import eth.serotine.domain.repository.TransactionInputs
import kotlinx.coroutines.flow.first

/**
 * A dApp's transaction from a Safe, proposed as a Safe transaction (docs/walletconnect.md, *Transactions*):
 * [proposal], the `SafeTx` that makes it (a plain call, no refund, at the Safe's next nonce), and the
 * Safe's owners whose keys are on this device ([heldOwners]), who may sign it here. Its other owners sign
 * through a co-signing request, and a local account executes it. Only [ProposeDappSafeTx] makes one.
 */
@ConsistentCopyVisibility
data class DappSafeProposal internal constructor(
    val review: DappMessageReview,
    val proposal: SafeTxProposal,
) {
    val id: DappRequestId get() = review.request.id

    /** The Safe's owners whose keys are on this device, in the Safe's order: who may sign it here. */
    val heldOwners: List<Address> get() = proposal.safe.ownersHeld
}

/** What [ProposeDappSafeTx] came to. */
sealed interface DappSafeProposing {
    /** [proposal] is ready for an owner held here to sign. */
    data class Proposed(val proposal: DappSafeProposal) : DappSafeProposing

    /** The request cannot be sent: [refusal] (another chain, an account not given the dApp, Sepolia off…). */
    data class Refused(val refusal: DappSignRefusal) : DappSafeProposing

    /** The Safe transaction is one this wallet signs, approves and executes none of: [refusal]. */
    data class SafeRefused(val refusal: SafeTxRefusal) : DappSafeProposing

    /** The Safe's balance cannot pay the value, with what its queued transactions may move. */
    data object InsufficientFunds : DappSafeProposing

    /** The Safe's call reverts, run as a verified call from the Safe at [blockNumber]. */
    data class Reverts(val blockNumber: Long?) : DappSafeProposing

    /** No owner of the Safe has a key on this device: nobody here can propose it. */
    data object NoOwnerHere : DappSafeProposing

    /**
     * It is queued already: [request], the Safe transaction proposed for it, which collects its owners'
     * signatures and is executed on the Safe transaction screen. Never proposed again.
     */
    data class AlreadyQueued(val request: SigningRequest) : DappSafeProposing

    /** The dApp stopped waiting. */
    data object Expired : DappSafeProposing

    /** The request is no longer waiting, or is no Safe's transaction. */
    data object Gone : DappSafeProposing
}

/**
 * Proposes a dApp's `eth_sendTransaction` from a Safe as a Safe transaction (docs/walletconnect.md,
 * *Transactions*): a `SafeTx` with operation `CALL` and no refund, of the request's value and call data to
 * its recipient, at the Safe's next nonce behind its queued transactions. Every Safe rule applies, as for
 * one the user builds ([SigningRequest.unsignable]): no delegate call, no gas terms, no call of the Safe
 * itself that is not exactly one of its settings changes, no fallback handler set but the pinned one, no
 * module enabled (owner rulings, 2026-10-18, #63, on #54 and #57). The call is run as a verified call from
 * the Safe, which must not revert, and its value must be in the Safe's verified balance with what its queued
 * transactions may move. A request queued once is that Safe transaction, never another: it is found in the
 * queue by its fields ([sameCall]). [clock] is in unix seconds.
 */
class ProposeDappSafeTx(
    private val requests: DappRequests,
    private val accounts: AccountRepository,
    private val networks: NetworkSettings,
    private val tokens: TokenCatalog,
    private val observeSafe: ObserveSafe,
    private val nativeBalances: NativeBalances,
    private val inputs: TransactionInputs,
    private val queue: SafeTxQueue,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val network = Network.SEPOLIA

    suspend operator fun invoke(id: DappRequestId): DappSafeProposing {
        val request = requests.observe().first().firstOrNull { it.id == id } ?: return DappSafeProposing.Gone
        val list = (accounts.observeAccounts().first() as? AccountList.Loaded)?.accounts
        val review = reviewDappMessage(request, list, networks.observe().first(), clock(), tokens.observeTokens(network).first())
            ?: return DappSafeProposing.Gone
        val transaction = (review.content as? DappSignContent.Transaction)?.transaction ?: return DappSafeProposing.Gone
        val safe = transaction.from
        // First: one queued already is that Safe transaction, whatever the review says now.
        queuedFor(queue, network, transaction)?.let { return DappSafeProposing.AlreadyQueued(it) }
        review.refusal?.let { return DappSafeProposing.Refused(it) }
        if (request.expiresAtSeconds <= clock()) return DappSafeProposing.Expired

        val reading = when (val check = observeSafe.check(AccountId(network, safe))) {
            is SafeCheck.Verified -> check.reading
            is SafeCheck.Refused -> return DappSafeProposing.SafeRefused(check.refusal)
        }
        if (reading.ownersHeld.isEmpty()) return DappSafeProposing.NoOwnerHere
        // The Safe's ether alone: a dApp's value is ether, and what its call moves of a token is the call's,
        // run as a verified call below.
        val balance = when (val reading = nativeBalances.observeBalance(network, safe).first()) {
            is BalanceReading.Verified -> SafeBalanceRead.Read(
                reading.wei.toUInt256OrNull()
                    ?: return DappSafeProposing.SafeRefused(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN)),
                reading.blockNumber,
            )
            BalanceReading.Syncing, is BalanceReading.LastKnown -> return DappSafeProposing.SafeRefused(SafeTxRefusal.Syncing)
            is BalanceReading.Unavailable -> return DappSafeProposing.SafeRefused(SafeTxRefusal.Unavailable(reading.reason))
        }
        val safeNonce = reading.state.nonce
        val pending = pendingOf(queue.observe(AccountId(network, safe)).first(), safeNonce, network)
        val nonce = nextNonce(pending, safeNonce)
        val queuedAmount = pending.filter { it.nonce < nonce }.queuedMoves(null) ?: return DappSafeProposing.InsufficientFunds
        val available = balance.amount.minusOrNull(queuedAmount)?.takeIf { it >= transaction.value }
            ?: return DappSafeProposing.InsufficientFunds
        val signingRequest = SigningRequest.of(network, safe, SafeTx.call(transaction.to, transaction.value, transaction.data, nonce))
        signingRequest.unsignable()?.let { return DappSafeProposing.SafeRefused(it) }
        val simulation = when (val run = inputs.simulate(network, safe, transaction.to, transaction.value, transaction.data)) {
            is Simulation.Succeeded -> SafeCallSimulation.Ran(run.blockNumber)
            is Simulation.Reverted -> return DappSafeProposing.Reverts(run.blockNumber)
            Simulation.Refused -> SafeCallSimulation.NotRun
            is Simulation.Unavailable -> return DappSafeProposing.SafeRefused(SafeTxRefusal.Unavailable(run.reason))
        }
        val proposal = SafeTxProposal(
            tx = DecodedSafeTx.of(signingRequest),
            safe = reading,
            queuedAhead = (nonce - safeNonce).toInt(),
            balanceBefore = balance.amount,
            queuedAmount = queuedAmount,
            balanceAfter = available - transaction.value,
            balanceBlock = balance.blockNumber,
            simulation = simulation,
        )
        return DappSafeProposing.Proposed(DappSafeProposal(review, proposal))
    }
}

/** Whether [tx] is the plain call [transaction] asks for: its recipient, value and data, no refund. */
internal fun sameCall(tx: SafeTx, transaction: DappTransactionRequest): Boolean =
    tx.operation == SafeOperation.CALL && tx.to == transaction.to && tx.value == transaction.value &&
        tx.data.contentEquals(transaction.data) && tx.gasPrice.isZero && tx.safeTxGas.isZero && tx.baseGas.isZero

/** The Safe transaction queued for [transaction] (its Safe being its `from`), when there is one not yet run past. */
internal suspend fun queuedFor(queue: SafeTxQueue, network: Network, transaction: DappTransactionRequest): SigningRequest? =
    (queue.observe(AccountId(network, transaction.from)).first() as? SafeTxQueueList.Loaded)?.requests
        ?.firstOrNull { it.network == network && it.safe == transaction.from && sameCall(it.tx, transaction) }

/**
 * The execution sent for [transaction] (its Safe being its `from`): an `execTransaction` of a Safe
 * transaction that is that call, in the sent record or the history, with where it stands. What answers the
 * dApp with the hash of the transaction sent, never the `safeTxHash` (docs/walletconnect.md, *Transactions*).
 */
internal fun executionFor(
    transaction: DappTransactionRequest,
    sent: List<SentTransfer>,
    past: List<eth.serotine.domain.repository.HistoryEntry>,
): SignedAnswer? {
    fun executes(transfer: SentTransfer): Boolean {
        val request = (transfer.action as? SentAction.SafeExecution)?.request ?: return false
        return request.safe == transaction.from && sameCall(request.tx, transaction)
    }
    val recorded = sent.firstOrNull(::executes)
    val entry = past.firstOrNull { executes(it.transfer) }
    val transfer = recorded ?: entry?.transfer ?: return null
    return SignedAnswer(transfer, stateOf(recorded, entry))
}

/** [executionFor], read from the sent record and the history now. */
internal suspend fun executionFor(
    transaction: DappTransactionRequest,
    sentTransfers: SentTransfers,
    history: TransactionHistory,
): SignedAnswer? = executionFor(
    transaction,
    (sentTransfers.observe().first() as? SentTransferList.Loaded)?.transfers.orEmpty(),
    (history.observe().first() as? HistoryList.Loaded)?.entries.orEmpty(),
)
