package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeCalls
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.HeldConflict
import eth.serotine.domain.repository.HeldSafeSignature
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.OutboxNotifier
import eth.serotine.domain.repository.QueueConflict
import eth.serotine.domain.repository.QueueRefusal
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeTxQueue
import eth.serotine.domain.repository.SafeTxQueueList
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long a queued transaction may wait for its check and still be sent without the user's tap:
 * an hour from its signature, as simple mode's hour (docs/lenient-mode.md, decision 4). A payment the
 * user wanted an hour ago may be one they no longer want, and the wallet cannot tell.
 */
const val QUEUED_UNATTENDED_SECONDS: Long = 60 * 60

/**
 * This base fee raised by the headroom the check asks of a queued transaction's signed fee cap
 * (decision 12): two full blocks' rise, 1.125² = 81/64 of it, rounded up. Null past 256 bits.
 */
internal fun UInt256.withHeadroom(): UInt256? {
    val (quotient, remainder) = timesOrNull(UInt256.of(81))?.divRem(64) ?: return null
    return if (remainder == 0) quotient else quotient.plusOrNull(UInt256.of(1))
}

/** The outcome of [CheckQueued]. */
sealed interface QueuedCheck {

    /** It stands at a fresh verified head: it may be sent. */
    data object Passes : QueuedCheck

    /**
     * It cannot be checked now, for [why] (the light client catching up, a figure not verifiable
     * right now, an earlier transaction maybe sent): it waits and is checked again later, its
     * signed bytes kept.
     */
    data class Waits(val why: TransferQuoting) : QueuedCheck

    /** It no longer stands, for [conflict]: its signed bytes are to be deleted, and the user signs again. */
    data class Conflicts(val conflict: QueueConflict) : QueuedCheck
}

/**
 * The check before sending (docs/lenient-mode.md, *The check before sending*; owner rulings,
 * 2026-11-06): the strict quote made again, fresh, from the queued transaction's own inputs, and
 * compared with what was signed. For a transfer, its account, recipient, asset, amount and fee level,
 * quoted as [QuoteTransfer] quotes it; for a Safe's approval on chain or its execution
 * (*Safe transactions in lenient mode*), its Safe transaction, account and fee level, quoted as
 * [QuoteSafeApproval] and [QuoteSafeExecution] quote them, with every Safe check made against the
 * Safe as it stands now (an execution runs with the confirmations it was signed with). Every quote is
 * as it is, but for its own test of the fee: a peer tests the signed figures, so the funds row tests
 * those. It passes when:
 *
 * - the quote is made, not refused;
 * - the fresh nonce is the one signed (a higher one: another transaction took it; a lower one: a
 *   gap the signed one would wait behind);
 * - the fresh estimate is within the signed gas limit;
 * - the signed fee cap covers the next base fee raised by the headroom (81/64), plus the signed tip;
 * - the fresh native balance covers the signed transaction's upfront cost (its value plus its gas
 *   limit times its fee cap) and what the account's transactions not mined yet may take.
 *
 * Not verifiable now (the light client catching up, a figure unavailable, the Safe not verifiable
 * this time) or held back by an earlier transaction maybe sent: it waits. Any other action is a
 * conflict, never sent unchecked.
 */
