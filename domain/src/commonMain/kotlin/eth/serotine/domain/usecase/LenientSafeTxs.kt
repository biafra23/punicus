package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeCalls
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.DraftBasis
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.HeldConflict
import eth.serotine.domain.repository.HeldRefusal
import eth.serotine.domain.repository.HeldSafeSignature
import eth.serotine.domain.repository.KeptSafe
import eth.serotine.domain.repository.KeptSafes
import eth.serotine.domain.repository.KeptTransactionInputs
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeDraftBasis
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.SafeTxQueue
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.SignatureRecovery
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

// Lenient mode's Safe transactions (docs/lenient-mode.md, *Safe transactions in lenient mode*; owner
// rulings, 2026-11-06): proposed and co-signed on the Safe last verified, the signature held in the
// outbox until its check at a fresh verified head; approved on chain and executed through the outbox
// like a send. Every Safe refusal applies when drafting, unchanged; nothing is shown to another owner,
// approved or executed before the check.

/**
 * The Safe at [safe] as last verified on this device ([KeptSafes]): what a lenient draft's review
 * shows, each value with its age, while the light client catches up.
 */
class ObserveKeptSafe(private val kept: KeptSafes) {
    operator fun invoke(safe: AccountId): Flow<KeptSafe?> = kept.observe(safe)
}

/**
 * Why this Safe transaction is never drafted on what was verified last, whatever the Safe says: one
 * of the Safe refusals the transaction alone decides ([unsignable]: a delegate call, gas terms, a
 * self-call not decoded exactly, a module enabled, a fallback handler not pinned), or a swap or its
 * token approval, which stay strict ([SafeTxRefusal.SwapNeedsFreshCheck]). Null when it may be.
 */
internal fun SigningRequest.undraftable(): SafeTxRefusal? {
    unsignable()?.let { return it }
    return when (SafeTxAction.of(safe, tx)) {
        is SafeTxAction.Swap, is SafeTxAction.TokenApproval -> SafeTxRefusal.SwapNeedsFreshCheck
        else -> null
    }
}

/** The owners of [state] whose keys the wallet holds, in the Safe's order, as [ObserveSafe] finds them. */
internal suspend fun AccountRepository.ownersHeldOf(state: SafeState): List<Address> {
    // An address is an owner on any chain, and its key, held here on one network, signs on every one.
    val inWallet = (observeAccounts().first() as? AccountList.Loaded)?.accounts.orEmpty()
        .filter { it.address in state.owners }
    return state.ownersHeld(inWallet)
}

/**
 * A transfer from a Safe drafted in lenient mode for review (docs/lenient-mode.md, *Safe
 * transactions in lenient mode*): [tx] at the Safe's next nonce as last verified ([safe], its age
 * with it) and as the transactions queued and held here for it run before it ([queuedAhead]); the
 * Safe's balance of the asset as last verified ([balanceBefore] at [balanceBlock], a head of
 * [balanceAsOf]), what those before it may move of it ([queuedAmount]) and what this one leaves if
 * nothing changed ([balanceAfter]); [ownersHeld], the owners held here that may sign it. Nothing in
 * it was simulated: its execution's quote runs the whole of it. Only [DraftSafeTx] makes one.
 */
@ConsistentCopyVisibility
data class SafeTxDraft internal constructor(
    val tx: DecodedSafeTx,
    val safe: KeptSafe,
    val ownersHeld: List<Address>,
    val queuedAhead: Int,
    val balanceBefore: UInt256,
    val queuedAmount: UInt256,
    val balanceAfter: UInt256,
    val balanceBlock: Long?,
    val balanceAsOf: Long?,
) {
    /** The request its owner signs, and holds. */
    val request: SigningRequest
        get() = tx.request

    /** What it was drafted on. */
    val builtOn: SafeDraftBasis
        get() = SafeDraftBasis(safe.blockNumber, safe.asOf, balanceBlock, balanceAsOf)
}

/** The outcome of [DraftSafeTx]. */
sealed interface SafeTxDrafting {

    /** [draft] is ready for review. */
    data class Drafted(val draft: SafeTxDraft) : SafeTxDrafting

    /** Refused as a strict proposal refuses it ([proposing]: one of [SafeTxProposing]'s refusals, never Proposed). */
    data class Refused(val proposing: SafeTxProposing) : SafeTxDrafting

    /**
     * The draft waits for [missing], which the light client has never verified on this device: the
     * Safe's owners and nonce ([DraftInput.SAFE]) or its balance ([DraftInput.BALANCE]).
     */
    data class Waits(val missing: DraftInput) : SafeTxDrafting
}

/**
 * Drafts a transfer from a Safe in lenient mode (docs/lenient-mode.md, *Safe transactions in lenient
 * mode*), as [ProposeSafeTx] proposes one, on what the light client verified last instead of now:
 * the Safe's nonce, owners and threshold kept ([KeptSafes]), and its balance of the asset, verified
 * now or last verified. A plain call with no refund ([SafeTx.call]) at the Safe's next nonce: its
 * kept nonce, or the first after the transactions queued ([SafeTxQueue]) and the signatures held
 * here ([Outbox]) for it from that nonce on without a gap, whose moves are not this one's to move.
 * The same refusals before anything is read: the zero address, the token's own contract, the Safe
 * itself, nothing to send. Never simulated, and nothing is saved: [HoldSafeSignature] signs and holds
 * it until its check. With nothing kept, it waits. On [network] only.
 */
