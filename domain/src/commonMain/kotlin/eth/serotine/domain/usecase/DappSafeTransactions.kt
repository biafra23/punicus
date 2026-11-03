package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DappCallMeaning
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappRequests
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.SafeTxQueue
import eth.serotine.domain.repository.SafeTxQueueList
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlin.time.Duration
import kotlinx.coroutines.async
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

    /** The request cannot be sent: [refusal] (another chain, an account not given the dApp, a gas limit…). */
    data class Refused(val refusal: DappSignRefusal) : DappSafeProposing

    /** The Safe transaction is one this wallet signs, approves and executes none of: [refusal]. */
    data class SafeRefused(val refusal: SafeTxRefusal) : DappSafeProposing

    /** The Safe's balance cannot pay the value, with what its queued transactions may move. */
    data object InsufficientFunds : DappSafeProposing

    /** The Safe's call reverts, or its token says it failed, run as a verified call from the Safe at [blockNumber]. */
    data class Reverts(val blockNumber: Long?) : DappSafeProposing

    /** No owner of the Safe has a key on this device: nobody here can sign it. */
    data object NoOwnerHere : DappSafeProposing

    /**
     * It is queued already: [request], the Safe transaction recorded for it, which collects its owners'
     * signatures and is executed on the Safe transaction screen. Never proposed again.
     */
    data class AlreadyQueued(val request: SigningRequest) : DappSafeProposing

    /**
     * The Safe transaction recorded for it, [safeTx], is no longer queued here and is not the one it would
     * be proposed as now: another took its nonce, or the Safe's nonce moved on. Never proposed as another,
     * so it can only be refused. (While its nonce is still free it is proposed again as itself.)
     */
    data class NoLongerQueued(val safeTx: Digest) : DappSafeProposing

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
 * the Safe, which must not revert (nor, for a token's `transfer` or `approve`, return false), and its value
 * must be in the Safe's verified balance with what its queued transactions may move. A request is the Safe
 * transaction recorded for it ([DappRequest.safeTx]) once one is, never another. [clock] is in unix seconds.
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

    suspend operator fun invoke(id: DappRequestId): DappSafeProposing = cancellingReads {
        val request = requests.observe().first().firstOrNull { it.id == id }
            ?: return@cancellingReads DappSafeProposing.Gone
        val list = (accounts.observeAccounts().first() as? AccountList.Loaded)?.accounts
        val catalog = tokens.observeTokens(network).first()
        val review = reviewDappMessage(request, list, networks.observe().first(), clock(), catalog)
            ?: return@cancellingReads DappSafeProposing.Gone
        val transaction = (review.content as? DappSignContent.Transaction)?.transaction
            ?: return@cancellingReads DappSafeProposing.Gone
        val safe = AccountId(network, transaction.from)
        // First: one queued already is that Safe transaction, whatever the review says now.
        val recorded = request.safeTx
        if (recorded != null) {
            queued(queue, safe, recorded)?.let { return@cancellingReads DappSafeProposing.AlreadyQueued(it) }
        }
        review.refusal?.let { return@cancellingReads DappSafeProposing.Refused(it) }
        if (request.expiresAtSeconds <= clock()) return@cancellingReads DappSafeProposing.Expired

        val reading = when (val check = observeSafe.check(safe)) {
            is SafeCheck.Verified -> check.reading
            is SafeCheck.Refused -> return@cancellingReads DappSafeProposing.SafeRefused(check.refusal)
        }
        if (reading.ownersHeld.isEmpty()) return@cancellingReads DappSafeProposing.NoOwnerHere
        // The Safe's ether alone: a dApp's value is ether, and what its call moves of a token is the call's,
        // run as a verified call below.
        val balance = async { safeNativeBalanceOf(network, nativeBalances, safe.address) }
        val queuedList = async { queue.observe(safe).first() }
        val safeNonce = reading.state.nonce
        val pending = pendingOf(queuedList.await(), safeNonce, network)
        val nonce = nextNonce(pending, safeNonce)
        val signingRequest =
            SigningRequest.of(network, safe.address, SafeTx.call(transaction.to, transaction.value, transaction.data, nonce))
        // Recorded once, it is that Safe transaction or none: never another one, at another nonce.
        if (recorded != null && signingRequest.hash != recorded) {
            return@cancellingReads DappSafeProposing.NoLongerQueued(recorded)
        }
        signingRequest.unsignable()?.let { return@cancellingReads DappSafeProposing.SafeRefused(it) }
        val before = when (val read = balance.await()) {
            is SafeBalanceRead.Read -> read
            // Ether has no token contract: only a token's read answers this.
            SafeBalanceRead.NotAToken -> return@cancellingReads DappSafeProposing.InsufficientFunds
            is SafeBalanceRead.Refused -> return@cancellingReads DappSafeProposing.SafeRefused(read.refusal)
        }
        val queuedAmount = pending.filter { it.nonce < nonce }.queuedMoves(null)
            ?: return@cancellingReads DappSafeProposing.InsufficientFunds
        val available = before.amount.minusOrNull(queuedAmount)?.takeIf { it >= transaction.value }
            ?: return@cancellingReads DappSafeProposing.InsufficientFunds
        val tokenCall = transaction.meaning is DappCallMeaning.TokenTransfer ||
            transaction.meaning is DappCallMeaning.TokenApproval
        val simulation = when (
            val run = inputs.simulate(network, safe.address, transaction.to, transaction.value, transaction.data)
        ) {
            is Simulation.Succeeded -> {
                // A token that answers false instead of reverting moved nothing.
                if (tokenCall && Erc20.returnedFalse(run.returnHex)) {
                    return@cancellingReads DappSafeProposing.Reverts(run.blockNumber)
                }
                SafeCallSimulation.Ran(run.blockNumber)
            }
            is Simulation.Reverted -> return@cancellingReads DappSafeProposing.Reverts(run.blockNumber)
            Simulation.Refused -> SafeCallSimulation.NotRun
            is Simulation.Unavailable ->
                return@cancellingReads DappSafeProposing.SafeRefused(SafeTxRefusal.Unavailable(run.reason))
        }
        queue.prune(safe, safeNonce)
        val proposal = SafeTxProposal(
            tx = DecodedSafeTx.of(signingRequest),
            safe = reading,
            queuedAhead = (nonce - safeNonce).toInt(),
            balanceBefore = before.amount,
            queuedAmount = queuedAmount,
            balanceAfter = available - transaction.value,
            balanceBlock = before.blockNumber,
            simulation = simulation,
        )
        DappSafeProposing.Proposed(DappSafeProposal(review, proposal))
    }
}

