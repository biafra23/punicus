package eth.serotine.ui.outbox

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
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
 * What became of a queued transaction, as a notification says it (docs/lenient-mode.md, *The
 * outbox*), display-ready for the hosts' notifiers: the transfer in token units and its recipient
 * in full, never its signed bytes.
 */
data class OutboxNotificationText(val title: String, val text: String) {
    companion object {
        /** [queued] was handed to the network. */
        fun sent(queued: QueuedTransaction): OutboxNotificationText = OutboxNotificationText(
            title = "Queued transfer sent on ${queued.network.displayName}",
            text = "${what(queued)} was handed to the network. Open Serotine to follow it.",
        )

        /** [queued] no longer stands, for [conflict]: its signature was deleted. */
        fun conflict(queued: QueuedTransaction, conflict: QueueConflict): OutboxNotificationText = OutboxNotificationText(
            title = "A queued transfer needs your signature again",
            text = "${conflictReason(conflict, queued)} ${what(queued)} on ${queued.network.displayName} was not " +
                "sent. Open Serotine to sign it again or discard it.",
        )

        /** "0.1 ETH to 0xAbC…", in full. */
        private fun what(queued: QueuedTransaction): String {
            val transfer = queued.action as? SentAction.Transfer ?: return "A queued transaction"
            return "${amountOf(transfer.amount, transfer.asset, queued.network)} to ${Eip55.format(transfer.to)}"
        }
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