class CheckQueued(
    private val quotes: (Network) -> QuoteTransfer,
    private val approvals: (Network) -> QuoteSafeApproval,
    private val executions: (Network) -> QuoteSafeExecution,
) {

    /** Checks [queued] against fresh verified state. */
    suspend operator fun invoke(queued: QueuedTransaction): QueuedCheck = when (val action = queued.action) {
        is SentAction.Transfer -> transfer(queued, action)
        is SentAction.SafeApproval ->
            safeCall(queued, approvals(queued.network).quoteForCheck(action.request, queued.account, queued.level))
        is SentAction.SafeExecution -> if (executes(queued.transaction, action.request)) {
            safeCall(
                queued,
                executions(queued.network).quoteForCheck(
                    action.request,
                    queued.account,
                    queued.level,
                    queued.transaction.data,
                ),
            )
        } else {
            QueuedCheck.Conflicts(QueueConflict.Refused(QueueRefusal.ENGINE_REFUSED))
        }
        else -> QueuedCheck.Conflicts(QueueConflict.Refused(QueueRefusal.ENGINE_REFUSED))
    }

    private suspend fun transfer(queued: QueuedTransaction, action: SentAction.Transfer): QueuedCheck {
        val checked = quotes(queued.network).quoteForCheck(
            queued.account,
            action.to,
            action.asset,
            action.amount,
            queued.level,
        )
        val quote = when (val quoting = checked.quoting) {
            is TransferQuoting.Quoted -> quoting.quote
            else -> return conflictOrWait(quoting)
        }
        return judge(
            queued,
            quote.transaction,
            quote.maxFee,
            quote.nativeBefore,
            quote.pendingCost,
            checked.nextBaseFee,
        )
    }

    private fun safeCall(queued: QueuedTransaction, checked: SafeCallQuoting): QueuedCheck = when (checked) {
        is SafeCallQuoting.Quoted -> {
            val call = checked.call
            judge(queued, call.transaction, call.maxFee, call.nativeBefore, call.pendingCost, checked.nextBaseFee)
        }
        is SafeCallQuoting.Refused -> conflictOrWait(checked.refusal)
    }

    /**
     * [queued], signed, against [fresh], the same transaction quoted again now: the quote's most fee
     * ([nowAtMost]), the account's fresh native balance ([nativeBefore]) and what its transactions not
     * mined yet may take of it ([pendingCost]), and the base fee the next block charges ([nextBaseFee]).
     */
    private fun judge(
        queued: QueuedTransaction,
        fresh: Eip1559Transaction,
        nowAtMost: UInt256,
        nativeBefore: UInt256,
        pendingCost: UInt256,
        nextBaseFee: UInt256?,
    ): QueuedCheck {
        val signed = queued.transaction
        // The same transaction, quoted again: what the check judges is what was signed.
        if (fresh.chainId != signed.chainId || fresh.to != signed.to || fresh.value != signed.value ||
            !fresh.data.contentEquals(signed.data)
        ) {
            return conflict(QueueConflict.Refused(QueueRefusal.ENGINE_REFUSED))
        }
        if (fresh.nonce > signed.nonce) return conflict(QueueConflict.NonceUsed(signed.nonce, fresh.nonce))
        if (fresh.nonce < signed.nonce) return conflict(QueueConflict.NonceGap(signed.nonce, fresh.nonce))
        if (fresh.gasLimit > signed.gasLimit) {
            return conflict(QueueConflict.NeedsMoreGas(signed.gasLimit, fresh.gasLimit))
        }
        val baseFee = nextBaseFee ?: return QueuedCheck.Waits(FEES_UNPROVEN)
        val needed = baseFee.withHeadroom()?.plusOrNull(signed.maxPriorityFeePerGas)
            ?: return QueuedCheck.Waits(FEES_UNPROVEN)
        if (signed.maxFeePerGas < needed) return conflict(QueueConflict.FeesRose(nowAtMost))
        val upfront = signed.maxFeePerGas.timesOrNull(UInt256.of(signed.gasLimit))?.plusOrNull(signed.value)
            ?: return conflict(QueueConflict.InsufficientFunds)
        val available = nativeBefore.minusOrNull(pendingCost)
        if (available == null || available < upfront) return conflict(QueueConflict.InsufficientFunds)
        return QueuedCheck.Passes
    }

    private fun conflict(conflict: QueueConflict) = QueuedCheck.Conflicts(conflict)

    /**
     * Whether [signed] is an `execTransaction` of [request]'s own Safe transaction, sent to its Safe
     * with no value: its call data is that transaction's fields and data, then the signatures, which
     * the check's run proves the Safe accepts now.
     */
    private fun executes(signed: Eip1559Transaction, request: SigningRequest): Boolean {
        val unsigned = SafeCalls.execTransaction(request.tx, emptyList())
        // Everything but the empty signatures' length word: the same for any signatures.
        val head = unsigned.copyOf(unsigned.size - ABI_WORD)
        val data = signed.data
        return signed.to == request.safe && signed.value.isZero && data.size > head.size &&
            data.copyOf(head.size).contentEquals(head)
    }

    /** A strict refusal at the check: a conflict where it is about the transfer, a wait where it is about now. */
    private fun conflictOrWait(quoting: TransferQuoting): QueuedCheck = when (quoting) {
        is TransferQuoting.Quoted -> QueuedCheck.Passes
        TransferQuoting.InsufficientFunds -> conflict(QueueConflict.InsufficientFunds)
        TransferQuoting.InsufficientToken -> conflict(QueueConflict.Refused(QueueRefusal.INSUFFICIENT_TOKEN))
        TransferQuoting.NotAToken -> conflict(QueueConflict.Refused(QueueRefusal.NOT_A_TOKEN))
        is TransferQuoting.Reverts -> conflict(QueueConflict.Refused(QueueRefusal.REVERTS))
        is TransferQuoting.Infeasible -> conflict(QueueConflict.Refused(QueueRefusal.INFEASIBLE, quoting.detail))
        TransferQuoting.Refused -> conflict(QueueConflict.Refused(QueueRefusal.ENGINE_REFUSED))
        // Refused before it was queued, never after: a draft was refused the same way.
        TransferQuoting.ToZeroAddress, TransferQuoting.ToTokenContract, TransferQuoting.NothingToSend ->
            conflict(QueueConflict.Refused(QueueRefusal.ENGINE_REFUSED))
        TransferQuoting.Syncing, is TransferQuoting.Unavailable, is TransferQuoting.EarlierMaybeSent ->
            QueuedCheck.Waits(quoting)
    }

    /**
     * A strict refusal of a Safe's approval or execution at the check: a wait where it is about now
     * (the light client, a figure or the Safe not verifiable this time, an earlier transaction maybe
     * sent), a conflict where it is about the transaction or the Safe as it stands.
     */
    private fun conflictOrWait(refusal: SafeTxRefusal): QueuedCheck = when (refusal) {
        SafeTxRefusal.Syncing -> QueuedCheck.Waits(TransferQuoting.Syncing)
        is SafeTxRefusal.Unavailable -> QueuedCheck.Waits(TransferQuoting.Unavailable(refusal.reason))
        is SafeTxRefusal.SafeUnavailable -> QueuedCheck.Waits(TransferQuoting.Unavailable(refusal.reason))
        is SafeTxRefusal.NotQuoted -> when (val call = refusal.refusal) {
            CallRefusal.Syncing -> QueuedCheck.Waits(TransferQuoting.Syncing)
            is CallRefusal.Unavailable -> QueuedCheck.Waits(TransferQuoting.Unavailable(call.reason, call.figure))
            is CallRefusal.EarlierMaybeSent ->
                QueuedCheck.Waits(TransferQuoting.EarlierMaybeSent(call.hash, call.queued))
            CallRefusal.InsufficientFunds -> conflict(QueueConflict.InsufficientFunds)
            is CallRefusal.Reverts -> conflict(QueueConflict.Refused(QueueRefusal.REVERTS))
            is CallRefusal.Infeasible -> conflict(QueueConflict.Refused(QueueRefusal.INFEASIBLE, call.detail))
            CallRefusal.Refused -> conflict(QueueConflict.Refused(QueueRefusal.ENGINE_REFUSED))
        }
        is SafeTxRefusal.NonceUsed -> conflict(QueueConflict.Refused(QueueRefusal.SAFE_NONCE_USED))
        is SafeTxRefusal.NotAnOwner -> conflict(QueueConflict.Refused(QueueRefusal.NOT_AN_OWNER))
        SafeTxRefusal.AlreadyApproved -> conflict(QueueConflict.Refused(QueueRefusal.ALREADY_APPROVED))
        SafeTxRefusal.AlreadySent -> conflict(QueueConflict.Refused(QueueRefusal.ALREADY_SENT))
        SafeTxRefusal.NotASafe -> conflict(QueueConflict.Refused(QueueRefusal.NOT_A_SAFE))
        // Every other refusal: the Safe as it stands, or the transaction itself, refuses it.
        else -> conflict(QueueConflict.Refused(QueueRefusal.SAFE_REFUSED))
    }

    private companion object {
        val FEES_UNPROVEN = TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.FEES)

        /** One ABI word's bytes. */
        const val ABI_WORD = 32
    }
}

