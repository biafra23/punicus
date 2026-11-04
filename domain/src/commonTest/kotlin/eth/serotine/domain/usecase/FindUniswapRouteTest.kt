package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.FeeTier
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PoolKey
import eth.serotine.domain.model.QuoterV2
import eth.serotine.domain.model.RouteQuote
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.model.UniswapV3Factory
import eth.serotine.domain.model.UniswapV3Pool
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeQuote
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

class FindUniswapRouteTest {
    private val usdc = Address.of("0x" + "1c".repeat(20))
    private val weth = Address.of("0x" + "ff".repeat(20))
    private val link = Address.of("0x" + "77".repeat(20))
    private val deployment = UniswapV3Deployment(
        factory = Address.of("0x" + "fa".repeat(20)),
        quoterV2 = Address.of("0x" + "0e".repeat(20)),
        swapRouter02 = Address.of("0x" + "50".repeat(20)),
        weth9 = weth,
    )
    private val usdcAsset = TransferAsset.Erc20Token(Token(usdc, "USDC", "USD Coin", 6, icon = null))
    private val linkAsset = TransferAsset.Erc20Token(Token(link, "LINK", "ChainLink Token", 18, icon = null))
    private val amount = UInt256.of(1_000_000)

    private val chain = FakeUniswapChain()
    private val inputs = FakeFees()
    private val find = FindUniswapRoute(chain, inputs)

    /** The pool of [key], at an address made of its fee's byte, with [liquidity] in range. */
    private fun pool(key: PoolKey, liquidity: Long, tag: Int): Address {
        val address = Address.of("0x" + tag.toString(16).padStart(2, '0').repeat(20))
        chain.answer(deployment.factory, UniswapV3Factory.getPool(key), word(address))
        chain.answer(address, UniswapV3Pool.liquidity(), UInt256.of(liquidity).toBytes32().toHex())
        return address
    }

    private fun quote(route: SwapRoute, amountIn: UInt256, amountOut: Long, gas: Long) = chain.answer(
        deployment.quoterV2,
        QuoterV2.quote(route, amountIn),
        if (route.isDirect) {
            UInt256.of(amountOut).toBytes32().toHex() + "00".repeat(64) + UInt256.of(gas).toBytes32().toHex()
        } else {
            // The amount, the two arrays' offsets, the gas; then each array of one entry per pool.
            listOf(amountOut, 0x80, 0xe0, gas, 2, 0, 0, 2, 0, 0).joinToString("") { UInt256.of(it).toBytes32().toHex() }
        },
    )

    private suspend fun usdcForLink(): RouteFinding = find(Network.SEPOLIA, usdcAsset, linkAsset, amount)

    private fun word(address: Address) = "00".repeat(12) + address.hex.drop(2)

    @Test
    fun theDeepestDirectPoolsQuoteWinsWithItsPoolsInTheRoutesOrder() = runTest {
        pool(PoolKey.of(usdc, link, FeeTier.LOW), 10, tag = 0x05)
        val medium = pool(PoolKey.of(usdc, link, FeeTier.MEDIUM), 10, tag = 0x30)
        quote(SwapRoute.direct(usdc, link, FeeTier.LOW), amount, amountOut = 900, gas = 100_000)
        quote(SwapRoute.direct(usdc, link, FeeTier.MEDIUM), amount, amountOut = 1_000, gas = 100_000)
        chain.block = 42

        val found = assertIs<RouteFinding.Found>(find(Network.SEPOLIA, usdcAsset, linkAsset, amount))
        assertEquals(RouteQuote(SwapRoute.direct(usdc, link, FeeTier.MEDIUM), UInt256.of(1_000), 100_000), found.quote)
        assertEquals(listOf(medium), found.pools)
        assertEquals(2, found.quotedRoutes)
        assertEquals(deployment, found.deployment)
        assertEquals(42L, found.blockNumber)
    }

