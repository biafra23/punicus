package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceImpact
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.SwapRouter02
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.swapsWith
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.CowOrderList
import eth.serotine.domain.repository.CowOrders
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first

/**
 * A Uniswap swap from an account this wallet holds, quoted for review (docs/swaps.md, *The
 * transaction*; screen 23): exactly [amountIn] of [sell] for at least [minimumOut] of [buy],
 * paid to [from], along [route] through the factory's [pools] and the router at [router], which
 * wraps and unwraps ETH as [weth9].
 *
 * - [quotedOut] is QuoterV2's verified quote of the route, at [quoteBlock]; [minimumOut] is that
 *   less [tolerance], rounded down: the pool's own answer for this exact amount, never a price.
 * - [returnedOut] is what the exact transaction returned, run as a verified call from [from] at
 *   [simulationBlock]: what the user receives if nothing moves before it is mined.
 * - [deadline] is the verified head's time plus [SWAP_DEADLINE_SECONDS] (unix seconds), never the
 *   device's clock: the router refuses the swap after it.
 * - The balances are verified, each at its block: the native one as every quote has it
 *   ([nativeBefore], [pendingCost], [nativeAfterAtLeast]); the token sold, when it is one, before
 *   ([sellBefore], at [tokenBalanceBlock]), what this wallet's own transactions not mined yet
 *   still move of it ([pendingSell]), what its CoW orders may still take of it ([heldByOrders])
 *   and after ([sellAfter]); the token bought, when it is one,
 *   before ([buyBefore], at [buyBalanceBlock]).
 * - [netOfFees]: whether the route was chosen net of the fees (`RouteFinding.Found.netOfFees`).
 * - [spotOut] is what the route returns at its pools' spot prices, less their fees, and
 *   [priceImpact] how far [quotedOut] falls below it; null when a pool's price could not be read.
 *
 * Only [QuoteSwap] makes one, and nothing copies it: what [SendSwap] signs is its [transaction].
 */
@ConsistentCopyVisibility
data class SwapQuote internal constructor(
    val network: Network,
    val from: Address,
    val sell: TransferAsset,
    val buy: TransferAsset,
    val amountIn: UInt256,
    val tolerance: SlippageTolerance,
    val level: FeeLevel,
    val route: SwapRoute,
    val pools: List<Address>,
    val router: Address,
    val weth9: Address,
    val quotedOut: UInt256,
    val quoteBlock: Long?,
    val netOfFees: Boolean,
    val spotOut: UInt256?,
    val minimumOut: UInt256,
    val returnedOut: UInt256,
    val deadline: Long,
    val transaction: Eip1559Transaction,
    val maxFee: UInt256,
    val nativeBefore: UInt256,
    val pendingCost: UInt256,
    val nativeAfterAtLeast: UInt256,
    val sellBefore: UInt256?,
    val pendingSell: UInt256?,
    val heldByOrders: UInt256?,
    val sellAfter: UInt256?,
    val buyBefore: UInt256?,
    val balanceBlock: Long?,
    val tokenBalanceBlock: Long?,
    val buyBalanceBlock: Long?,
    val feeBlock: Long,
    val simulationBlock: Long?,
) {
    /** The price impact in basis points ([PriceImpact.basisPoints]), or null when [spotOut] is not known. */
    val priceImpact: Int?
        get() = spotOut?.let { PriceImpact.basisPoints(it, quotedOut) }

    /** The quote of the transaction itself, which [SendSwap] signs. */
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
    internal val action: SentAction.Swap
        get() = SentAction.Swap(
            router,
            sell,
            amountIn,
            buy,
            minimumOut,
            weth = weth9.takeIf { buy == TransferAsset.Native },
        )
}

/**
 * How long an account's swap may wait to be mined: ten minutes past the verified head's time it
 * was quoted at (docs/swaps.md, *The transaction*). A Safe's has its own, longer one.
 */
const val SWAP_DEADLINE_SECONDS: Long = 10 * 60