/**
 * The one lock the outbox's checks and the user's changes to it share, so that a discard or an
 * edit waits while a check runs and a check never sends what was discarded meanwhile
 * (docs/lenient-mode.md, *The outbox*). The composition root makes one and hands it to
 * [FollowOutbox], [DiscardQueued] and [ConfirmQueued].
 */
class OutboxLock {
    internal val mutex = Mutex()
}

/**
 * Sends what is queued once it checks out (docs/lenient-mode.md, *The outbox*): every [interval],
 * each queued transaction whose bytes are kept and whose network's light client verifies is
 * checked ([CheckQueued]) and, within its hour ([QUEUED_UNATTENDED_SECONDS]) from its signature or
 * from the user's tap ([QueuedTransaction.confirmedAt]), asked again right before the broadcast,
 * sent as strict mode sends. It is marked as sending in the outbox first, and not sent when that
 * mark cannot be saved. Then: a peer took it, recorded in [SentTransfers] and gone from the outbox;
 * no peer answered, recorded as maybe sent, as strict mode does, and gone from the outbox, its
 * status offering to try it again; rejected, a conflict. One still marked as sending (the process
 * ended, or a record could not be saved, after its broadcast) is recorded as maybe sent and gone
 * from the outbox, never checked again: its own record would read as another transaction having
 * taken its nonce. A conflict deletes the signed bytes and keeps the rest, so the user can sign
 * again or discard it. Each send and each conflict is told through [notifier].
 *
 * Each Safe signature held there ([HeldSafeSignature]; *Safe transactions in lenient mode*) whose
 * network's light client verifies is checked too ([checkHeld]): passing, it is saved to the Safe's
 * queue ([safeTxQueue], pruned to the Safe's nonce first), where its QR code and text are offered,
 * and only once saved is it released from the outbox; one that could not be saved stays held and is
 * checked again, and one found saved already (its release could not be saved) is only released. A
 * transfer proposed here that finds another transaction at its nonce in the queue is a conflict
 * ([HeldConflict.NonceTaken]): only one of the two could ever run. A conflict deletes its signature,
 * never shown, and keeps the rest so the user is told and can sign again or discard it. Each is
 * told through [notifier]. A held signature has no hour: passing its check shows it to nobody, and
 * sends nothing.
 *
 * Run for as long as the app runs (the hosts start it, never `App`): the check needs a verified
 * head, which only a light client that is awake has. [clock] is in unix seconds. Runs until
 * cancelled; a port that breaks its promise not to throw costs that round only.
 */
