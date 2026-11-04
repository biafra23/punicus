package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PoolKey
import eth.serotine.domain.model.PoolSlot0
import eth.serotine.domain.model.PriceImpact
import eth.serotine.domain.model.QuoterV2
import eth.serotine.domain.model.RouteQuote
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.SwapRoutes
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.model.UniswapV3Factory
import eth.serotine.domain.model.UniswapV3Pool
import eth.serotine.domain.model.swapsWith
import eth.serotine.domain.model.tokenOn
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** How [FindUniswapRoute] ranks the routes it quotes. */
enum class RouteRanking {
    /**
     * By what each returns once its expected network fee is taken off, in the bought token: the
     * account that sends the swap pays the fee (docs/swaps.md, *Finding a route*).
     */
    NET_OF_FEES,

    /** By what each returns: a Safe's swap, paid for by whoever executes it. */
    GROSS,
}

/**
 * The best Uniswap v3 route for selling exactly an amount of one asset for another
 * (docs/swaps.md, *Finding a route*), from verified chain data alone:
 *
 * 1. the network's pinned deployment, once its code is proven ([UniswapV3Chain.venue]);
 * 2. each pool [SwapRoutes.poolsToRead] names, as the factory's `getPool` and, for one it lists,
 *    its `liquidity()`;
 * 3. each candidate [SwapRoutes.candidates] makes of them, quoted by QuoterV2 for the amount;
 * 4. the quote that returns the most ([SwapRoutes.best]), ranked as [RouteRanking] says.
 *
 * Ranked net of fees, a route's fee is its QuoterV2 gas × (the next base fee + the Normal tip),
 * converted into the bought token by one more quote: that much WETH sold for it in its deepest
 * pool, for the route with the most gas, the others' fees taking their share of it by their gas.
 * The fee per gas is the same for every route, so their fees are in proportion to their gas. When
 * the bought token is WETH (or ETH), nothing is converted. A fee that cannot be had, or converted,
 * leaves the routes ranked gross, and the finding says so ([RouteFinding.Found.netOfFees]): the
 * ranking only picks among verified quotes, and the signed minimum comes from the chosen route's
 * own quote either way.
 *
 * Every read is made at once, each pool and each quote in parallel. One that could not be had
 * leaves no route (unavailable, with its reason): a route found from part of the pools could be
 * a worse one, unsaid. A quote that reverts drops its route (its pool cannot take the amount).
 * The pools' spot prices, read beside the quotes, are the exception: they rank nothing, so one
 * that could not be had, or was read at another block than the quote, drops only the price
 * impact ([RouteFinding.Found.spotOut]), and the route stands.
 */