    @Test
    fun theChosenRoutesSpotIsReadFromItsPoolsAndLeftOutWhenOneCannotBeRead() = runTest {
        val medium = pool(PoolKey.of(usdc, link, FeeTier.MEDIUM), 10, tag = 0x30)
        quote(SwapRoute.direct(usdc, link, FeeTier.MEDIUM), amount, amountOut = 990_000, gas = 100_000)
        assertNull(assertIs<RouteFinding.Found>(usdcForLink()).spotOut, "slot0 reverted")

        // At a price of one: 1 000 000 less the pool's 0.3 %.
        val q96 = UInt256.of("79228162514264337593543950336")
        chain.answer(medium, UniswapV3Pool.slot0(), q96.toBytes32().toHex() + "00".repeat(32 * 6))
        assertEquals(UInt256.of(997_000), assertIs<RouteFinding.Found>(usdcForLink()).spotOut)

        // Read at another block than the quote: no measure of the quote's state.
        chain.blockOf[medium] = chain.block + 1
        assertNull(assertIs<RouteFinding.Found>(usdcForLink()).spotOut)
    }

    @Test
    fun aHopThatOnlyGrossesMoreLosesToTheDirectRouteNetOfFees() = runTest {
        pool(PoolKey.of(usdc, link, FeeTier.MEDIUM), 10, tag = 0x30)
        val first = pool(PoolKey.of(usdc, weth, FeeTier.LOW), 10, tag = 0x05)
        val second = pool(PoolKey.of(weth, link, FeeTier.HIGH), 50, tag = 0x99)
        pool(PoolKey.of(weth, link, FeeTier.LOW), 20, tag = 0x06)
        val direct = SwapRoute.direct(usdc, link, FeeTier.MEDIUM)
        val hop = SwapRoute.via(usdc, FeeTier.LOW, weth, FeeTier.HIGH, link)
        quote(direct, amount, amountOut = 1_000, gas = 100_000)
        quote(hop, amount, amountOut = 1_015, gas = 200_000)
        // 11 gwei a gas: the hop's 200 000 gas cost 2.2e15 wei, sold in WETH/LINK's deepest pool for 40.
        quote(SwapRoute.direct(weth, link, FeeTier.HIGH), UInt256.of(2_200_000_000_000_000L), amountOut = 40, gas = 1)

        val net = assertIs<RouteFinding.Found>(find(Network.SEPOLIA, usdcAsset, linkAsset, amount))
        assertEquals(direct, net.quote.route, "the direct route nets 1000 - 20, the hop 1015 - 40")
        assertEquals(true, net.netOfFees)

        val gross = assertIs<RouteFinding.Found>(
            find(Network.SEPOLIA, usdcAsset, linkAsset, amount, RouteRanking.GROSS),
        )
        assertEquals(hop, gross.quote.route)
        assertEquals(listOf(first, second), gross.pools)
        assertEquals(false, gross.netOfFees)
    }

    @Test
    fun buyingEtherCountsTheFeeInWeiWithNoConversion() = runTest {
        pool(PoolKey.of(usdc, weth, FeeTier.LOW), 10, tag = 0x05)
        pool(PoolKey.of(usdc, weth, FeeTier.HIGH), 10, tag = 0x99)
        quote(SwapRoute.direct(usdc, weth, FeeTier.LOW), amount, amountOut = 3_000_000_000_000_000L, gas = 100_000)
        // 0.1e15 wei more, for 2.2e15 wei of fees against 1.1e15 at 11 gwei a gas.
        quote(SwapRoute.direct(usdc, weth, FeeTier.HIGH), amount, amountOut = 3_100_000_000_000_000L, gas = 200_000)

        val found = assertIs<RouteFinding.Found>(find(Network.SEPOLIA, usdcAsset, TransferAsset.Native, amount))
        assertEquals(FeeTier.LOW, found.quote.route.fees.single())
        assertEquals(2, chain.quotesAsked, "the two routes, and no conversion")
    }

    @Test
    fun aSingleRoutesFeeIsHadOnlyWhenAskedFor() = runTest {
        pool(PoolKey.of(usdc, weth, FeeTier.LOW), 10, tag = 0x05)
        quote(SwapRoute.direct(usdc, weth, FeeTier.LOW), amount, amountOut = 3_000_000_000_000_000L, gas = 100_000)

        val plain = assertIs<RouteFinding.Found>(find(Network.SEPOLIA, usdcAsset, TransferAsset.Native, amount))
        assertEquals(null, plain.feeOut, "one route ranks nothing: no fee is had for it")
        // 100 000 gas at 10 gwei + 1 gwei: 1.1e15 wei, no conversion for ether bought.
        val withFee = assertIs<RouteFinding.Found>(
            find(Network.SEPOLIA, usdcAsset, TransferAsset.Native, amount, RouteRanking.NET_OF_FEES, withFee = true),
        )
        assertEquals(UInt256.of(1_100_000_000_000_000L), withFee.feeOut)
    }

