package eth.serotine.domain.usecase

import eth.serotine.domain.model.AbiWords
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.FeeTier
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PoolKey
import eth.serotine.domain.model.QuoterV2
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.SwapRouter02
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.model.UniswapV3Factory
import eth.serotine.domain.model.UniswapV3Pool
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProposeSafeSwapTest {
    private val world = SafeWorld()
    private val safe = world.safe
    private val usdcToken = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USD Coin", 6, icon = null)
    private val linkToken = Token(Address.of("0x" + "77".repeat(20)), "LINK", "ChainLink Token", 18, icon = null)
    private val usdc = TransferAsset.Erc20Token(usdcToken)
    private val link = TransferAsset.Erc20Token(linkToken)
    private val weth = Address.of("0x" + "ff".repeat(20))
    private val deployment = UniswapV3Deployment(
        factory = Address.of("0x" + "fa".repeat(20)),
        quoterV2 = Address.of("0x" + "0e".repeat(20)),
        swapRouter02 = Address.of("0x" + "50".repeat(20)),
        weth9 = weth,
    )
    private val router = deployment.swapRouter02
    private val twoUsdc = UInt256.of(2_000_000) // of the Safe's 5
    private val quoted = UInt256.of("86000000000000000") // 0.086 LINK
    private val minimum = UInt256.of("85570000000000000") // less 0.5 %
    private val headTime = 1_792_419_000L

    private val chain = PoolChain()
    private val inputs = SafeRuns()
    private val light = FakeLightClient().apply {
        status.value = LightClientStatus.Synced(headBlock = 120, headTimestamp = headTime, finalized = null)
    }
    private val propose = ProposeSafeSwap(
        world.observeSafe,
        FindUniswapRoute(chain, inputs),
        chain,
        world.nativeBalances,
        world.tokenBalances,
        inputs,
        world.queue,
        light,
        Network.SEPOLIA,
    )

    private fun word(value: UInt256) = value.toBytes32().toHex()
    private fun word(address: Address) = "00".repeat(12) + address.hex.drop(2)

    /** One pool of [tokenIn] and [tokenOut] at 0.3 %, quoting [out] for [amountIn]. */
    private fun pool(tokenIn: Address, tokenOut: Address, amountIn: UInt256 = twoUsdc, out: UInt256 = quoted) {
        val key = PoolKey.of(tokenIn, tokenOut, FeeTier.MEDIUM)
        val address = Address.of("0x" + "30".repeat(20))
        chain.answers[deployment.factory to UniswapV3Factory.getPool(key).toHex()] = word(address)
        chain.answers[address to UniswapV3Pool.liquidity().toHex()] = word(UInt256.of(1_000))
        val route = SwapRoute.direct(tokenIn, tokenOut, FeeTier.MEDIUM)
        chain.answers[deployment.quoterV2 to QuoterV2.quote(route, amountIn).toHex()] =
            word(out) + "00".repeat(64) + word(UInt256.of(100_000))
    }

    /** The router's multicall returning [out] for the swap. */
    private fun returns(out: UInt256) =
        Simulation.Succeeded(AbiWords.withBytesArray(ByteArray(0), emptyList(), listOf(out.toBytes32())).toHex(), 121)

    private val allowanceCall = usdcToken.address to Erc20.allowanceData(safe, router).toHex()

    private fun allow(amount: UInt256) {
        chain.answers[allowanceCall] = word(amount)
    }

    private fun queuedSwap(nonce: Long, minimumOut: UInt256 = UInt256.ONE): SafeTx {
        val call = SwapRouter02.swap(
            deployment,
            usdc,
            link,
            SwapRoute.direct(usdcToken.address, linkToken.address, FeeTier.MEDIUM),
            twoUsdc,
            minimumOut,
            safe,
            headTime,
        )
        return SafeTx.call(call.to, call.value, call.data, nonce)
    }

    private fun approval(nonce: Long, amount: UInt256) =
        SafeTx.call(usdcToken.address, UInt256.ZERO, Erc20.approveData(router, amount), nonce)

    private suspend fun proposeUsdcForLink(replacing: Long? = null) =
        propose(safe, usdc, link, twoUsdc, SlippageTolerance.DEFAULT, replacing)

    @Test
    fun aTokenTheRouterMayNotTakeYetIsApprovedThenSwappedAtTheNextNonce() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.ZERO)

        val proposal = assertIs<SafeSwapProposing.Proposed>(proposeUsdcForLink()).proposal

        val approval = assertIs<SafeTxAction.TokenApproval>(proposal.approval?.action)
        assertEquals(SafeTxAction.TokenApproval(usdcToken.address, router, twoUsdc), approval, "the exact amount")
        assertEquals(listOf(4L, 5L), proposal.requests.map { it.nonce })
        val swap = assertIs<SafeTxAction.Swap>(proposal.swap.action).swap
        assertEquals(router, swap.router)
        assertEquals(twoUsdc, swap.amountIn)
        assertEquals(minimum, swap.minimumOut)
        assertEquals(headTime + SAFE_SWAP_DEADLINE_SECONDS, swap.deadline)
        assertEquals(minimum, proposal.minimumOut)
        assertEquals(quoted, proposal.quotedOut)
        assertEquals(UInt256.ZERO, proposal.allowance)
        assertEquals(SafeCallSimulation.Ran(120), proposal.approvalSimulation)
        assertEquals(SafeSwapSimulation.AfterApproval, proposal.swapSimulation, "no allowance on chain to run on")
        assertEquals(UInt256.of(3_000_000), proposal.balanceAfter)
        assertEquals(false, proposal.replaces)
        assertTrue(inputs.simulated.all { it.first == safe }, "every run is the Safe's")
        assertEquals(emptyList(), world.queue.requests.value, "nothing is queued until it is signed")
    }

    @Test
    fun anAllowanceThatCoversTheAmountTakesOneTransactionRunFromTheSafe() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(twoUsdc)
        inputs.routerRun = returns(UInt256.of("85900000000000000"))

        val proposal = assertIs<SafeSwapProposing.Proposed>(proposeUsdcForLink()).proposal

        assertNull(proposal.approval)
        assertEquals(4, proposal.swap.request.nonce)
        assertEquals(SafeSwapSimulation.Ran(UInt256.of("85900000000000000"), 121), proposal.swapSimulation)
        assertEquals(listOf(proposal.swap.request), proposal.requests)
    }

    @Test
    fun aSwapWhoseRunReturnsLessThanItsMinimumIsNotProposed() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(twoUsdc)
        inputs.routerRun = returns(minimum - UInt256.ONE)

        assertEquals(SafeSwapProposing.Reverts(121), proposeUsdcForLink())
    }

    @Test
    fun anApprovalQueuedBeforeItCountsAndTheSwapWaitsForIt() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.ZERO)
        world.queue.save(world.request(approval(4, twoUsdc)))

        val proposal = assertIs<SafeSwapProposing.Proposed>(proposeUsdcForLink()).proposal

        assertNull(proposal.approval, "the one queued at 4 sets the allowance it needs")
        assertEquals(5, proposal.swap.request.nonce)
        assertEquals(twoUsdc, proposal.allowance)
        assertEquals(SafeSwapSimulation.AfterApproval, proposal.swapSimulation, "the chain has no allowance yet")
    }

    @Test
    fun aLaterQueuedApprovalSetsTheAllowanceWhateverAnEarlierOneSet() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.ZERO)
        world.queue.save(world.request(approval(4, UInt256.of(5_000_000))))
        world.queue.save(world.request(approval(5, UInt256.of(1_000_000))))

        val proposal = assertIs<SafeSwapProposing.Proposed>(proposeUsdcForLink()).proposal

        assertEquals(UInt256.of(1_000_000), proposal.allowance, "the one at 5 runs last")
        assertEquals(listOf(6L, 7L), proposal.requests.map { it.nonce }, "an approval of its own")
    }

    @Test
    fun anApprovalOfAnotherSpenderOrTokenLeavesTheRoutersAllowanceAsItWas() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.ZERO)
        val other = Address.of("0x" + "66".repeat(20))
        // USDC to another spender at 4, and LINK to the router at 5.
        world.queue.save(
            world.request(SafeTx.call(usdcToken.address, UInt256.ZERO, Erc20.approveData(other, twoUsdc), 4)),
        )
        world.queue.save(
            world.request(SafeTx.call(linkToken.address, UInt256.ZERO, Erc20.approveData(router, twoUsdc), 5)),
        )

        val proposal = assertIs<SafeSwapProposing.Proposed>(proposeUsdcForLink()).proposal

        assertEquals(UInt256.ZERO, proposal.allowance)
        assertEquals(listOf(6L, 7L), proposal.requests.map { it.nonce })
    }

    @Test
    fun aRejectionBesideAQueuedApprovalLeavesTheAllowanceTheChainHas() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.ZERO)
        world.queue.save(world.request(approval(4, twoUsdc)))
        world.queue.save(world.request(SafeTx.call(safe, UInt256.ZERO, ByteArray(0), 4)))

        val proposal = assertIs<SafeSwapProposing.Proposed>(proposeUsdcForLink()).proposal

        assertEquals(listOf(5L, 6L), proposal.requests.map { it.nonce }, "an approval of its own, after both")
    }

    @Test
    fun aSwapOfTheTokenQueuedBeforeItHoldsItBack() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.of(10_000_000))
        world.queue.save(world.request(queuedSwap(4)))

        assertEquals(SafeSwapProposing.EarlierSwapQueued(4), proposeUsdcForLink())
    }

    @Test
    fun anApprovalIsNotProposedWhenTheNonceAfterItIsTaken() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.ZERO)
        // Queued further ahead, at 5: the swap would land beside it.
        world.queue.save(world.request(SafeTx.call(world.recipient, UInt256.ONE, ByteArray(0), 5)))

        assertEquals(SafeSwapProposing.NextNonceTaken(5), proposeUsdcForLink())
    }

    @Test
    fun aSwapProposedAgainTakesTheNonceOfTheOneItReplaces() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(twoUsdc)
        inputs.routerRun = returns(quoted)
        world.queue.save(world.request(queuedSwap(4, minimumOut = UInt256.ONE)))

        val proposal = assertIs<SafeSwapProposing.Proposed>(proposeUsdcForLink(replacing = 4)).proposal

        assertEquals(4, proposal.swap.request.nonce)
        assertEquals(0, proposal.queuedAhead)
        assertEquals(minimum, assertIs<SafeTxAction.Swap>(proposal.swap.action).swap.minimumOut)
        assertEquals(true, proposal.replaces)
        assertEquals(UInt256.ZERO, proposal.queuedAmount, "the one it replaces never runs beside it")
    }

    @Test
    fun aSwapProposedAgainGetsNoApprovalOfItsOwn() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.of(500_000))
        world.queue.save(world.request(queuedSwap(4)))

        assertEquals(SafeSwapProposing.NeedsApproval(UInt256.of(500_000)), proposeUsdcForLink(replacing = 4))
    }

    @Test
    fun aSwapIsProposedAgainOnlyInPlaceOfTheSameSwapStillQueued() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(twoUsdc)

        assertEquals(SafeSwapProposing.NothingToReplace, proposeUsdcForLink(replacing = 4))
        assertEquals(SafeSwapProposing.NothingToReplace, proposeUsdcForLink(replacing = 3), "the Safe passed it")

        // An approval at 4 and its swap at 5: in place of the approval, both swaps could run.
        world.queue.save(world.request(approval(4, twoUsdc)))
        world.queue.save(world.request(queuedSwap(5)))
        assertEquals(SafeSwapProposing.NothingToReplace, proposeUsdcForLink(replacing = 4), "no swap")
        assertEquals(
            SafeSwapProposing.NothingToReplace,
            propose(safe, usdc, link, UInt256.of(3_000_000), SlippageTolerance.DEFAULT, replacing = 5),
            "another amount would leave those queued after it short",
        )
        assertEquals(
            SafeSwapProposing.NothingToReplace,
            propose(safe, usdc, TransferAsset.Native, twoUsdc, SlippageTolerance.DEFAULT, replacing = 5),
            "another pair",
        )
        inputs.routerRun = returns(quoted)
        assertIs<SafeSwapProposing.Proposed>(proposeUsdcForLink(replacing = 5))
    }

    @Test
    fun aSwapPastAGapIsNotProposedAgain() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(twoUsdc)
        // Nothing at 4: the swap at 5 cannot run before something fills it.
        world.queue.save(world.request(queuedSwap(5)))

        assertEquals(SafeSwapProposing.NothingToReplace, proposeUsdcForLink(replacing = 5))
    }

    @Test
    fun anApprovalThatReturnsFalseIsRefusedAsOneThatReverts() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.ZERO)
        inputs.answers[usdcToken.address to Erc20.approveData(router, twoUsdc).toHex()] =
            Simulation.Succeeded("00".repeat(32), 120)

        assertEquals(SafeSwapProposing.ApprovalReverts(120), proposeUsdcForLink())
    }

    @Test
    fun anEngineThatRunsNoCallFromAContractLeavesTheRunsNotRunAndStillReadsTheAllowance() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.ZERO)
        inputs.refusing = true

        val approving = assertIs<SafeSwapProposing.Proposed>(proposeUsdcForLink()).proposal
        assertEquals(SafeCallSimulation.NotRun, approving.approvalSimulation)
        assertEquals(SafeSwapSimulation.AfterApproval, approving.swapSimulation)

        allow(twoUsdc)
        val swapping = assertIs<SafeSwapProposing.Proposed>(proposeUsdcForLink()).proposal
        assertEquals(SafeSwapSimulation.NotRun, swapping.swapSimulation)
    }

    @Test
    fun anAllowanceThatCannotBeReadProposesNothing() = runTest {
        pool(usdcToken.address, linkToken.address)

        assertEquals(SafeSwapProposing.NotAToken, proposeUsdcForLink(), "the token's contract reverted")

        chain.answering[allowanceCall] = Simulation.Refused
        assertEquals(
            SafeSwapProposing.Unavailable(ReadUnavailable.REFUSED, QuoteFigure.ALLOWANCE),
            proposeUsdcForLink(),
        )
    }

    @Test
    fun aRunWhoseAnswerIsNoAmountIsNoSwap() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(twoUsdc)
        inputs.routerRun = Simulation.Succeeded("00".repeat(32), 121)

        assertEquals(SafeSwapProposing.Reverts(121), proposeUsdcForLink())
    }

    @Test
    fun etherSoldIsTheValueTheSafeSendsTheRouter() = runTest {
        val amount = UInt256.of(100_000_000_000_000_000) // 0.1 of the Safe's 1 ETH
        pool(weth, usdcToken.address, amountIn = amount, out = UInt256.of(250_000_000))
        inputs.routerRun = returns(UInt256.of(249_000_000))

        val proposal = assertIs<SafeSwapProposing.Proposed>(
            propose(safe, TransferAsset.Native, usdc, amount, SlippageTolerance.DEFAULT),
        ).proposal

        assertNull(proposal.approval)
        assertNull(proposal.allowance)
        assertEquals(amount, proposal.swap.request.tx.value)
        assertEquals(true, assertIs<SafeTxAction.Swap>(proposal.swap.action).swap.sellsEther)
        assertEquals(UInt256.of(900_000_000_000_000_000), proposal.balanceAfter)
    }

    @Test
    fun whatTheQueueMovesBeforeItIsNotThisSwapsToSell() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.ZERO)
        fun transfer(amount: UInt256, nonce: Long) =
            SafeTx.call(usdcToken.address, UInt256.ZERO, Erc20.transferData(world.recipient, amount), nonce)
        world.queue.save(world.request(transfer(twoUsdc, 4)))
        world.queue.save(world.request(transfer(UInt256.of(1_500_000), 5)))

        assertEquals(SafeSwapProposing.InsufficientToken, proposeUsdcForLink(), "5 held, 3.5 queued before it")
    }

    @Test
    fun aTokenThatRefusesOneAllowanceForAnotherIsSetToZeroFirst() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.ONE)
        // USDT's kind: the approval of the amount reverts while an allowance is set; one of zero runs.
        inputs.answers[usdcToken.address to Erc20.approveData(router, twoUsdc).toHex()] = Simulation.Reverted(120)

        val proposal = assertIs<SafeSwapProposing.Proposed>(proposeUsdcForLink()).proposal

        assertEquals(SafeTxAction.TokenApproval(usdcToken.address, router, UInt256.ZERO), proposal.reset?.action)
        assertEquals(SafeTxAction.TokenApproval(usdcToken.address, router, twoUsdc), proposal.approval?.action)
        assertIs<SafeTxAction.Swap>(proposal.swap.action)
        assertEquals(listOf(4L, 5L, 6L), proposal.requests.map { it.nonce }, "the reset, the approval, the swap")
        assertEquals(SafeCallSimulation.Ran(120), proposal.resetSimulation)
        assertEquals(null, proposal.approvalSimulation, "it cannot run before the reset has")
        assertEquals(SafeSwapSimulation.AfterApproval, proposal.swapSimulation)
    }

    @Test
    fun aResetQueuedAlreadyIsNotProposedAgain() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.ONE)
        inputs.answers[usdcToken.address to Erc20.approveData(router, twoUsdc).toHex()] = Simulation.Reverted(120)
        // A reset signed and queued at 4, its approval not: proposed again, the approval goes after it.
        world.queue.save(world.request(approval(4, UInt256.ZERO)))

        val proposal = assertIs<SafeSwapProposing.Proposed>(proposeUsdcForLink()).proposal

        assertEquals(null, proposal.reset)
        assertEquals(SafeTxAction.TokenApproval(usdcToken.address, router, twoUsdc), proposal.approval?.action)
        assertEquals(listOf(5L, 6L), proposal.requests.map { it.nonce }, "the approval, the swap")
        assertEquals(null, proposal.approvalSimulation, "it cannot run before the queued reset has")
        assertEquals(SafeSwapSimulation.AfterApproval, proposal.swapSimulation)
    }

    @Test
    fun anApprovalTheTokenRefusesEvenFromZeroIsNotProposed() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.ONE)
        inputs.answers[usdcToken.address to Erc20.approveData(router, twoUsdc).toHex()] = Simulation.Reverted(120)
        inputs.answers[usdcToken.address to Erc20.approveData(router, UInt256.ZERO).toHex()] = Simulation.Reverted(121)
        assertEquals(SafeSwapProposing.ApprovalReverts(121, toZero = true), proposeUsdcForLink())

        // With no allowance to set to zero, a refused approval is refused outright.
        allow(UInt256.ZERO)
        assertEquals(SafeSwapProposing.ApprovalReverts(120), proposeUsdcForLink())
    }

    @Test
    fun aResetIsNotProposedWhenTheNonceTwoAheadIsTaken() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(UInt256.ONE)
        inputs.answers[usdcToken.address to Erc20.approveData(router, twoUsdc).toHex()] = Simulation.Reverted(120)
        // Queued at 6: the swap after the reset and the approval would land beside it.
        world.queue.save(world.request(SafeTx.call(world.recipient, UInt256.ONE, ByteArray(0), 6)))

        assertEquals(SafeSwapProposing.NextNonceTaken(6), proposeUsdcForLink())
    }

    @Test
    fun nothingIsProposedOnLessThanVerifiedState() = runTest {
        pool(usdcToken.address, linkToken.address)
        allow(twoUsdc)

        light.status.value = LightClientStatus.CatchingUp(done = 1, total = 2)
        assertEquals(SafeSwapProposing.Refused(SafeTxRefusal.Syncing), proposeUsdcForLink())

        light.status.value = LightClientStatus.Synced(headBlock = 120, headTimestamp = null, finalized = null)
        assertEquals(SafeSwapProposing.Unavailable(ReadUnavailable.UNPROVEN, figure = null), proposeUsdcForLink())

        light.status.value = LightClientStatus.Synced(headBlock = 120, headTimestamp = headTime, finalized = null)
        world.tokens = TokenBalanceReading.LastKnown(TokenUnits.of("5000000"), 100, headTime)
        assertEquals(SafeSwapProposing.Refused(SafeTxRefusal.Syncing), proposeUsdcForLink())

        world.tokens = TokenBalanceReading.Verified(TokenUnits.of("5000000"), 100)
        chain.venue = VenueReading.CodeMismatch(router)
        assertEquals(SafeSwapProposing.NoVenue(router), proposeUsdcForLink())
    }

    @Test
    fun theSameTokenOnBothSidesIsNoSwap() = runTest {
        assertEquals(
            SafeSwapProposing.NotASwap,
            propose(safe, usdc, usdc, twoUsdc, SlippageTolerance.DEFAULT),
        )
        assertEquals(
            SafeSwapProposing.NothingToSwap,
            propose(safe, usdc, link, UInt256.ZERO, SlippageTolerance.DEFAULT),
        )
    }

    private inner class PoolChain : UniswapV3Chain {
        var venue: VenueReading = VenueReading.Verified(deployment)
        val answers = mutableMapOf<Pair<Address, String>, String>()

        override suspend fun venue(network: Network): VenueReading = venue

        /** Calls answered with this instead, as their contract and their data in hex. */
        val answering = mutableMapOf<Pair<Address, String>, Simulation>()

        override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation {
            answering[to to data.toHex()]?.let { return it }
            answers[to to data.toHex()]?.let { return Simulation.Succeeded(it, 120) }
            // A pair the test did not list: the factory has no pool for it.
            if (to == deployment.factory) return Simulation.Succeeded("00".repeat(32), 120)
            return Simulation.Reverted(120)
        }
    }

    /**
     * The Safe's runs: the router answers [routerRun], a call in [answers] its answer, any other one
     * word of 1; none at all when [refusing]. Nothing here is ever sent, so nothing else is asked.
     */
    private inner class SafeRuns : TransactionInputs {
        var routerRun: Simulation = Simulation.Reverted(121)
        val answers = mutableMapOf<Pair<Address, String>, Simulation>()

        /** Every call run, as who ran it and the contract it called. */
        val simulated = mutableListOf<Pair<Address, Address>>()

        override suspend fun nonce(network: Network, account: Address): NonceReading = error("a proposal sends nothing")

        override suspend fun minedNonce(network: Network, account: Address): NonceReading = error(
            "a proposal sends nothing",
        )

        override suspend fun fees(network: Network): FeeReading = error(
            "whoever executes it pays: the route is ranked gross",
        )

        override suspend fun estimateGas(
            network: Network,
            from: Address,
            to: Address,
            value: UInt256,
            data: ByteArray,
        ): GasEstimate =
            error("a proposal sends nothing")

        /** An engine that runs no call from a contract's address. */
        var refusing = false

        override suspend fun simulate(
            network: Network,
            from: Address,
            to: Address,
            value: UInt256,
            data: ByteArray,
        ): Simulation {
            simulated += from to to
            if (refusing) return Simulation.Refused
            if (to == router) return routerRun
            return answers[to to data.toHex()] ?: Simulation.Succeeded("00".repeat(31) + "01", 120)
        }
    }
}