/** What [SignDappSafeTx] came to. */
sealed interface DappSafeSigning {
    /** Signed and, when [saved], queued: [request] carries the signature now. */
    data class Signed(val request: SigningRequest, val saved: Boolean) : DappSafeSigning

    /** Not signed, for the reason [signing] gives: nothing left the key store. */
    data class NotSigned(val signing: Signing) : DappSafeSigning

    /** Not signed: checked against the Safe now, [refusal]. */
    data class Refused(val refusal: SafeTxRefusal) : DappSafeSigning

    /** Not signed: the request could not be tied to the Safe transaction (no longer waiting, or not saved). */
    data object NotRecorded : DappSafeSigning
}

/**
 * Signs a dApp's [DappSafeProposal] as the first of the Safe's owners held here and queues it, as
 * [SignSafeTx] signs one (screen D6). Once the signature is made, and before it is queued, the request
 * is tied to the Safe transaction ([DappRequests.recordSafeTx]): nothing is queued unless that is saved, so
 * a request signed for is that Safe transaction, never another, and is answered from its execution alone.
 * A signature not made (a wrong password, a prompt cancelled) ties nothing.
 */
class SignDappSafeTx(
    private val requests: DappRequests,
    private val signSafeTx: SignSafeTx,
) {
    /** Whether the owner may sign [proposal] now, as [SignSafeTx.check], waiting at most [budget]. */
    suspend fun check(proposal: DappSafeProposal, budget: Duration = SAFE_CHECK_BUDGET): SafeTxSignCheck =
        signSafeTx.check(proposal.proposal.request, proposal.heldOwners.first(), budget)

    /** Signs [checked] with [unlock], ties [proposal]'s request to it, and queues it. */
    suspend fun sign(proposal: DappSafeProposal, checked: SafeTxSignCheck.Ready, unlock: Unlock): DappSafeSigning {
        val signed = when (val made = signSafeTx.signature(checked, unlock)) {
            is SafeTxSignature.Made -> made.request
            is SafeTxSignature.NotMade -> return when (val outcome = made.outcome) {
                is SafeTxSigning.NotSigned -> DappSafeSigning.NotSigned(outcome.signing)
                is SafeTxSigning.Refused -> DappSafeSigning.Refused(outcome.refusal)
                is SafeTxSigning.Signed -> DappSafeSigning.Signed(outcome.request, outcome.saved)
            }
        }
        // Not tied, it is dropped: never queued, never shared, so it can never run for this request.
        if (!requests.recordSafeTx(proposal.id, signed.hash)) return DappSafeSigning.NotRecorded
        val queued = signSafeTx.queue(checked, signed)
        return DappSafeSigning.Signed(queued.request, queued.saved)
    }
}

/** The Safe transaction [safeTx] of [safe], when it is in the queue. */
internal suspend fun queued(queue: SafeTxQueue, safe: AccountId, safeTx: Digest): SigningRequest? =
    (queue.observe(safe).first() as? SafeTxQueueList.Loaded)?.requests
        ?.firstOrNull { it.network == safe.network && it.safe == safe.address && it.hash == safeTx }

/**
 * The execution sent of the Safe transaction [safeTx], on Sepolia: an `execTransaction` of it in the sent
 * record or the history, with where it stands; of several (one sent again after another was dropped), the
 * one furthest along. What answers the dApp with the hash of the transaction sent, never the `safeTxHash`
 * (docs/walletconnect.md, *Transactions*).
 */
internal fun executionFor(safeTx: Digest, sent: List<SentTransfer>, past: List<HistoryEntry>): SignedAnswer? {
    fun executes(transfer: SentTransfer): Boolean {
        val request = (transfer.action as? SentAction.SafeExecution)?.request ?: return false
        return transfer.network == Network.SEPOLIA && request.network == Network.SEPOLIA && request.hash == safeTx
    }
    val hashes = (sent.filter(::executes).map { it.hash } + past.filter { executes(it.transfer) }.map { it.transfer.hash })
        .distinct()
    return hashes.map { hash ->
        val recorded = sent.firstOrNull { it.hash == hash && executes(it) }
        val entry = past.firstOrNull { it.transfer.hash == hash && executes(it.transfer) }
        SignedAnswer(recorded ?: checkNotNull(entry).transfer, stateOf(recorded, entry))
    }.maxByOrNull { FURTHEST.indexOf(it.state) }
}

/** Where an execution stands, least far along first. */
private val FURTHEST = listOf(SignedState.DROPPED, SignedState.ON_ITS_WAY, SignedState.SENT, SignedState.MINED)