class FollowOutbox(
    private val outbox: Outbox,
    private val check: CheckQueued,
    private val lightClient: LightClient,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val notifier: OutboxNotifier,
    private val lock: OutboxLock,
    private val checkHeld: CheckHeldSignature,
    private val safeTxQueue: SafeTxQueue,
    private val clock: () -> Long = ::epochSecondsNow,
    private val interval: Duration = 15.seconds,
) {
    /** Runs until cancelled. */
    suspend operator fun invoke() {
        while (true) {
            try {
                round()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The ports promise not to throw; the next round tries again.
            }
            delay(interval)
        }
    }

    /**
     * One pass over what is queued and held; each transaction and each held signature under [lock],
     * read again inside it. One still marked as sending is resolved whatever the light client does:
     * it reads nothing from the chain, and until it is resolved its bytes are in no record. The rest
     * wait for a verified head.
     */
    internal suspend fun round() {
        val list = outbox.observe().first() as? OutboxList.Loaded ?: return
        for (entry in list.queued) {
            if (entry.signed == null) continue
            if (!entry.sending && lightClient.observeStatus(entry.network).first() !is LightClientStatus.Synced) {
                continue
            }
            lock.mutex.withLock { follow(entry.accountId) }
        }
        for (held in list.held) {
            if (held.signature == null) continue
            if (lightClient.observeStatus(held.network).first() !is LightClientStatus.Synced) continue
            lock.mutex.withLock { followHeld(held.safe, held.hash) }
        }
    }

    private suspend fun followHeld(safe: AccountId, hash: Digest) {
        // Read again under the lock: it may have been discarded, or signed again, meanwhile.
        val entry = (outbox.observe().first() as? OutboxList.Loaded)?.held
            ?.firstOrNull { it.safe == safe && it.hash == hash }
            ?: return
        val signed = entry.signed() ?: return
        val queue = safeTxQueue.observe(safe).first() as? SafeTxQueueList.Loaded ?: return
        // Saved by a round whose release could not be saved: it passed its check then, so it only goes.
        val saved = queue.requests.firstOrNull { it.hash == hash }?.signatures.orEmpty()
        if (signed.signatures.all { it in saved }) {
            if (outbox.release(safe, hash)) notifier.heldChecked(entry)
            return
        }
        when (val result = checkHeld(entry)) {
            is HeldCheck.Passes -> withContext(NonCancellable) {
                // A transfer proposed here never lands beside another transaction at its nonce: the queue
                // read again, as it is after the check, which may have waited a while.
                val after = safeTxQueue.observe(safe).first() as? SafeTxQueueList.Loaded ?: return@withContext
                val taken = entry.proposed && after.requests.any { it.nonce == entry.nonce && it.hash != hash }
                if (taken) {
                    heldConflicted(entry, HeldConflict.NonceTaken)
                } else {
                    checked(entry, signed, result.state.nonce)
                }
            }
            HeldCheck.Waits -> Unit
            is HeldCheck.Conflicts -> heldConflicted(entry, result.conflict)
        }
    }

    /**
     * [entry], checked against the Safe at [safeNonce]: saved to the Safe's queue as [signed], and
     * only then released from the outbox. One not saved stays held, and is checked again.
     */
    private suspend fun checked(entry: HeldSafeSignature, signed: SigningRequest, safeNonce: Long) {
        safeTxQueue.prune(entry.safe, safeNonce)
        safeTxQueue.save(signed) ?: return
        if (outbox.release(entry.safe, entry.hash)) notifier.heldChecked(entry)
    }

    private suspend fun heldConflicted(entry: HeldSafeSignature, conflict: HeldConflict) {
        // The signature goes first: nothing can show it once the conflict is found.
        val stripped = entry.copy(signature = null, conflict = conflict)
        // The notifier is told the copy kept, never the signature.
        if (outbox.hold(stripped)) notifier.heldConflict(stripped, conflict)
    }

    private suspend fun follow(account: AccountId) {
        // Read again under the lock: it may have been discarded, or signed again, meanwhile.
        val entry = (outbox.observe().first() as? OutboxList.Loaded)?.queued
            ?.firstOrNull { it.accountId == account }
            ?: return
        val bytes = entry.signed ?: return
        if (entry.sending) return resolveSending(entry, bytes)
        if (!withinItsHour(entry)) return
        when (val result = check(entry)) {
            // The check's reads can take a while: the hour is asked again right before the broadcast.
            QueuedCheck.Passes -> if (withinItsHour(entry)) send(entry, bytes)
            is QueuedCheck.Waits -> Unit
            is QueuedCheck.Conflicts -> conflicted(entry, result.conflict)
        }
    }

    /** Whether [entry] may still be sent by itself: within an hour of its signature, or of the user's tap. */
    private fun withinItsHour(entry: QueuedTransaction): Boolean =
        clock() - (entry.confirmedAt ?: entry.signedAt) <= QUEUED_UNATTENDED_SECONDS

    private suspend fun send(entry: QueuedTransaction, bytes: SignedTransfer): Unit = withContext(NonCancellable) {
        // Marked first: a broadcast whose outcome is not recorded is never taken for a conflict.
        if (!outbox.put(entry.copy(sending = true))) return@withContext
        val transfer = record(entry, bytes)
        when (val broadcast = broadcaster.send(entry.network, bytes.raw)) {
            is Broadcast.Sent -> settle(entry, transfer.copy(sentAt = clock(), maybeSent = false))
            is Broadcast.Unavailable -> settle(entry, transfer.copy(sentAt = clock(), maybeSent = true))
            is Broadcast.Rejected -> conflicted(entry, QueueConflict.Rejected(broadcast.detail))
            is Broadcast.Refused -> conflicted(entry, QueueConflict.Refused(QueueRefusal.ENGINE_REFUSED))
        }
    }

    /**
     * [entry], found still marked as sending: its broadcast may have reached the network. Recorded
     * as maybe sent, unless its record is there already, and gone from the outbox.
     */
    private suspend fun resolveSending(entry: QueuedTransaction, bytes: SignedTransfer): Unit =
        withContext(NonCancellable) {
            val recorded = (sentTransfers.observe().first() as? SentTransferList.Loaded)?.transfers.orEmpty()
                .any { it.hash == bytes.hash }
            if (recorded) {
                outbox.remove(entry.accountId)
                Unit
            } else {
                settle(entry, record(entry, bytes).copy(sentAt = clock(), maybeSent = true))
            }
        }

    /** Records [transfer] and only then lets [entry] go from the outbox: one that is not recorded stays, marked. */
    private suspend fun settle(entry: QueuedTransaction, transfer: SentTransfer) {
        if (!sentTransfers.record(transfer)) return
        outbox.remove(entry.accountId)
        notifier.sent(entry, transfer.hash)
    }

    private fun record(entry: QueuedTransaction, bytes: SignedTransfer) = SentTransfer(
        network = entry.network,
        account = entry.account,
        nonce = entry.transaction.nonce,
        signed = bytes,
        action = entry.action,
        maxCost = entry.maxCost,
        sentAt = clock(),
    )

    private suspend fun conflicted(entry: QueuedTransaction, conflict: QueueConflict) {
        // The signed bytes go first: nothing can send them once the conflict is found.
        val stripped = entry.copy(signed = null, conflict = conflict, sending = false)
        // The notifier is told the copy kept, never the bytes of a transaction not sent.
        if (outbox.put(stripped)) notifier.conflict(stripped, conflict)
    }
}

