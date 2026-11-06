package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeCalls
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.ApprovalReading
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.KeptSafes
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeApprovals
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.SignatureRecovery
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/** How one owner of a Safe stands on a transaction (docs/wallet-ux.md, screen 17). */
sealed interface OwnerConfirmation {

    /** Its signature is in the request, and recovers to it over the hash computed here. */
    data object Signed : OwnerConfirmation

    /** It approved the hash on chain, as a verified call at [blockNumber] says (null: not said). */
    data class ApprovedOnChain(val blockNumber: Long?) : OwnerConfirmation

    /** Neither. */
    data object Missing : OwnerConfirmation

    /** No signature, and whether it approved on chain cannot be verified right now ([reason]). */
    data class Unknown(val reason: ReadUnavailable) : OwnerConfirmation
}

/** [owner], one of the Safe's current owners, whose key the wallet holds or not ([held]), and how it stands. */
data class SafeTxOwner(val owner: Address, val held: Boolean, val confirmation: OwnerConfirmation) {
    /** Whether it confirms the transaction, by signature or on chain. */
    val confirms: Boolean
        get() = confirmation == OwnerConfirmation.Signed || confirmation is OwnerConfirmation.ApprovedOnChain
}

/**
 * Where a Safe transaction stands with the Safe's current owners (docs/wallet-ux.md, screen 17):
 * for each, as its verified storage lists them, whether it signed (a signature in the request
 * that recovers to it over the hash computed here), approved the hash on chain
 * ([SafeApprovals]), or neither. A signature of an address that is no owner now is not counted.
 * On [network] only.
 */
class ReadSafeTxOwners(
    private val observeSafe: ObserveSafe,
    private val recovery: SignatureRecovery,
    private val approvals: SafeApprovals,
    private val network: Network,
) {
    suspend operator fun invoke(request: SigningRequest): SafeTxOwnersReading = coroutineScope {
        if (request.network != network) {
            return@coroutineScope SafeTxOwnersReading.Refused(SafeTxRefusal.WrongChain(request.network.chainId))
        }
        val safe = when (val check = observeSafe.check(AccountId(request.network, request.safe))) {
            is SafeCheck.Verified -> check.reading
            is SafeCheck.Refused -> return@coroutineScope SafeTxOwnersReading.Refused(check.refusal)
        }
        val signed = recovery.valid(request, safe.state.owners).map { it.owner }.toSet()
        val held = safe.ownersHeld.toSet()
        val owners = safe.state.owners.map { owner ->
            async {
                val confirmation = if (owner in signed) {
                    OwnerConfirmation.Signed
                } else {
                    when (val approval = approvals.approval(request.network, request.safe, owner, request.hash)) {
                        is ApprovalReading.Read -> if (approval.approved) {
                            OwnerConfirmation.ApprovedOnChain(approval.blockNumber)
                        } else {
                            OwnerConfirmation.Missing
                        }
                        is ApprovalReading.Unavailable -> OwnerConfirmation.Unknown(approval.reason)
                    }
                }
                SafeTxOwner(owner, owner in held, confirmation)
            }
        }.awaitAll()
        SafeTxOwnersReading.Read(
            owners,
            safe.state.threshold,
            safe.state.nonce,
            safe.blockNumber,
            refusal = safe.state.tooOldToSign() ?: request.withoutRecovery(safe.state),
        )
    }
}

/** The outcome of [ReadSafeTxOwners]. */
sealed interface SafeTxOwnersReading {