class FindUniswapRoute(
    private val chain: UniswapV3Chain,
    private val inputs: TransactionInputs,
) {
    /** [network]'s Uniswap v3 deployment once its code is proven ([UniswapV3Chain.venue]). */
    suspend fun venue(network: Network): VenueReading = chain.venue(network)

    /**
     * The best route on [network] for selling exactly [amountIn] (not zero) of [sell] for [buy],
     * ranked by [ranking]. With [withFee], ranked net of fees, the chosen route's own fee in the
     * bought token is had even when it is the only route ([RouteFinding.Found.feeOut]): a CoW
     * order's floor stands on it (docs/swaps.md, *The signed minimum*).
     */
    suspend operator fun invoke(
        network: Network,
        sell: TransferAsset,
        buy: TransferAsset,
        amountIn: UInt256,
        ranking: RouteRanking = RouteRanking.NET_OF_FEES,
        withFee: Boolean = false,
    ): RouteFinding {
        require(!amountIn.isZero) { "a swap sells something" }
        require(!withFee || ranking == RouteRanking.NET_OF_FEES) { "a route's fee is had only net of fees" }
        val deployment = when (val venue = chain.venue(network)) {
            is VenueReading.Verified -> venue.deployment
            VenueReading.NotPinned -> return RouteFinding.NotPinned
            is VenueReading.CodeMismatch -> return RouteFinding.CodeMismatch(venue.contract)
            is VenueReading.Unavailable -> return RouteFinding.Unavailable(venue.reason)
        }
        if (!sell.swapsWith(buy, deployment)) return RouteFinding.NotASwap
        val tokenIn = sell.tokenOn(deployment)
        val tokenOut = buy.tokenOn(deployment)
        return coroutineScope {
            // The fees do not depend on the pools: read beside them, not after.
            val fees = if (ranking == RouteRanking.NET_OF_FEES) async { inputs.fees(network) } else null
            find(network, deployment, tokenIn, tokenOut, amountIn, ranking, withFee) { fees?.await() }
                .also { fees?.cancel() }
        }
    }

    private suspend fun find(
        network: Network,
        deployment: UniswapV3Deployment,
        tokenIn: Address,
        tokenOut: Address,
        amountIn: UInt256,
        ranking: RouteRanking,
        withFee: Boolean,
        fees: suspend () -> FeeReading?,
    ): RouteFinding {
        val toRead = SwapRoutes.poolsToRead(tokenIn, tokenOut, deployment.weth9)
        val pools = when (val read = readPools(network, deployment, toRead)) {
            is PoolsRead.Read -> read.pools
            is PoolsRead.Unavailable -> return RouteFinding.Unavailable(read.reason)
        }
        val liquidity = pools.mapValues { it.value.liquidity }
        val candidates = SwapRoutes.candidates(tokenIn, tokenOut, deployment.weth9, liquidity)
        if (candidates.isEmpty()) return RouteFinding.NoRoute

        // The candidates' pools' spot prices, read beside the quotes: the price impact's measure.
        val (quoted, prices) = coroutineScope {
            val spot = candidates.flatMap { it.poolKeys }.distinct().associateWith { key ->
                async { slot0(network, pools.getValue(key).address) }
            }
            val quotes = candidates.map { async { quote(network, deployment, it, amountIn) } }.awaitAll()
            quotes to spot.mapValues { it.value.await() }
        }
        quoted.firstNotNullOfOrNull { (it as? Quoted.Unavailable)?.reason }
            ?.let { return RouteFinding.Unavailable(it) }
        val quotes = quoted.filterIsInstance<Quoted.Read>()
        if (quotes.isEmpty()) return RouteFinding.NoRoute

        val costs = if (ranking == RouteRanking.NET_OF_FEES && (quotes.size > 1 || withFee)) {
            costs(network, deployment, quotes.map { it.quote }, tokenOut, liquidity, fees())
        } else {
            null
        }
        val best = SwapRoutes.best(quotes.map { it.quote }) { costs?.get(it) ?: UInt256.ZERO }
            ?: return RouteFinding.NoRoute
        val blockNumber = quotes.first { it.quote == best }.blockNumber
        return RouteFinding.Found(
            deployment = deployment,
            quote = best,
            pools = best.route.poolKeys.map { pools.getValue(it).address },
            quotedRoutes = quotes.size,
            netOfFees = ranking == RouteRanking.NET_OF_FEES && (costs != null || quotes.size == 1),
            blockNumber = blockNumber,
            spotOut = spotOut(best.route, amountIn, best.route.poolKeys.map { prices[it] }, blockNumber),
            feeOut = costs?.get(best),
        )
    }

    /** A pool's `slot0`, with the block it was read at; null when it could not be read. */
    private suspend fun slot0(network: Network, pool: Address): Pair<PoolSlot0, Long>? {
        val answer = chain.call(network, pool, UniswapV3Pool.slot0()) as? Simulation.Succeeded ?: return null
        val block = answer.blockNumber ?: return null
        return UniswapV3Pool.slot0Of(answer.returnHex)?.let { it to block }
    }

    /**
     * What [route] returns for [amountIn] at its pools' spot [prices] ([PriceImpact.spotOut]),
     * each read at the quote's block [quoteBlock]; null when one could not be read, or was read at
     * another block (the impact would compare two states of the chain): the price impact is then
     * not said, and the route stands as found.
     */
    private fun spotOut(
        route: SwapRoute,
        amountIn: UInt256,
        prices: List<Pair<PoolSlot0, Long>?>,
        quoteBlock: Long?,
    ): UInt256? {
        if (quoteBlock == null || prices.any { it?.second != quoteBlock }) return null
        return PriceImpact.spotOut(route, amountIn, prices.map { checkNotNull(it).first })
    }

    /** Each of [keys] the factory lists, with its address and its liquidity in range. */
    private suspend fun readPools(
        network: Network,
        deployment: UniswapV3Deployment,
        keys: List<PoolKey>,
    ): PoolsRead = coroutineScope {
        val read = keys.map { key -> async { key to readPool(network, deployment, key) } }.awaitAll()
        read.firstNotNullOfOrNull { (_, pool) -> (pool as? PoolRead.Unavailable)?.reason }
            ?.let { return@coroutineScope PoolsRead.Unavailable(it) }
        PoolsRead.Read(read.mapNotNull { (key, pool) -> (pool as? PoolRead.Listed)?.let { key to it.pool } }.toMap())
    }

    private suspend fun readPool(network: Network, deployment: UniswapV3Deployment, key: PoolKey): PoolRead {
        val listed = when (val answer = chain.call(network, deployment.factory, UniswapV3Factory.getPool(key))) {
            is Simulation.Succeeded -> UniswapV3Factory.poolOf(answer.returnHex) ?: return UNPROVEN_POOL
            else -> return PoolRead.Unavailable(answer.unavailable())
        }
        if (listed == Address.ZERO) return PoolRead.NotListed
        val liquidity = when (val answer = chain.call(network, listed, UniswapV3Pool.liquidity())) {
            is Simulation.Succeeded -> UniswapV3Pool.liquidityOf(answer.returnHex) ?: return UNPROVEN_POOL
            else -> return PoolRead.Unavailable(answer.unavailable())
        }
        return PoolRead.Listed(Pool(listed, liquidity))
    }

    private suspend fun quote(
        network: Network,
        deployment: UniswapV3Deployment,
        route: SwapRoute,
        amountIn: UInt256,
    ): Quoted =
        when (val answer = chain.call(network, deployment.quoterV2, QuoterV2.quote(route, amountIn))) {
            is Simulation.Succeeded -> QuoterV2.quoteOf(route, answer.returnHex)
                ?.let { Quoted.Read(it, answer.blockNumber) }
                ?: Quoted.Unavailable(ReadUnavailable.UNPROVEN)
            is Simulation.Reverted -> Quoted.Reverted
            Simulation.Refused -> Quoted.Unavailable(ReadUnavailable.REFUSED)
            is Simulation.Unavailable -> Quoted.Unavailable(answer.reason)
        }

    /**
     * Each of [quotes]' expected network fee in [tokenOut]'s units at [reading]'s fees, or null
     * when it cannot be had: no fee reading, no pool to convert through, or a conversion that did
     * not answer.
     */
    private suspend fun costs(
        network: Network,
        deployment: UniswapV3Deployment,
        quotes: List<RouteQuote>,
        tokenOut: Address,
        liquidity: Map<PoolKey, UInt256>,
        reading: FeeReading?,
    ): Map<RouteQuote, UInt256>? {
        val fees = (reading as? FeeReading.Quoted)?.quote ?: return null
        val perGas = fees.nextBaseFee.plusOrNull(fees.tip(FeeLevel.NORMAL)) ?: return null
        val mostGas = quotes.maxOf { it.gasEstimate }.takeIf { it in 1..Int.MAX_VALUE } ?: return null
        val mostWei = perGas.timesOrNull(UInt256.of(mostGas)) ?: return null
        if (mostWei.isZero) return quotes.associateWith { UInt256.ZERO }
        val mostOut = if (tokenOut == deployment.weth9) {
            mostWei
        } else {
            val tier = SwapRoutes.deepestTier(deployment.weth9, tokenOut, liquidity) ?: return null
            val conversion = SwapRoute.direct(deployment.weth9, tokenOut, tier)
            (quote(network, deployment, conversion, mostWei) as? Quoted.Read)?.quote?.amountOut ?: return null
        }
        return quotes.associateWith { quote ->
            // mostOut × gas / mostGas, rounded down: a fee in proportion to the route's gas.
            mostOut.timesOrNull(UInt256.of(quote.gasEstimate))?.divRem(mostGas.toInt())?.first ?: return null
        }
    }

    private fun Simulation.unavailable(): ReadUnavailable = when (this) {
        is Simulation.Unavailable -> reason
        Simulation.Refused -> ReadUnavailable.REFUSED
        // A pinned factory's getter, or a pool's, does not revert: what did is no answer of theirs.
        is Simulation.Reverted, is Simulation.Succeeded -> ReadUnavailable.UNPROVEN
    }

    /** A pool the factory listed: its [address] and its [liquidity] in range. */
    private class Pool(val address: Address, val liquidity: UInt256)

    private sealed interface PoolRead {
        data class Listed(val pool: Pool) : PoolRead
        data object NotListed : PoolRead
        data class Unavailable(val reason: ReadUnavailable) : PoolRead
    }

    private sealed interface PoolsRead {
        data class Read(val pools: Map<PoolKey, Pool>) : PoolsRead
        data class Unavailable(val reason: ReadUnavailable) : PoolsRead
    }

    private sealed interface Quoted {
        data class Read(val quote: RouteQuote, val blockNumber: Long?) : Quoted
        data object Reverted : Quoted
        data class Unavailable(val reason: ReadUnavailable) : Quoted
    }

    private companion object {
        val UNPROVEN_POOL = PoolRead.Unavailable(ReadUnavailable.UNPROVEN)
    }
}

