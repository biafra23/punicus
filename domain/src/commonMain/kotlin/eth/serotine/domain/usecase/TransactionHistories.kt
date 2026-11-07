package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.TransactionHistory
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.TransactionProgress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [sentTransfers], whose every record also goes into [history] at the moment it is made: what
 * the composition roots hand every use case that sends, so the history misses no transaction a
 * peer took (or maybe took), and none of them has to know of it. Reading and pruning are
 * [sentTransfers]' own; the history is never pruned. What [record] answers is [sentTransfers]'
 * answer, since that is the record that holds a nonce back: a history that could not be saved
 * is caught up by [FollowSentTransactions] at the next start.
 */
class HistoryRecordingSentTransfers(
    private val sentTransfers: SentTransfers,
    private val history: TransactionHistory,
) : SentTransfers by sentTransfers {

    override suspend fun record(transfer: SentTransfer): Boolean {
        val recorded = sentTransfers.record(transfer)
        history.record(transfer)
        return recorded
    }
}

/**
 * Records how each transaction in the [history] ends, whatever screen is open (issue #85): its
 * verified receipt's block and result ([SentOutcome.Included], then [SentOutcome.Finalized]), or,
 * when the engine never showed it a receipt, that its account's mined count passed its nonce
 * ([SentOutcome.NonceUsed]). The engine finds a receipt only for a transaction mined while it is
 * watched, so this runs for as long as the app is open (the hosts start it, never `App`).
 *
 * It starts by adding to the history what [sentTransfers] holds and the history does not: the
 * records made before the history existed, and any whose history could not be saved.
 *
 * A receipt is watched for [FOLLOW_SECONDS] after a peer took the transaction, and no longer (one
 * no block took by then may never be mined, and each watch costs the engine a block body per
 * head), and not again once a block was seen to take it before the watch started. The mined
 * count is read every [nonceInterval] while no block was seen to take it, and
 * [SentOutcome.NonceUsed] recorded only when two reads in a row find its nonce used, so that a
 * receipt in the block that used it has [nonceInterval] to be seen first. [clock] is in unix seconds.
 */