    /**
     * Every current [owners]' stand, in the Safe's order; its [threshold] and [safeNonce], verified
     * at [blockNumber]; and [refusal], why this wallet signs, approves and executes none of it
     * against the Safe as it stands now, which the transaction alone does not say: a change that
     * leaves the Safe an n-of-n with no recovery module (owner ruling, 2026-10-18, #54). Null: none.
     */
    data class Read(
        val owners: List<SafeTxOwner>,
        val threshold: Int,
        val safeNonce: Long,
        val blockNumber: Long,
        val refusal: SafeTxRefusal? = null,
    ) : SafeTxOwnersReading {
        /** How many owners confirm it, by signature or on chain. */
        val confirmations: Int
            get() = owners.count { it.confirms }

        /**
         * How many confirm it when [executor] executes it: an owner executing it confirms it by
         * sending it, as [QuoteSafeExecution] counts, whether it signed or not.
         */
        fun confirmationsExecutedBy(executor: Address): Int =
            owners.count { it.confirms || it.owner == executor }
    }

    /** Not read: [refusal] (the Safe is none, or cannot be verified now). */
    data class Refused(val refusal: SafeTxRefusal) : SafeTxOwnersReading

    /**
     * Not verifiable now, the light client catching up, and read in lenient mode from the Safe as
     * last verified instead ([ReadKeptSafeTxOwners]; docs/lenient-mode.md, *Safe transactions in
     * lenient mode*): its [owners] then, each [OwnerConfirmation.Signed] by a signature in the request
     * that recovers to it, the rest [OwnerConfirmation.Unknown] (approvals on chain need a fresh
     * head); its [threshold] and [safeNonce], verified at [blockNumber], a head of [asOf] (unix
     * seconds, null when unknown). [refusal] is what this wallet will not draft against it (the
     * threshold it would leave, a version before 1.3.0, a swap, which stays strict). Never a stand
     * of now: nothing is shown to another owner, approved or executed on it before the check.
     */
    data class LastVerified(
        val owners: List<SafeTxOwner>,
        val threshold: Int,
        val safeNonce: Long,
        val blockNumber: Long,
        val asOf: Long?,
        val refusal: SafeTxRefusal? = null,
    ) : SafeTxOwnersReading {
        /** How many owners' signatures in the request confirm it. */
        val signatures: Int
            get() = owners.count { it.confirmation == OwnerConfirmation.Signed }

        /** How many confirm it when [executor] executes it: its signatures, and the executor's own when an owner. */
        fun confirmationsExecutedBy(executor: Address): Int =
            owners.count { it.confirmation == OwnerConfirmation.Signed || it.owner == executor }
    }
}

/**
 * Where the Safe's owners stood on a transaction when the Safe was last verified, for lenient mode
 * while the light client catches up (docs/lenient-mode.md, *Safe transactions in lenient mode*):
 * the owners, threshold and nonce kept ([KeptSafes]), each owner's signature in [request] checked to
 * recover to it over the hash computed here ([SignatureRecovery]), the owners held here as
 * [ObserveSafe] finds them. An approval on chain cannot be read without a fresh head, so an owner
 * with no signature is [OwnerConfirmation.Unknown]. With no Safe kept, the light client is syncing.
 * On [network] only.
 */
class ReadKeptSafeTxOwners(
    private val kept: KeptSafes,
    private val accounts: AccountRepository,
    private val recovery: SignatureRecovery,
    private val network: Network,
) {
    suspend operator fun invoke(request: SigningRequest): SafeTxOwnersReading {
        if (request.network != network) {
            return SafeTxOwnersReading.Refused(SafeTxRefusal.WrongChain(request.network.chainId))
        }
        val safe = kept.observe(AccountId(request.network, request.safe)).first()
            ?: return SafeTxOwnersReading.Refused(SafeTxRefusal.Syncing)
        val state = safe.state
        val signed = recovery.valid(request, state.owners).map { it.owner }.toSet()
        val held = accounts.ownersHeldOf(state).toSet()
        val owners = state.owners.map { owner ->
            val confirmation = if (owner in signed) {
                OwnerConfirmation.Signed
            } else {
                OwnerConfirmation.Unknown(ReadUnavailable.SYNCING)
            }
            SafeTxOwner(owner, owner in held, confirmation)
        }
        return SafeTxOwnersReading.LastVerified(
            owners,
            state.threshold,
            state.nonce,
            safe.blockNumber,
            safe.asOf,
            refusal = state.tooOldToSign() ?: request.withoutRecovery(state) ?: request.undraftable(),
        )
    }
}