class DraftSafeTx(
    private val kept: KeptSafes,
    private val accounts: AccountRepository,
    private val nativeBalances: NativeBalances,
    private val tokenBalances: TokenBalances,
    private val queue: SafeTxQueue,
    private val outbox: Outbox,
    private val network: Network,
) {
    /** Drafts [amount] of [asset] from [safe] to [to]. */
    suspend operator fun invoke(safe: Address, to: Address, asset: TransferAsset, amount: UInt256): SafeTxDrafting {
        val token = (asset as? TransferAsset.Erc20Token)?.token
        if (to == Address.ZERO) return refused(SafeTxProposing.ToZeroAddress)
        if (token != null && to == token.address) return refused(SafeTxProposing.ToTokenContract)
        if (to == safe) return refused(SafeTxProposing.ToSafeItself)
        if (amount.isZero) return refused(SafeTxProposing.NothingToSend)
        val id = AccountId(network, safe)
        val keptSafe = kept.observe(id).first() ?: return SafeTxDrafting.Waits(DraftInput.SAFE)
        keptSafe.state.tooOldToSign()?.let { return refused(SafeTxProposing.Refused(it)) }
        val held = when (val list = outbox.observe().first()) {
            // It may hold a signature at the nonce this one would take.
            OutboxList.Unreadable -> return refused(SafeTxProposing.Refused(UNPROVEN))
            // Conflicted ones too: one waits at its nonce to be dismissed, and a hold there would wait.
            is OutboxList.Loaded -> heldOf(list, id)
        }
        val (before, balanceBlock, balanceAsOf) = when (val read = lastVerifiedBalance(safe, token?.address)) {
            is KnownBalance.Read -> Triple(read.amount, read.block, read.asOf)
            KnownBalance.Waits -> return SafeTxDrafting.Waits(DraftInput.BALANCE)
            KnownBalance.NotAToken -> return refused(SafeTxProposing.NotAToken)
            is KnownBalance.Refused -> return refused(SafeTxProposing.Refused(read.refusal))
        }
        if (before < amount) return insufficient(token != null)
        val safeNonce = keptSafe.state.nonce
        // What is queued here, and what is held, from the kept nonce on: both run before this one.
        val pending = pendingOf(queue.observe(id).first(), safeNonce, network) + held.filter { it.nonce >= safeNonce }
        val nonce = nextNonce(pending, safeNonce)
        val ahead = pending.filter { it.nonce < nonce }
        val queuedAmount = ahead.queuedMoves(token?.address) ?: return insufficient(token != null)
        val available = before.minusOrNull(queuedAmount)?.takeIf { it >= amount } ?: return insufficient(token != null)
        val txTo = token?.address ?: to
        val value = if (token == null) amount else UInt256.ZERO
        val data = if (token == null) ByteArray(0) else Erc20.transferData(to, amount)
        val request = SigningRequest.of(network, safe, SafeTx.call(txTo, value, data, nonce))
        // Decided by the transaction itself, unchanged in lenient mode: never past them.
        request.undraftable()?.let { return refused(SafeTxProposing.Refused(it)) }
        return SafeTxDrafting.Drafted(
            SafeTxDraft(
                tx = DecodedSafeTx.of(request),
                safe = keptSafe,
                ownersHeld = accounts.ownersHeldOf(keptSafe.state),
                queuedAhead = (nonce - safeNonce).toInt(),
                balanceBefore = before,
                queuedAmount = queuedAmount,
                balanceAfter = available - amount,
                balanceBlock = balanceBlock,
                balanceAsOf = balanceAsOf,
            ),
        )
    }

    /** What [lastVerifiedBalance] found. */
    private sealed interface KnownBalance {
        data class Read(val amount: UInt256, val block: Long?, val asOf: Long?) : KnownBalance
        data object Waits : KnownBalance
        data object NotAToken : KnownBalance
        data class Refused(val refusal: SafeTxRefusal) : KnownBalance
    }

    /** [safe]'s balance of the token at [token] (null: of the network's currency), verified now or last verified. */
    private suspend fun lastVerifiedBalance(safe: Address, token: Address?): KnownBalance {
        val unproven = KnownBalance.Refused(UNPROVEN)
        if (token == null) {
            return when (val reading = nativeBalances.observeBalance(network, safe).first()) {
                is BalanceReading.Verified -> reading.wei.toUInt256OrNull()
                    ?.let { KnownBalance.Read(it, reading.blockNumber, reading.provenAt) } ?: unproven
                is BalanceReading.LastKnown -> reading.wei.toUInt256OrNull()
                    ?.let { KnownBalance.Read(it, reading.blockNumber, reading.asOf) } ?: unproven
                BalanceReading.Syncing -> KnownBalance.Waits
                is BalanceReading.Unavailable -> KnownBalance.Refused(SafeTxRefusal.Unavailable(reading.reason))
            }
        }
        return when (val reading = tokenBalances.observeBalance(network, safe, token).first()) {
            is TokenBalanceReading.Verified -> UInt256.parse(reading.units.decimal)
                ?.let { KnownBalance.Read(it, reading.blockNumber, reading.provenAt) } ?: unproven
            is TokenBalanceReading.LastKnown -> UInt256.parse(reading.units.decimal)
                ?.let { KnownBalance.Read(it, reading.blockNumber, reading.asOf) } ?: unproven
            TokenBalanceReading.Syncing -> KnownBalance.Waits
            TokenBalanceReading.NotAToken -> KnownBalance.NotAToken
            is TokenBalanceReading.Unavailable -> KnownBalance.Refused(SafeTxRefusal.Unavailable(reading.reason))
        }
    }

    private fun refused(proposing: SafeTxProposing) = SafeTxDrafting.Refused(proposing)

    private fun insufficient(token: Boolean) =
        refused(if (token) SafeTxProposing.InsufficientToken else SafeTxProposing.InsufficientFunds)

    private companion object {
        val UNPROVEN = SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN)
    }
}