/** What [FindUniswapRoute] finds. */
sealed interface RouteFinding {

    /**
     * The best route on [deployment]: its [quote] (the route, what it returns, its gas), through
     * the [pools] its factory returned, in the route's order; chosen among [quotedRoutes] quotes,
     * ranked net of their fees or not ([netOfFees]); the quote was run against the state of
     * [blockNumber] (null: not said). [spotOut] is what the route returns at its pools' spot
     * prices, less their fees, the price impact's measure, each read at [blockNumber]; null when
     * a pool's `slot0` could not be read at that block. [feeOut] is the route's expected network
     * fee, its QuoterV2 gas × (the next base fee + the Normal tip), in the bought token; null when
     * it was not asked for or could not be had (no fee reading, no pool to convert through).
     */
    data class Found(
        val deployment: UniswapV3Deployment,
        val quote: RouteQuote,
        val pools: List<Address>,
        val quotedRoutes: Int,
        val netOfFees: Boolean,
        val blockNumber: Long?,
        val spotOut: UInt256? = null,
        val feeOut: UInt256? = null,
    ) : RouteFinding

    /** The two assets are one token on Uniswap: the same one, or ETH and WETH, a wrap. */
    data object NotASwap : RouteFinding

    /** No pool takes the pair, directly or through WETH, or none takes this amount. */
    data object NoRoute : RouteFinding

    /** No Uniswap v3 deployment is pinned for the network. */
    data object NotPinned : RouteFinding

    /** The code at [contract], one of the pinned contracts, is not its pinned code: no venue. */
    data class CodeMismatch(val contract: Address) : RouteFinding

    /** A read the route needs could not be had right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : RouteFinding
}