    @Test
    fun withoutFeesTheRoutesAreRankedGrossAndTheFindingSaysSo() = runTest {
        pool(PoolKey.of(usdc, link, FeeTier.LOW), 10, tag = 0x05)
        pool(PoolKey.of(usdc, link, FeeTier.MEDIUM), 10, tag = 0x30)
        quote(SwapRoute.direct(usdc, link, FeeTier.LOW), amount, amountOut = 1_000, gas = 100_000)
        quote(SwapRoute.direct(usdc, link, FeeTier.MEDIUM), amount, amountOut = 1_001, gas = 300_000)
        inputs.fees = FeeReading.Unavailable(ReadUnavailable.UNPROVEN)

        val found = assertIs<RouteFinding.Found>(find(Network.SEPOLIA, usdcAsset, linkAsset, amount))
        assertEquals(FeeTier.MEDIUM, found.quote.route.fees.single())
        assertEquals(false, found.netOfFees)
    }

    @Test
    fun withNoPoolToConvertTheFeeThroughTheRoutesAreRankedGross() = runTest {
        pool(PoolKey.of(usdc, link, FeeTier.LOW), 10, tag = 0x05)
        pool(PoolKey.of(usdc, link, FeeTier.MEDIUM), 10, tag = 0x30)
        quote(SwapRoute.direct(usdc, link, FeeTier.LOW), amount, amountOut = 1_000, gas = 100_000)
        quote(SwapRoute.direct(usdc, link, FeeTier.MEDIUM), amount, amountOut = 1_001, gas = 300_000)
        // The fees are read, but the factory lists no WETH/LINK pool to convert them through.

        val found = assertIs<RouteFinding.Found>(usdcForLink())
        assertEquals(FeeTier.MEDIUM, found.quote.route.fees.single())
        assertEquals(false, found.netOfFees)
        assertEquals(2, chain.quotesAsked, "no conversion is quoted")
    }

    @Test
    fun aRouteWhoseQuoteRevertsIsDroppedAndNoneLeftIsNoRoute() = runTest {
        pool(PoolKey.of(usdc, link, FeeTier.LOW), 10, tag = 0x05)
        pool(PoolKey.of(usdc, link, FeeTier.MEDIUM), 10, tag = 0x30)
        quote(SwapRoute.direct(usdc, link, FeeTier.MEDIUM), amount, amountOut = 1_000, gas = 100_000)
        // The 0.05 % pool's quote is not set: it reverts.

        val found = assertIs<RouteFinding.Found>(find(Network.SEPOLIA, usdcAsset, linkAsset, amount))
        assertEquals(FeeTier.MEDIUM, found.quote.route.fees.single())
        assertEquals(1, found.quotedRoutes)

        chain.answers.keys.removeAll { it.first == deployment.quoterV2 }
        assertEquals(RouteFinding.NoRoute, find(Network.SEPOLIA, usdcAsset, linkAsset, amount))
    }

    @Test
    fun aPairWithNoPoolInRangeHasNoRoute() = runTest {
        pool(PoolKey.of(usdc, link, FeeTier.LOW), 0, tag = 0x05)
        pool(PoolKey.of(usdc, weth, FeeTier.LOW), 10, tag = 0x06)
        assertEquals(RouteFinding.NoRoute, find(Network.SEPOLIA, usdcAsset, linkAsset, amount))
        assertEquals(0, chain.quotesAsked)
    }

    @Test
    fun aReadThatCannotBeHadLeavesNoRoute() = runTest {
        pool(PoolKey.of(usdc, link, FeeTier.LOW), 10, tag = 0x05)
        chain.unavailable = Address.of("0x" + "05".repeat(20)) to ReadUnavailable.UNPROVEN
        assertEquals(RouteFinding.Unavailable(ReadUnavailable.UNPROVEN), usdcForLink())

        chain.unavailable = null
        quote(SwapRoute.direct(usdc, link, FeeTier.LOW), amount, amountOut = 1_000, gas = 100_000)
        chain.unavailable = deployment.quoterV2 to ReadUnavailable.REFUSED
        assertEquals(RouteFinding.Unavailable(ReadUnavailable.REFUSED), usdcForLink())

        chain.unavailable = null
        chain.answer(deployment.factory, UniswapV3Factory.getPool(PoolKey.of(usdc, link, FeeTier.HIGH)), "00")
        assertEquals(
            RouteFinding.Unavailable(ReadUnavailable.UNPROVEN),
            find(Network.SEPOLIA, usdcAsset, linkAsset, amount),
            "a factory answer that is no address",
        )
    }