/**
 * Quotes a Uniswap swap from an account this wallet holds (docs/swaps.md, *Uniswap*): the best
 * route ([FindUniswapRoute], net of the fees), the signed minimum ([SlippageTolerance.minimumOf]
 * of its quote), the allowance the router needs, and the swap's transaction, quoted as every
 * transaction from an account held here is ([CallQuoter]) and run as a verified call that must
 * return at least the minimum. Nothing is quoted on anything less than verified state, and the
 * amount is in the sold asset's smallest unit: prices never reach a swap.
 *
 * A token sold needs the router's allowance for the whole amount: one that falls short is
 * [SwapQuoting.NeedsApproval] (the approval is its own transaction, [QuoteTokenApproval]), and one
 * whose approval is sent but not mined is [SwapQuoting.ApprovalPending]: the swap is quoted only
 * once the approval's receipt is in. One swap of a token is in flight at a time
 * ([SwapQuoting.EarlierSwapPending]): the allowance it counts on is its own. What this wallet's
 * own transactions not mined yet move of the token sold is not this swap's to sell, nor what its
 * CoW orders may still take of it ([CowOrders], until a finalized block is past an order's
 * `validTo`, or the settlement reads the order done at the head, [CowChain]); a record of orders
 * that cannot be read sells nothing ([SwapQuoting.CowOrdersUnreadable]). Every refusal
 * that holds whatever the route is said before the route is quoted, and every one that holds
 * whatever the allowance before an approval is asked for. On [network] only. [clock] is in unix
 * seconds.
 */
