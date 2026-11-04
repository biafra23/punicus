package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceImpact
import eth.serotine.domain.model.QuoterV2
import eth.serotine.domain.model.RouterSwap
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.SwapRouter02
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.model.swapsWith
import eth.serotine.domain.model.tokenOn
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeTxQueue
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * Where a Safe's approval or swap stands as the chain is now (docs/swaps.md, *A Safe's swap*):
 * whether the contract it calls or approves is Uniswap's pinned SwapRouter02 on [network], its
 * code proven ([UniswapV3Chain.venue]); for a swap, what it would return now by a verified
 * QuoterV2 quote of its own route and amount, against the minimum its owners sign, and whether
 * the verified head is past its deadline. Nothing here re-quotes the swap itself: its minimum and
 * deadline are in the `SafeTx` its owners sign, and changing them would void their signatures.
 */
class CheckSafeSwap(
    private val uniswap: UniswapV3Chain,
    private val lightClient: LightClient,
    private val network: Network,
) {
    /** [action] checked, or null when it is neither an approval nor a swap. */
    suspend operator fun invoke(action: SafeTxAction): SafeSwapCheck? = when (action) {
        is SafeTxAction.TokenApproval -> approval(action.spender)
        is SafeTxAction.Swap -> swap(action.swap)
        else -> null
    }

    private suspend fun approval(spender: Address): SafeSwapCheck = when (val venue = uniswap.venue(network)) {
        is VenueReading.Verified -> if (spender == venue.deployment.swapRouter02) {
            SafeSwapCheck.ApprovalOfRouter
        } else {
            SafeSwapCheck.NotUniswap(spender)
        }
        VenueReading.NotPinned, is VenueReading.CodeMismatch -> SafeSwapCheck.NotUniswap(spender)
        is VenueReading.Unavailable -> SafeSwapCheck.Unavailable(venue.reason)
    }

    private suspend fun swap(swap: RouterSwap): SafeSwapCheck = coroutineScope {
        val deployment = when (val venue = uniswap.venue(network)) {
            is VenueReading.Verified -> venue.deployment
            VenueReading.NotPinned, is VenueReading.CodeMismatch ->
                return@coroutineScope SafeSwapCheck.NotUniswap(swap.router)
            is VenueReading.Unavailable ->
                return@coroutineScope SafeSwapCheck.Unavailable(venue.reason)
        }
        // The router is the pinned one, and ETH goes in and out through its WETH9 alone.
        val wethEnds = listOfNotNull(
            swap.route.tokenIn.takeIf { swap.sellsEther },
            swap.route.tokenOut.takeIf { swap.buysEther },
        )
        if (swap.router != deployment.swapRouter02 || wethEnds.any { it != deployment.weth9 }) {
            return@coroutineScope SafeSwapCheck.NotUniswap(swap.router)
        }
        // Read beside the quote, once the verdict needs it: a status not in yet holds back no other.
        val head = async { lightClient.observeStatus(network).first() }
        val now = when (val answer = uniswap.call(
            network,
            deployment.quoterV2,
            QuoterV2.quote(swap.route, swap.amountIn),
        )) {
            is Simulation.Succeeded -> QuoterV2.quoteOf(swap.route, answer.returnHex)
                ?.let { SwapNow.Quoted(it.amountOut, answer.blockNumber) }
                ?: SwapNow.Unavailable(ReadUnavailable.UNPROVEN)
            is Simulation.Reverted -> SwapNow.NoQuote
            Simulation.Refused -> SwapNow.Unavailable(ReadUnavailable.REFUSED)
            is Simulation.Unavailable -> SwapNow.Unavailable(answer.reason)
        }
        // A verified head's time, whether peers serve proofs for it right now or not.
        val headTime = when (val status = head.await()) {
            is LightClientStatus.Synced -> status.headTimestamp
            is LightClientStatus.WaitingForProofPeers -> status.headTimestamp
            else -> null
        }
        // The execution lands in a block after the head, a slot later at the soonest: past the deadline
        // by then, the router refuses it whatever runs at the head.
        SafeSwapCheck.SwapOnUniswap(swap, now, expired = headTime?.let { it + SLOT_SECONDS > swap.deadline })
    }
}

