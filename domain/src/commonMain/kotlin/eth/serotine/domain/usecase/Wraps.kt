package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Weth9
import eth.serotine.domain.model.WrapDirection
import eth.serotine.domain.model.wrapDirection
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.CowOrderList
import eth.serotine.domain.repository.CowOrders
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first

/**
 * A wrap from an account this wallet holds on [network], quoted for review (docs/swaps.md,
 * *Wrapping ETH*): exactly [amount] of ETH into [weth] ([WrapDirection.WRAP], `deposit`) or of [weth] back into ETH
 * ([WrapDirection.UNWRAP], `withdraw`), one for one, through the pinned WETH9 whose code the
 * light client proved. The unsigned [transaction] at [level]'s fees, run as a verified call from
 * [from] at [simulationBlock], went through.
 *
 * The balances are verified, each at its block: the native one as every quote has it
 * ([nativeBefore], [pendingCost], [nativeAfterAtLeast], the value of a wrap included), and
 * [weth]'s ([wethBefore], at [tokenBalanceBlock]). For an unwrap, what this wallet's own
 * transactions not mined yet still move of [weth] ([pendingWeth]) and what its CoW orders may
 * still take of it ([heldByOrders]) are not this one's to unwrap; both are zero for a wrap.
 *
 * Only [QuoteWrap] makes one, and nothing copies it: what [SendWrap] signs is its [transaction].
 */
@ConsistentCopyVisibility
data class WrapQuote internal constructor(
    val network: Network,
    val from: Address,
    val direction: WrapDirection,
    val weth: Token,
    val amount: UInt256,
    val level: FeeLevel,
    val transaction: Eip1559Transaction,
    val maxFee: UInt256,
    val nativeBefore: UInt256,
    val pendingCost: UInt256,
    val nativeAfterAtLeast: UInt256,
    val wethBefore: UInt256,
    val pendingWeth: UInt256,
    val heldByOrders: UInt256,
    val balanceBlock: Long?,
    val tokenBalanceBlock: Long?,
    val feeBlock: Long,
    val simulationBlock: Long?,
) {
    /** The least the native balance is after it: for an unwrap, with the ETH it pays. */
    val nativeAfter: UInt256
        get() = when (direction) {
            WrapDirection.WRAP -> nativeAfterAtLeast
            WrapDirection.UNWRAP -> nativeAfterAtLeast + amount
        }

    /** [weth]'s balance after it, less what earlier transactions and CoW orders may still take. */
    val wethAfter: UInt256
        get() = when (direction) {
            WrapDirection.WRAP -> wethBefore + amount
            WrapDirection.UNWRAP -> wethBefore - pendingWeth - heldByOrders - amount
        }

    /** The quote of the transaction itself, which [SendWrap] signs. */
    internal val call: CallQuote
        get() = CallQuote(
            from = from,
            level = level,
            transaction = transaction,
            maxFee = maxFee,
            nativeBefore = nativeBefore,
            pendingCost = pendingCost,
            nativeAfterAtLeast = nativeAfterAtLeast,
            balanceBlock = balanceBlock,
            feeBlock = feeBlock,
            simulationBlock = simulationBlock,
        )

    /** What the account's record says this transaction does. */
    internal val action: SentAction
        get() = when (direction) {
            WrapDirection.WRAP -> SentAction.Wrap(weth, amount)
            WrapDirection.UNWRAP -> SentAction.Unwrap(weth, amount)
        }
}

/**
 * Quotes a wrap from an account this wallet holds (docs/swaps.md, *Wrapping ETH*): selling ETH for
 * the pinned WETH9's token, or that token for ETH, is no swap but WETH9's own `deposit` or
 * `withdraw`, one for one. The WETH9 is the one pinned with Uniswap's deployment, its code proven
 * ([UniswapV3Chain.venue]); a token at any other address, or a WETH9 not proven, is no wrap
 * ([WrapQuoting.NotAWrap]), said before anything but the venue is read, so a swap goes on to its
 * own quote, which says why it cannot be one where it cannot.
 *
 * The transaction is quoted as every transaction from an account held here is ([CallQuoter]) and
 * run as a verified call that must go through. What this wallet's own transactions not mined yet
 * move of the WETH, and what its CoW orders may still take of it ([CowOrders], [CowChain]), is not
 * an unwrap's to take; a record of orders that cannot be read unwraps nothing
 * ([WrapQuoting.CowOrdersUnreadable]). Nothing is quoted on anything less than verified state, and
 * the amount is in wei: prices never reach it. On [network] only. [clock] is in unix seconds.
 */
