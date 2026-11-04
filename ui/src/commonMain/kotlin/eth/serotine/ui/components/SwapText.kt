package eth.serotine.ui.components

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.SwapEstimating

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

/** A price impact of [basisPoints] as a percentage, without its sign: "0.12", or "under 0.01" for none. */
private fun impactFigure(basisPoints: Int): String =
    if (basisPoints == 0) "under 0.01" else UInt256.of(basisPoints.toLong()).formatUnits(2)

/** The price impact of [basisPoints] (null: not known), as a review says it. */
internal fun priceImpactText(basisPoints: Int?): String = basisPoints?.let {
    "Price impact: ${impactFigure(it)} %, the quote against the pools' spot prices, their fees aside."
} ?: "Price impact: not known this time: the pools' spot prices could not be read at the quote's block, or " +
    "give no measure for this amount."

/** What the prices the wallet shows say of a quote on [network]: on a testnet, nothing (display only). */
internal fun priceHintText(network: Network): String? = if (network.testnet) {
    "${network.displayName}'s pools and Chainlink's testnet feeds do not track each other: this quote is not " +
        "held against the prices the wallet shows."
} else {
    null
}

/** [sell] and [buy], one token on Uniswap. */
internal fun notASwapText(sell: String, buy: String): String =
    "$sell and $buy are one token on Uniswap: ETH for WETH and back is a wrap, not a swap."

/** No pool for [sell] and [buy]. */
internal fun noRouteText(sell: String, buy: String): String =
    "No Uniswap v3 pool takes $sell for $buy, directly or through WETH, for this amount."

/** Uniswap is no venue on [network]: not pinned, or [contract]'s code is not its pin. */
internal fun noVenueText(contract: Address?, network: Network): String = contract?.let {
    "The code at ${Eip55.format(it)}, one of Uniswap's pinned contracts, is not the code pinned with it, as the " +
        "light client proves it: Uniswap is no venue here."
} ?: "Uniswap is not pinned for ${network.displayName}, so nothing can be swapped here."

/**
 * [units] in whole units with [decimals], cut to six significant digits from one up and to four
 * below one, never rounded up: "2499.98" for 2499.987654, "0.0004123" for 0.000412345. What an
 * estimate shows; a review shows the exact amount ([exactAmount]).
 */
internal fun approxUnits(units: UInt256, decimals: Int, symbol: String): String {
    val exact = units.formatUnits(decimals)
    val whole = exact.substringBefore('.')
    val fraction = exact.substringAfter('.', missingDelimiterValue = "")
    val places = if (whole != "0") {
        (ESTIMATE_DIGITS - whole.length).coerceAtLeast(0)
    } else {
        fraction.takeWhile { it == '0' }.length + ESTIMATE_SMALL_DIGITS
    }
    val kept = fraction.take(places).trimEnd('0')
    return if (kept.isEmpty()) "$whole $symbol" else "$whole.$kept $symbol"
}

/** Significant digits an estimate keeps of an amount of one or more. */
private const val ESTIMATE_DIGITS = 6

/** Significant digits an estimate keeps of an amount below one. */
private const val ESTIMATE_SMALL_DIGITS = 4

/** The price impact, in basis points, from which the compose step warns of it: 1 %. */
private const val ESTIMATE_IMPACT_WARNING = 100

/**
 * What the compose step shows of [estimating], a verified Uniswap quote of selling [sell] for
 * [buy] on [network] (docs/swaps.md, *The compose step's estimate*), null while it is being read; at
 * [tolerance] when one is typed. [cow] when the swap goes to CoW, whose own estimate comes only
 * with the review: the figures are then said to be Uniswap's.
 */
internal fun swapEstimateItem(
    estimating: SwapEstimating?,
    sell: AssetOption,
    buy: AssetOption,
    tolerance: SlippageTolerance?,
    cow: Boolean,
    network: Network,
): SwapEstimateItem {
    val label = if (cow) "On Uniswap, you would receive" else "You receive"
    val bought = { units: UInt256 -> approxUnits(units, buy.decimals, buy.symbol) }
    return when (estimating) {
        null -> SwapEstimateItem(label, note = "Asking Uniswap's quoter, through the light client.", checking = true)
        is SwapEstimating.Estimated -> {
            val block = estimating.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()
            SwapEstimateItem(
                label = label,
                receive = "≈ " + bought(estimating.amountOut),
                minimum = tolerance?.let { "At least ${bought(it.minimumOf(estimating.amountOut))} at $it tolerance" },
                rate = estimating.rate?.let { "1 ${sell.symbol} ≈ ${bought(it)}" },
                inverseRate = estimating.inverseRate?.let {
                    "1 ${buy.symbol} ≈ ${approxUnits(it, sell.decimals, sell.symbol)}"
                },
                impact = estimating.priceImpact?.takeIf { it >= ESTIMATE_IMPACT_WARNING }?.let {
                    "Price impact ${impactFigure(it)} %: this amount moves the pools' price. " +
                        "The review says more."
                },
                note = if (cow) {
                    "Uniswap's verified quote$block, to compare. CoW is asked for its own estimate only when " +
                        "you review the order."
                } else {
                    "Uniswap's verified quote$block. The review quotes the swap again, with its network fee, " +
                        "before anything is signed."
                },
                simpleNote = if (cow) {
                    "Uniswap's price, checked on chain. CoW's own estimate comes with the review."
                } else {
                    "Checked on chain. The review checks it again before you confirm."
                },
            )
        }
        SwapEstimating.NotASwap -> SwapEstimateItem(label, note = notASwapText(sell.symbol, buy.symbol))
        SwapEstimating.NoRoute -> SwapEstimateItem(label, note = noRouteText(sell.symbol, buy.symbol))
        is SwapEstimating.NoVenue -> SwapEstimateItem(label, note = noVenueText(estimating.contract, network))
        is SwapEstimating.Unavailable -> SwapEstimateItem(label, note = estimateUnavailableText(estimating.reason))
    }
}

/** Why the compose step has no estimate when a read it needs could not be had, for [reason]. */
private fun estimateUnavailableText(reason: ReadUnavailable): String = when (reason) {
    ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so the swap cannot be quoted."
    ReadUnavailable.NETWORK_OFF -> "The network's light client is not running, so the swap cannot be quoted."
    ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so the swap cannot be quoted."
    ReadUnavailable.SYNCING -> "The light client is still syncing: the swap is quoted once it has caught up."
    ReadUnavailable.UNPROVEN -> "The quote could not be verified this time: it is asked for again shortly."
    ReadUnavailable.REFUSED -> "The light client on this device cannot quote swaps."
}
