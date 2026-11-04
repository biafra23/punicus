package eth.serotine.domain.usecase

import eth.serotine.domain.model.GPv2Settlement
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first

/**
 * A CoW order's cancellation on chain, quoted for review: [order]'s `invalidateOrder`, sent from
 * its account to its settlement contract, quoted as every transaction from an account held here is
 * ([call]: its nonce, fees, the most its fee can be and the native balance it leaves, its run as a
 * verified call). Once mined and finalized, nothing can fill the order (docs/swaps.md, *Proving an
 * order's end*). Only [QuoteCowCancellation] makes one.
 */
@ConsistentCopyVisibility
data class CowCancellationQuote internal constructor(val order: CowOrderRecord, val call: CallQuote)

/**
 * Quotes the cancellation on chain of a CoW order this wallet signed, for a user who does not
 * want to wait out its `validTo` (docs/swaps.md, *Proving an order's end*). An order whose end is
 * proven, or that a finalized block past its `validTo` already put out of reach, has nothing to
 * cancel; one whose cancellation is sent and not mined is not cancelled twice. On [network] only.
 * [clock] is in unix seconds.
 */
class QuoteCowCancellation(
    private val inputs: TransactionInputs,
    private val nativeBalances: NativeBalances,
    private val sentTransfers: SentTransfers,
    private val cowChain: CowChain,
    private val lightClient: LightClient,
    private val network: Network,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock)

    /** [order] (an order on this quote's network) cancelled at [level]'s fees. */
    suspend operator fun invoke(order: CowOrderRecord, level: FeeLevel): CowCancellationQuoting = cancellingReads {
        require(order.network == network) { "an order of ${order.network}, quoted on $network" }
        if (!order.live) return@cancellingReads CowCancellationQuoting.AlreadyEnded
        val from = order.account
        quoter.heldBack(from)?.let { return@cancellingReads it.asCancellationQuoting() }
        val native = async { nativeBalances.observeBalance(network, from).first() }
        val synced = lightClient.observeStatus(network).first() as? LightClientStatus.Synced
        val finalized = synced?.finalized ?: return@cancellingReads CowCancellationQuoting.Syncing
        val head = synced.headTimestamp ?: return@cancellingReads CowCancellationQuoting.Syncing
        // Past validTo, at a finalized block or at the head, no later block can fill it: nothing to
        // cancel, and a cancellation would land after it all the same.
        if (!order.canStillTake(finalized) || head > order.order.validTo) {
            return@cancellingReads CowCancellationQuoting.ValidityOver
        }
        // What the settlement holds of it, at the head: filled, or cancelled by a cancellation mined
        // and not finalized yet, is nothing to cancel again.
        when (val run = cowChain.call(network, order.settlement, GPv2Settlement.filledAmount(order.uid))) {
            is Simulation.Succeeded -> {
                val filled = GPv2Settlement.filledAmountOf(run.returnHex)
                    ?: return@cancellingReads CowCancellationQuoting.Unavailable(ReadUnavailable.UNPROVEN)
                if (!filled.isZero) return@cancellingReads CowCancellationQuoting.AlreadyEnded
            }
            is Simulation.Reverted ->
                return@cancellingReads CowCancellationQuoting.Unavailable(ReadUnavailable.UNPROVEN)
            Simulation.Refused -> return@cancellingReads CowCancellationQuoting.Refused
            is Simulation.Unavailable -> return@cancellingReads CowCancellationQuoting.Unavailable(run.reason)
        }
        when (val read = unminedOf(network, sentTransfers, inputs, from, clock)) {
            is Unmined.Read -> read.transfers
                .lastOrNull { (it.action as? SentAction.CowCancellation)?.uid == order.uid }
                ?.let { return@cancellingReads CowCancellationQuoting.CancellationPending(it.hash) }
            is Unmined.Unavailable ->
                return@cancellingReads CowCancellationQuoting.Unavailable(read.reason, QuoteFigure.NONCE)
        }
        val nativeBefore = when (val read = NativeRead.of(native.await())) {
            is NativeRead.Before -> read
            is NativeRead.NotRead -> return@cancellingReads read.refusal.asCancellationQuoting()
        }
        val data = GPv2Settlement.invalidateOrder(order.uid)
        when (val quoting = quoter.quote(from, order.settlement, UInt256.ZERO, data, level, nativeBefore) { true }) {
            is CallQuoting.Quoted -> CowCancellationQuoting.Quoted(CowCancellationQuote(order, quoting.quote))
            is CallQuoting.Refused -> quoting.refusal.asCancellationQuoting()
        }
    }
}

/**
 * Signs and sends a quoted cancellation, as every transaction from an account held here is
 * (`signAndSend`), recorded as the cancellation it is ([SentAction.CowCancellation]).
 */
class SendCowCancellation(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    /** [quote] signed with [unlock] and sent. */
    suspend operator fun invoke(quote: CowCancellationQuote, unlock: Unlock): TransferSending = signAndSend(
        signer,
        broadcaster,
        sentTransfers,
        quote.order.network,
        quote.call,
        SentAction.CowCancellation(quote.order.settlement, quote.order.uid),
        clock,
        unlock,
    )
}

/** The outcome of [QuoteCowCancellation]. */
sealed interface CowCancellationQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: CowCancellationQuote) : CowCancellationQuoting

    /**
     * The order's end is proven already, or the settlement holds it filled or cancelled at the head:
     * nothing to cancel.
     */
    data object AlreadyEnded : CowCancellationQuoting

    /** A finalized block, or the head, is past the order's `validTo`: it can fill no more, so nothing to cancel. */
    data object ValidityOver : CowCancellationQuoting

    /** A cancellation of the order, [hash], is sent and not mined yet. */
    data class CancellationPending(val hash: String) : CowCancellationQuoting

    /** The sender's balance of the network's currency cannot pay the most the fee can be. */
    data object InsufficientFunds : CowCancellationQuoting

    /** It cannot succeed for a reason other than the sender's funds ([detail], the EVM's words). */
    data class Infeasible(val detail: String) : CowCancellationQuoting

    /** The cancellation reverts, run as a verified call at [blockNumber]. */
    data class Reverts(val blockNumber: Long?) : CowCancellationQuoting

    /** The engine will not estimate or run it on this build. */
    data object Refused : CowCancellationQuoting

    /** The light client has no verified head, or no finalized block, yet. */
    data object Syncing : CowCancellationQuoting

    /** A verified figure could not be had right now, for [reason]; [figure] says which (null: not said). */
    data class Unavailable(val reason: ReadUnavailable, val figure: QuoteFigure? = null) : CowCancellationQuoting

    /** An earlier transaction from the sender, [hash], may still be on its way: retried, not replaced. */
    data class EarlierMaybeSent(val hash: String) : CowCancellationQuoting
}

/** This refusal of the transaction as the cancellation's. */
private fun CallRefusal.asCancellationQuoting(): CowCancellationQuoting = when (this) {
    CallRefusal.InsufficientFunds -> CowCancellationQuoting.InsufficientFunds
    is CallRefusal.Infeasible -> CowCancellationQuoting.Infeasible(detail)
    is CallRefusal.Reverts -> CowCancellationQuoting.Reverts(blockNumber)
    CallRefusal.Refused -> CowCancellationQuoting.Refused
    CallRefusal.Syncing -> CowCancellationQuoting.Syncing
    is CallRefusal.Unavailable -> CowCancellationQuoting.Unavailable(reason, figure)
    is CallRefusal.EarlierMaybeSent -> CowCancellationQuoting.EarlierMaybeSent(hash)
}
