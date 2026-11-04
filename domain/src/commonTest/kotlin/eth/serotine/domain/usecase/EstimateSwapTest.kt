package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.FeeTier
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PoolKey
import eth.serotine.domain.model.QuoterV2
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.model.UniswapV3Factory
import eth.serotine.domain.model.UniswapV3Pool
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class EstimateSwapTest {
    private val usdc = Address.of("0x" + "1c".repeat(20))
    private val weth = Address.of("0x" + "ff".repeat(20))
    private val pool = Address.of("0x" + "30".repeat(20))
    private val deployment = UniswapV3Deployment(
        factory = Address.of("0x" + "fa".repeat(20)),
        quoterV2 = Address.of("0x" + "0e".repeat(20)),
        swapRouter02 = Address.of("0x" + "50".repeat(20)),
        weth9 = weth,
    )
    private val usdcAsset = TransferAsset.Erc20Token(Token(usdc, "USDC", "USD Coin", 6, icon = null))
    private val wethAsset = TransferAsset.Erc20Token(Token(weth, "WETH", "Wrapped Ether", 18, icon = null))

    private val chain = FakeChain()
    private val estimate = EstimateSwap(FindUniswapRoute(chain, NoFees), RouteRanking.GROSS)

    /** One USDC/WETH pool at 0.3 %, which QuoterV2 quotes [amountOut] for [amountIn]. */
    private fun onePool(sell: Address, buy: Address, amountIn: UInt256, amountOut: UInt256) {
        chain.answers[deployment.factory to UniswapV3Factory.getPool(PoolKey.of(usdc, weth, FeeTier.MEDIUM)).toHex()] =
            "00".repeat(12) + pool.hex.drop(2)
        chain.answers[pool to UniswapV3Pool.liquidity().toHex()] = UInt256.of(10).toBytes32().toHex()
        chain.answers[deployment.quoterV2 to QuoterV2.quote(SwapRoute.direct(sell, buy, FeeTier.MEDIUM), amountIn).toHex()] =
            amountOut.toBytes32().toHex() + "00".repeat(64) + UInt256.of(100_000).toBytes32().toHex()
    }

    @Test
    fun theQuoteComesWithItsRateBothWays() = runTest {
        // 2 WETH for 5 000 USDC: 2 500 USDC a WETH, 0.0004 WETH a USDC.
        val twoWeth = UInt256.of(2_000_000_000_000_000_000L)
        onePool(weth, usdc, twoWeth, UInt256.of(5_000_000_000L))

        val estimated = assertIs<SwapEstimating.Estimated>(estimate(Network.SEPOLIA, wethAsset, usdcAsset, twoWeth))
        assertEquals(UInt256.of(5_000_000_000L), estimated.amountOut)
        assertEquals(UInt256.of(2_500_000_000L), estimated.rate, "2 500 USDC in its 6 decimals")
        assertEquals(UInt256.of(400_000_000_000_000L), estimated.inverseRate, "0.0004 WETH in its 18 decimals")
        assertEquals(7L, estimated.blockNumber)
        assertNull(estimated.priceImpact, "slot0 was not read")
    }

    @Test
    fun etherIsCountedInEighteenDecimals() = runTest {
        val hundred = UInt256.of(100_000_000L)
        onePool(usdc, weth, hundred, UInt256.of(40_000_000_000_000_000L))

        val estimated = assertIs<SwapEstimating.Estimated>(
            estimate(Network.SEPOLIA, usdcAsset, TransferAsset.Native, hundred),
        )
        assertEquals(UInt256.of(400_000_000_000_000L), estimated.rate, "0.0004 ETH a USDC")
        assertEquals(UInt256.of(2_500_000_000L), estimated.inverseRate, "2 500 USDC an ETH")
    }

    @Test
    fun aQuoteOfNothingHasNoInverseRate() = runTest {
        val dust = UInt256.of(1)
        onePool(usdc, weth, dust, UInt256.ZERO)

        val estimated = assertIs<SwapEstimating.Estimated>(estimate(Network.SEPOLIA, usdcAsset, wethAsset, dust))
        assertEquals(UInt256.ZERO, estimated.rate)
        assertNull(estimated.inverseRate)
    }

    @Test
    fun whatLeavesNoQuoteIsSaid() = runTest {
        val amount = UInt256.of(1_000_000)
        assertEquals(SwapEstimating.NoRoute, estimate(Network.SEPOLIA, usdcAsset, wethAsset, amount))
        assertEquals(SwapEstimating.NotASwap, estimate(Network.SEPOLIA, TransferAsset.Native, wethAsset, amount))

        chain.venue = VenueReading.Unavailable(ReadUnavailable.SYNCING)
        assertEquals(
            SwapEstimating.Unavailable(ReadUnavailable.SYNCING),
            estimate(Network.SEPOLIA, usdcAsset, wethAsset, amount),
        )
        chain.venue = VenueReading.NotPinned
        assertEquals(SwapEstimating.NoVenue(contract = null), estimate(Network.SEPOLIA, usdcAsset, wethAsset, amount))
        chain.venue = VenueReading.CodeMismatch(deployment.quoterV2)
        assertEquals(
            SwapEstimating.NoVenue(deployment.quoterV2),
            estimate(Network.SEPOLIA, usdcAsset, wethAsset, amount),
        )
        assertFailsWith<IllegalArgumentException> { estimate(Network.SEPOLIA, usdcAsset, wethAsset, UInt256.ZERO) }
    }

    /** Verified calls the test answers by contract and call data; the factory lists no other pool, the rest revert. */
    private inner class FakeChain : UniswapV3Chain {
        var venue: VenueReading = VenueReading.Verified(deployment)
        val answers = mutableMapOf<Pair<Address, String>, String>()

        override suspend fun venue(network: Network): VenueReading = venue

        override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation {
            val answer = answers[to to data.toHex()]
            return when {
                answer != null -> Simulation.Succeeded(answer, 7)
                to == deployment.factory -> Simulation.Succeeded("00".repeat(32), 7)
                else -> Simulation.Reverted(7)
            }
        }
    }

    /** Ranked gross, nothing reads the fees. */
    private object NoFees : TransactionInputs {
        override suspend fun fees(network: Network): FeeReading = error("not asked")
        override suspend fun nonce(network: Network, account: Address): NonceReading = error("not asked")
        override suspend fun minedNonce(network: Network, account: Address): NonceReading = error("not asked")
        override suspend fun estimateGas(
            network: Network,
            from: Address,
            to: Address,
            value: UInt256,
            data: ByteArray,
        ): GasEstimate = error("not asked")
        override suspend fun simulate(
            network: Network,
            from: Address,
            to: Address,
            value: UInt256,
            data: ByteArray,
        ): Simulation = error("not asked")
    }
}
