package eth.serotine.ui.components

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.UInt256

/** Why the tolerance typed is none: what a swap's form says under it. */
internal const val TOLERANCE_ERROR = "Between 0.1 % and 5 %, with at most two decimals (0.5)."

/** Uniswap's router, as a review names it. */
internal const val ROUTER_NAME = "Uniswap's SwapRouter02"

/** A swap whose minimum, less the tolerance, rounds to nothing. */
internal const val TOO_SMALL = "This amount is too small: less the tolerance, the swap could return nothing."

/** A token's contract that does not answer as one. */
internal const val NOT_A_TOKEN = "A token's contract does not answer as a token: nothing can be swapped with it."

/**
 * [route]'s pools, one step each, through the factory's [pools]: the pair in [sell]'s and [buy]'s
 * symbols (WETH between them), its fee tier and the pool.
 */
internal fun routeSteps(route: SwapRoute, pools: List<Address>, sell: AssetOption, buy: AssetOption): List<RouteStep> {
    val symbolOf = { address: Address ->
        when (address) {
            sell.contract -> sell.symbol
            buy.contract -> buy.symbol
            else -> "WETH"
        }
    }
    return route.fees.indices.map { i ->
        RouteStep(
            pair = "${symbolOf(route.tokens[i])} → ${symbolOf(route.tokens[i + 1])}",
            fee = feeTierText(route.fees[i].pips),
            pool = Eip55.format(pools[i]),
        )
    }
}

/** The price impact of [basisPoints] (null: not known), as a review says it. */
internal fun priceImpactText(basisPoints: Int?): String = basisPoints?.let {
    val figure = if (it == 0) "under 0.01" else UInt256.of(it.toLong()).formatUnits(2)
    "Price impact: $figure %, the quote against the pools' spot prices, their fees aside."
} ?: "Price impact: not known this time: the pools' spot prices could not be read at the quote's block, or " +
    "give no measure for this amount."

/** What the prices the wallet shows say of a quote on [network]: on a testnet, nothing (display only). */
internal fun priceHintText(network: Network): String? = if (network.testnet) {
    "${network.displayName}'s pools and Chainlink's testnet feeds do not track each other: this quote is not " +
        "held against the prices the wallet shows."
} else {
    null
}

/** [sell] and [buy], one token on Uniswap, as a Safe's swap says it: a wrap, which a Safe cannot propose yet. */
internal fun notASwapText(sell: String, buy: String): String =
    "$sell and $buy are one token on Uniswap: ETH for WETH and back is a wrap, not a swap, and a Safe cannot " +
        "propose a wrap here yet."

/** No pool for [sell] and [buy]. */
internal fun noRouteText(sell: String, buy: String): String =
    "No Uniswap v3 pool takes $sell for $buy, directly or through WETH, for this amount."

/** Uniswap is no venue on [network]: not pinned, or [contract]'s code is not its pin. */
internal fun noVenueText(contract: Address?, network: Network): String = contract?.let {
    "The code at ${Eip55.format(it)}, one of Uniswap's pinned contracts, is not the code pinned with it, as the " +
        "light client proves it: Uniswap is no venue here."
} ?: "Uniswap is not pinned for ${network.displayName}, so nothing can be swapped here."
