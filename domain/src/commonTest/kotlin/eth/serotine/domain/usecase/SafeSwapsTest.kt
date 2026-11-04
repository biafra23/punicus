package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.FeeTier
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.QuoterV2
import eth.serotine.domain.model.RouterSwap
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SafeSwapsTest {
    private val usdc = Address.of("0x" + "1c".repeat(20))
    private val link = Address.of("0x" + "77".repeat(20))
    private val weth = Address.of("0x" + "ff".repeat(20))
    private val deployment = UniswapV3Deployment(
        factory = Address.of("0x" + "fa".repeat(20)),
        quoterV2 = Address.of("0x" + "0e".repeat(20)),
        swapRouter02 = Address.of("0x" + "50".repeat(20)),
        weth9 = weth,
    )
    private val other = Address.of("0x" + "66".repeat(20))
    private val deadline = 1_792_419_600L
    private val swap = RouterSwap(
        router = deployment.swapRouter02,
        route = SwapRoute.direct(usdc, link, FeeTier.MEDIUM),
        sellsEther = false,
        amountIn = UInt256.of(100_000_000),
        buysEther = false,
        minimumOut = UInt256.of(4_200),
        deadline = deadline,
    )

    private val chain = object : UniswapV3Chain {
        var venue: VenueReading = VenueReading.Verified(deployment)
        var quote: Simulation = Simulation.Reverted(9)

        override suspend fun venue(network: Network): VenueReading = venue

        override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation {
            check(to == deployment.quoterV2 && data.contentEquals(QuoterV2.quote(swap.route, swap.amountIn)))
            return quote
        }
    }
    private val light = FakeLightClient().apply {
        status.value = LightClientStatus.Synced(headBlock = 9, headTimestamp = deadline - 60, finalized = null)
    }
    private val checkSwap = CheckSafeSwap(chain, light, Network.SEPOLIA)

    private fun quoted(out: Long) = Simulation.Succeeded(
        UInt256.of(out).toBytes32().toHex() + "00".repeat(64) + UInt256.of(100_000).toBytes32().toHex(),
        9,
    )

    @Test
    fun anApprovalIsOfUniswapOnlyWhenItsSpenderIsThePinnedRouter() = runTest {
        assertEquals(
            SafeSwapCheck.ApprovalOfRouter,
            checkSwap(SafeTxAction.TokenApproval(usdc, deployment.swapRouter02, UInt256.ONE)),
        )
        assertEquals(SafeSwapCheck.NotUniswap(other), checkSwap(SafeTxAction.TokenApproval(usdc, other, UInt256.ONE)))
        chain.venue = VenueReading.CodeMismatch(deployment.swapRouter02)
        assertEquals(
            SafeSwapCheck.NotUniswap(deployment.swapRouter02),
            checkSwap(SafeTxAction.TokenApproval(usdc, deployment.swapRouter02, UInt256.ONE)),
        )
        assertNull(checkSwap(SafeTxAction.Rejection), "neither an approval nor a swap")
    }

    @Test
    fun aSwapIsQuotedNowAgainstTheMinimumItsOwnersSign() = runTest {
        chain.quote = quoted(4_210)
        val checked = assertIs<SafeSwapCheck.SwapOnUniswap>(checkSwap(SafeTxAction.Swap(swap)))
        assertEquals(SwapNow.Quoted(UInt256.of(4_210), 9), checked.now)
        assertEquals(true, checked.meetsMinimum)
        assertEquals(false, checked.expired)
        assertNull(checked.leftOpen, "4 210 less the default 0.5 % keeps 4 188: the minimum of 4 200 is above it")

        chain.quote = quoted(4_100)
        assertEquals(false, assertIs<SafeSwapCheck.SwapOnUniswap>(checkSwap(SafeTxAction.Swap(swap))).meetsMinimum)

        chain.quote = Simulation.Reverted(9)
        val noPool = assertIs<SafeSwapCheck.SwapOnUniswap>(checkSwap(SafeTxAction.Swap(swap)))
        assertEquals(SwapNow.NoQuote, noPool.now)
        assertNull(noPool.meetsMinimum)
    }

    @Test
    fun aMinimumFallenBehindTheMarketByMoreThanTheToleranceIsSaidWithWhatItLeavesOpen() = runTest {
        // 4 500 now, less 0.5 % is 4 477: a minimum of 4 200 leaves 300 open.
        chain.quote = quoted(4_500)
        assertEquals(
            UInt256.of(300),
            assertIs<SafeSwapCheck.SwapOnUniswap>(checkSwap(SafeTxAction.Swap(swap))).leftOpen,
        )
    }

    @Test
    fun aSwapPastItsDeadlineAtTheVerifiedHeadIsExpired() = runTest {
        chain.quote = quoted(4_210)
        light.status.value = LightClientStatus.Synced(headBlock = 10, headTimestamp = deadline + 1, finalized = null)
        assertEquals(true, assertIs<SafeSwapCheck.SwapOnUniswap>(checkSwap(SafeTxAction.Swap(swap))).expired)
        // A verified head with no proof peers right now still has its time.
        light.status.value = LightClientStatus.WaitingForProofPeers(headBlock = 10, headTimestamp = deadline + 1)
        assertEquals(true, assertIs<SafeSwapCheck.SwapOnUniswap>(checkSwap(SafeTxAction.Swap(swap))).expired)
        light.status.value = LightClientStatus.Starting
        assertNull(assertIs<SafeSwapCheck.SwapOnUniswap>(checkSwap(SafeTxAction.Swap(swap))).expired)
    }

    @Test
    fun aSwapWhoseDeadlineFallsWithinASlotOfTheHeadIsExpiredAlready() = runTest {
        // The router takes a block at its deadline, and the next block is a slot after the head at the soonest.
        chain.quote = quoted(4_210)
        light.status.value =
            LightClientStatus.Synced(headBlock = 10, headTimestamp = deadline - SLOT_SECONDS, finalized = null)
        assertEquals(false, assertIs<SafeSwapCheck.SwapOnUniswap>(checkSwap(SafeTxAction.Swap(swap))).expired)
        light.status.value =
            LightClientStatus.Synced(headBlock = 11, headTimestamp = deadline - SLOT_SECONDS + 1, finalized = null)
        assertEquals(true, assertIs<SafeSwapCheck.SwapOnUniswap>(checkSwap(SafeTxAction.Swap(swap))).expired)
    }

    @Test
    fun aSwapThroughAnotherRouterOrWrappingThroughAnotherTokenIsNotUniswaps() = runTest {
        assertEquals(SafeSwapCheck.NotUniswap(other), checkSwap(SafeTxAction.Swap(swap.copy(router = other))))
        // ETH in through a token that is not the pinned WETH9.
        val fakeWrap = swap.copy(route = SwapRoute.direct(other, link, FeeTier.MEDIUM), sellsEther = true)
        assertEquals(SafeSwapCheck.NotUniswap(deployment.swapRouter02), checkSwap(SafeTxAction.Swap(fakeWrap)))
        chain.venue = VenueReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(SafeSwapCheck.Unavailable(ReadUnavailable.UNPROVEN), checkSwap(SafeTxAction.Swap(swap)))
    }

    @Test
    fun aVerdictThatNeedsNoHeadIsGivenWhileTheLightClientSaysNothingYet() = runTest {
        val silent = object : LightClient {
            override val datesFinalizedBlocks = true
            override fun observeStatus(network: Network): Flow<LightClientStatus> = flow { awaitCancellation() }
        }
        val checkSilently = CheckSafeSwap(chain, silent, Network.SEPOLIA)

        assertEquals(
            SafeSwapCheck.NotUniswap(other),
            checkSilently(SafeTxAction.Swap(swap.copy(router = other))),
            "the router is not the pinned one, whatever the head",
        )
    }
}