class QuoteSwap(
    private val findRoute: FindUniswapRoute,
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

    /** Exactly [amountIn] of [sell] for [buy] from [from], at [tolerance], at [level]'s fees. */
    suspend operator fun invoke(
        from: Address,
        sell: TransferAsset,
        buy: TransferAsset,
        amountIn: UInt256,
        tolerance: SlippageTolerance,
        level: FeeLevel,
    ): SwapQuoting = cancellingReads {
        if (amountIn.isZero) return@cancellingReads SwapQuoting.NothingToSwap
        // An earlier transaction maybe still on its way: refused before any other read.
        quoter.heldBack(from)?.let { return@cancellingReads it.asSwapQuoting() }
        val soldToken = (sell as? TransferAsset.Erc20Token)?.token
        val boughtToken = (buy as? TransferAsset.Erc20Token)?.token
        // What refuses the swap whatever its route is read first, side by side: the route's
        // quotes, the costly reads, come only once none of it does.
        val native = async { nativeBalances.observeBalance(network, from).first() }
        val soldBalance = soldToken?.let { async { tokenBalances.observeBalance(network, from, it.address).first() } }
        val boughtBalance = boughtToken?.let { async { tokenBalances.observeBalance(
            network,
            from,
            it.address,
        ).first() } }
        val head = async { lightClient.observeStatus(network).first() }
        val records = async { unminedOf(network, sentTransfers, inputs, from, clock) }
        val orders = soldToken?.let { async { cowOrders.observe().first() } }
        val deployment = when (val venue = findRoute.venue(network)) {
            is VenueReading.Verified -> venue.deployment
            VenueReading.NotPinned -> return@cancellingReads SwapQuoting.NoVenue(contract = null)
            is VenueReading.CodeMismatch -> return@cancellingReads SwapQuoting.NoVenue(venue.contract)
            is VenueReading.Unavailable ->
                return@cancellingReads SwapQuoting.Unavailable(venue.reason, QuoteFigure.ROUTE)
        }
        if (!sell.swapsWith(buy, deployment)) return@cancellingReads SwapQuoting.NotASwap
        val router = deployment.swapRouter02

        val nativeBefore = when (val read = NativeRead.of(native.await())) {
            is NativeRead.Before -> read
            is NativeRead.NotRead -> return@cancellingReads read.refusal.asSwapQuoting()
        }
        var tokenBlock: Long? = null
        val sellBefore = when (val read = soldBalance?.await()?.let(::tokenUnits)) {
            null -> null
            is TokenRead.Units -> read.units.also { tokenBlock = read.blockNumber }
            is TokenRead.NotRead -> return@cancellingReads read.refusal
        }
        var buyBlock: Long? = null
        val buyBefore = when (val read = boughtBalance?.await()?.let(::tokenUnits)) {
            null -> null
            is TokenRead.Units -> read.units.also { buyBlock = read.blockNumber }
            is TokenRead.NotRead -> return@cancellingReads read.refusal
        }
        val earlier = when (val read = records.await()) {
            is Unmined.Read -> read.transfers
            is Unmined.Unavailable -> return@cancellingReads SwapQuoting.Unavailable(read.reason, QuoteFigure.NONCE)
        }
        // What the account's CoW orders may still take of the token: not this swap's to sell, or
        // the order and the swap could both spend it (docs/swaps.md, *A signed order is live until
        // it is proven dead*). A record that cannot be read could hide one: nothing is sold then.
        val held = when (val read = orders?.await()) {
            null -> UInt256.ZERO
            is CowOrderList.Loaded -> soldToken?.let {
                val finalized = (head.await() as? LightClientStatus.Synced)?.finalized
                read.orders.holding(network, from, it.address, finalized, cowChain)
            } ?: return@cancellingReads SwapQuoting.InsufficientToken
            CowOrderList.Unreadable -> return@cancellingReads SwapQuoting.CowOrdersUnreadable
        }
        if (sellBefore != null && soldToken != null) {
            val moving = earlier.mapNotNull { it.tokenAmount(soldToken.address) }.sumOrNull()
                ?.plusOrNull(held)
                ?: return@cancellingReads SwapQuoting.InsufficientToken
            if ((sellBefore.minusOrNull(moving) ?: UInt256.ZERO) < amountIn) {
                return@cancellingReads SwapQuoting.InsufficientToken
            }
        }
        if (soldToken == null && nativeBefore.wei < amountIn) return@cancellingReads SwapQuoting.InsufficientFunds
        val deadline = when (val status = head.await()) {
            // A verified head whose time is not known yet gives no deadline: no figure of the swap's
            // own is missing, so none is named.
            is LightClientStatus.Synced -> status.headTimestamp?.plus(SWAP_DEADLINE_SECONDS)
                ?: return@cancellingReads SwapQuoting.Unavailable(ReadUnavailable.UNPROVEN, figure = null)
            else -> return@cancellingReads SwapQuoting.Syncing
        }
        if (soldToken != null) {
            earlier.lastOrNull { it.sells(soldToken) }
                ?.let { return@cancellingReads SwapQuoting.EarlierSwapPending(it.hash) }
            earlier.lastOrNull { it.approves(soldToken, router) }
                ?.let { return@cancellingReads SwapQuoting.ApprovalPending(it.hash) }
        }

        val found = when (val finding = findRoute(network, sell, buy, amountIn, RouteRanking.NET_OF_FEES)) {
            is RouteFinding.Found -> finding
            else -> return@cancellingReads finding.asSwapQuoting()
        }
        val minimum = tolerance.minimumOf(found.quote.amountOut)
        if (minimum.isZero) return@cancellingReads SwapQuoting.TooSmall
        if (soldToken != null) {
            val allowance = when (val read = readAllowance(network, inputs, from, soldToken, router)) {
                is AllowanceRead.Read -> read.allowance
                AllowanceRead.NotAToken -> return@cancellingReads SwapQuoting.NotAToken
                AllowanceRead.Refused -> return@cancellingReads SwapQuoting.Refused
                is AllowanceRead.Unavailable ->
                    return@cancellingReads SwapQuoting.Unavailable(read.reason, QuoteFigure.ALLOWANCE)
            }
            if (allowance < amountIn) {
                return@cancellingReads SwapQuoting.NeedsApproval(soldToken, router, allowance)
            }
        }
        val swap = SwapRouter02.swap(found.deployment, sell, buy, found.quote.route, amountIn, minimum, from, deadline)
        val buysEther = buy == TransferAsset.Native
        var returned: UInt256? = null
        // The run must return the swap's amount, and that at least the minimum (the router checks
        // it too, but a run that says less is no swap to sign).
        val accepts: (String) -> Boolean = { hex ->
            returned = SwapRouter02.amountOutOf(hex, buysEther)
            returned?.let { it >= minimum } == true
        }
        val (call, unmined) = when (
            val quoting = quoter.quote(from, swap.to, swap.value, swap.data, level, nativeBefore, accepts)
        ) {
            is CallQuoting.Quoted -> quoting
            is CallQuoting.Refused -> return@cancellingReads quoting.refusal.asSwapQuoting()
        }
        // The quote's own record of what is not mined, which its nonce stands on, has the last word.
        val pendingSell = soldToken?.let { sold ->
            unmined.mapNotNull { it.tokenAmount(sold.address) }.sumOrNull()
                ?: return@cancellingReads SwapQuoting.InsufficientToken
        }
        val sellAvailable = sellBefore?.let { before ->
            before.minusOrNull(pendingSell ?: UInt256.ZERO)?.minusOrNull(held)?.takeIf { it >= amountIn }
                ?: return@cancellingReads SwapQuoting.InsufficientToken
        }
        SwapQuoting.Quoted(
            SwapQuote(
                network = network,
                from = from,
                sell = sell,
                buy = buy,
                amountIn = amountIn,
                tolerance = tolerance,
                level = level,
                route = found.quote.route,
                pools = found.pools,
                router = router,
                weth9 = found.deployment.weth9,
                quotedOut = found.quote.amountOut,
                quoteBlock = found.blockNumber,
                netOfFees = found.netOfFees,
                spotOut = found.spotOut,
                minimumOut = minimum,
                returnedOut = checkNotNull(returned) { "an accepted run returned the amount" },
                deadline = deadline,
                transaction = call.transaction,
                maxFee = call.maxFee,
                nativeBefore = call.nativeBefore,
                pendingCost = call.pendingCost,
                nativeAfterAtLeast = call.nativeAfterAtLeast,
                sellBefore = sellBefore,
                pendingSell = pendingSell,
                heldByOrders = held.takeIf { soldToken != null },
                sellAfter = sellAvailable?.let { it - amountIn },
                buyBefore = buyBefore,
                balanceBlock = call.balanceBlock,
                tokenBalanceBlock = tokenBlock,
                buyBalanceBlock = buyBlock,
                feeBlock = call.feeBlock,
                simulationBlock = call.simulationBlock,
            ),
        )
    }
}