/**
 * An owner's on-chain approval of a Safe transaction, quoted for review: [request], whose hash
 * [owner] approves, and the transaction [owner] sends for it ([call]: `approveHash(hash)` to the
 * Safe, quoted as every transaction from an account this wallet holds is). Only
 * [QuoteSafeApproval] makes one.
 */
@ConsistentCopyVisibility
data class SafeApprovalQuote internal constructor(
    val request: SigningRequest,
    val owner: Address,
    val call: CallQuote,
)

/**
 * Quotes an owner's approval of a Safe transaction on chain (docs/wallet-ux.md, *Co-signing a
 * Safe transaction*: the alternative that needs no transport between devices): `approveHash` of
 * its hash, computed here, sent to the Safe by [owner], an account this wallet holds. Only a
 * current owner, as the Safe's verified storage says, approves, only a transaction whose nonce
 * the Safe has not passed, and not one it approved already ([SafeApprovals]); a delegate call is
 * refused. The approval is quoted as every transaction from an account this wallet holds is
 * ([CallQuoter]: the nonce behind its own transactions not mined yet, the fees, the gas, and
 * `approveHash` run as a verified call from [owner], which the Safe reverts for a non-owner),
 * and refused while [owner]'s approval of it sent before is not mined yet. On [network] only;
 * [clock] is in unix seconds.
 */
class QuoteSafeApproval(
    private val observeSafe: ObserveSafe,
    private val approvals: SafeApprovals,
    private val nativeBalances: NativeBalances,
    inputs: TransactionInputs,
    sentTransfers: SentTransfers,
    private val network: Network,
    /** What the account has queued in lenient mode, which holds a new quote back (docs/lenient-mode.md). */
    private val outbox: Outbox,
    clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock, outbox)

    /**
     * Quotes [owner]'s approval of [request] at the fee [level]. [safeChecked] is called once the
     * Safe is verified ([ObserveSafe.check], which may wait out an unproven reading), before the
     * approval, the balance, the fees, the nonce and the run are read: where a review measures the
     * quote's age from.
     */
    suspend operator fun invoke(
        request: SigningRequest,
        owner: Address,
        level: FeeLevel,
        safeChecked: () -> Unit = {},
    ): SafeApprovalQuoting = when (val quoted = quote(request, owner, level, safeChecked, forCheck = false)) {
        is SafeCallQuoting.Quoted -> SafeApprovalQuoting.Quoted(SafeApprovalQuote(request, owner, quoted.call))
        is SafeCallQuoting.Refused -> SafeApprovalQuoting.Refused(quoted.refusal)
    }

    /**
     * The quote of [owner]'s approval of [request] at [level] again, fresh, for the check of the
     * approval queued in lenient mode ([CheckQueued]; docs/lenient-mode.md, *The check before
     * sending*): every check above, the Safe's among them, but for the two that would judge the
     * queued transaction against itself or against the fresh figures: what the owner has queued does
     * not hold it back, and the native balance is not tested against the fresh fee (the check tests
     * the signed figures). With the base fee the next block charges.
     */
    internal suspend fun quoteForCheck(request: SigningRequest, owner: Address, level: FeeLevel): SafeCallQuoting =
        quote(request, owner, level, safeChecked = {}, forCheck = true)

    private suspend fun quote(
        request: SigningRequest,
        owner: Address,
        level: FeeLevel,
        safeChecked: () -> Unit,
        forCheck: Boolean,
    ): SafeCallQuoting {
        fun refused(refusal: SafeTxRefusal) = SafeCallQuoting.Refused(refusal)
        if (request.network != network) return refused(SafeTxRefusal.WrongChain(request.network.chainId))
        request.unsignable()?.let { return refused(it) }
        // An earlier transaction of the owner's maybe still on its way: refused before the Safe is read.
        quoter.heldBack(owner, holdQueued = !forCheck)?.let { return refused(SafeTxRefusal.NotQuoted(it)) }
        val state = when (val check = observeSafe.check(AccountId(request.network, request.safe))) {
            is SafeCheck.Verified -> check.reading.state
            is SafeCheck.Refused -> return refused(check.refusal)
        }
        safeChecked()
        state.tooOldToSign()?.let { return refused(it) }
        if (request.nonce < state.nonce) return refused(SafeTxRefusal.NonceUsed(state.nonce))
        if (owner !in state.owners) return refused(SafeTxRefusal.NotAnOwner(owner))
        request.withoutRecovery(state)?.let { return refused(it) }
        when (val approval = approvals.approval(request.network, request.safe, owner, request.hash)) {
            is ApprovalReading.Read -> if (approval.approved) return refused(SafeTxRefusal.AlreadyApproved)
            is ApprovalReading.Unavailable -> return refused(SafeTxRefusal.Unavailable(approval.reason))
        }
        val native = when (val read = NativeRead.of(nativeBalances.observeBalance(request.network, owner).first())) {
            is NativeRead.Before -> read
            is NativeRead.NotRead -> return refused(SafeTxRefusal.NotQuoted(read.refusal))
        }
        val data = SafeCalls.approveHash(request.hash)
        val quoting = quoter.quote(
            owner,
            request.safe,
            UInt256.ZERO,
            data,
            level,
            native,
            testFunds = !forCheck,
            holdQueued = !forCheck,
        )
        return when (quoting) {
            is CallQuoting.Quoted -> if (quoting.unmined.any { it.approves(request) }) {
                refused(SafeTxRefusal.AlreadySent)
            } else {
                SafeCallQuoting.Quoted(quoting.quote, quoting.nextBaseFee)
            }
            is CallQuoting.Refused -> refused(SafeTxRefusal.NotQuoted(quoting.refusal))
        }
    }
}