/** What [HoldSafeSignature.check] came to. */
sealed interface HeldSignCheck {

    /**
     * [owner] may sign [request] (with no signature) and hold it: the Safe as last verified ([safe])
     * says so. Made only by [HoldSafeSignature.check].
     */
    @ConsistentCopyVisibility
    data class Ready internal constructor(
        val request: SigningRequest,
        val owner: Address,
        val safe: KeptSafe,
    ) : HeldSignCheck

    /** Not to be signed: [refusal], as the strict path says it. */
    data class Refused(val refusal: SafeTxRefusal) : HeldSignCheck

    /** It waits for [missing], which the light client never verified on this device: the Safe. */
    data class Waits(val missing: DraftInput) : HeldSignCheck

    /**
     * A signature for the Safe at the same nonce is held already ([held], its Safe transaction's
     * hash): one per Safe and nonce, so this one waits for that one to be checked or discarded.
     */
    data class NonceBusy(val held: Digest) : HeldSignCheck
}

/** The outcome of [HoldSafeSignature.sign]. */
sealed interface SafeSignatureHolding {

    /** Signed and held as [held], until its check. */
    data class Held(val held: HeldSafeSignature) : SafeSignatureHolding

    /** Not signed: [refusal]. */
    data class Refused(val refusal: SafeTxRefusal) : SafeSignatureHolding

    /** Not signed, for the reason [signing] gives: nothing left the key store. */
    data class NotSigned(val signing: Signing) : SafeSignatureHolding

    /** Another signature for the Safe at that nonce was held meanwhile ([held]): this one was dropped. */
    data class NonceBusy(val held: Digest) : SafeSignatureHolding

    /** Signed, but the outbox could not save it: nothing is held, and the signature is dropped. */
    data object NotSaved : SafeSignatureHolding
}

/**
 * Signs a Safe transaction in lenient mode and holds the signature (docs/lenient-mode.md, *Safe
 * transactions in lenient mode*): a proposal drafted here ([SafeTxDraft]) or a request of the Safe's
 * queue, co-signed. [check] holds it to every check [SignSafeTx.check] makes, against the Safe as
 * last verified ([KeptSafes]) where that one reads it now: the network; every refusal the
 * transaction alone decides, and a swap or its approval, which stay strict ([undraftable]); a Safe
 * before 1.3.0; a nonce the kept nonce has passed; a signer that is no kept owner; the threshold it
 * would leave; the owner's valid signature in it already. One held signature per Safe and nonce:
 * another at that nonce makes it wait ([HeldSignCheck.NonceBusy]). [sign] signs its hash, computed
 * here, with the owner's key as the unlock opens it, checks that it recovers to the owner, and holds
 * it in the outbox under [lock], the busy nonce asked again there. Nothing is saved to the Safe's
 * queue, and nothing is shown: [FollowOutbox] does that once [CheckHeldSignature] passes it. A held
 * signature takes no place among the account's queued transactions. On [network] only; [clock] is in
 * unix seconds.
 */