    @Test
    fun noVerifiedVenueIsNoRouteWithItsReason() = runTest {
        chain.venue = VenueReading.NotPinned
        assertEquals(RouteFinding.NotPinned, find(Network.SEPOLIA, usdcAsset, linkAsset, amount))
        chain.venue = VenueReading.CodeMismatch(deployment.quoterV2)
        assertEquals(RouteFinding.CodeMismatch(deployment.quoterV2), usdcForLink())
        chain.venue = VenueReading.Unavailable(ReadUnavailable.NO_ENGINE)
        assertEquals(
            RouteFinding.Unavailable(ReadUnavailable.NO_ENGINE),
            find(Network.SEPOLIA, usdcAsset, linkAsset, amount),
        )
        assertEquals(0, chain.calls)
    }

    @Test
    fun etherAgainstWethIsAWrapAndNothingIsRead() = runTest {
        val wethAsset = TransferAsset.Erc20Token(Token(weth, "WETH", "Wrapped Ether", 18, icon = null))
        assertEquals(RouteFinding.NotASwap, find(Network.SEPOLIA, TransferAsset.Native, wethAsset, amount))
        assertEquals(RouteFinding.NotASwap, find(Network.SEPOLIA, usdcAsset, usdcAsset, amount))
        assertEquals(0, chain.calls)
        assertFailsWith<IllegalArgumentException> { find(Network.SEPOLIA, usdcAsset, linkAsset, UInt256.ZERO) }
    }

    /** Verified calls a test answers per contract and call data; anything else reverts. */
    private inner class FakeUniswapChain : UniswapV3Chain {
        var venue: VenueReading = VenueReading.Verified(deployment)
        val answers = mutableMapOf<Pair<Address, String>, String>()
        var block = 7L

        /** A contract that answers at another block than [block]. */
        val blockOf = mutableMapOf<Address, Long>()
        var calls = 0
        var quotesAsked = 0

        /** A contract whose every call has no verified answer, for the reason given. */
        var unavailable: Pair<Address, ReadUnavailable>? = null

        fun answer(to: Address, data: ByteArray, returnHex: String) {
            answers[to to data.toHex()] = returnHex
        }

        override suspend fun venue(network: Network): VenueReading = venue

        override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation {
            calls++
            if (to == deployment.quoterV2) quotesAsked++
            unavailable?.takeIf { it.first == to }?.let { return Simulation.Unavailable(it.second) }
            if (to == deployment.factory) {
                // A pool the test did not list: the factory has none.
                return Simulation.Succeeded(answers[to to data.toHex()] ?: "00".repeat(32), block)
            }
            val at = blockOf[to] ?: block
            return answers[to to data.toHex()]?.let { Simulation.Succeeded(it, at) } ?: Simulation.Reverted(at)
        }
    }

    /** Fees a test sets: 10 gwei base fee and 1 gwei tips, 11 gwei a gas at Normal. */
    private class FakeFees : TransactionInputs {
        var fees: FeeReading = FeeReading.Quoted(
            FeeQuote(
                nextBaseFee = UInt256.of(10_000_000_000L),
                tips = FeeLevel.entries.associateWith { UInt256.of(1_000_000_000L) },
                blockNumber = 99,
            ),
        )

        override suspend fun fees(network: Network): FeeReading = fees
        override suspend fun nonce(network: Network, account: Address): NonceReading = error("not asked")
        override suspend fun minedNonce(network: Network, account: Address): NonceReading = error("not asked")
        override suspend fun estimateGas(
            network: Network,
            from: Address,
            to: Address,
            value: UInt256,
            data: ByteArray,
        ): GasEstimate =
            error("not asked")
        override suspend fun simulate(
            network: Network,
            from: Address,
            to: Address,
            value: UInt256,
            data: ByteArray,
        ): Simulation =
            error("not asked")
    }
}