/** What [unminedOf] read. */
internal sealed interface Unmined {
    data class Read(val transfers: List<SentTransfer>) : Unmined
    data class Unavailable(val reason: ReadUnavailable) : Unmined
}

/**
 * [from]'s own transactions a peer took that no block has yet, within their lifetime, as
 * [CallQuoter] counts them: the record read once, and the mined count asked only when the record
 * holds one. A record that cannot be read holds none, as for every quote.
 */
internal suspend fun unminedOf(
    network: Network,
    sentTransfers: SentTransfers,
    inputs: TransactionInputs,
    from: Address,
    clock: () -> Long,
): Unmined {
    val since = clock() - RECORD_LIFETIME_SECONDS
    val mine = (sentTransfers.observe().first() as? SentTransferList.Loaded)?.transfers.orEmpty()
        .filter { it.network == network && it.account == from && !it.maybeSent && it.sentAt >= since }
    if (mine.isEmpty()) return Unmined.Read(emptyList())
    val mined = when (val read = inputs.minedNonce(network, from)) {
        is NonceReading.Read -> read.nonce
        is NonceReading.Unavailable -> return Unmined.Unavailable(read.reason)
    }
    return Unmined.Read(mine.filter { it.nonce >= mined })
}

/** Whether this transaction is a swap that sells [token]. */
private fun SentTransfer.sells(token: Token): Boolean =
    ((action as? SentAction.Swap)?.sell as? TransferAsset.Erc20Token)?.token?.address == token.address

/** Whether this transaction is an approval of [spender] for [token]. */
private fun SentTransfer.approves(token: Token, spender: Address): Boolean =
    (action as? SentAction.TokenApproval)?.let { it.token.address == token.address && it.spender == spender } == true

/** What [readAllowance] read: the [Read.allowance], or why there is none. */
internal sealed interface AllowanceRead {
    data class Read(val allowance: UInt256) : AllowanceRead

    /** The token's address answered as no token: a revert, or no word. */
    data object NotAToken : AllowanceRead

    data object Refused : AllowanceRead

    data class Unavailable(val reason: ReadUnavailable) : AllowanceRead
}

/** [token]'s allowance of [spender] over [owner]'s tokens, as a verified call from [owner]. */
internal suspend fun readAllowance(
    network: Network,
    inputs: TransactionInputs,
    owner: Address,
    token: Token,
    spender: Address,
): AllowanceRead = readAllowance(owner, token.address, spender) { to, data ->
    inputs.simulate(network, owner, to, UInt256.ZERO, data)
}