class HoldSafeSignature(
    private val signer: Signer,
    private val kept: KeptSafes,
    private val recovery: SignatureRecovery,
    private val outbox: Outbox,
    private val lock: OutboxLock,
    private val network: Network,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    /** Whether [owner] may sign [request] now and hold it, as the Safe last verified says. Asks for no key. */
    suspend fun check(request: SigningRequest, owner: Address): HeldSignCheck {
        fun refused(refusal: SafeTxRefusal) = HeldSignCheck.Refused(refusal)
        if (request.network != network) return refused(SafeTxRefusal.WrongChain(request.network.chainId))
        request.undraftable()?.let { return refused(it) }
        val safe = kept.observe(AccountId(request.network, request.safe)).first()
            ?: return HeldSignCheck.Waits(DraftInput.SAFE)
        val state = safe.state
        state.tooOldToSign()?.let { return refused(it) }
        if (request.nonce < state.nonce) return refused(SafeTxRefusal.NonceUsed(state.nonce))
        if (owner !in state.owners) return refused(SafeTxRefusal.NotAnOwner(owner))
        request.withoutRecovery(state)?.let { return refused(it) }
        // Of the signatures the request carries, only the ones valid against the kept owners count.
        if (recovery.valid(request, state.owners).any { it.owner == owner }) {
            return refused(SafeTxRefusal.AlreadySigned(owner))
        }
        busy(request)?.let { return it }
        return HeldSignCheck.Ready(SigningRequest.of(request.network, request.safe, request.tx), owner, safe)
    }

    /**
     * Signs [checked]'s request as its owner with [unlock] and holds it: a proposal of [draft]'s when
     * given (its balance's age is kept with it), a co-signature otherwise.
     */
    suspend fun sign(checked: HeldSignCheck.Ready, unlock: Unlock, draft: SafeTxDraft? = null): SafeSignatureHolding {
        val request = checked.request
        require(draft == null || draft.request.hash == request.hash) { "a draft holds the request it drafted" }
        val signature = when (val signing = signer.sign(checked.owner, request.hash, unlock)) {
            is Signing.Signed -> signing.signature
            else -> return SafeSignatureHolding.NotSigned(signing)
        }
        // The key store's signature is checked like a co-signer's: one that recovers to another
        // address is never held.
        if (recovery.recover(request.hash, signature) != checked.owner) {
            return SafeSignatureHolding.Refused(SafeTxRefusal.BadSignature(checked.owner))
        }
        val held = HeldSafeSignature(
            request = request,
            owner = checked.owner,
            signature = signature,
            proposed = draft != null,
            signedAt = clock(),
            builtOn = draft?.builtOn ?: SafeDraftBasis(checked.safe.blockNumber, checked.safe.asOf),
        )
        return lock.mutex.withLock {
            when (val busy = busy(request)) {
                is HeldSignCheck.NonceBusy -> SafeSignatureHolding.NonceBusy(busy.held)
                is HeldSignCheck.Refused -> SafeSignatureHolding.NotSaved
                else -> if (outbox.hold(held)) SafeSignatureHolding.Held(held) else SafeSignatureHolding.NotSaved
            }
        }
    }

    /** What holds [request] back in the outbox: another held at its nonce, or an outbox that cannot be read. */
    private suspend fun busy(request: SigningRequest): HeldSignCheck? = when (val list = outbox.observe().first()) {
        OutboxList.Unreadable -> HeldSignCheck.Refused(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN))
        is OutboxList.Loaded -> list.held
            .firstOrNull { it.safe == AccountId(request.network, request.safe) && it.nonce == request.nonce }
            ?.let { HeldSignCheck.NonceBusy(it.hash) }
    }
}

/** The outcome of [CheckHeldSignature]. */
sealed interface HeldCheck {

    /** It stands at a fresh verified head, against the Safe's [state]: it may be saved to the Safe's queue. */
    data class Passes(val state: SafeState) : HeldCheck

    /** It cannot be checked now (the light client, the Safe not verifiable this time): held, and checked later. */
    data object Waits : HeldCheck

    /** It no longer stands, for [conflict]: its signature is to be deleted, never shown. */
    data class Conflicts(val conflict: HeldConflict) : HeldCheck
}

/**
 * The check of a held Safe signature at a fresh verified head (docs/lenient-mode.md, *Safe
 * transactions in lenient mode*; owner rulings, 2026-11-06): the Safe verified as a Safe now
 * ([ObserveSafe.check], a one-shot check), its Safe transaction's nonce not used yet, the signer a
 * current owner. Also, as [SignSafeTx] holds a signature to them: the Safe on a singleton from 1.3.0
 * on, the threshold the transaction would leave, the transaction's own refusals, and the signature
 * recovering to its owner over the hash computed here. A Safe that cannot be verified this time
 * waits; one verified as no Safe is a conflict.
 */
class CheckHeldSignature(private val observeSafe: ObserveSafe, private val recovery: SignatureRecovery) {
    suspend operator fun invoke(held: HeldSafeSignature): HeldCheck {
        val signature = held.signature ?: return HeldCheck.Waits
        val request = held.request
        if (request.undraftable() != null) return conflict(HeldConflict.Refused(HeldRefusal.UNSIGNABLE))
        val state = when (val check = observeSafe.check(held.safe)) {
            is SafeCheck.Verified -> check.reading.state
            is SafeCheck.Refused -> return when (check.refusal) {
                SafeTxRefusal.NotASafe -> conflict(HeldConflict.Refused(HeldRefusal.NOT_A_SAFE))
                // Syncing, or not verifiable this time: checked again later.
                else -> HeldCheck.Waits
            }
        }
        if (state.tooOldToSign() != null) return conflict(HeldConflict.Refused(HeldRefusal.TOO_OLD_TO_SIGN))
        if (request.nonce < state.nonce) return conflict(HeldConflict.NonceUsed(state.nonce))
        if (held.owner !in state.owners) return conflict(HeldConflict.NotAnOwner)
        if (request.withoutRecovery(state) != null) {
            return conflict(HeldConflict.Refused(HeldRefusal.NEEDS_RECOVERY_MODULE))
        }
        if (recovery.recover(request.hash, signature) != held.owner) {
            return conflict(HeldConflict.Refused(HeldRefusal.BAD_SIGNATURE))
        }
        return HeldCheck.Passes(state)
    }

