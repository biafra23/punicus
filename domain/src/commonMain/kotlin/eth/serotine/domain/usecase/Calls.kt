package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * A transaction from an account this wallet holds, quoted for review: the unsigned
 * [transaction] at [level]'s fees, the most its fee can be ([maxFee], gas limit × fee cap), and
 * the native balance it leaves — [nativeBefore], what this wallet's own transactions not mined
 * yet may still take of it ([pendingCost]), and the least it can be after
 * ([nativeAfterAtLeast]). Every figure comes from verified state, each at its own block: the
 * balance at [balanceBlock], the fees from the history up to [feeBlock], and the transaction run
 * as a verified call from [from] at [simulationBlock].
 *
 * What a transfer quote ([TransferQuote]) and a Safe transaction's approval or execution
 * ([SafeApprovalQuote], [SafeExecutionQuote]) are built on. Only this module makes one.
 */
@ConsistentCopyVisibility
data class CallQuote internal constructor(
    val from: Address,
    val level: FeeLevel,
    val transaction: Eip1559Transaction,
    val maxFee: UInt256,
    val nativeBefore: UInt256,
    val pendingCost: UInt256,
    val nativeAfterAtLeast: UInt256,
    val balanceBlock: Long?,
    val feeBlock: Long,
    val simulationBlock: Long?,
) {
    /** The most the transaction can take of the account's native balance: its value and its fee. */
    val maxCost: UInt256
        get() = maxFee.plusOrNull(transaction.value) ?: maxFee
}

/** Why a transaction from an account this wallet holds was not quoted; nothing is signed. */
sealed interface CallRefusal {

    /** The sender's balance of the network's currency cannot pay the value and the most the fee can be. */
    data object InsufficientFunds : CallRefusal

    /** It cannot succeed for a reason other than the sender's funds ([detail], the EVM's words). */
    data class Infeasible(val detail: String) : CallRefusal

    /** It reverts, run as a verified call from the sender (at [blockNumber]). */
    data class Reverts(val blockNumber: Long?) : CallRefusal

    /** The engine will not estimate or run it on this build. */
    data object Refused : CallRefusal

    /** The light client has no verified head yet. */
    data object Syncing : CallRefusal

    /**
     * A verified figure could not be had right now, for [reason]; [figure] says which one (null:
     * not said), so that the screen can name it.
     */
    data class Unavailable(val reason: ReadUnavailable, val figure: QuoteFigure? = null) : CallRefusal

    /**
     * An earlier transaction from the sender, [hash], may still be on its way: its broadcast found
     * no peer to answer, so it may have reached one all the same ([SentTransfer.maybeSent]), and
     * it is not mined. It is retried with its own bytes ([RebroadcastTransfer], from its status),
     * never replaced by a new one, which could take the next nonce and pay twice.
     */
    data class EarlierMaybeSent(val hash: String) : CallRefusal
}

/** Which verified figure of a quote could not be had ([CallRefusal.Unavailable], [TransferQuoting.Unavailable]). */
enum class QuoteFigure {
    /** The sender's balance (of the network's currency, or of the token sent). */
    BALANCE,

    /** The sender's nonce: its pending or its mined transaction count. */
    NONCE,

    /** The fees, from the fee history. */
    FEES,

    /** The gas the transaction takes, the engine's estimate. */
    GAS,

    /** The transaction run as a verified call. */
    RUN,

    /** The allowance a token's spender has, which a swap needs. */
    ALLOWANCE,

    /** A swap's route: its pools and their quotes. */
    ROUTE,
}

/** A verified native balance a quote starts from, or why there is none. */
internal sealed interface NativeRead {

    /** [wei] at [blockNumber]. */
    data class Before(val wei: UInt256, val blockNumber: Long) : NativeRead

    /** No balance to quote on: [refusal]. */
    data class NotRead(val refusal: CallRefusal) : NativeRead

    companion object {
        /** What [reading] gives a quote. */
        fun of(reading: BalanceReading): NativeRead = when (reading) {
            is BalanceReading.Verified -> reading.wei.toUInt256OrNull()?.let { Before(it, reading.blockNumber) }
                ?: NotRead(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.BALANCE))
            // A balance last verified at an older head may have changed: nothing to quote on.
            BalanceReading.Syncing, is BalanceReading.LastKnown -> NotRead(CallRefusal.Syncing)
            is BalanceReading.Unavailable -> NotRead(CallRefusal.Unavailable(reading.reason, QuoteFigure.BALANCE))
        }
    }
}

/** What [CallQuoter.quote] gives. */
internal sealed interface CallQuoting {

    /** [quote], and the sender's own transactions not mined yet it was quoted behind ([unmined]). */
    data class Quoted(val quote: CallQuote, val unmined: List<SentTransfer>) : CallQuoting