/** A slot of the beacon chain, in seconds: the soonest a block after the head is made. */
internal const val SLOT_SECONDS: Long = 12

/** What [CheckSafeSwap] found. */
sealed interface SafeSwapCheck {

    /** The approval lets Uniswap's pinned SwapRouter02 take the tokens, its code proven. */
    data object ApprovalOfRouter : SafeSwapCheck

    /**
     * The swap goes through Uniswap's pinned SwapRouter02, its code proven: [now] is what it would
     * return now; [expired] whether a block after the verified head, a slot later at the soonest,
     * would be past its deadline (null: the head's time is not known yet).
     */
    data class SwapOnUniswap(val swap: RouterSwap, val now: SwapNow, val expired: Boolean?) : SafeSwapCheck {

        /** Whether what it returns now meets the minimum its owners sign; null when that is not known. */
        val meetsMinimum: Boolean?
            get() = (now as? SwapNow.Quoted)?.let { it.amountOut >= swap.minimumOut }

        /**
         * How much more than its minimum the swap returns now, when that is more than the default
         * tolerance would leave ([SlippageTolerance.DEFAULT]): what a sandwich could take of a minimum
         * that has fallen behind the market (owner ruling, 2026-10-19: said at Execute, with the
         * offer to propose it again; it may still be executed as signed). Null otherwise.
         */
        val leftOpen: UInt256?
            get() {
                val quoted = (now as? SwapNow.Quoted)?.amountOut ?: return null
                if (swap.minimumOut >= SlippageTolerance.DEFAULT.minimumOf(quoted)) return null
                return quoted - swap.minimumOut
            }
    }

    /**
     * [contract], the router called or the spender approved, is not Uniswap's pinned SwapRouter02
     * (or no Uniswap is pinned here, or its code is not the pinned code): what it does with the
     * tokens is not known here.
     */
    data class NotUniswap(val contract: Address) : SafeSwapCheck

    /** Whether the contract is Uniswap's could not be verified right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : SafeSwapCheck
}

/** What a Safe's swap would return now, by a verified QuoterV2 quote of its route and amount. */
sealed interface SwapNow {

    /** [amountOut], quoted against the state of [blockNumber] (null: not said). */
    data class Quoted(val amountOut: UInt256, val blockNumber: Long?) : SwapNow

    /** The quote reverted: no pool on the route takes this amount now. */
    data object NoQuote : SwapNow

