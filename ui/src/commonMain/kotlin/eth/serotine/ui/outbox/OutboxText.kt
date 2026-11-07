package eth.serotine.ui.outbox

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.HeldConflict
import eth.serotine.domain.repository.HeldRefusal
import eth.serotine.domain.repository.HeldSafeSignature
import eth.serotine.domain.repository.QueueConflict
import eth.serotine.domain.repository.QueueRefusal
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.usecase.QUEUED_UNATTENDED_SECONDS
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NATIVE_DECIMALS
import eth.serotine.ui.components.exactAmount
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * What became of a queued transaction, or of a held Safe signature, as a notification says it
 * (docs/lenient-mode.md, *The outbox*, *Safe transactions in lenient mode*), display-ready for the
 * hosts' notifiers: the transfer in token units and its recipient in full, or the Safe transaction's
 * nonce and its Safe in full; never signed bytes, nor a signature.
 */
data class OutboxNotificationText(val title: String, val text: String) {
    companion object {
        /** [queued] was handed to the network. */
        fun sent(queued: QueuedTransaction): OutboxNotificationText = OutboxNotificationText(
            title = "Queued ${kind(queued)} sent on ${queued.network.displayName}",
            text = "${what(queued)} was handed to the network. Open Punicus to follow it.",
        )

        /** [queued] no longer stands, for [conflict]: its signature was deleted. */
        fun conflict(queued: QueuedTransaction, conflict: QueueConflict): OutboxNotificationText = OutboxNotificationText(
            title = "A queued ${kind(queued)} needs your signature again",
            text = "${conflictReason(conflict, queued)} ${what(queued)} on ${queued.network.displayName} was not " +
                "sent. Open Punicus to sign it again or discard it.",
        )

        /** [held] checked out at a fresh head: it is in the Safe's queue, to be shared. */
        fun heldChecked(held: HeldSafeSignature): OutboxNotificationText = OutboxNotificationText(
            title = "Safe signature checked on ${held.network.displayName}",
            text = "Your signature of ${safeTxOf(held.nonce, held.request.safe)} checked out on chain. It's in the " +
                "Safe's queue on this device now: open Punicus to share it with the other owners.",
        )

        /** [held] no longer stands, for [conflict]: its signature was deleted, never shown. */
        fun heldConflict(held: HeldSafeSignature, conflict: HeldConflict): OutboxNotificationText =
            OutboxNotificationText(
                title = when {
                    conflict == HeldConflict.NotAnOwner -> "A Safe signature was discarded"
                    held.proposed && conflict.proposesAgain ->
                        "A Safe transaction needs your signature again"
                    else -> "A Safe signature did not check out"
                },
                text = "${heldConflictReason(conflict, held)} Your signature of " +
                    "${safeTxOf(held.nonce, held.request.safe)} on ${held.network.displayName} was deleted and never " +
                    "shown. Open Punicus to see it.",
            )

        /** What a queued transaction is called: a transfer, or a transaction. */
        private fun kind(queued: QueuedTransaction): String =
            if (queued.action is SentAction.Transfer) "transfer" else "transaction"

        /** "0.1 ETH to 0xAbC…", in full, or the Safe transaction approved or executed. */
        private fun what(queued: QueuedTransaction): String = when (val action = queued.action) {
            is SentAction.Transfer ->
                "${amountOf(action.amount, action.asset, queued.network)} to ${Eip55.format(action.to)}"
            is SentAction.SafeApproval ->
                "The approval on chain of ${safeTxOf(action.request.nonce, action.request.safe)}"
            is SentAction.SafeExecution ->
                "The execution of ${safeTxOf(action.request.nonce, action.request.safe)}"
            else -> "A queued transaction"
        }

        /** "Safe transaction 12 of 0xAbC…", the Safe in full. */
        private fun safeTxOf(nonce: Long, safe: Address): String = "Safe transaction $nonce of ${Eip55.format(safe)}"
    }
}

/**
 * Whether a proposal this device made is proposed again after [this]: its nonce was used, taken or
 * left behind a gap, so a new nonce makes a new hash and its owner signs anew.
 */
val HeldConflict.proposesAgain: Boolean
    get() = this is HeldConflict.NonceUsed || this == HeldConflict.NonceTaken || this is HeldConflict.NonceGap

/**
 * Why a held Safe signature no longer stands (docs/lenient-mode.md, *Safe transactions in lenient
 * mode*), in the words the Safe transaction screen and the notification share: one sentence.
 */
fun heldConflictReason(conflict: HeldConflict, held: HeldSafeSignature): String = when (conflict) {
    is HeldConflict.NonceUsed ->
        "Another Safe transaction used nonce ${held.nonce}: the Safe is at nonce ${conflict.safeNonce} now."
    HeldConflict.NonceTaken ->
        "Another transaction at nonce ${held.nonce} was put in this Safe's queue meanwhile: only one of the two " +
            "could ever run."
    is HeldConflict.NonceGap ->
        "Nothing in this Safe's queue takes nonce ${conflict.next}, which comes before it, so nonce ${held.nonce} " +
            "could not run."
    HeldConflict.NotAnOwner -> "${Eip55.format(held.owner)} is no longer an owner of this Safe, so it cannot sign it."
    is HeldConflict.Refused -> when (conflict.reason) {
        HeldRefusal.NOT_A_SAFE -> "The address no longer holds a Safe this app recognises."
        HeldRefusal.TOO_OLD_TO_SIGN -> "The Safe is on a version from before 1.3.0, which nothing here is signed for."
        HeldRefusal.NEEDS_RECOVERY_MODULE -> "Checked on chain, it would now leave every owner of the Safe needed, " +
            "which only a recovery module makes safe."
        HeldRefusal.BAD_SIGNATURE -> "The signature does not recover to its owner over the hash computed here."
        HeldRefusal.UNSIGNABLE -> "This wallet signs nothing that does what it does."
    }
}