/**
 * What a Safe call's quote came to, for its review or for the check of one queued in lenient mode:
 * the [call] and the base fee per gas the next block charges ([nextBaseFee]), or a [refusal].
 */
internal sealed interface SafeCallQuoting {
    data class Quoted(val call: CallQuote, val nextBaseFee: UInt256) : SafeCallQuoting
    data class Refused(val refusal: SafeTxRefusal) : SafeCallQuoting
}

/** The outcome of [QuoteSafeApproval]. */
sealed interface SafeApprovalQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: SafeApprovalQuote) : SafeApprovalQuoting

    /** Not quoted: [refusal]. */
    data class Refused(val refusal: SafeTxRefusal) : SafeApprovalQuoting
}

/**
 * Sends a reviewed on-chain approval ([SafeApprovalQuote]): its transaction signed by the owner's
 * key as [unlock] opens it and broadcast, exactly as quoted, and recorded once a peer took it as
 * the approval of its request ([SentAction.SafeApproval]) — what keeps its nonce, and what its
 * status shows. As every transaction this wallet sends ([SendTransfer]); a retry goes through
 * [RebroadcastTransfer]. [clock] is in unix seconds.
 */
class ApproveSafeTxOnChain(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(quote: SafeApprovalQuote, unlock: Unlock): TransferSending = signAndSend(
        signer,
        broadcaster,
        sentTransfers,
        quote.request.network,
        quote.call,
        SentAction.SafeApproval(quote.request),
        clock,
        unlock,
    )
}

/**
 * A Safe transaction's execution, quoted for review: [request], the confirmations it is executed
 * with ([signatures], as many as the Safe's threshold, packed by owner into the call), and the
 * transaction [executor] sends for it ([call]: `execTransaction` to the Safe, quoted as every
 * transaction from an account this wallet holds is, its run the whole execution). Only
 * [QuoteSafeExecution] makes one.
 */
@ConsistentCopyVisibility
data class SafeExecutionQuote internal constructor(
    val request: SigningRequest,
    val executor: Address,
    val signatures: List<SafeSignature>,
    val call: CallQuote,
)