class FollowSentTransactions(
    private val history: TransactionHistory,
    private val sentTransfers: SentTransfers,
    private val receipts: ReceiptTracker,
    private val inputs: TransactionInputs,
    private val clock: () -> Long = ::epochSecondsNow,
    private val nonceInterval: Duration = 5.minutes,
) {
    /** Runs until cancelled. */
    suspend operator fun invoke() {
        catchUp()
        history.observe()
            .readEach(
                keys = { list ->
                    (list as? HistoryList.Loaded)?.entries.orEmpty()
                        .filter { open(it.outcome) }
                        .map { it.transfer }
                        .toSet()
                },
                read = { transfer -> flow<Unit> { follow(transfer) } },
            )
            .collect()
    }

    /** Adds to the history every record [sentTransfers] holds that it lacks. */
    private suspend fun catchUp() {
        val records = (sentTransfers.observe().first() as? SentTransferList.Loaded)?.transfers ?: return
        val known = (history.observe().first() as? HistoryList.Loaded)?.entries ?: return
        val hashes = known.mapTo(HashSet()) { it.transfer.hash }
        records.filterNot { it.hash in hashes }.forEach { history.record(it) }
    }

    /** Whether [outcome] may still change: no block seen, or one seen and not finalized. */
    private fun open(outcome: SentOutcome): Boolean =
        outcome == SentOutcome.NotSeen || outcome is SentOutcome.Included

    /**
     * Watches [transfer]'s receipt for what is left of its follow time, and its nonce while no block
     * took it. One already seen in a block when this starts (in an earlier run, or before the app
     * last went to the background) is not watched again: the engine looks for a receipt only from
     * a few blocks below its head on, so it would never find that one, and each watch costs it a
     * block body per head. Such a one stays [SentOutcome.Included].
     */
    private suspend fun follow(transfer: SentTransfer): Unit = coroutineScope {
        val left = transfer.sentAt + FOLLOW_SECONDS - clock()
        if (left > 0 && outcomeOf(transfer.hash) !is SentOutcome.Included) {
            launch {
                withTimeoutOrNull(left.seconds) {
                    receipts.observe(transfer.network, transfer.hash).collect { progress -> settle(transfer, progress) }
                }
            }
        }
        launch { watchNonce(transfer) }
    }

    /**
     * Records what [progress] says of [transfer], when it says more than the history holds. A
     * receipt no longer found says nothing: after a restart the engine does not look back far
     * enough to find one mined while the app was closed.
     */
    private suspend fun settle(transfer: SentTransfer, progress: TransactionProgress) {
        val outcome = when (progress) {
            is TransactionProgress.Included -> SentOutcome.Included(progress.blockNumber, progress.succeeded)
            is TransactionProgress.Finalized -> SentOutcome.Finalized(progress.blockNumber, progress.succeeded)
            TransactionProgress.Pending, is TransactionProgress.Unavailable -> return
        }
        val current = outcomeOf(transfer.hash) ?: return
        if (current != outcome && current !is SentOutcome.Finalized) history.settle(transfer.hash, outcome)
    }

    /**
     * While no block was seen to take [transfer], reads its account's mined count every
     * [nonceInterval], and records [SentOutcome.NonceUsed] once two reads in a row find its nonce used.
     */
    private suspend fun watchNonce(transfer: SentTransfer) {
        var usedBefore = false
        while (true) {
            if (outcomeOf(transfer.hash) != SentOutcome.NotSeen) return
            val used = when (val reading = minedCount(transfer.network, transfer.account)) {
                is NonceReading.Read -> reading.nonce > transfer.nonce
                is NonceReading.Unavailable, null -> null
            }
            if (used == true && usedBefore) {
                // Read again just before: a receipt seen meanwhile says more than the nonce.
                if (outcomeOf(transfer.hash) == SentOutcome.NotSeen) {
                    history.settle(transfer.hash, SentOutcome.NonceUsed)
                }
                return
            }
            if (used != null) usedBefore = used
            delay(nonceInterval)
        }
    }

    /** [account]'s verified count of mined transactions, or null when the port broke its promise not to throw. */
    private suspend fun minedCount(network: Network, account: Address): NonceReading? = try {
        inputs.minedNonce(network, account)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** How the history says [hash] ended, or null when it does not hold it or cannot be read. */
    private suspend fun outcomeOf(hash: String): SentOutcome? =
        (history.observe().first() as? HistoryList.Loaded)?.entries?.lastOrNull { it.transfer.hash == hash }?.outcome
}

/**
 * The transactions of the account at [address] in the history (issue #85), newest first: those
 * it sent, and for a Safe the approvals and executions of its transactions and its deployment,
 * whichever account of this wallet sent them.
 */
class ObserveAccountHistory(private val history: TransactionHistory) {
    operator fun invoke(account: AccountId): Flow<HistoryList> = history.observe().map { list ->
        when (list) {
            is HistoryList.Loaded -> HistoryList.Loaded(
                list.entries.filter { it.concerns(account) }.sortedByDescending { it.transfer.sentAt },
            )
            HistoryList.Unreadable -> list
        }
    }
}

/**
 * What this wallet sent, as this device's history of its transactions holds it (owner's request on
 * #136, 2026-10-21: incoming transfers from anyone else are cautioned): the [addresses] it sent
 * to, and what each swap it sent bought ([bought], by the swap's transaction hash). A swap's
 * proceeds come from a pool it never sent to; the asset it bought, arriving in that transaction,
 * is what the user asked for, whoever the pool is. Nothing else in a transaction sent from here
 * vouches for its sender: a dApp's call or a contract paid can run anyone's code.
 */
data class SentTo(val addresses: Set<Address>, val bought: Map<String, TransferAsset>)

/**
 * What this wallet sent ([SentTo]): the recipient of each transfer any of its accounts sent, and
 * of each Safe transaction one of them approved or executed (its ETH or token transfer, decoded as
 * the Safe transaction screen decodes it; a call of anything else names no recipient), and the
 * asset each swap bought. Nothing while the history cannot be read, so that no sender passes for
 * one sent to.
 */
class ObserveSentTo(private val history: TransactionHistory) {
    operator fun invoke(): Flow<SentTo> = history.observe()
        .map { list ->
            val entries = (list as? HistoryList.Loaded)?.entries.orEmpty()
            SentTo(
                addresses = entries.mapNotNullTo(HashSet()) { it.recipient() },
                bought = entries.mapNotNull { entry ->
                    (entry.transfer.action as? SentAction.Swap)?.let { entry.transfer.hash to it.buy }
                }.toMap(),
            )
        }
        .distinctUntilChanged()

    /** Whom this transaction sent value to, when it is a transfer: the account's own or its Safe's. */
    private fun HistoryEntry.recipient(): Address? = when (val action = transfer.action) {
        is SentAction.Transfer -> action.to
        is SentAction.SafeApproval -> action.request.recipient()
        is SentAction.SafeExecution -> action.request.recipient()
        // A dApp's call names no recipient the app decoded itself: it vouches for no sender.
        is SentAction.TokenApproval, is SentAction.Swap, is SentAction.Wrap, is SentAction.Unwrap,
        is SentAction.SafeDeployment, is SentAction.CowCancellation, is SentAction.DappCall,
        -> null
    }

    private fun SigningRequest.recipient(): Address? = when (val action = SafeTxAction.of(safe, tx)) {
        is SafeTxAction.NativeTransfer -> action.to
        is SafeTxAction.TokenTransfer -> action.to
        else -> null
    }
}

/** Whether the account [id] sent this transaction, or is the Safe it approves, executes or deploys. */
fun HistoryEntry.concerns(id: AccountId): Boolean {
    if (transfer.accountId == id) return true
    if (transfer.network != id.network) return false
    return when (val action = transfer.action) {
        is SentAction.Transfer, is SentAction.TokenApproval, is SentAction.Swap, is SentAction.Wrap,
        is SentAction.Unwrap, is SentAction.CowCancellation, is SentAction.DappCall,
        -> false
        is SentAction.SafeApproval -> action.request.safe == id.address
        is SentAction.SafeExecution -> action.request.safe == id.address
        is SentAction.SafeDeployment -> action.safe == id.address
    }
}

/**
 * How long after a peer took a transaction [FollowSentTransactions] watches for its receipt: a
 * day, well past the three hours a pool keeps one not yet executable ([RECORD_LIFETIME_SECONDS]),
 * for one that waited longer in a pool as executable.
 */
const val FOLLOW_SECONDS: Long = 24 * 60 * 60