/**
 * Why a queued transaction no longer stands (docs/lenient-mode.md, *The check before sending*), in
 * the words the status screen and the notification share: one sentence, said as strict mode says
 * the same refusal.
 */
fun conflictReason(conflict: QueueConflict, queued: QueuedTransaction): String {
    val network = queued.network
    val symbol = (queued.action as? SentAction.Transfer)?.asset?.let { symbolOf(it, network) } ?: network.nativeSymbol
    return when (conflict) {
        is QueueConflict.NonceUsed -> "Another transaction from this account came first."
        is QueueConflict.NonceGap -> "It would wait behind an earlier transaction from this account that is not there."
        is QueueConflict.NeedsMoreGas -> "It needs more gas than it was signed for."
        is QueueConflict.FeesRose -> "Fees rose since you signed: the most it can cost is now " +
            exactAmount(conflict.nowAtMost, NATIVE_DECIMALS, network.nativeSymbol) + "."
        QueueConflict.InsufficientFunds -> "Not enough ${network.nativeSymbol} for the most this can cost."
        is QueueConflict.Rejected -> "The network refused it: ${conflict.detail}."
        is QueueConflict.Refused -> when (conflict.reason) {
            QueueRefusal.INSUFFICIENT_TOKEN -> "This account holds less $symbol than that now."
            QueueRefusal.NOT_A_TOKEN -> "The token's contract does not answer as a token."
            QueueRefusal.REVERTS -> "Checked on chain, it fails now."
            QueueRefusal.INFEASIBLE -> "It cannot succeed: ${conflict.detail ?: "the chain refuses it"}."
            QueueRefusal.ENGINE_REFUSED -> "The light client on this device cannot check it."
            QueueRefusal.SAFE_NONCE_USED -> "The Safe's nonce has passed its transaction: it ran, or another " +
                "transaction used its nonce."
            QueueRefusal.NOT_AN_OWNER -> "This account is no longer an owner of the Safe."
            QueueRefusal.ALREADY_APPROVED -> "This owner has approved the Safe transaction on chain already."
            QueueRefusal.ALREADY_SENT -> "An approval or execution of the Safe transaction was sent from here " +
                "already, and no block has it yet."
            QueueRefusal.NOT_A_SAFE -> "The address no longer holds a Safe this app recognises."
            QueueRefusal.SAFE_REFUSED -> "Checked on chain, the Safe as it stands now refuses it: open the Safe " +
                "transaction to see why."
        }
    }
}

/**
 * Why nothing new is quoted from an account that has a transaction queued, in either mode
 * (docs/lenient-mode.md, *The outbox*, decision 6: one per account): the words every screen that
 * quotes says it in, with a way to its status.
 */
const val QUEUED_FIRST = "A transaction from this account is waiting to be sent. Send or discard it first."

/** [QUEUED_FIRST] where the screen has no button to its status: where the user finds it. */
const val QUEUED_FIRST_SEE_ACTIVITY = "$QUEUED_FIRST Its status is first in the account's Activity."

/** The button that opens the status of what the account has queued. */
const val OPEN_QUEUED = "Open the transaction waiting to be sent"

private fun amountOf(amount: UInt256, asset: TransferAsset, network: Network): String = when (asset) {
    TransferAsset.Native -> exactAmount(amount, NATIVE_DECIMALS, network.nativeSymbol)
    is TransferAsset.Erc20Token -> exactAmount(amount, asset.token.decimals, asset.token.symbol)
}

private fun symbolOf(asset: TransferAsset, network: Network): String = when (asset) {
    TransferAsset.Native -> network.nativeSymbol
    is TransferAsset.Erc20Token -> asset.token.symbol
}

/**
 * Whether this queued transaction is past its hour at [now] (unix seconds): an hour from its
 * signature, or from the user's last tap ([QueuedTransaction.confirmedAt]). Past it, it is never sent
 * by itself: it waits for the user's answer (docs/lenient-mode.md, decision 4).
 */
fun QueuedTransaction.pastItsHour(now: Long): Boolean = now - (confirmedAt ?: signedAt) > QUEUED_UNATTENDED_SECONDS

/**
 * Each list of queued transactions, emitted again the moment one of them passes its hour (at [now],
 * unix seconds; [QueuedTransaction.pastItsHour]): what a summary line ("waiting to send", "waits for
 * your answer") is drawn from, so it changes when the transaction does, with no tick in between.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<List<QueuedTransaction>>.redatedAtTheirHour(now: () -> Long): Flow<List<QueuedTransaction>> =
    flatMapLatest { queued ->
        flow {
            emit(queued)
            val hours = queued.filter { it.conflict == null }
                .map { (it.confirmedAt ?: it.signedAt) + QUEUED_UNATTENDED_SECONDS + 1 }
                .sorted()
            for (at in hours) {
                val wait = at - now()
                if (wait > 0) delay(wait.seconds)
                emit(queued)
            }
        }
    }