/** The allowance of [spender] over [owner]'s tokens at [token], as [call] runs `allowance` verified. */
internal suspend fun readAllowance(
    owner: Address,
    token: Address,
    spender: Address,
    call: suspend (to: Address, data: ByteArray) -> Simulation,
): AllowanceRead =
    when (val run = call(token, Erc20.allowanceData(owner, spender))) {
        is Simulation.Succeeded -> Erc20.allowanceOf(run.returnHex)?.let { AllowanceRead.Read(it) }
            ?: AllowanceRead.NotAToken
        is Simulation.Reverted -> AllowanceRead.NotAToken
        Simulation.Refused -> AllowanceRead.Refused
        is Simulation.Unavailable -> AllowanceRead.Unavailable(run.reason)
    }

/** A token balance a swap quote starts from, or why there is none. */
private sealed interface TokenRead {
    data class Units(val units: UInt256, val blockNumber: Long?) : TokenRead
    data class NotRead(val refusal: SwapQuoting) : TokenRead
}

private fun tokenUnits(reading: TokenBalanceReading): TokenRead = when (reading) {
    is TokenBalanceReading.Verified -> UInt256.parse(reading.units.decimal)
        ?.let { TokenRead.Units(it, reading.blockNumber) }
        ?: TokenRead.NotRead(SwapQuoting.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.BALANCE))
    // A last-known balance is the light client's own past proof: never quoted on, as nothing is.
    TokenBalanceReading.Syncing, is TokenBalanceReading.LastKnown -> TokenRead.NotRead(SwapQuoting.Syncing)
    TokenBalanceReading.NotAToken -> TokenRead.NotRead(SwapQuoting.NotAToken)
    is TokenBalanceReading.Unavailable ->
        TokenRead.NotRead(SwapQuoting.Unavailable(reading.reason, QuoteFigure.BALANCE))
}

/** This refusal of the transaction as the swap's. */
private fun CallRefusal.asSwapQuoting(): SwapQuoting = when (this) {
    CallRefusal.InsufficientFunds -> SwapQuoting.InsufficientFunds
    is CallRefusal.Infeasible -> SwapQuoting.Infeasible(detail)
    is CallRefusal.Reverts -> SwapQuoting.Reverts(blockNumber)
    CallRefusal.Refused -> SwapQuoting.Refused
    CallRefusal.Syncing -> SwapQuoting.Syncing
    is CallRefusal.Unavailable -> SwapQuoting.Unavailable(reason, figure)
    is CallRefusal.EarlierMaybeSent -> SwapQuoting.EarlierMaybeSent(hash)
}

/** A route that was not found, as the swap's refusal. */
private fun RouteFinding.asSwapQuoting(): SwapQuoting = when (this) {
    is RouteFinding.Found -> error("a route found is no refusal")
    RouteFinding.NotASwap -> SwapQuoting.NotASwap
    RouteFinding.NoRoute -> SwapQuoting.NoRoute
    RouteFinding.NotPinned -> SwapQuoting.NoVenue(contract = null)
    is RouteFinding.CodeMismatch -> SwapQuoting.NoVenue(contract)
    is RouteFinding.Unavailable -> SwapQuoting.Unavailable(reason, QuoteFigure.ROUTE)
}