    /** Not quoted: [refusal]. */
    data class Refused(val refusal: CallRefusal) : CallQuoting
}

/**
 * The quote of any transaction from an account this wallet holds, the part every kind of
 * transaction shares: the sender's next nonce, the fees at a [FeeLevel], the gas it takes, the
 * transaction run as a verified call from the sender, which must not revert, and the native
 * balance it needs. Nothing is quoted on anything less than verified state. On [network] only,
 * its chain id signed into the transaction.
 *
 * The sender's own transactions a peer took and no block has yet ([SentTransfers]) count: the
 * nonce is never below the last of them plus one, and what they may still take of the native
 * balance is not available to this one. Those mined since are forgotten. One a peer took more
 * than [RECORD_LIFETIME_SECONDS] ago and still not mined no longer counts, though its record
 * stays until the mined count passes it: kept counting, it would hold every later transaction
 * behind a nonce no block may ever take. Only one transaction per nonce can be mined, so taking
 * such a nonce again can never pay twice. One that may have been sent ([SentTransfer.maybeSent])
 * counts for neither, but while it is within that lifetime and not mined, nothing new is quoted
 * ([CallRefusal.EarlierMaybeSent], [heldBack]): that is checked first, from the record alone, and
 * only a record that holds one back asks the engine anything (the mined count, to see whether a
 * block took it since). [clock] is in unix seconds.
 *
 * Every other refusal comes once the reads it needs have answered. The engine's reads block in
 * native code and cannot be interrupted, so one already started runs to its end even when the
 * quote has refused meanwhile; [cancellingReads] cancels only what can be cancelled.
 */