    /** No verified quote right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : SwapNow
}

/**
 * How long a Safe's swap may wait to be executed: 24 hours past the verified head's time it was
 * proposed at (owner ruling, 2026-10-19, docs/swaps.md, *A Safe's swap*), since its owners'
 * signatures are collected by QR code or text. An account's swap has [SWAP_DEADLINE_SECONDS].
 */
const val SAFE_SWAP_DEADLINE_SECONDS: Long = 24 * 60 * 60

/**
 * Proposes a Uniswap swap from a Safe (docs/swaps.md, *A Safe's swap*): exactly [amountIn] of the
 * asset sold for at least a minimum of the asset bought, paid to the Safe, as Safe transactions
 * its owners sign through the queue. The route is the best by what it returns
 * ([RouteRanking.GROSS]: whoever executes it pays the fee), the minimum its verified QuoterV2
 * quote less the tolerance, and the deadline the verified head's time plus
 * [SAFE_SWAP_DEADLINE_SECONDS]: both fixed in the `SafeTx` its owners sign. Nothing is proposed on
 * less than verified state, and prices never reach it.
 *
 * - **The approval.** A token sold needs the router's allowance for the whole amount, as the
 *   chain has it (a verified call from no sender, [UniswapV3Chain.call]) and as the approvals of
 *   the router queued before the swap leave it (per nonce the least of its alternatives, since
 *   any one of them may be the one that runs). One that falls short is approved for the exact
 *   amount (owner ruling, 2026-10-19, #69's decision 3) in its own Safe transaction at the Safe's
 *   next nonce, and the swap takes the nonce after it: two Safe transactions, since a MultiSend
 *   batch is a delegate call, refused until it is built. A token that refuses one allowance for
 *   another (USDT's kind: its approval of the amount reverts, or returns false, while the router's
 *   allowance on chain is not zero, and its approval of zero goes through) gets a third: the
 *   allowance set to zero first, at the next nonce, the approval of the amount at the one after
 *   it, and the swap last (the owner's request, 2026-10-23).
 * - **The nonce.** The Safe's next one, as [ProposeSafeTx] takes it. Or, to propose a swap again
 *   with a fresh minimum ([replacing]), the nonce of one queued there that is the same swap (the
 *   pair, the amount and the router): only one transaction at a nonce ever runs, and one that
 *   moves what the one it replaces moves leaves those queued after it as they were. Such a swap
 *   gets no approval of its own.
 * - **What is held back.** What the transactions queued before it may move of the asset sold is
 *   not this one's to sell. A swap of the token through the router queued before it counts on the
 *   allowance this one would set, so one is proposed only once that one has run or gone.
 * - **The runs.** Each is run as a verified call from the Safe's address at the head: the
 *   approval, which must not revert nor return false; the swap, which must return at least its
 *   minimum, but only while the allowance on chain already covers it: one waiting for an approval
 *   cannot run yet ([SafeSwapSimulation.AfterApproval]). For a token of USDT's kind the reset to
 *   zero is run, and the approval after it cannot run before the reset has: it is run when its
 *   execution is quoted. So is one whose reset is queued before it already (a reset whose
 *   approval was not signed), which is proposed alone, with no second reset. An engine that
 *   will not run a call from a contract's address leaves them not run. The runs are against the
 *   head, before anything queued: where an approval of the router queued before this one sets a
 *   non-zero allowance, such a token's approval passes here and reverts once that one has run. No
 *   funds move: the whole execution reverts, its quote says so before anyone pays, and the owners
 *   replace it at its nonce.
 *
 * What refuses it from the queue and the light client alone is said before the engine is asked
 * for a balance or a route. Nothing is saved: signing each ([SignSafeTx]) queues it. On [network]
 * only.
 */
class ProposeSafeSwap(
    private val observeSafe: ObserveSafe,
    private val findRoute: FindUniswapRoute,
    private val uniswap: UniswapV3Chain,
    private val nativeBalances: NativeBalances,
    private val tokenBalances: TokenBalances,
    private val inputs: TransactionInputs,
    private val queue: SafeTxQueue,
    private val lightClient: LightClient,
    private val network: Network,
) {
    /**
     * Exactly [amountIn] of [sell] for [buy] from [safe], at [tolerance]; at the nonce
     * [replacing], when set, in place of the same swap queued there. [safeChecked] is called once
     * the Safe is verified ([ObserveSafe.check], which may wait out an unproven reading), before
     * the rest is read: where a review measures the proposal's age from.
     */
    suspend operator fun invoke(
        safe: Address,
        sell: TransferAsset,
        buy: TransferAsset,
        amountIn: UInt256,
        tolerance: SlippageTolerance,
        replacing: Long? = null,
        safeChecked: () -> Unit = {},
    ): SafeSwapProposing = cancellingReads {
        if (amountIn.isZero) return@cancellingReads SafeSwapProposing.NothingToSwap
        val soldToken = (sell as? TransferAsset.Erc20Token)?.token
        val deployment = when (val venue = findRoute.venue(network)) {
            is VenueReading.Verified -> venue.deployment
            VenueReading.NotPinned -> return@cancellingReads SafeSwapProposing.NoVenue(contract = null)
            is VenueReading.CodeMismatch -> return@cancellingReads SafeSwapProposing.NoVenue(venue.contract)
            is VenueReading.Unavailable ->
                return@cancellingReads SafeSwapProposing.Unavailable(venue.reason, QuoteFigure.ROUTE)
        }
        if (!sell.swapsWith(buy, deployment)) return@cancellingReads SafeSwapProposing.NotASwap
        val router = deployment.swapRouter02
        // The Safe first: its check may wait a while, and what is read after it is as old as the
        // proposal is said to be.
        val reading = when (val check = observeSafe.check(AccountId(network, safe))) {
            is SafeCheck.Verified -> check.reading
            is SafeCheck.Refused -> return@cancellingReads SafeSwapProposing.Refused(check.refusal)
        }
        safeChecked()

        // The queue and the light client are this device's own: what they refuse is said before
        // the engine is asked for anything.
        val safeNonce = reading.state.nonce
        val pending = pendingOf(queue.observe(AccountId(network, safe)).first(), safeNonce, network)
        // Each decoded once: a swap's decoding builds the call again to compare it.
        val actions = pending.associateWith { SafeTxAction.of(it.safe, it.tx) }
        val next = nextNonce(pending, safeNonce)
        val nonce = when {
            replacing == null -> next
            // One past a gap could not run before the gap is filled: none to replace.
            replacing < next && pending.any {
                it.nonce == replacing && actions.getValue(it).isSwap(deployment, sell, buy, amountIn)
            } ->
                replacing
            else -> return@cancellingReads SafeSwapProposing.NothingToReplace
        }
        val ahead = pending.filter { it.nonce < nonce }
        if (soldToken != null) {
            ahead.lastOrNull { actions.getValue(it).sells(soldToken.address, router) }
                ?.let { return@cancellingReads SafeSwapProposing.EarlierSwapQueued(it.nonce) }
        }
        val deadline = when (val status = lightClient.observeStatus(network).first()) {
            // A verified head whose time is not known yet gives no deadline.
            is LightClientStatus.Synced -> status.headTimestamp?.plus(SAFE_SWAP_DEADLINE_SECONDS)
                ?: return@cancellingReads SafeSwapProposing.Unavailable(ReadUnavailable.UNPROVEN, figure = null)
            else -> return@cancellingReads SafeSwapProposing.Refused(SafeTxRefusal.Syncing)
        }

        val (before, balanceBlock) = when (
            val read = safeBalanceOf(network, nativeBalances, tokenBalances, safe, soldToken?.address)
        ) {
            is SafeBalanceRead.Read -> read.amount to read.blockNumber
            SafeBalanceRead.NotAToken -> return@cancellingReads SafeSwapProposing.NotAToken
            is SafeBalanceRead.Refused -> return@cancellingReads SafeSwapProposing.Refused(read.refusal)
        }
        val queuedAmount = ahead.queuedMoves(soldToken?.address) ?: return@cancellingReads insufficient(soldToken)
        val available = before.minusOrNull(queuedAmount)?.takeIf { it >= amountIn }
            ?: return@cancellingReads insufficient(soldToken)

        val found = when (val finding = findRoute(network, sell, buy, amountIn, RouteRanking.GROSS)) {
            is RouteFinding.Found -> finding
            else -> return@cancellingReads finding.asSafeSwapProposing()
        }
        // The route's venue is read again inside the route's finding: the approval and the swap
        // go to one router, or nothing is proposed.
        if (found.deployment != deployment) {
            return@cancellingReads SafeSwapProposing.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.ROUTE)
        }
        val minimum = tolerance.minimumOf(found.quote.amountOut)
        if (minimum.isZero) return@cancellingReads SafeSwapProposing.TooSmall

        // The allowance the router has on chain, then as the approvals queued before the swap set it:
        // only those decoded exactly count, the verified run before execution being the backstop
        // (docs/swaps.md, *Only what is decoded counts*).
        val allowance = soldToken?.let { token ->
            val onChain = when (val read = readAllowance(
                safe,
                token.address,
                router,
            ) { to, data -> uniswap.call(network, to, data) }) {
                is AllowanceRead.Read -> read.allowance
                AllowanceRead.NotAToken -> return@cancellingReads SafeSwapProposing.NotAToken
                AllowanceRead.Refused ->
                    return@cancellingReads SafeSwapProposing.Unavailable(ReadUnavailable.REFUSED, QuoteFigure.ALLOWANCE)
                is AllowanceRead.Unavailable ->
                    return@cancellingReads SafeSwapProposing.Unavailable(read.reason, QuoteFigure.ALLOWANCE)
            }
            val byNonce = ahead.groupBy { it.nonce }.entries.sortedBy { it.key }
            val queued = byNonce.fold(onChain) { set, (_, alternatives) ->
                alternatives.minOf { actions.getValue(it).approvalOf(token.address, router) ?: set }
            }
            SafeAllowance(onChain, queued)
        }

        var reset: DecodedSafeTx? = null
        var resetRun: SafeCallSimulation? = null
        var approval: DecodedSafeTx? = null
        var approvalRun: SafeCallSimulation? = null
        if (soldToken != null && allowance != null && allowance.queued < amountIn) {
            if (replacing != null) return@cancellingReads SafeSwapProposing.NeedsApproval(allowance.queued)
            if (pending.any { it.nonce == nonce + 1 }) {
                return@cancellingReads SafeSwapProposing.NextNonceTaken(nonce + 1)
            }
            fun requestOf(amount: UInt256, at: Long) = DecodedSafeTx.of(
                SigningRequest.of(
                    network,
                    safe,
                    SafeTx.call(soldToken.address, UInt256.ZERO, Erc20.approveData(router, amount), at),
                ),
            )
            when (val direct = runApproval(safe, soldToken.address, router, amountIn)) {
                is ApprovalRun.Ran -> {
                    approvalRun = direct.simulation
                    approval = requestOf(amountIn, nonce)
                }
                is ApprovalRun.Unavailable ->
                    return@cancellingReads SafeSwapProposing.Unavailable(direct.reason, QuoteFigure.RUN)
                is ApprovalRun.Refused -> {
                    // Refused while an allowance is set: a token of USDT's kind, if setting it to zero goes through.
                    if (allowance.onChain.isZero) {
                        return@cancellingReads SafeSwapProposing.ApprovalReverts(direct.blockNumber)
                    }
                    if (allowance.queued.isZero) {
                        // Set to zero by a transaction queued before it already (a reset whose approval
                        // was not signed): the approval goes after that one, and is run when its
                        // execution is quoted.
                        approval = requestOf(amountIn, nonce)
                    } else {
                        if (pending.any { it.nonce == nonce + 2 }) {
                            return@cancellingReads SafeSwapProposing.NextNonceTaken(nonce + 2)
                        }
                        when (val zero = runApproval(safe, soldToken.address, router, UInt256.ZERO)) {
                            is ApprovalRun.Ran -> resetRun = zero.simulation
                            is ApprovalRun.Refused -> return@cancellingReads SafeSwapProposing.ApprovalReverts(
                                zero.blockNumber,
                                toZero = true,
                            )
                            is ApprovalRun.Unavailable ->
                                return@cancellingReads SafeSwapProposing.Unavailable(zero.reason, QuoteFigure.RUN)
                        }
                        reset = requestOf(UInt256.ZERO, nonce)
                        // It cannot run before the reset has: it is run when its execution is quoted.
                        approval = requestOf(amountIn, nonce + 1)
                    }
                }
            }
        }
        val call = SwapRouter02.swap(deployment, sell, buy, found.quote.route, amountIn, minimum, safe, deadline)
        val swapRun = if (allowance != null && allowance.onChain < amountIn) {
            // An approval, its own or one queued before it, has to run first.
            SafeSwapSimulation.AfterApproval
        } else {
            when (val run = inputs.simulate(network, safe, call.to, call.value, call.data)) {
                // The run must return the swap's amount, and that at least the minimum.
                is Simulation.Succeeded -> SwapRouter02.amountOutOf(run.returnHex, buy == TransferAsset.Native)
                    ?.takeIf { it >= minimum }
                    ?.let { SafeSwapSimulation.Ran(it, run.blockNumber) }
                    ?: return@cancellingReads SafeSwapProposing.Reverts(run.blockNumber)
                is Simulation.Reverted -> return@cancellingReads SafeSwapProposing.Reverts(run.blockNumber)
                Simulation.Refused -> SafeSwapSimulation.NotRun
                is Simulation.Unavailable ->
                    return@cancellingReads SafeSwapProposing.Unavailable(run.reason, QuoteFigure.RUN)
            }
        }
        val swapNonce = nonce + listOfNotNull(reset, approval).size
        queue.prune(AccountId(network, safe), safeNonce)
        SafeSwapProposing.Proposed(
            SafeSwapProposal(
                safe = reading,
                reset = reset,
                approval = approval,
                swap = DecodedSafeTx.of(
                    SigningRequest.of(network, safe, SafeTx.call(call.to, call.value, call.data, swapNonce)),
                ),
                sell = sell,
                buy = buy,
                amountIn = amountIn,
                tolerance = tolerance,
                route = found.quote.route,
                pools = found.pools,
                router = router,
                quotedOut = found.quote.amountOut,
                quoteBlock = found.blockNumber,
                spotOut = found.spotOut,
                minimumOut = minimum,
                deadline = deadline,
                allowance = allowance?.queued,
                queuedAhead = (nonce - safeNonce).toInt(),
                balanceBefore = before,
                queuedAmount = queuedAmount,
                balanceAfter = available - amountIn,
                balanceBlock = balanceBlock,
                resetSimulation = resetRun,
                approvalSimulation = approvalRun,
                swapSimulation = swapRun,
                replaces = replacing != null,
            ),
        )
    }

