package eth.serotine.data.engine

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.TransactionProgress
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn

/**
 * [ReceiptTracker] over the engine: every [pollInterval] the receipt is asked for
 * ([TransactingChain.receipt], verified against the including block's receipts root) while the
 * light client can verify reads, and a receipt whose block is at or below the finalized head
 * ends the watch as [TransactionProgress.Finalized]. A state or a receipt that cannot be read
 * is [ReadUnavailable.UNPROVEN] for that poll, never an answer kept from before, as for every
 * verified read; a device without an engine answers [ReadUnavailable.NO_ENGINE] once.
 */
class EngineReceipts internal constructor(
    private val served: Network,
    private val chain: TransactingChain?,
    private val dispatcher: CoroutineDispatcher,
    private val pollInterval: Duration,
) : ReceiptTracker {

    override fun observe(network: Network, hash: String): Flow<TransactionProgress> {
        if (network != served) return flowOf(NO_ENGINE)
        // Not a transaction hash: refused once, not polled as unproven forever.
        if (!HASH.matches(hash)) return flowOf(TransactionProgress.Unavailable(ReadUnavailable.REFUSED))
        val chain = chain ?: return flowOf(TransactionProgress.Unavailable(ReadUnavailable.NO_ENGINE))
        return flow {
            while (true) {
                val progress = try {
                    progressOf(chain, hash)
                } catch (e: Throwable) {
                    currentCoroutineContext().ensureActive() // as in ProvenReads
                    TransactionProgress.Unavailable(ReadUnavailable.UNPROVEN)
                }
                emit(progress)
                if (progress is TransactionProgress.Finalized) return@flow
                if (progress == NO_ENGINE) return@flow
                delay(pollInterval)
            }
        }.distinctUntilChanged().flowOn(dispatcher)
    }

    private fun progressOf(chain: TransactingChain, hash: String): TransactionProgress {
        val snapshot = chain.snapshot()
        whyUnreadable(snapshot)?.let { return TransactionProgress.Unavailable(it) }
        val json = chain.receipt(hash) ?: return TransactionProgress.Unavailable(ReadUnavailable.UNPROVEN)
        val receipt = EngineJson.receipt(json) ?: return TransactionProgress.Pending
        val finalized = (snapshot as EngineSnapshot.Running).finalizedBlock
        return if (finalized > 0 && receipt.blockNumber <= finalized) {
            TransactionProgress.Finalized(receipt.blockNumber, receipt.succeeded, receipt.logs)
        } else {
            TransactionProgress.Included(receipt.blockNumber, receipt.succeeded, receipt.logs)
        }
    }

    private companion object {
        val NO_ENGINE = TransactionProgress.Unavailable(ReadUnavailable.NO_ENGINE)
        val HASH = Regex("^0x[0-9a-fA-F]{64}$")
    }
}