class QuoteWrap(
    private val uniswap: UniswapV3Chain,
    private val inputs: TransactionInputs,
    private val nativeBalances: NativeBalances,
    private val tokenBalances: TokenBalances,
    private val sentTransfers: SentTransfers,
    private val cowOrders: CowOrders,
    private val cowChain: CowChain,
    private val lightClient: LightClient,
    private val network: Network,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock)

    /** Exactly [amount] of [sell] for [buy] from [from], at [level]'s fees, when the two are a wrap. */
    suspend operator fun invoke(
        from: Address,
        sell: TransferAsset,
        buy: TransferAsset,
        amount: UInt256,
        level: FeeLevel,
    ): WrapQuoting = cancellingReads {
        // Neither side ETH, or both: no wrap, and nothing to read to say so.
        if ((sell == TransferAsset.Native) == (buy == TransferAsset.Native)) return@cancellingReads WrapQuoting.NotAWrap
        val deployment = when (val venue = uniswap.venue(network)) {
            is VenueReading.Verified -> venue.deployment
            // No WETH9 proven: no wrap, and the swap's quote says why Uniswap is no venue.
            VenueReading.NotPinned, is VenueReading.CodeMismatch -> return@cancellingReads WrapQuoting.NotAWrap
            is VenueReading.Unavailable -> return@cancellingReads WrapQuoting.Unavailable(venue.reason)
        }
        val direction = wrapDirection(sell, buy, deployment.weth9) ?: return@cancellingReads WrapQuoting.NotAWrap
        val weth = ((if (direction == WrapDirection.WRAP) buy else sell) as TransferAsset.Erc20Token).token
        if (amount.isZero) return@cancellingReads WrapQuoting.NothingToWrap
        // An earlier transaction maybe still on its way: refused before any other read.
        quoter.heldBack(from)?.let { return@cancellingReads it.asWrapQuoting() }
        val native = async { nativeBalances.observeBalance(network, from).first() }
        val wethBalance = async { tokenBalances.observeBalance(network, from, weth.address).first() }
        val records = async { unminedOf(network, sentTransfers, inputs, from, clock) }
        // Only an unwrap takes a token, which CoW orders may hold: a wrap reads neither.
        val unwrap = direction == WrapDirection.UNWRAP
        val orders = if (unwrap) async { cowOrders.observe().first() } else null
        val head = if (unwrap) async { lightClient.observeStatus(network).first() } else null

        val nativeBefore = when (val read = NativeRead.of(native.await())) {
            is NativeRead.Before -> read
            is NativeRead.NotRead -> return@cancellingReads read.refusal.asWrapQuoting()
        }
        var tokenBlock: Long? = null
        val wethBefore = when (val reading = wethBalance.await()) {
            is TokenBalanceReading.Verified -> UInt256.parse(reading.units.decimal)
                ?.also { tokenBlock = reading.blockNumber }
                ?: return@cancellingReads WrapQuoting.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.BALANCE)
            // A last-known balance is the light client's own past proof: never quoted on, as nothing is.
            TokenBalanceReading.Syncing, is TokenBalanceReading.LastKnown -> return@cancellingReads WrapQuoting.Syncing
            TokenBalanceReading.NotAToken -> return@cancellingReads WrapQuoting.NotAToken
            is TokenBalanceReading.Unavailable ->
                return@cancellingReads WrapQuoting.Unavailable(reading.reason, QuoteFigure.BALANCE)
        }
        (records.await() as? Unmined.Unavailable)?.let {
            return@cancellingReads WrapQuoting.Unavailable(it.reason, QuoteFigure.NONCE)
        }
        // What the account's CoW orders may still take of the WETH: not an unwrap's to take
        // (docs/swaps.md, *A signed order is live until it is proven dead*). A record that cannot
        // be read could hide one: nothing is unwrapped then.
        val held = when (val read = orders?.await()) {
            null -> UInt256.ZERO
            is CowOrderList.Loaded -> {
                val finalized = (head?.await() as? LightClientStatus.Synced)?.finalized
                read.orders.holding(network, from, weth.address, finalized, cowChain)
                    ?: return@cancellingReads WrapQuoting.InsufficientToken
            }
            CowOrderList.Unreadable -> return@cancellingReads WrapQuoting.CowOrdersUnreadable
        }
        when (direction) {
            WrapDirection.WRAP -> if (nativeBefore.wei < amount) return@cancellingReads WrapQuoting.InsufficientFunds
            WrapDirection.UNWRAP -> if (wethBefore < amount) return@cancellingReads WrapQuoting.InsufficientToken
        }

        val call = when (direction) {
            WrapDirection.WRAP -> Weth9.wrap(deployment.weth9, amount)
            WrapDirection.UNWRAP -> Weth9.unwrap(deployment.weth9, amount)
        }
        val (quote, unmined) = when (
            val quoting = quoter.quote(from, call.to, call.value, call.data, level, nativeBefore, Weth9::returnsNothing)
        ) {
            is CallQuoting.Quoted -> quoting
            is CallQuoting.Refused -> return@cancellingReads quoting.refusal.asWrapQuoting()
        }
        // The quote's own record of what is not mined, which its nonce stands on, has the last word.
        val pendingWeth = when (direction) {
            WrapDirection.WRAP -> UInt256.ZERO
            WrapDirection.UNWRAP -> unmined.mapNotNull { it.tokenAmount(weth.address) }.sumOrNull()
                ?: return@cancellingReads WrapQuoting.InsufficientToken
        }
        if (direction == WrapDirection.UNWRAP) {
            val available = wethBefore.minusOrNull(pendingWeth)?.minusOrNull(held)
            if (available == null || available < amount) return@cancellingReads WrapQuoting.InsufficientToken
        }
        WrapQuoting.Quoted(
            WrapQuote(
                network = network,
                from = from,
                direction = direction,
                weth = weth,
                amount = amount,
                level = level,
                transaction = quote.transaction,
                maxFee = quote.maxFee,
                nativeBefore = quote.nativeBefore,
                pendingCost = quote.pendingCost,
                nativeAfterAtLeast = quote.nativeAfterAtLeast,
                wethBefore = wethBefore,
                pendingWeth = pendingWeth,
                heldByOrders = held,
                balanceBlock = quote.balanceBlock,
                tokenBalanceBlock = tokenBlock,
                feeBlock = quote.feeBlock,
                simulationBlock = quote.simulationBlock,
            ),
        )
    }
}