/** The outcome of [QuoteSwap]. */
sealed interface SwapQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: SwapQuote) : SwapQuoting

    /** The amount is zero: there is nothing to swap. */
    data object NothingToSwap : SwapQuoting

    /** The two assets are one token on Uniswap: the same one, or ETH and WETH, a wrap. */
    data object NotASwap : SwapQuoting

    /** No pool takes the pair, directly or through WETH, or none takes this amount. */
    data object NoRoute : SwapQuoting

    /**
     * Uniswap is no venue here: no deployment is pinned for the network ([contract] null), or the
     * code at [contract], one of the pinned contracts, is not its pinned code.
     */
    data class NoVenue(val contract: Address?) : SwapQuoting

    /**
     * The router may take only [allowance] of [token], less than the amount: an approval of
     * [spender] for the amount comes first, its own transaction ([QuoteTokenApproval]).
     */
    data class NeedsApproval(val token: Token, val spender: Address, val allowance: UInt256) : SwapQuoting

    /** The approval this swap needs, [hash], is sent and not mined yet: the swap waits for its receipt. */
    data class ApprovalPending(val hash: String) : SwapQuoting

    /**
     * An earlier swap of the same token, [hash], is sent and not mined yet: this one waits for it.
     * Quoted beside it, this one could count on an allowance that one is about to use up, and
     * revert once it is mined, its fee paid all the same.
     */
    data class EarlierSwapPending(val hash: String) : SwapQuoting

    /** The quote less the tolerance rounds down to nothing: a swap that may return nothing is not signed. */
    data object TooSmall : SwapQuoting

    /** The sender's balance of the network's currency cannot pay the amount and the most the fee can be. */
    data object InsufficientFunds : SwapQuoting

    /** The sender holds less of the token sold than the amount, with what its own sends still move. */
    data object InsufficientToken : SwapQuoting

    /** It cannot succeed for a reason other than the sender's funds ([detail], the EVM's words). */
    data class Infeasible(val detail: String) : SwapQuoting

    /**
     * The swap reverts, or returns less than its minimum, run as a verified call from the sender
     * (at [blockNumber]): nothing is signed.
     */
    data class Reverts(val blockNumber: Long?) : SwapQuoting

    /** A token's address answers as no token. */
    data object NotAToken : SwapQuoting

    /** The engine will not estimate or run it on this build. */
    data object Refused : SwapQuoting

    /** The light client has no verified head yet. */
    data object Syncing : SwapQuoting

    /** A verified figure could not be had right now, for [reason]; [figure] says which (null: not said). */
    data class Unavailable(val reason: ReadUnavailable, val figure: QuoteFigure? = null) : SwapQuoting

    /** An earlier transaction from the sender, [hash], may still be on its way: retried, not replaced. */
    data class EarlierMaybeSent(val hash: String) : SwapQuoting

    /**
     * The record of the CoW orders signed here cannot be read: one may still fill, so no token is
     * sold until it can be read.
     */
    data object CowOrdersUnreadable : SwapQuoting
}

/**
 * Signs a reviewed [SwapQuote] and hands it to the network, as every transaction from an account
 * held here is (`signAndSend`), recorded as the swap it is ([SentAction.Swap]). Its exact
 * transaction is run again at the current head first (docs/swaps.md, *What both venues share*):
 * when it no longer returns the minimum (the market moved past the tolerance, or the deadline
 * passed), nothing is signed and the review quotes again ([SwapSending.NoLongerMet]). [clock] is
 * in unix seconds.
 */
class SendSwap(
    private val inputs: TransactionInputs,
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(quote: SwapQuote, unlock: Unlock): SwapSending {
        val tx = quote.transaction
        val buysEther = quote.buy == TransferAsset.Native
        when (val run = inputs.simulate(quote.network, quote.from, tx.to, tx.value, tx.data)) {
            is Simulation.Succeeded -> {
                val out = SwapRouter02.amountOutOf(run.returnHex, buysEther)
                if (out == null || out < quote.minimumOut) return SwapSending.NoLongerMet(run.blockNumber)
            }
            is Simulation.Reverted -> return SwapSending.NoLongerMet(run.blockNumber)
            Simulation.Refused -> return SwapSending.NotRun(ReadUnavailable.REFUSED)
            is Simulation.Unavailable -> return SwapSending.NotRun(run.reason)
        }
        return SwapSending.Done(
            signAndSend(signer, broadcaster, sentTransfers, quote.network, quote.call, quote.action, clock, unlock),
        )
    }
}

/** The outcome of [SendSwap]. */
sealed interface SwapSending {

    /** It was run again and signed: what came of it ([sending]). */
    data class Done(val sending: TransferSending) : SwapSending

    /**
     * Run again at [blockNumber], it reverted or returned less than its minimum: nothing was signed,
     * and the swap is quoted again.
     */
    data class NoLongerMet(val blockNumber: Long?) : SwapSending

    /** It could not be run again right now, for [reason]: nothing was signed. */
    data class NotRun(val reason: ReadUnavailable) : SwapSending
}