    private fun insufficient(token: Token?) =
        if (token != null) SafeSwapProposing.InsufficientToken else SafeSwapProposing.InsufficientFunds

    /** [token]'s `approve(router, amount)` run as a verified call from [safe] at the head. */
    private suspend fun runApproval(safe: Address, token: Address, router: Address, amount: UInt256): ApprovalRun =
        when (val run = inputs.simulate(network, safe, token, UInt256.ZERO, Erc20.approveData(router, amount))) {
            // A token that returns false refused the approval as surely as one that reverts.
            is Simulation.Succeeded -> if (Erc20.returnedFalse(run.returnHex)) {
                ApprovalRun.Refused(run.blockNumber)
            } else {
                ApprovalRun.Ran(SafeCallSimulation.Ran(run.blockNumber))
            }
            is Simulation.Reverted -> ApprovalRun.Refused(run.blockNumber)
            Simulation.Refused -> ApprovalRun.Ran(SafeCallSimulation.NotRun)
            is Simulation.Unavailable -> ApprovalRun.Unavailable(run.reason)
        }

    /** How an approval ran from the Safe: as [simulation] says, refused by the token, or not verified now. */
    private sealed interface ApprovalRun {
        data class Ran(val simulation: SafeCallSimulation) : ApprovalRun
        data class Refused(val blockNumber: Long?) : ApprovalRun
        data class Unavailable(val reason: ReadUnavailable) : ApprovalRun
    }