/** This refusal of the transaction as the wrap's. */
private fun CallRefusal.asWrapQuoting(): WrapQuoting = when (this) {
    CallRefusal.InsufficientFunds -> WrapQuoting.InsufficientFunds
    is CallRefusal.Infeasible -> WrapQuoting.Infeasible(detail)
    is CallRefusal.Reverts -> WrapQuoting.Reverts(blockNumber)
    CallRefusal.Refused -> WrapQuoting.Refused
    CallRefusal.Syncing -> WrapQuoting.Syncing
    is CallRefusal.Unavailable -> WrapQuoting.Unavailable(reason, figure)
    is CallRefusal.EarlierMaybeSent -> WrapQuoting.EarlierMaybeSent(hash)
}

/** The outcome of [QuoteWrap]. */
sealed interface WrapQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: WrapQuote) : WrapQuoting

    /** The pair is not ETH against the pinned WETH9: a swap, if anything, quoted as one. */
    data object NotAWrap : WrapQuoting

    /** The amount is zero: there is nothing to wrap. */
    data object NothingToWrap : WrapQuoting

    /** The sender's balance of the network's currency cannot pay the amount wrapped and the most the fee can be. */
    data object InsufficientFunds : WrapQuoting

    /** The sender holds less WETH than the amount, with what its own sends and CoW orders still move. */
    data object InsufficientToken : WrapQuoting

    /** It cannot succeed for a reason other than the sender's funds ([detail], the EVM's words). */
    data class Infeasible(val detail: String) : WrapQuoting

    /** It reverts, run as a verified call from the sender (at [blockNumber]): nothing is signed. */
    data class Reverts(val blockNumber: Long?) : WrapQuoting

    /** WETH's address answers as no token. */
    data object NotAToken : WrapQuoting

    /** The engine will not estimate or run it on this build. */
    data object Refused : WrapQuoting

    /** The light client has no verified head yet. */
    data object Syncing : WrapQuoting

    /** A verified figure could not be had right now, for [reason]; [figure] says which (null: not said). */
    data class Unavailable(val reason: ReadUnavailable, val figure: QuoteFigure? = null) : WrapQuoting

    /** An earlier transaction from the sender, [hash], may still be on its way: retried, not replaced. */
    data class EarlierMaybeSent(val hash: String) : WrapQuoting

    /**
     * The record of the CoW orders signed here cannot be read: one selling WETH may still fill, so
     * nothing is unwrapped until it can be read.
     */
    data object CowOrdersUnreadable : WrapQuoting
}

/**
 * Signs a reviewed [WrapQuote] and hands it to the network, as every transaction from an account
 * held here is (`signAndSend`), recorded as the wrap or unwrap it is ([SentAction.Wrap],
 * [SentAction.Unwrap]). One for one, with no price to move, so it is not run again before it is
 * signed: what the review ran is what is signed. [clock] is in unix seconds.
 */
class SendWrap(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(quote: WrapQuote, unlock: Unlock): TransferSending =
        signAndSend(signer, broadcaster, sentTransfers, quote.network, quote.call, quote.action, clock, unlock)
}