/**
 * An approval quoted for review (docs/swaps.md, *What both venues share*): the unsigned
 * [transaction] from [from] that sets [spender]'s allowance over [token] to [amount], at
 * [level]'s fees, from [allowance] now. [resetsFirst] when [amount] is zero although a swap needs
 * more: the token refuses to move one non-zero allowance to another (USDT's kind), as the run of
 * the approval of the whole amount showed, so it is set to zero first and the whole amount is
 * approved once that is mined. The native balance is every quote's ([nativeBefore],
 * [pendingCost], [nativeAfterAtLeast]); each figure comes from verified state at its block.
 *
 * Only [QuoteTokenApproval] makes one: what [SendTokenApproval] signs is its [transaction].
 */
@ConsistentCopyVisibility
data class TokenApprovalQuote internal constructor(
    val network: Network,
    val from: Address,
    val token: Token,
    val spender: Address,
    val amount: UInt256,
    val allowance: UInt256,
    val resetsFirst: Boolean,
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
}

/**
 * Quotes the approval a swap needs before it: [token]'s `approve(spender, amount)` from an account
 * held here, for the exact amount (owner ruling, 2026-10-19, #69's decision 3), as every
 * transaction from it is quoted ([CallQuoter]). An allowance that already covers the amount needs
 * none ([ApprovalQuoting.AlreadyAllowed]). A token whose `approve` of the amount reverts (or
 * returns false) while an allowance is set is quoted for zero first ([TokenApprovalQuote.resetsFirst]),
 * as docs/swaps.md has it for USDT's kind. The run cannot tell that kind from a token that refuses
 * every approval for another reason (one paused, say), whose zero approval then goes through and
 * the next approval reverts all the same: a fee spent, said by that next review, never a swap
 * signed. An approval waits for an earlier swap of the token, or an earlier approval of it, not
 * mined yet. On [network] only. [clock] is in unix seconds.
 */
class QuoteTokenApproval(
    private val inputs: TransactionInputs,
    private val nativeBalances: NativeBalances,
    private val sentTransfers: SentTransfers,
    private val network: Network,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock)

    /** [spender]'s allowance over [from]'s [token] set to [amount] (not zero), at [level]'s fees. */
    suspend operator fun invoke(
        from: Address,
        token: Token,
        spender: Address,
        amount: UInt256,
        level: FeeLevel,
    ): ApprovalQuoting = cancellingReads {
        require(!amount.isZero) { "an approval a swap needs is of something" }
        quoter.heldBack(from)?.let { return@cancellingReads it.asApprovalQuoting() }
        val native = async { nativeBalances.observeBalance(network, from).first() }
        // An approval sets the allowance, whatever it was: one sent beside an earlier swap of the
        // token could take from that swap what it counts on, and one beside an earlier approval
        // is that approval again.
        when (val read = unminedOf(network, sentTransfers, inputs, from, clock)) {
            is Unmined.Read -> {
                read.transfers.lastOrNull { it.sells(token) }
                    ?.let { return@cancellingReads ApprovalQuoting.EarlierSwapPending(it.hash) }
                read.transfers.lastOrNull { it.approves(token, spender) }
                    ?.let { return@cancellingReads ApprovalQuoting.ApprovalPending(it.hash) }
            }
            is Unmined.Unavailable ->
                return@cancellingReads ApprovalQuoting.Unavailable(read.reason, QuoteFigure.NONCE)
        }
        val allowance = when (val read = readAllowance(network, inputs, from, token, spender)) {
            is AllowanceRead.Read -> read.allowance
            AllowanceRead.NotAToken -> return@cancellingReads ApprovalQuoting.NotAToken
            AllowanceRead.Refused -> return@cancellingReads ApprovalQuoting.Refused
            is AllowanceRead.Unavailable ->
                return@cancellingReads ApprovalQuoting.Unavailable(read.reason, QuoteFigure.ALLOWANCE)
        }
        if (allowance >= amount) return@cancellingReads ApprovalQuoting.AlreadyAllowed(allowance)
        val nativeBefore = when (val read = NativeRead.of(native.await())) {
            is NativeRead.Before -> read
            is NativeRead.NotRead -> return@cancellingReads read.refusal.asApprovalQuoting()
        }
        // A token that returns false refused the approval as surely as one that reverts.
        val accepts: (String) -> Boolean = { returned -> !Erc20.returnedFalse(returned) }
        suspend fun quote(setTo: UInt256): CallQuoting {
            val data = Erc20.approveData(spender, setTo)
            return quoter.quote(from, token.address, UInt256.ZERO, data, level, nativeBefore, accepts)
        }

        var resetsFirst = false
        var quoting = quote(amount)
        val reverted = (quoting as? CallQuoting.Refused)?.refusal is CallRefusal.Reverts
        if (reverted && !allowance.isZero) {
            resetsFirst = true
            quoting = quote(UInt256.ZERO)
        }
        val call = when (quoting) {
            is CallQuoting.Quoted -> quoting.quote
            is CallQuoting.Refused -> return@cancellingReads quoting.refusal.asApprovalQuoting()
        }
        ApprovalQuoting.Quoted(
            TokenApprovalQuote(
                network = network,
                from = from,
                token = token,
                spender = spender,
                amount = if (resetsFirst) UInt256.ZERO else amount,
                allowance = allowance,
                resetsFirst = resetsFirst,
                level = level,
                transaction = call.transaction,
                maxFee = call.maxFee,
                nativeBefore = call.nativeBefore,
                pendingCost = call.pendingCost,
                nativeAfterAtLeast = call.nativeAfterAtLeast,
                balanceBlock = call.balanceBlock,
                feeBlock = call.feeBlock,
                simulationBlock = call.simulationBlock,
            ),
        )
    }
}