/**
 * Discards the transaction [account] has queued under [id] (docs/lenient-mode.md, *The outbox*): its
 * signed bytes are deleted with it. Waits while a check of it runs ([OutboxLock]), so the check never
 * sends what was discarded, and decides under that lock what became of it: one marked as sending
 * (its broadcast ran, or runs, and may have reached the network) is never discarded, nor is another
 * transaction queued from the account since; what is sent is never told as discarded.
 */
class DiscardQueued(private val outbox: Outbox, private val lock: OutboxLock) {
    suspend operator fun invoke(account: AccountId, id: String): QueuedDiscard = lock.mutex.withLock {
        val list = outbox.observe().first() as? OutboxList.Loaded ?: return@withLock QueuedDiscard.NotSaved
        val entry = list.queued.firstOrNull { it.accountId == account && it.id.equals(id, ignoreCase = true) }
        when {
            entry == null -> QueuedDiscard.Gone
            entry.sending -> QueuedDiscard.BeingSent
            outbox.remove(account) -> QueuedDiscard.Discarded
            else -> QueuedDiscard.NotSaved
        }
    }
}

/** The outcome of [DiscardQueued]. */
sealed interface QueuedDiscard {

    /** Deleted, its signed bytes with it: nothing was sent. */
    data object Discarded : QueuedDiscard