    /** The router's allowance [onChain] now, and as the approvals queued before the swap leave it ([queued]). */
    private data class SafeAllowance(val onChain: UInt256, val queued: UInt256)
}

/**
 * Whether this queued request's action is the swap [deployment]'s router makes of exactly
 * [amountIn] of [sell] for [buy], whatever its minimum, deadline and route: the one a swap
 * proposed again replaces.
 */
private fun SafeTxAction.isSwap(
    deployment: UniswapV3Deployment,
    sell: TransferAsset,
    buy: TransferAsset,
    amountIn: UInt256,
): Boolean {
    val swap = (this as? SafeTxAction.Swap)?.swap ?: return false
    return swap.router == deployment.swapRouter02 &&
        swap.amountIn == amountIn &&
        swap.sellsEther == (sell == TransferAsset.Native) &&
        swap.buysEther == (buy == TransferAsset.Native) &&
        swap.route.tokenIn == sell.tokenOn(deployment) &&
        swap.route.tokenOut == buy.tokenOn(deployment)
}

/** What this action sets the [spender]'s allowance over [token] to, if it is such an approval. */
private fun SafeTxAction.approvalOf(token: Address, spender: Address): UInt256? =
    (this as? SafeTxAction.TokenApproval)
        ?.takeIf { it.token == token && it.spender == spender }
        ?.amount

/** Whether this action is a swap through [router] that sells the token at [token]. */
private fun SafeTxAction.sells(token: Address, router: Address): Boolean =
    (this as? SafeTxAction.Swap)?.swap
        ?.let { it.router == router && !it.sellsEther && it.route.tokenIn == token } == true

/** A route that was not found, as the proposal's refusal. */
private fun RouteFinding.asSafeSwapProposing(): SafeSwapProposing = when (this) {
    is RouteFinding.Found -> error("a route found is no refusal")
    RouteFinding.NotASwap -> SafeSwapProposing.NotASwap
    RouteFinding.NoRoute -> SafeSwapProposing.NoRoute
    RouteFinding.NotPinned -> SafeSwapProposing.NoVenue(contract = null)
    is RouteFinding.CodeMismatch -> SafeSwapProposing.NoVenue(contract)
    is RouteFinding.Unavailable -> SafeSwapProposing.Unavailable(reason, QuoteFigure.ROUTE)
}

/**
 * A Safe's swap proposed for review (docs/swaps.md, *A Safe's swap*): the router's [approval] for
 * the exact amount when it needs one, at the Safe's next nonce (after the allowance's [reset] to
 * zero, at that nonce, for a token that refuses one allowance for another), and the [swap] after
 * it (or at that nonce): exactly [amountIn] of [sell] for at least [minimumOut] of [buy], paid to the Safe,
 * along [route] through [pools] and the [router], refused by it after [deadline] (unix seconds).
 *
 * - [quotedOut] is QuoterV2's verified quote of the route at [quoteBlock], [minimumOut] that less
 *   [tolerance]; [spotOut] what the route returns at its pools' spot prices, less their fees.
 * - [allowance] is what the router may take of the token sold when the swap's turn comes, as the
 *   chain and the approvals queued before it have it (null for ETH).
 * - The Safe's verified state ([safe]), [queuedAhead] nonces queued before the first of them;
 *   its balance of the asset sold ([balanceBefore] at [balanceBlock]), what those queued before
 *   may move of it ([queuedAmount]) and what the swap leaves ([balanceAfter]).
 * - How each ran from the Safe: [resetSimulation] (null: no reset), [approvalSimulation] (null: no
 *   approval, or one after a reset, proposed with it or queued before, which it cannot run
 *   before), [swapSimulation].
 * - [replaces] when it takes the nonce of a swap queued there, to be signed in its place.
 *
 * Only [ProposeSafeSwap] makes one.
 */
@ConsistentCopyVisibility
data class SafeSwapProposal internal constructor(
    val safe: SafeReading.Verified,
    val reset: DecodedSafeTx?,
    val approval: DecodedSafeTx?,
    val swap: DecodedSafeTx,
    val sell: TransferAsset,
    val buy: TransferAsset,
    val amountIn: UInt256,
    val tolerance: SlippageTolerance,
    val route: SwapRoute,
    val pools: List<Address>,
    val router: Address,
    val quotedOut: UInt256,
    val quoteBlock: Long?,
    val spotOut: UInt256?,
    val minimumOut: UInt256,
    val deadline: Long,
    val allowance: UInt256?,
    val queuedAhead: Int,
    val balanceBefore: UInt256,
    val queuedAmount: UInt256,
    val balanceAfter: UInt256,
    val balanceBlock: Long?,
    val resetSimulation: SafeCallSimulation?,
    val approvalSimulation: SafeCallSimulation?,
    val swapSimulation: SafeSwapSimulation,
    val replaces: Boolean,
) {
    /** The price impact in basis points ([PriceImpact.basisPoints]), or null when [spotOut] is not known. */
    val priceImpact: Int?
        get() = spotOut?.let { PriceImpact.basisPoints(it, quotedOut) }

    /** The requests its owners sign, in nonce order: the reset and the approval, if any, then the swap. */
    val requests: List<SigningRequest>
        get() = listOfNotNull(reset?.request, approval?.request, swap.request)
}

/** How a Safe's swap ran as a verified call from the Safe. */
sealed interface SafeSwapSimulation {