internal class CallQuoter(
    private val inputs: TransactionInputs,
    private val sentTransfers: SentTransfers,
    private val network: Network,
    private val clock: () -> Long,
) {

    /**
     * Why nothing new may be quoted from [from] now, before any other read: an earlier transaction
     * of its, maybe sent and within its lifetime, that no block has taken
     * ([CallRefusal.EarlierMaybeSent]); or, when such a record is there, that the mined count it
     * needs could not be read. Null when nothing holds it back. The record alone answers when it
     * holds no such transaction; otherwise the one read asked is the mined count. A record that
     * cannot be read holds nothing back here: the quote then goes by the engine's nonce.
     */
    suspend fun heldBack(from: Address): CallRefusal? {
        val since = clock() - RECORD_LIFETIME_SECONDS
        val maybe = (sentTransfers.observe().first() as? SentTransferList.Loaded)?.transfers.orEmpty()
            .filter { it.network == network && it.account == from && it.maybeSent && it.sentAt >= since }
        if (maybe.isEmpty()) return null
        val count = when (val mined = inputs.minedNonce(network, from)) {
            is NonceReading.Read -> mined.nonce
            is NonceReading.Unavailable -> return CallRefusal.Unavailable(mined.reason, QuoteFigure.NONCE)
        }
        return maybe.firstOrNull { it.nonce >= count }?.let { CallRefusal.EarlierMaybeSent(it.hash) }
    }

    /**
     * [value] wei and [data] from [from] to [to] at [level]'s fees, on [native], the sender's
     * verified balance read before. [accepts] judges what the run returned: false is a revert
     * (a token's `transfer` that returns false refused as surely as one that reverts). [heldBack]
     * is asked first, before any other read starts.
     */
    suspend fun quote(
        from: Address,
        to: Address,
        value: UInt256,
        data: ByteArray,
        level: FeeLevel,
        native: NativeRead.Before,
        accepts: (returnHex: String) -> Boolean = { true },
    ): CallQuoting = cancellingReads {
        fun refused(refusal: CallRefusal) = CallQuoting.Refused(refusal)

        // An earlier transaction that may still be on its way stops this one before any read of
        // it starts: it is retried, not replaced.
        heldBack(from)?.let { return@cancellingReads refused(it) }

        // The nonces, the record and the fee history, alongside the estimate and then the run.
        val nonce = async { inputs.nonce(network, from) }
        val mined = async { inputs.minedNonce(network, from) }
        val recorded = async { sentTransfers.observe().first() }
        val fees = async { inputs.fees(network) }
        val estimated = async { inputs.estimateGas(network, from, to, value, data) }

        val expiredBefore = clock() - RECORD_LIFETIME_SECONDS
        val records = (recorded.await() as? SentTransferList.Loaded)?.transfers.orEmpty()
            .filter { it.network == network && it.account == from && it.sentAt >= expiredBefore }

        val gas = when (val estimate = estimated.await()) {
            // The engine's, priced under the fork the chain runs: on Sepolia's Amsterdam a plain
            // transfer takes less than the 21000 it did before (EIP-2780), so no floor is added.
            is GasEstimate.Estimated -> estimate.gas
            is GasEstimate.Reverted -> return@cancellingReads refused(CallRefusal.Reverts(blockNumber = null))
            // The EVM's words: about what the sender can pay, or something else it cannot do.
            is GasEstimate.Infeasible -> return@cancellingReads refused(
                if (aboutFunds(estimate.detail)) {
                    CallRefusal.InsufficientFunds
                } else {
                    CallRefusal.Infeasible(estimate.detail)
                },
            )
            is GasEstimate.Refused -> return@cancellingReads refused(CallRefusal.Refused)
            is GasEstimate.Unavailable ->
                return@cancellingReads refused(CallRefusal.Unavailable(estimate.reason, QuoteFigure.GAS))
        }
        val simulationBlock = when (val simulation = inputs.simulate(network, from, to, value, data)) {
            is Simulation.Succeeded -> if (accepts(simulation.returnHex)) {
                simulation.blockNumber
            } else {
                return@cancellingReads refused(CallRefusal.Reverts(simulation.blockNumber))
            }
            is Simulation.Reverted -> return@cancellingReads refused(CallRefusal.Reverts(simulation.blockNumber))
            Simulation.Refused -> return@cancellingReads refused(CallRefusal.Refused)
            is Simulation.Unavailable ->
                return@cancellingReads refused(CallRefusal.Unavailable(simulation.reason, QuoteFigure.RUN))
        }
        val quote = when (val reading = fees.await()) {
            is FeeReading.Quoted -> reading.quote
            is FeeReading.Unavailable ->
                return@cancellingReads refused(CallRefusal.Unavailable(reading.reason, QuoteFigure.FEES))
        }
        val pendingNonce = when (val reading = nonce.await()) {
            is NonceReading.Read -> reading.nonce
            is NonceReading.Unavailable ->
                return@cancellingReads refused(CallRefusal.Unavailable(reading.reason, QuoteFigure.NONCE))
        }
        val minedCount = when (val reading = mined.await()) {
            is NonceReading.Read -> reading.nonce
            is NonceReading.Unavailable ->
                return@cancellingReads refused(CallRefusal.Unavailable(reading.reason, QuoteFigure.NONCE))
        }
        // This wallet's own sends not mined yet, within their lifetime and taken by a peer: a
        // record that cannot be read leaves the engine's.
        val unmined = records.filter { it.nonce >= minedCount && !it.maybeSent }
        // Never below the mined count either: the two counts may come from two heads.
        val next = maxOf(pendingNonce, minedCount, (unmined.maxOfOrNull { it.nonce } ?: -1) + 1)
        sentTransfers.prune(AccountId(network, from), minedCount, clock() - DEPLOYMENT_LIFETIME_SECONDS)
        val insufficient = refused(CallRefusal.InsufficientFunds)
        val pendingCost = unmined.map { it.maxCost }.sumOrNull() ?: return@cancellingReads insufficient
        val available = native.wei.minusOrNull(pendingCost) ?: return@cancellingReads insufficient
        // An engine answer this large is no answer: the use case never throws over it.
        val unproven = refused(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.FEES))
        val maxFeePerGas = quote.maxFeePerGasOrNull(level) ?: return@cancellingReads unproven
        val maxFee = maxFeePerGas.timesOrNull(UInt256.of(gas)) ?: return@cancellingReads unproven
        val cost = maxFee.plusOrNull(value) ?: return@cancellingReads insufficient
        if (available < cost) return@cancellingReads insufficient
        CallQuoting.Quoted(
            CallQuote(
                from = from,
                level = level,
                transaction = Eip1559Transaction(
                    chainId = network.chainId,
                    nonce = next,
                    maxPriorityFeePerGas = quote.tip(level),
                    maxFeePerGas = maxFeePerGas,
                    gasLimit = gas,
                    to = to,
                    value = value,
                    data = data,
                ),
                maxFee = maxFee,
                nativeBefore = native.wei,
                pendingCost = pendingCost,
                nativeAfterAtLeast = available - cost,
                balanceBlock = native.blockNumber,
                feeBlock = quote.blockNumber,
                simulationBlock = simulationBlock,
            ),
            unmined,
        )
    }
}

/**
 * How long a recorded transaction that no block took holds its nonce and its cost (and, one that
 * may have been sent, holds new transactions back): three hours, geth's `txpool.lifetime`. That
 * lifetime evicts only queued transactions, those not executable yet; an executable one can stay
 * in a pool longer and still be mined, which is safe, since a nonce is mined once whichever
 * transaction takes it. Past it the record stays, holding nothing, until the sender's mined count
 * passes its nonce, so its status survives ([SentTransfers.prune]).
 */