    private fun conflict(conflict: HeldConflict) = HeldCheck.Conflicts(conflict)
}

/** The outcome of [DiscardHeld]. */
sealed interface HeldDiscard {

    /** Deleted, its signature with it: it was never shown to anyone. */
    data object Discarded : HeldDiscard

    /** Nothing is held under that hash any more: checked meanwhile (it is in the Safe's queue now), or discarded. */
    data object Gone : HeldDiscard

    /** The outbox could not be read or saved, and nothing changed. */
    data object NotSaved : HeldDiscard
}

/**
 * Discards the signature held for the Safe [safe] of its transaction [hash]: deleted, never shown.
 * Waits while a check of it runs ([OutboxLock]), so the check never saves to the Safe's queue what was
 * discarded meanwhile; one checked before the discard came is in that queue, and is not discarded
 * here ([HeldDiscard.Gone]).
 */
class DiscardHeld(private val outbox: Outbox, private val lock: OutboxLock) {
    suspend operator fun invoke(safe: AccountId, hash: Digest): HeldDiscard = lock.mutex.withLock {
        val list = outbox.observe().first() as? OutboxList.Loaded ?: return@withLock HeldDiscard.NotSaved
        when {
            list.held.none { it.safe == safe && it.hash == hash } -> HeldDiscard.Gone
            outbox.release(safe, hash) -> HeldDiscard.Discarded
            else -> HeldDiscard.NotSaved
        }
    }
}

/**
 * A transaction from an account this wallet holds, drafted in lenient mode on what the light client
 * verified last (docs/lenient-mode.md, *What a draft is built on*): the unsigned [transaction] at
 * [level]'s fees, signed for a pinned gas ceiling ([DraftGas]); the most its fee can be ([maxFee]),
 * the account's native balance last verified ([nativeBefore]), what its transactions not mined yet
 * may still take ([pendingCost]) and the least it leaves if nothing changed ([nativeAfterAtLeast]),
 * each figure as old as [builtOn] says. What a Safe's approval or execution draft is built on
 * ([SafeCallDraft]). Only this module makes one.
 */
@ConsistentCopyVisibility
data class CallDraft internal constructor(
    val from: Address,
    val level: FeeLevel,
    val transaction: Eip1559Transaction,
    val maxFee: UInt256,
    val nativeBefore: UInt256,
    val pendingCost: UInt256,
    val nativeAfterAtLeast: UInt256,
    val builtOn: DraftBasis,
) {
    /** The most it can take of the native balance: its value and the most its fee can be. */
    val maxCost: UInt256
        get() = maxFee.plusOrNull(transaction.value) ?: maxFee
}

/** What [CallDrafter.draft] gives. */
internal sealed interface CallDrafting {
    /** [draft], and the account's own transactions not mined yet it was drafted behind ([unmined]). */
    data class Drafted(val draft: CallDraft, val unmined: List<SentTransfer>) : CallDrafting
    data class Refused(val refusal: CallRefusal) : CallDrafting
    data class Waits(val missing: DraftInput) : CallDrafting
    data class AccountBusy(val queued: String) : CallDrafting
}

/**
 * The part every lenient draft of a transaction from an account this wallet holds shares, as
 * [DraftTransfer] drafts a transfer's: the mined count and the fee quote last verified ([kept]), the
 * native balance verified now or last verified, the account's own transactions not mined yet
 * ([SentTransfers]: the nonce is past them, and what they may take is not this one's), and the one
 * queued transaction per account ([Outbox]: an account with one is busy, and every account while the
 * outbox cannot be read). An earlier transaction maybe sent within its lifetime holds it back. Never
 * simulated. On [network] only; [clock] is in unix seconds.
 */
