package eth.serotine.data.prices

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.FeeTier
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId

/**
 * A Uniswap v3 pool pinned to price [priceId] where no Chainlink feed is pinned for it (CLAUDE.md,
 * *Data sources*: owner ruling, 2026-10-29, #205): the pool of [token] (with [tokenDecimals]) and
 * the network's WETH in the [fee] tier, at
 * [pool], quoted in [quote]. The pin says which pool is expected; the network's pinned factory
 * proves it (`getPool` must return [pool]). [minQuoteDepth] is the floor of the pool's depth:
 * its in-range liquidity as the WETH it stands for at the price now, in whole WETH. A shallower
 * pool gives no price.
 */
data class PinnedPool(
    val priceId: PriceId,
    val token: Address,
    val tokenDecimals: Int,
    val fee: FeeTier,
    val pool: Address,
    val minQuoteDepth: Long,
    val quote: PriceId = PriceId("ETH"),
)

/**
 * The pools pinned per network, each for a built-in token with no Chainlink feed there. Ethereum
 * alone: Gnosis Chain pins no Uniswap, and Sepolia has no market.
 *
 * Chosen 2026-10-29 at mainnet block 26,298,245, read through two public nodes, which agreed (a
 * debugging source only, CLAUDE.md, *Data sources*), among the pinned factory's pools of each
 * token with WETH and USDC, every fee tier:
 * - COW: the 1 % tier (≈ 2.0 M COW and 26 WETH held, in range ≈ 77.5 WETH deep; 76 observations
 *   reaching back ≈ 202 h). The 0.3 % tier is deeper, ≈ 209 WETH, but keeps one observation, so its
 *   "average" is its spot price.
 * - SAFE: the 0.3 % tier (≈ 1.06 M SAFE and 49 WETH held, ≈ 292 WETH deep; 51 observations
 *   reaching back ≈ 2.8 h, so a day back is "24h —"). The 1 % tier is 0.3 WETH deep.
 * The USDC pools of both hold dust. The floor, 25 WETH deep, keeps both and refuses a pool
 * drained to a third of the shallower one's depth today.
 */
object UniswapPools {

    /** The pools pinned for [network]. */
    fun of(network: Network): List<PinnedPool> = when (network) {
        Network.ETHEREUM -> ETHEREUM
        Network.GNOSIS, Network.SEPOLIA -> emptyList()
    }

    /** The averaging window: 30 minutes (owner ruling, 2026-10-29, #205). */
    const val WINDOW_SECONDS = 1_800L

    /**
     * How far the pool's price now may be from its average, in ticks: 500 ticks is about 5 %. A
     * pool further off was just moved, and its average is no price until it settles.
     */
    const val MAX_TICK_DIVERGENCE = 500L

    private const val MIN_WETH_DEPTH = 25L

    private val ETHEREUM = listOf(
        PinnedPool(
            PriceId("COW"),
            Address.of("0xdef1ca1fb7fbcdc777520aa7f396b4e015f497ab"),
            tokenDecimals = 18,
            fee = FeeTier.HIGH,
            pool = Address.of("0xfcfdfc98062d13a11cec48c44e4613eb26a34293"),
            minQuoteDepth = MIN_WETH_DEPTH,
        ),
        PinnedPool(
            PriceId("SAFE"),
            Address.of("0x5afe3855358e112b5647b952709e6165e1c1eeee"),
            tokenDecimals = 18,
            fee = FeeTier.MEDIUM,
            pool = Address.of("0x000ba527862e5b82cff0f7c66b646af023274aa1"),
            minQuoteDepth = MIN_WETH_DEPTH,
        ),
    )
}