/**
 * Quotes a Safe transaction's execution (docs/wallet-ux.md, *Co-signing a Safe transaction*):
 * `execTransaction` sent to the Safe by [executor], an account this wallet holds, once its
 * owners' confirmations reach the threshold. Any account whose key is on this device may send it
 * and pay for it, owner or not (owner ruling, 2026-10-18, #56): the Safe asks nothing of the
 * sender but the gas. Checked against the Safe's verified state first: a
 * delegate call and gas or refund terms are refused ([SafeTxRefusal.GasTerms]), so the Safe's
 * call always gets the gas the execution has left and a call that fails reverts it; its nonce
 * must be the Safe's verified nonce (one below has run or
 * been replaced, one above waits for the transactions before it); and the confirmations are
 * counted from the Safe's current owners alone — each signature in the request that recovers to
 * its owner over the hash computed here, [executor]'s own when it is an owner (the Safe takes the
 * owner executing it as confirming), and approvals on chain ([SafeApprovals]), read only when
 * the rest fall short. As many as the threshold are used, the executor's first.
 *
 * The execution is quoted as every transaction from an account this wallet holds is
 * ([CallQuoter]), and its run is the whole of it — the Safe's checks of the signatures and the
 * nonce, its guard, and the call itself — as a verified call from [executor] at the head, with no
 * state override: a revert, or an execution that does not return true, stops it here, and the
 * estimate finds a limit it passes at. So does an execution of it that any account of this
 * wallet sent and no block has yet: run at the head, a second passes while the first is on its
 * way, and reverts once that is mined. On [network] only; [clock] is in unix seconds.
 */