    /** It returned [amountOut], at least its minimum, against the state of [blockNumber] (null: not said). */
    data class Ran(val amountOut: UInt256, val blockNumber: Long?) : SafeSwapSimulation

    /** It cannot run before the router's allowance is approved on chain: not simulated yet. */
    data object AfterApproval : SafeSwapSimulation

    /** The engine will not run a call from the Safe's address on this build: not simulated. */
    data object NotRun : SafeSwapSimulation
}

/** The outcome of [ProposeSafeSwap]. */
sealed interface SafeSwapProposing {

    /** [proposal] is ready for review. */
    data class Proposed(val proposal: SafeSwapProposal) : SafeSwapProposing

    /** The amount is zero: there is nothing to swap. */
    data object NothingToSwap : SafeSwapProposing

    /** The two assets are one token on Uniswap: the same one, or ETH and WETH. */
    data object NotASwap : SafeSwapProposing

    /** No pool of the pinned factory takes the pair at this amount. */
    data object NoRoute : SafeSwapProposing

    /** No Uniswap is pinned here, or [contract]'s code is not the pinned code. */
    data class NoVenue(val contract: Address?) : SafeSwapProposing

    /** The minimum rounds to zero: a swap is never signed without one. */
    data object TooSmall : SafeSwapProposing