internal class CallDrafter(
    private val kept: KeptTransactionInputs,
    private val nativeBalances: NativeBalances,
    private val sentTransfers: SentTransfers,
    private val outbox: Outbox,
    private val network: Network,
    private val clock: () -> Long,
) {
    /** [value] wei and [data] from [from] to [to] at [level]'s fees, for at most [gas], on [safe] for a Safe's call. */
    suspend fun draft(
        from: Address,
        to: Address,
        value: UInt256,
        data: ByteArray,
        level: FeeLevel,
        gas: Long,
        safe: KeptSafe?,
    ): CallDrafting {
        when (val list = outbox.observe().first()) {
            // It may hold a transaction already broadcast whose record is not written yet.
            OutboxList.Unreadable ->
                return CallDrafting.Refused(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.NONCE))
            is OutboxList.Loaded -> list.queued.firstOrNull { it.network == network && it.account == from }
                ?.let { return CallDrafting.AccountBusy(it.id) }
        }
        val nonce = kept.minedNonce(network, from) ?: return CallDrafting.Waits(DraftInput.NONCE)
        val fees = kept.fees(network) ?: return CallDrafting.Waits(DraftInput.FEES)
        val since = clock() - RECORD_LIFETIME_SECONDS
        val records = (sentTransfers.observe().first() as? SentTransferList.Loaded)?.transfers.orEmpty()
            .filter { it.network == network && it.account == from && it.sentAt >= since && it.nonce >= nonce.count }
        records.firstOrNull { it.maybeSent }?.let { return CallDrafting.Refused(CallRefusal.EarlierMaybeSent(it.hash)) }
        val unmined = records.filter { !it.maybeSent }
        val reading = nativeBalances.observeBalance(network, from).first()
        val (before, balanceBlock, balanceAsOf) = when (reading) {
            is BalanceReading.Verified -> Triple(reading.wei.toUInt256OrNull(), reading.blockNumber, reading.provenAt)
            is BalanceReading.LastKnown -> Triple(reading.wei.toUInt256OrNull(), reading.blockNumber, reading.asOf)
            BalanceReading.Syncing -> return CallDrafting.Waits(DraftInput.BALANCE)
            is BalanceReading.Unavailable ->
                return CallDrafting.Refused(CallRefusal.Unavailable(reading.reason, QuoteFigure.BALANCE))
        }
        val nativeBefore = before
            ?: return CallDrafting.Refused(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.BALANCE))
        val feesUnproven = CallDrafting.Refused(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.FEES))
        val cap = fees.quote.maxFeePerGasOrNull(level) ?: return feesUnproven
        val maxFee = cap.timesOrNull(UInt256.of(gas)) ?: return feesUnproven
        val insufficient = CallDrafting.Refused(CallRefusal.InsufficientFunds)
        val cost = maxFee.plusOrNull(value) ?: return insufficient
        val pendingCost = unmined.map { it.maxCost }.sumOrNull() ?: return insufficient
        val available = nativeBefore.minusOrNull(pendingCost) ?: return insufficient
        if (available < cost) return insufficient
        val next = maxOf(nonce.count, (unmined.maxOfOrNull { it.nonce } ?: -1) + 1)
        val transaction = Eip1559Transaction(
            chainId = network.chainId,
            nonce = next,
            maxPriorityFeePerGas = fees.quote.tip(level),
            maxFeePerGas = cap,
            gasLimit = gas,
            to = to,
            value = value,
            data = data,
        )
        return CallDrafting.Drafted(
            CallDraft(
                from = from,
                level = level,
                transaction = transaction,
                maxFee = maxFee,
                nativeBefore = nativeBefore,
                pendingCost = pendingCost,
                nativeAfterAtLeast = available - cost,
                builtOn = DraftBasis(
                    balanceBlock = balanceBlock,
                    balanceAsOf = balanceAsOf,
                    tokenBalanceBlock = null,
                    tokenBalanceAsOf = null,
                    nonceBlock = nonce.blockNumber,
                    nonceAsOf = nonce.asOf,
                    feeBlock = fees.quote.blockNumber,
                    feeAsOf = fees.asOf,
                    safeBlock = safe?.blockNumber,
                    safeAsOf = safe?.asOf,
                ),
            ),
            unmined,
        )
    }
}

/** What a Safe call drafted in lenient mode sends. */
enum class SafeCallKind {
    /** An owner's `approveHash` of the Safe transaction's hash. */
    APPROVAL,

    /** The Safe transaction's `execTransaction`. */
    EXECUTION,
}

/**
 * A Safe's approval on chain or execution ([kind]) of [request], drafted in lenient mode
 * (docs/lenient-mode.md, *Safe transactions in lenient mode*): [call], from an account this wallet
 * holds, built on the Safe as last verified ([safe]) and on the account's inputs last verified; for
 * an execution, the confirmations it is executed with ([signatures], as many as the kept threshold,
 * the executor's own first). Signed, it is queued in the outbox like a send ([QueueSafeCall]), and
 * sent only once the check at a fresh head passes ([CheckQueued]). Only [DraftSafeCall] makes one.
 */
@ConsistentCopyVisibility
data class SafeCallDraft internal constructor(
    val kind: SafeCallKind,
    val request: SigningRequest,
    val safe: KeptSafe,
    val signatures: List<SafeSignature>,
    val call: CallDraft,
) {
    /** What it does, as its record says once it is sent. */
    val action: SentAction
        get() = when (kind) {
            SafeCallKind.APPROVAL -> SentAction.SafeApproval(request)
            SafeCallKind.EXECUTION -> SentAction.SafeExecution(request)
        }
}

/** The outcome of [DraftSafeCall]. */
sealed interface SafeCallDrafting {

    /** [draft] is ready for review. */
    data class Drafted(val draft: SafeCallDraft) : SafeCallDrafting