class QuoteSafeExecution(
    private val observeSafe: ObserveSafe,
    private val recovery: SignatureRecovery,
    private val approvals: SafeApprovals,
    private val nativeBalances: NativeBalances,
    private val inputs: TransactionInputs,
    private val sentTransfers: SentTransfers,
    private val network: Network,
    /** What the account has queued in lenient mode, which holds a new quote back (docs/lenient-mode.md). */
    private val outbox: Outbox,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock, outbox)

    /**
     * Quotes [executor]'s execution of [request] at the fee [level]. [safeChecked] is called once
     * the Safe is verified ([ObserveSafe.check], which may wait out an unproven reading), before the
     * confirmations, the balance, the fees, the nonce and the run are read: where a review measures
     * the quote's age from.
     */
    suspend operator fun invoke(
        request: SigningRequest,
        executor: Address,
        level: FeeLevel,
        safeChecked: () -> Unit = {},
    ): SafeExecutionQuoting = when (val quoted = quote(request, executor, level, safeChecked, signed = null)) {
        is ExecutionQuoted.Quoted ->
            SafeExecutionQuoting.Quoted(SafeExecutionQuote(request, executor, quoted.signatures, quoted.call))
        is ExecutionQuoted.Refused -> SafeExecutionQuoting.Refused(quoted.refusal)
    }

    /**
     * The quote of [executor]'s execution of [request] at [level] again, fresh, for the check of the
     * execution queued in lenient mode ([CheckQueued]; docs/lenient-mode.md, *The check before
     * sending*), with [signedData], the call data that was signed: every check above, the Safe's
     * among them, and the whole execution run as a verified call at the head, but with the
     * confirmations that were signed rather than counted anew: the run is what proves the Safe
     * accepts them now (its `checkSignatures`), and a request that gathered other signatures since
     * still runs as signed. What the executor has queued does not hold it back, and the native
     * balance is not tested against the fresh fee (the check tests the signed figures). With the
     * base fee the next block charges.
     */
    internal suspend fun quoteForCheck(
        request: SigningRequest,
        executor: Address,
        level: FeeLevel,
        signedData: ByteArray,
    ): SafeCallQuoting = when (val quoted = quote(request, executor, level, safeChecked = {}, signed = signedData)) {
        is ExecutionQuoted.Quoted -> SafeCallQuoting.Quoted(quoted.call, quoted.nextBaseFee)
        is ExecutionQuoted.Refused -> SafeCallQuoting.Refused(quoted.refusal)
    }

    private sealed interface ExecutionQuoted {
        data class Quoted(val call: CallQuote, val signatures: List<SafeSignature>, val nextBaseFee: UInt256) :
            ExecutionQuoted
        data class Refused(val refusal: SafeTxRefusal) : ExecutionQuoted
    }

    private suspend fun quote(
        request: SigningRequest,
        executor: Address,
        level: FeeLevel,
        safeChecked: () -> Unit,
        signed: ByteArray?,
    ): ExecutionQuoted {
        val forCheck = signed != null
        fun refused(refusal: SafeTxRefusal) = ExecutionQuoted.Refused(refusal)
        if (request.network != network) return refused(SafeTxRefusal.WrongChain(request.network.chainId))
        request.unsignable()?.let { return refused(it) }
        // An earlier transaction of the executor's maybe still on its way: refused before the Safe is read.
        quoter.heldBack(executor, holdQueued = !forCheck)?.let { return refused(SafeTxRefusal.NotQuoted(it)) }
        val state = when (val check = observeSafe.check(AccountId(request.network, request.safe))) {
            is SafeCheck.Verified -> check.reading.state
            is SafeCheck.Refused -> return refused(check.refusal)
        }
        safeChecked()
        state.tooOldToSign()?.let { return refused(it) }
        if (request.nonce < state.nonce) return refused(SafeTxRefusal.NonceUsed(state.nonce))
        if (request.nonce > state.nonce) return refused(SafeTxRefusal.NotNext(state.nonce))
        request.withoutRecovery(state)?.let { return refused(it) }
        // Sent already from any account here, and in no block yet: the nonce is the Safe's, not the sender's.
        sentAndUnmined(request)?.let { return refused(it) }
        val signatures = if (signed != null) {
            emptyList()
        } else {
            when (val confirmed = confirmations(request, state, executor)) {
                is Confirmed.Enough -> confirmed.signatures
                is Confirmed.Refused -> return refused(confirmed.refusal)
            }
        }
        val native = when (val read = NativeRead.of(nativeBalances.observeBalance(request.network, executor).first())) {
            is NativeRead.Before -> read
            is NativeRead.NotRead -> return refused(SafeTxRefusal.NotQuoted(read.refusal))
        }
        val data = signed ?: SafeCalls.execTransaction(request.tx, signatures)
        val quoting = quoter.quote(
            executor,
            request.safe,
            UInt256.ZERO,
            data,
            level,
            native,
            testFunds = !forCheck,
            holdQueued = !forCheck,
        ) { it == TRUE }
        return when (quoting) {
            is CallQuoting.Quoted -> ExecutionQuoted.Quoted(quoting.quote, signatures, quoting.nextBaseFee)
            is CallQuoting.Refused -> refused(SafeTxRefusal.NotQuoted(quoting.refusal))
        }
    }

    /**
     * [SafeTxRefusal.AlreadySent] when an execution of [request] that an account of this wallet
     * sent is recorded and not mined: within its pool lifetime ([RECORD_LIFETIME_SECONDS]), and
     * its sender's verified count of mined transactions not past it. One that a block took and
     * that reverted (an owner swapped meanwhile, a guard's refusal) leaves the Safe's nonce as it
     * was and blocks nothing: the sender's count has passed it. A count that cannot be verified
     * now is the refusal it is ([SafeTxRefusal.Unavailable]). Null when nothing is pending.
     */
    private suspend fun sentAndUnmined(request: SigningRequest): SafeTxRefusal? {
        val since = clock() - RECORD_LIFETIME_SECONDS
        val sent = (sentTransfers.observe().first() as? SentTransferList.Loaded)?.transfers.orEmpty()
            .filter { it.network == request.network && it.sentAt >= since && it.executes(request) }
        for (execution in sent) {
            when (val mined = inputs.minedNonce(request.network, execution.account)) {
                is NonceReading.Read -> if (execution.nonce >= mined.nonce) return SafeTxRefusal.AlreadySent
                is NonceReading.Unavailable -> return SafeTxRefusal.Unavailable(mined.reason)
            }
        }
        return null
    }

    /** As many confirmations of [request] by [state]'s owners as its threshold, or why there are not. */
    private suspend fun confirmations(request: SigningRequest, state: SafeState, executor: Address): Confirmed =
        coroutineScope {
            val threshold = state.threshold
            val signed = recovery.valid(request, state.owners)
            // The executor's own confirmation, when it is an owner that has not signed: the Safe
            // takes the owner sending execTransaction as having approved it.
            val ownApproval = executor in state.owners && signed.none { it.owner == executor }
            val confirmed = listOfNotNull(SafeSignature.ApprovedHash(executor).takeIf { ownApproval }) + signed
            if (confirmed.size >= threshold) return@coroutineScope Confirmed.Enough(confirmed.take(threshold))
            val others = state.owners.filter { owner -> confirmed.none { it.owner == owner } }
            val readings = others
                .map {
                    owner -> async { owner to approvals.approval(request.network, request.safe, owner, request.hash) } }
                .awaitAll()
            val approved = readings.filter { (_, reading) -> (reading as? ApprovalReading.Read)?.approved == true }
                .map { (owner, _) -> SafeSignature.ApprovedHash(owner) }
            val all = confirmed + approved
            if (all.size >= threshold) return@coroutineScope Confirmed.Enough(all.take(threshold))
            // Short: an approval that could not be read might have made up the difference.
            readings.firstNotNullOfOrNull { (_, reading) -> reading as? ApprovalReading.Unavailable }?.let {
                return@coroutineScope Confirmed.Refused(SafeTxRefusal.Unavailable(it.reason))
            }
            Confirmed.Refused(SafeTxRefusal.ThresholdNotMet(all.size, threshold))
        }

    private sealed interface Confirmed {
        data class Enough(val signatures: List<SafeSignature>) : Confirmed
        data class Refused(val refusal: SafeTxRefusal) : Confirmed
    }

    private companion object {
        /** What `execTransaction` returns when the Safe's call succeeded: `true`, one ABI word. */
        val TRUE = "0".repeat(63) + "1"
    }
}

