package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.OutboxNotifier
import eth.serotine.domain.repository.QueueConflict
import eth.serotine.domain.repository.QueueRefusal
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.ReadUnavailable
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
 * 2026-11-06): the strict quote made again, fresh, from the queued transfer's own inputs (its
 * account, recipient, asset, amount and fee level), and compared with what was signed. The quote
 * is [QuoteTransfer] as it is, but for its own test of the fee: a peer tests the signed figures,
 * so the funds row tests those. It passes when:
 *
 * - the quote is made, not refused;
 * - the fresh nonce is the one signed (a higher one: another transaction took it; a lower one: a
 *   gap the signed one would wait behind);
 * - the fresh estimate is within the signed gas limit;
 * - the signed fee cap covers the next base fee raised by the headroom (81/64), plus the signed tip;
 * - the fresh native balance covers the signed transaction's upfront cost (its value plus its gas
 *   limit times its fee cap) and what the account's transactions not mined yet may take.
 *
 * Not verifiable now (the light client catching up, a figure unavailable) or held back by an
 * earlier transaction maybe sent: it waits. Only a transfer is checked here; any other action is a
 * conflict, never sent unchecked.
 */
class CheckQueued(private val quotes: (Network) -> QuoteTransfer) {

    /** Checks [queued] against fresh verified state. */
    suspend operator fun invoke(queued: QueuedTransaction): QueuedCheck {
        val signed = queued.transaction
        val action = queued.action as? SentAction.Transfer
            ?: return QueuedCheck.Conflicts(QueueConflict.Refused(QueueRefusal.ENGINE_REFUSED))
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
        val fresh = quote.transaction
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
        val nextBaseFee = checked.nextBaseFee ?: return QueuedCheck.Waits(FEES_UNPROVEN)
        val needed = nextBaseFee.withHeadroom()?.plusOrNull(signed.maxPriorityFeePerGas)
            ?: return QueuedCheck.Waits(FEES_UNPROVEN)
        if (signed.maxFeePerGas < needed) {
            val nowAtMost = quote.maxFee
            return conflict(QueueConflict.FeesRose(nowAtMost))
        }
        val upfront = signed.maxFeePerGas.timesOrNull(UInt256.of(signed.gasLimit))?.plusOrNull(signed.value)
            ?: return conflict(QueueConflict.InsufficientFunds)
        val available = quote.nativeBefore.minusOrNull(quote.pendingCost)
        if (available == null || available < upfront) return conflict(QueueConflict.InsufficientFunds)
        return QueuedCheck.Passes
    }

    private fun conflict(conflict: QueueConflict) = QueuedCheck.Conflicts(conflict)

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

    private companion object {
        val FEES_UNPROVEN = TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.FEES)
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
     * One pass over what is queued; each transaction under [lock], read again inside it. One still
     * marked as sending is resolved whatever the light client does: it reads nothing from the
     * chain, and until it is resolved its bytes are in no record. The rest wait for a verified head.
     */
    internal suspend fun round() {
        val queued = (outbox.observe().first() as? OutboxList.Loaded)?.queued.orEmpty()
        for (entry in queued) {
            if (entry.signed == null) continue
            if (!entry.sending && lightClient.observeStatus(entry.network).first() !is LightClientStatus.Synced) {
                continue
            }
            lock.mutex.withLock { follow(entry.accountId) }
        }
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
        val kept = outbox.put(entry.copy(signed = null, conflict = conflict, sending = false))
        if (kept) notifier.conflict(entry, conflict)
    }
}

/**
 * Discards what [account] had queued (docs/lenient-mode.md, *The outbox*): its signed bytes are
 * deleted with it. Waits while a check of it runs ([OutboxLock]), so the check never sends what was
 * discarded. False, and nothing changed, when the outbox could not be saved, and when it is marked
 * as sending ([QueuedTransaction.sending]): its bytes may be on the network and are in no record
 * yet, so only the follower resolves it, into a record of a transaction maybe sent.
 */
class DiscardQueued(private val outbox: Outbox, private val lock: OutboxLock) {
    suspend operator fun invoke(account: AccountId): Boolean = lock.mutex.withLock {
        val entry = (outbox.observe().first() as? OutboxList.Loaded)?.queued?.firstOrNull { it.accountId == account }
            ?: return@withLock false
        if (entry.sending) return@withLock false
        outbox.remove(account)
    }
}

/**
 * The user's tap on "Still send this?" for what [account] had queued past its hour
 * ([QUEUED_UNATTENDED_SECONDS]): it is sent once its check passes within an hour of the tap; past
 * that, it asks again (decision 4). [clock] is in unix seconds. False when nothing with kept bytes
 * is queued from [account], or it could not be saved.
 */
class ConfirmQueued(
    private val outbox: Outbox,
    private val lock: OutboxLock,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(account: AccountId): Boolean = lock.mutex.withLock {
        val entry = (outbox.observe().first() as? OutboxList.Loaded)?.queued
            ?.firstOrNull { it.accountId == account && it.signed != null }
            ?: return@withLock false
        outbox.put(entry.copy(confirmedAt = clock()))
    }
}

/** What is queued in the outbox, for the screens (docs/lenient-mode.md, *Where it shows*). */
class ObserveOutbox(private val outbox: Outbox) {
    operator fun invoke(): Flow<OutboxList> = outbox.observe()
}