    /** Not drafted: [refusal], as the strict quote says it. */
    data class Refused(val refusal: SafeTxRefusal) : SafeCallDrafting

    /** It waits for [missing], which the light client never verified on this device. */
    data class Waits(val missing: DraftInput) : SafeCallDrafting

    /** Something is queued from the account already ([queued]: its id): one per account. */
    data class AccountBusy(val queued: String) : SafeCallDrafting

    /**
     * The signatures here make [confirmations] of the [threshold] the execution needs, against the
     * owners last verified; the owners' approvals on chain, which may make up the rest, are read only
     * once the light client has caught up.
     */
    data class ShortOfSignatures(val confirmations: Int, val threshold: Int) : SafeCallDrafting
}

/**
 * Drafts a Safe's approval on chain ([approval]) or its execution ([execution]) in lenient mode
 * (docs/lenient-mode.md, *Safe transactions in lenient mode*), as [QuoteSafeApproval] and
 * [QuoteSafeExecution] quote them, on what the light client verified last: the Safe's nonce, owners
 * and threshold kept ([KeptSafes]) and the sending account's mined count, fees and balance last
 * verified ([CallDrafter]), with a pinned gas ceiling ([DraftGas.SAFE_APPROVAL],
 * [DraftGas.SAFE_EXECUTION]) for the estimate. Every Safe check is made against the kept Safe: the
 * transaction's own refusals and a swap or its approval ([undraftable]), a Safe before 1.3.0, a nonce
 * passed (an execution's must be the kept nonce), the owner (for an approval), the threshold it
 * would leave, and an approval or execution of it sent and not mined (an execution queued here too,
 * from any account). An execution counts its
 * confirmations from the request's signatures valid against the kept owners and the executor's own;
 * approvals on chain cannot be read while the light client catches up, so a request short of its
 * threshold without them waits ([SafeCallDrafting.ShortOfSignatures]). Never simulated: the check
 * before sending runs the whole of it. On [network] only; [clock] is in unix seconds.
 */
class DraftSafeCall(
    private val keptSafes: KeptSafes,
    keptInputs: KeptTransactionInputs,
    private val recovery: SignatureRecovery,
    nativeBalances: NativeBalances,
    private val sentTransfers: SentTransfers,
    private val outbox: Outbox,
    private val network: Network,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val kept = keptInputs
    private val drafter = CallDrafter(keptInputs, nativeBalances, sentTransfers, outbox, network, clock)

    /** [owner]'s approval on chain of [request] at [level], drafted. */
    suspend fun approval(request: SigningRequest, owner: Address, level: FeeLevel): SafeCallDrafting {
        val safe = when (val read = keptSafe(request)) {
            is KeptRead.Kept -> read.safe
            is KeptRead.Not -> return read.drafting
        }
        val state = safe.state
        if (owner !in state.owners) return refused(SafeTxRefusal.NotAnOwner(owner))
        // Its signature is in the request already: its approval would add nothing.
        if (recovery.valid(request, state.owners).any { it.owner == owner }) {
            return refused(SafeTxRefusal.AlreadySigned(owner))
        }
        val data = SafeCalls.approveHash(request.hash)
        val drafted = drafter.draft(owner, request.safe, UInt256.ZERO, data, level, DraftGas.SAFE_APPROVAL, safe)
        return when (drafted) {
            is CallDrafting.Drafted -> if (drafted.unmined.any { it.approves(request) }) {
                refused(SafeTxRefusal.AlreadySent)
            } else {
                val draft = SafeCallDraft(SafeCallKind.APPROVAL, request, safe, emptyList(), drafted.draft)
                SafeCallDrafting.Drafted(draft)
            }
            else -> drafted.asSafeCallDrafting()
        }
    }

    /** [executor]'s execution of [request] at [level], drafted. */
    suspend fun execution(request: SigningRequest, executor: Address, level: FeeLevel): SafeCallDrafting {
        val safe = when (val read = keptSafe(request)) {
            is KeptRead.Kept -> read.safe
            is KeptRead.Not -> return read.drafting
        }
        val state = safe.state
        if (request.nonce > state.nonce) return refused(SafeTxRefusal.NotNext(state.nonce))
        sentAndUnmined(request)?.let { return refused(it) }
        // Queued already from any account here: only one execution of it can ever run.
        val queuedHere = (outbox.observe().first() as? OutboxList.Loaded)?.queued.orEmpty()
            .any { it.network == network && (it.action as? SentAction.SafeExecution)?.request?.hash == request.hash }
        if (queuedHere) return refused(SafeTxRefusal.AlreadySent)
        val signed = recovery.valid(request, state.owners)
        // The executor's own confirmation, when it is a kept owner that has not signed.
        val own = executor in state.owners && signed.none { it.owner == executor }
        val confirmed = listOfNotNull(SafeSignature.ApprovedHash(executor).takeIf { own }) + signed
        if (confirmed.size < state.threshold) {
            return SafeCallDrafting.ShortOfSignatures(confirmed.size, state.threshold)
        }
        val signatures = confirmed.take(state.threshold)
        val data = SafeCalls.execTransaction(request.tx, signatures)
        val drafted = drafter.draft(executor, request.safe, UInt256.ZERO, data, level, DraftGas.SAFE_EXECUTION, safe)
        return when (drafted) {
            is CallDrafting.Drafted -> SafeCallDrafting.Drafted(
                SafeCallDraft(SafeCallKind.EXECUTION, request, safe, signatures, drafted.draft),
            )
            else -> drafted.asSafeCallDrafting()
        }
    }

    private sealed interface KeptRead {
        data class Kept(val safe: KeptSafe) : KeptRead
        data class Not(val drafting: SafeCallDrafting) : KeptRead
    }

    /** The checks both share, against the kept Safe, and the kept Safe itself. */
    private suspend fun keptSafe(request: SigningRequest): KeptRead {
        fun not(refusal: SafeTxRefusal) = KeptRead.Not(refused(refusal))
        if (request.network != network) return not(SafeTxRefusal.WrongChain(request.network.chainId))
        request.undraftable()?.let { return not(it) }
        val safe = keptSafes.observe(AccountId(request.network, request.safe)).first()
            ?: return KeptRead.Not(SafeCallDrafting.Waits(DraftInput.SAFE))
        safe.state.tooOldToSign()?.let { return not(it) }
        if (request.nonce < safe.state.nonce) return not(SafeTxRefusal.NonceUsed(safe.state.nonce))
        request.withoutRecovery(safe.state)?.let { return not(it) }
        return KeptRead.Kept(safe)
    }

    /**
     * [SafeTxRefusal.AlreadySent] when an execution of [request] an account of this wallet sent is
     * recorded within its pool lifetime and its sender's mined count last verified is not past it;
     * a sender whose count was never kept here is taken as not past it.
     */
    private suspend fun sentAndUnmined(request: SigningRequest): SafeTxRefusal? {
        val since = clock() - RECORD_LIFETIME_SECONDS
        val sent = (sentTransfers.observe().first() as? SentTransferList.Loaded)?.transfers.orEmpty()
            .filter { it.network == request.network && it.sentAt >= since && it.executes(request) }
        for (execution in sent) {
            val mined = kept.minedNonce(request.network, execution.account)?.count
            if (mined == null || execution.nonce >= mined) return SafeTxRefusal.AlreadySent
        }
        return null
    }

    private fun refused(refusal: SafeTxRefusal) = SafeCallDrafting.Refused(refusal)

    private fun CallDrafting.asSafeCallDrafting(): SafeCallDrafting = when (this) {
        is CallDrafting.Drafted -> error("a draft is no refusal")
        is CallDrafting.Refused -> refused(SafeTxRefusal.NotQuoted(refusal))
        is CallDrafting.Waits -> SafeCallDrafting.Waits(missing)
        is CallDrafting.AccountBusy -> SafeCallDrafting.AccountBusy(queued)
    }
}