    /** The Safe's ETH, less what those queued before it may move, is below the amount. */
    data object InsufficientFunds : SafeSwapProposing

    /** The Safe holds less of the token than the amount, less what those queued before it may move. */
    data object InsufficientToken : SafeSwapProposing

    /** The token's address answers as no token. */
    data object NotAToken : SafeSwapProposing

    /** A swap of the token through the router is queued at [nonce], before this one: it runs or goes first. */
    data class EarlierSwapQueued(val nonce: Long) : SafeSwapProposing

    /**
     * A transaction of the proposal after its first (the swap after its approval, or the approval after
     * its reset) would take [nonce], where a transaction is queued already.
     */
    data class NextNonceTaken(val nonce: Long) : SafeSwapProposing

    /** Proposed again in place of a swap, it needs an approval first: the router may take only [allowance]. */
    data class NeedsApproval(val allowance: UInt256) : SafeSwapProposing

    /** Nothing is queued at the nonce to propose it in place of, or the Safe has passed it. */
    data object NothingToReplace : SafeSwapProposing

    /**
     * The approval reverts (or returns false), run from the Safe at [blockNumber]: the approval of
     * the amount with the allowance on chain at zero, or, with [toZero], the reset to zero that a
     * token refusing one allowance for another needs first.
     */
    data class ApprovalReverts(val blockNumber: Long?, val toZero: Boolean = false) : SafeSwapProposing

    /** The swap reverts, or returns less than its minimum, run from the Safe at [blockNumber]. */
    data class Reverts(val blockNumber: Long?) : SafeSwapProposing

    /** The Safe's state, its balance or the light client is not verified now ([refusal]). */
    data class Refused(val refusal: SafeTxRefusal) : SafeSwapProposing

    /** [figure] (null: none of the swap's own) could not be verified right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable, val figure: QuoteFigure?) : SafeSwapProposing
}
