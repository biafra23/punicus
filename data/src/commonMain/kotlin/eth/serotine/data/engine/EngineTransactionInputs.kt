package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionInputs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay

/**
 * [TransactionInputs] over the engine's verified reads ([TransactingChain]): asked only while
 * the light client can verify reads at its head ([whyUnreadable]), on [dispatcher], and an
 * engine that fails, or a CancellationException its own code throws, is no answer — as for
 * every verified read ([ProvenReads]). A device without an engine ([chain] null) answers
 * [ReadUnavailable.NO_ENGINE].
 *
 * The fee history is asked again while it is cold ([fees]): every [feeRetryInterval], never
 * starting a read that could end past [feeWait] from the first, given the [engineFeeWait] the
 * engine itself may take, measured on [timeSource].
 *
 * Each verified mined count and fee quote is kept in [kept], with the head it was read at, for a
 * lenient draft after a restart (docs/lenient-mode.md); null keeps none. Keeping it never changes
 * the answer.
 */
class EngineTransactionInputs internal constructor(
    private val served: Network,
    private val chain: TransactingChain?,
    private val dispatcher: CoroutineDispatcher,
    private val feeRetryInterval: Duration = 2_500.milliseconds,
    private val feeWait: Duration = 15.seconds,
    private val engineFeeWait: Duration = 5.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val kept: KeptInputsFile? = null,
) : TransactionInputs {

    override suspend fun nonce(network: Network, account: Address): NonceReading =
        ask(network, NonceReading::Unavailable) { chain ->
        chain.pendingNonce(account)?.takeIf { it >= 0 }?.let(NonceReading::Read)
            ?: NonceReading.Unavailable(ReadUnavailable.UNPROVEN)
    }

    override suspend fun minedNonce(network: Network, account: Address): NonceReading {
        val (reading, head) = ask(network, { Answered(NonceReading.Unavailable(it), null) }) { chain ->
            val before = chain.readableHeadAt()
            val reading = chain.minedNonce(account)?.takeIf { it >= 0 }?.let(NonceReading::Read)
                ?: NonceReading.Unavailable(ReadUnavailable.UNPROVEN)
            // The engine reads at `latest`: the head the answer is of is known only while it did not move.
            Answered(reading, before?.takeIf { it == chain.readableHeadAt() })
        }
        if (reading is NonceReading.Read && head != null) {
            kept?.saveNonce(network, account, reading.nonce, head.block, head.asOf)
        }
        return reading
    }

    /**
     * The fee levels of the verified fee history. The engine's history is cold on its first request
     * after a start, and on one more than 120 s after the last: it builds ten blocks of it, waits
     * only 5 s for that, and answers nothing ("still being computed; retry shortly"), and a caller
     * right behind gets nothing at once ("the build just failed"). The build goes on, so a request a
     * few seconds later is answered. So an empty answer is asked again every [feeRetryInterval],
     * and [feeWait] is a ceiling: no read starts that could end past it, given the engine's own
     * [engineFeeWait]. The wait is a [delay] between the blocking reads, so a quote
     * cancelled meanwhile stops at the next one. Only an engine that answered nothing is asked
     * again: one that cannot verify reads, or none at all, is said at once.
     */
    override suspend fun fees(network: Network): FeeReading {
        val started = timeSource.markNow()
        while (true) {
            val (reading, head) = ask(network, { Answered(FeeReading.Unavailable(it), null) }) { chain ->
                val head = chain.readableHeadAt()
                val reading = chain.feeHistory(FEE_BLOCKS, EngineJson.PERCENTILES)
                    ?.let(EngineJson::feeQuote)
                    ?.let(FeeReading::Quoted)
                    ?: COLD
                Answered(reading, head)
            }
            // The engine's history may be older than the head: dated only when it ends at it.
            if (reading is FeeReading.Quoted) {
                kept?.saveFees(network, reading.quote, head?.takeIf { it.block == reading.quote.blockNumber }?.asOf)
            }
            if (reading !== COLD) return reading
            // No room for another wait and a read the engine may hold for its own wait: no answer.
            if (started.elapsedNow() + feeRetryInterval + engineFeeWait > feeWait) return COLD
            delay(feeRetryInterval)
        }
    }

    override suspend fun estimateGas(
        network: Network,
        from: Address,
        to: Address,
        value: UInt256,
        data: ByteArray,
    ): GasEstimate =
        ask(network, { GasEstimate.Unavailable(it) }) { chain ->
            when (val answer = chain.estimateGas(from, to, value.toDecimal(), data)) {
                // No transaction takes no gas: a non-positive estimate is no answer.
                is EstimateAnswer.Gas -> answer.gas.takeIf { it > 0 }?.let(GasEstimate::Estimated)
                    ?: GasEstimate.Unavailable(ReadUnavailable.UNPROVEN)
                is EstimateAnswer.Reverted -> GasEstimate.Reverted(answer.revertHex)
                is EstimateAnswer.Infeasible -> GasEstimate.Infeasible(answer.detail)
                is EstimateAnswer.Refused -> GasEstimate.Refused(answer.detail)
                is EstimateAnswer.Unavailable -> GasEstimate.Unavailable(ReadUnavailable.UNPROVEN, answer.detail)
            }
        }

    override suspend fun simulate(
        network: Network,
        from: Address,
        to: Address,
        value: UInt256,
        data: ByteArray,
    ): Simulation =
        ask(
            network,
            Simulation::Unavailable,
        ) { chain -> chain.callFrom(from, to, value.toDecimal(), data).toSimulation() }

    /**
     * [read] on [dispatcher] while reads can be verified; otherwise, or when the engine fails, [unavailable].
     * A [network] this adapter does not serve is [ReadUnavailable.NO_ENGINE]: never read from another chain.
     */
    private suspend fun <T> ask(
        network: Network,
        unavailable: (ReadUnavailable) -> T,
        read: (TransactingChain) -> T,
    ): T =
        if (network != served) unavailable(
                ReadUnavailable.NO_ENGINE,
            ) else chain.askVerified(dispatcher, unavailable, read)

    /** A [reading], and the verified [head] it was read at (null: none could be told). */
    private data class Answered<T>(val reading: T, val head: HeadAt?)

    /** A verified head: [block], produced at [asOf] (unix seconds; null when unknown). */
    private data class HeadAt(val block: Long, val asOf: Long?)

    /** The head reads are verified at right now, from the engine's snapshot, or null when none can be told. */
    private fun TransactingChain.readableHeadAt(): HeadAt? = (snapshot() as? EngineSnapshot.Running)
        ?.let { running -> running.readableHead?.let { HeadAt(it, running.headTimestamp) } }

    private companion object {
        /** The blocks the fee history covers: about two minutes of Sepolia. */
        const val FEE_BLOCKS = 10

        /** An engine that answered no fee history (or one it could not read): asked again, while time is left. */
        val COLD = FeeReading.Unavailable(ReadUnavailable.UNPROVEN)
    }
}
