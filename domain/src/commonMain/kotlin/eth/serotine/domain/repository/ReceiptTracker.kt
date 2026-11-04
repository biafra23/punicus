package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import kotlinx.coroutines.flow.Flow

/**
 * The port for what became of a transaction once it was sent: its receipt, verified against
 * the receipts root of the block that includes it, and that block's finality, as the embedded
 * light client sees them. :data implements it over the Myotis engine. Never fails.
 */
interface ReceiptTracker {
    /**
     * Where the transaction [hash] (`0x` + 64 hex) on [network] stands, re-emitted as the
     * verified head moves until it is finalized; consecutive equal readings are not repeated.
     * A [network] no adapter serves here is refused ([ReadUnavailable.NO_ENGINE]).
     */
    fun observe(network: Network, hash: String): Flow<TransactionProgress>
}

/** One answer of [ReceiptTracker.observe]. */
sealed interface TransactionProgress {

    /** No verified block includes it yet. */
    data object Pending : TransactionProgress

    /**
     * Included in block [blockNumber], its receipt verified; [succeeded] false when it
     * reverted (its fee is paid all the same). [logs] are the receipt's own, in its order, part
     * of what the receipts root proves; null when the receipt carried none that could be read.
     */
    data class Included(
        val blockNumber: Long,
        val succeeded: Boolean,
        val logs: List<ReceiptLog>? = null,
    ) : TransactionProgress

    /** Included in block [blockNumber], which the beacon chain has finalized; [logs] as [Included]'s. */
    data class Finalized(
        val blockNumber: Long,
        val succeeded: Boolean,
        val logs: List<ReceiptLog>? = null,
    ) : TransactionProgress

    /** Where it stands cannot be verified right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : TransactionProgress
}

/**
 * One log of a verified receipt: the contract that emitted it ([address]), its [topics] (each
 * `0x` and 64 lower-case hex digits, the event's signature hash first) and its [data] (`0x` and
 * lower-case hex).
 */
data class ReceiptLog(val address: Address, val topics: List<String>, val data: String)