    /**
     * Nothing under that id is queued from the account any more: sent meanwhile (its record says
     * so), discarded already, or replaced by a transaction signed since, which is left as it is.
     */
    data object Gone : QueuedDiscard

    /**
     * Its broadcast ran, or runs: it may have reached the network, so it is not discarded. The
     * outbox records it as sent, or as maybe sent ([FollowOutbox]).
     */
    data object BeingSent : QueuedDiscard

    /** The outbox could not be read or saved, and nothing changed. */
    data object NotSaved : QueuedDiscard
}

/**
 * The user's tap on "Still send this?" for the transaction [account] has queued under [id] past its
 * hour ([QUEUED_UNATTENDED_SECONDS]): it is sent once its check passes within an hour of the tap;
 * past that, it asks again (decision 4). [clock] is in unix seconds. False when nothing with kept
 * bytes is queued from [account] under [id], or it could not be saved.
 */
class ConfirmQueued(
    private val outbox: Outbox,
    private val lock: OutboxLock,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(account: AccountId, id: String): Boolean = lock.mutex.withLock {
        val entry = (outbox.observe().first() as? OutboxList.Loaded)?.queued
            ?.firstOrNull { it.accountId == account && it.id.equals(id, ignoreCase = true) && it.signed != null }
            ?: return@withLock false
        outbox.put(entry.copy(confirmedAt = clock()))
    }
}

/** What is queued in the outbox, for the screens (docs/lenient-mode.md, *Where it shows*). */
class ObserveOutbox(private val outbox: Outbox) {
    operator fun invoke(): Flow<OutboxList> = outbox.observe()
}

/**
 * The user dismissed the notice that queued signed bytes were lost ([OutboxList.Loaded.lost]; the
 * seal key gone, docs/lenient-mode.md, *The signed bytes kept*): it is said once. False when that
 * could not be saved, and the notice stays.
 */
class AcknowledgeLostOutbox(private val outbox: Outbox) {
    suspend operator fun invoke(): Boolean = outbox.acknowledgeLost()
}