/**
 * Signs a reviewed Safe call ([SafeCallDraft]: an approval on chain or an execution) and queues it in
 * the outbox, like a transfer ([QueueTransfer]): its EIP-1559 signing digest signed by the sending
 * account's key as the unlock opens it, exactly what was drafted and reviewed, recorded as the action
 * it is ([SafeCallDraft.action]). Nothing is broadcast: the outbox sends it once the check at a fresh
 * head passes ([CheckQueued]). An account with something queued already is refused before anything
 * is signed, and again under [lock] before it is put. [clock] is in unix seconds.
 */
class QueueSafeCall(
    private val signer: Signer,
    private val outbox: Outbox,
    private val lock: OutboxLock,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    /** Signs [draft] and queues it. */
    suspend operator fun invoke(draft: SafeCallDraft, unlock: Unlock): TransferQueuing {
        val call = draft.call
        busy(call)?.let { return TransferQueuing.AccountBusy(it.id) }
        val transaction = call.transaction
        val signature = when (val signed = signer.sign(call.from, transaction.signingDigest(), unlock)) {
            is Signing.Signed -> signed.signature
            else -> return TransferQueuing.NotSigned(signed)
        }
        val signed = SignedTransfer(transaction.encodeSigned(signature))
        val queued = QueuedTransaction(
            network = draft.request.network,
            account = call.from,
            id = signed.hash,
            action = draft.action,
            level = call.level,
            transaction = transaction,
            signed = signed,
            maxCost = call.maxCost,
            signedAt = clock(),
            builtOn = call.builtOn,
        )
        return lock.mutex.withLock {
            busy(call)?.let { return@withLock TransferQueuing.AccountBusy(it.id) }
            if (outbox.put(queued)) TransferQueuing.Queued(queued) else TransferQueuing.NotSaved
        }
    }

    private suspend fun busy(call: CallDraft): QueuedTransaction? =
        (outbox.observe().first() as? OutboxList.Loaded)?.queued
            ?.firstOrNull { it.network.chainId == call.transaction.chainId && it.account == call.from }
}