/** Whether this transaction is an on-chain approval of [request]'s Safe transaction. */
internal fun SentTransfer.approves(request: SigningRequest): Boolean =
    (action as? SentAction.SafeApproval)?.request?.hash == request.hash

/** Whether this transaction is an execution of [request]'s Safe transaction. */
internal fun SentTransfer.executes(request: SigningRequest): Boolean =
    (action as? SentAction.SafeExecution)?.request?.hash == request.hash

/** The outcome of [QuoteSafeExecution]. */
sealed interface SafeExecutionQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: SafeExecutionQuote) : SafeExecutionQuoting

    /** Not quoted: [refusal]. */
    data class Refused(val refusal: SafeTxRefusal) : SafeExecutionQuoting
}

/**
 * Sends a reviewed execution ([SafeExecutionQuote]): its transaction signed by the executor's
 * key as [unlock] opens it and broadcast, exactly as quoted, and recorded once a peer took it as
 * the execution of its request ([SentAction.SafeExecution]) — what keeps its nonce, and what its
 * status is followed by ([ObserveSentTransfer]: its record and its verified receipt, as a send's;
 * docs/wallet-ux.md, screen 9). As every transaction this wallet sends ([SendTransfer]); a retry
 * goes through [RebroadcastTransfer]. [clock] is in unix seconds.
 */
class ExecuteSafeTx(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(quote: SafeExecutionQuote, unlock: Unlock): TransferSending = signAndSend(
        signer,
        broadcaster,
        sentTransfers,
        quote.request.network,
        quote.call,
        SentAction.SafeExecution(quote.request),
        clock,
        unlock,
    )
}