/** This refusal of the transaction as the approval's. */
private fun CallRefusal.asApprovalQuoting(): ApprovalQuoting = when (this) {
    CallRefusal.InsufficientFunds -> ApprovalQuoting.InsufficientFunds
    is CallRefusal.Infeasible -> ApprovalQuoting.Infeasible(detail)
    is CallRefusal.Reverts -> ApprovalQuoting.Reverts(blockNumber)
    CallRefusal.Refused -> ApprovalQuoting.Refused
    CallRefusal.Syncing -> ApprovalQuoting.Syncing
    is CallRefusal.Unavailable -> ApprovalQuoting.Unavailable(reason, figure)
    is CallRefusal.EarlierMaybeSent -> ApprovalQuoting.EarlierMaybeSent(hash)
}

/** The outcome of [QuoteTokenApproval]. */
sealed interface ApprovalQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: TokenApprovalQuote) : ApprovalQuoting

    /** The spender may already take [allowance], enough: no approval is needed. */
    data class AlreadyAllowed(val allowance: UInt256) : ApprovalQuoting

    /** An approval of the same spender for the token, [hash], is sent and not mined yet. */
    data class ApprovalPending(val hash: String) : ApprovalQuoting

    /** A swap of the token, [hash], is sent and not mined yet: its allowance is not to be set under it. */
    data class EarlierSwapPending(val hash: String) : ApprovalQuoting

    /** The sender's balance of the network's currency cannot pay the most the fee can be. */
    data object InsufficientFunds : ApprovalQuoting

    /** It cannot succeed for a reason other than the sender's funds ([detail], the EVM's words). */
    data class Infeasible(val detail: String) : ApprovalQuoting

    /** The approval reverts (or the token returns false), run as a verified call at [blockNumber]. */
    data class Reverts(val blockNumber: Long?) : ApprovalQuoting

    /** The token's address answers as no token. */
    data object NotAToken : ApprovalQuoting

    /** The engine will not estimate or run it on this build. */
    data object Refused : ApprovalQuoting

    /** The light client has no verified head yet. */
    data object Syncing : ApprovalQuoting

    /** A verified figure could not be had right now, for [reason]; [figure] says which (null: not said). */
    data class Unavailable(val reason: ReadUnavailable, val figure: QuoteFigure? = null) : ApprovalQuoting

    /** An earlier transaction from the sender, [hash], may still be on its way: retried, not replaced. */
    data class EarlierMaybeSent(val hash: String) : ApprovalQuoting
}

/**
 * Signs a reviewed [TokenApprovalQuote] and hands it to the network, as every transaction from an
 * account held here is (`signAndSend`), recorded as the approval it is ([SentAction.TokenApproval]):
 * what holds the swap back until its receipt is in. [clock] is in unix seconds.
 */
class SendTokenApproval(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(quote: TokenApprovalQuote, unlock: Unlock): TransferSending = signAndSend(
        signer,
        broadcaster,
        sentTransfers,
        quote.network,
        quote.call,
        SentAction.TokenApproval(quote.token, quote.spender, quote.amount),
        clock,
        unlock,
    )
}