const val RECORD_LIFETIME_SECONDS: Long = 3 * 60 * 60

/**
 * How long the record of a Safe's deployment is kept while its Safe is not listed: a week, for the
 * app to be opened again after it was sent and list it ([FollowSafeDeployments]). One that cannot
 * be listed by then is given up.
 */
const val DEPLOYMENT_LIFETIME_SECONDS: Long = 7 * 24 * 60 * 60

/**
 * [block] in a scope of its own whose children still running when it returns are cancelled: a
 * quote that refuses early stops the reads it no longer needs where they can be stopped (a
 * suspending read, a record's flow). An engine read blocks in native code, which cancelling
 * cannot interrupt, so the scope still waits for one already started: what holds a quote back is
 * checked before any starts ([CallQuoter.heldBack]).
 */
internal suspend fun <T> cancellingReads(block: suspend CoroutineScope.() -> T): T = coroutineScope {
    try {
        block()
    } finally {
        coroutineContext.cancelChildren()
    }
}

/** The sum of these numbers, or null when it overflows 256 bits. */
internal fun Iterable<UInt256>.sumOrNull(): UInt256? = fold(UInt256.ZERO as UInt256?) { sum, it -> sum?.plusOrNull(it) }

/** Whether an infeasible estimate's [detail] (geth's words) is about the sender's funds. */
private fun aboutFunds(detail: String): Boolean =
    "insufficient funds" in detail || "exceeds allowance" in detail

/**
 * Signs [quote]'s transaction with its sender's key as [unlock] opens it ([Signer]) and hands it
 * to the network: what every transaction this wallet sends goes through. What is signed is
 * exactly what was quoted. Once a peer took it, it is recorded as doing [action] ([SentTransfers]):
 * what keeps its nonce from a later transaction after a restart, and what its status shows. One
 * whose broadcast no peer answered may have reached one all the same: it is recorded as maybe
 * sent ([SentTransfer.maybeSent]), which holds new transactions back until it is retried, mined
 * or past its lifetime. One the engine rejected or refused is not recorded, so it holds no nonce:
 * a later quote may take the same one, and only one of the two can ever be mined. The broadcast
 * and the record are one step a cancellation cannot cut in half; a record that cannot be saved
 * does not undo the send.
 */
internal suspend fun signAndSend(
    signer: Signer,
    broadcaster: Broadcaster,
    sentTransfers: SentTransfers,
    network: Network,
    quote: CallQuote,
    action: SentAction,
    clock: () -> Long,
    unlock: Unlock,
): TransferSending {
    val transaction = quote.transaction
    val signature = when (val signed = signer.sign(quote.from, transaction.signingDigest(), unlock)) {
        is Signing.Signed -> signed.signature
        else -> return TransferSending.NotSigned(signed)
    }
    val transfer = SentTransfer(
        network = network,
        account = quote.from,
        nonce = transaction.nonce,
        signed = SignedTransfer(transaction.encodeSigned(signature)),
        action = action,
        maxCost = quote.maxCost,
        sentAt = clock(), // restamped once a peer took it
    )
    return withContext(NonCancellable) {
        when (val broadcast = broadcaster.send(network, transfer.signed.raw)) {
            is Broadcast.Sent -> sent(sentTransfers, transfer, clock)
            is Broadcast.Unavailable -> maybeSent(sentTransfers, broadcast, transfer, clock)
            else -> TransferSending.NotSent(broadcast, transfer)
        }
    }
}

/**
 * [transfer], which a peer took: recorded with the moment it was taken ([clock], unix seconds),
 * which its lifetime in the network's pools runs from, and [TransferSending.Sent] by its hash.
 */
internal suspend fun sent(sentTransfers: SentTransfers, transfer: SentTransfer, clock: () -> Long): TransferSending {
    sentTransfers.record(transfer.copy(sentAt = clock(), maybeSent = false))
    // The hash is the raw bytes' own: what the receipt is looked up by.
    return TransferSending.Sent(transfer.hash)
}

/**
 * [transfer], whose broadcast came back [unavailable]: no peer answered, yet one may have taken
 * it. Recorded as maybe sent ([SentTransfer.maybeSent]), from now ([clock], unix seconds), so that
 * a restart neither loses its bytes nor quotes a transaction past it; and [TransferSending.NotSent]
 * with that record, for a retry.
 */
internal suspend fun maybeSent(
    sentTransfers: SentTransfers,
    unavailable: Broadcast.Unavailable,
    transfer: SentTransfer,
    clock: () -> Long,
): TransferSending {
    val record = transfer.copy(sentAt = clock(), maybeSent = true)
    sentTransfers.record(record)
    return TransferSending.NotSent(unavailable, record)
}
