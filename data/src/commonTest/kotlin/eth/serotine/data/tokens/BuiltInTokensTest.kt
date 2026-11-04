package eth.serotine.data.tokens

import eth.serotine.data.prices.ChainlinkFeeds
import eth.serotine.data.prices.UniswapPools
import eth.serotine.data.swaps.UniswapDeployments
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltInTokensTest {

    @Test
    fun theSepoliaListHasOneEntryPerAddressAndSymbolEachWithAnIcon() {
        val tokens = BuiltInTokens.of(Network.SEPOLIA)

        assertEquals(tokens.size, tokens.distinctBy { it.address }.size)
        assertEquals(tokens.size, tokens.distinctBy { it.symbol }.size)
        assertTrue(tokens.all { it.icon != null }, "a built-in token has a bundled icon (CLAUDE.md, Data sources)")
        assertEquals(
            listOf("USDC", "EURC", "PYUSD", "LINK", "UNI", "WETH", "GHO", "DAI", "USDT", "WBTC", "AAVE"),
            tokens.map { it.symbol },
        )
    }

    @Test
    fun theEthereumListIsThePlansCandidatesEachWithAnIconAndPricedButGno() {
        val tokens = BuiltInTokens.of(Network.ETHEREUM)

        assertEquals(tokens.size, tokens.distinctBy { it.address }.size)
        assertTrue(tokens.all { it.icon != null }, "a built-in token has a bundled icon (CLAUDE.md, Data sources)")
        assertEquals(
            listOf(
                "USDC", "USDT", "DAI", "WETH", "WBTC", "LINK", "UNI", "EURC", "PYUSD", "GHO", "AAVE",
                "GNO", "COW", "SAFE",
            ),
            tokens.map { it.symbol },
        )
        // Each priced on Ethereum by a feed pinned there, or where none is by a pool pinned there
        // (COW and SAFE), none by another network's, whose chain of quotes ends in USD there (WBTC's
        // goes through BTC, the pools' through ETH); GNO has neither.
        val feeds = ChainlinkFeeds.of(Network.ETHEREUM)
        // One feed per price id: a second would shadow the first, unseen (VerifiedPriceFeeds keys by it).
        assertEquals(feeds.size, feeds.distinctBy { it.priceId }.size, "one Ethereum feed per price id")
        val pools = UniswapPools.of(Network.ETHEREUM)
        assertTrue(pools.none { pool -> feeds.any { it.priceId == pool.priceId } }, "a pool only where no feed is")
        val quotes = feeds.associate { it.priceId to it.quote } + pools.associate { it.priceId to it.quote }
        assertEquals(listOf("COW", "SAFE"), pools.map { it.priceId.id })
        pools.forEach { pool ->
            assertEquals(pool.token, tokens.single { it.priceId == pool.priceId }.address, "${pool.priceId.id}'s token")
        }
        assertEquals(listOf("GNO"), tokens.filter { it.priceId == null }.map { it.symbol })
        tokens.filter { it.priceId != null }.forEach { token ->
            var id = token.priceId
            val seen = mutableSetOf<PriceId?>()
            while (id != null && id != PriceId.USD && seen.add(id)) id = quotes[id]
            assertEquals(PriceId.USD, id, "${token.symbol}'s feeds on Ethereum reach USD")
        }
    }

    @Test
    fun theGnosisListIsThePlansCandidatesEachWithAnIconAndPricedOnGnosisChainButSDaiCowAndSafe() {
        val tokens = BuiltInTokens.of(Network.GNOSIS)

        assertEquals(tokens.size, tokens.distinctBy { it.address }.size)
        assertTrue(tokens.all { it.icon != null }, "a built-in token has a bundled icon (CLAUDE.md, Data sources)")
        assertEquals(
            listOf("WXDAI", "USDC", "USDC.e", "USDT", "WETH", "GNO", "EURe", "sDAI", "COW", "SAFE"),
            tokens.map { it.symbol },
        )
        val feeds = ChainlinkFeeds.of(Network.GNOSIS)
        assertEquals(feeds.size, feeds.distinctBy { it.priceId }.size, "one Gnosis feed per price id")
        val quotes = feeds.associate { it.priceId to it.quote }
        // Each priced by a feed pinned on Gnosis Chain whose quote is USD; sDAI, a vault share, and the
        // bridged COW and SAFE, which have no feed there, by none.
        assertEquals(listOf("sDAI", "COW", "SAFE"), tokens.filter { it.priceId == null }.map { it.symbol })
        tokens.mapNotNull { it.priceId }.forEach { assertEquals(PriceId.USD, quotes[it], "${it.id} on Gnosis Chain") }
        // xDAI itself, by DAI / USD's answer pinned under its own id.
        assertEquals(PriceId.USD, quotes[Network.GNOSIS.nativePriceId])
    }

    @Test
    fun theBuiltInWethIsTheWeth9TheSwapsPin() {
        // ETH against this token is a wrap only while the two addresses are one (docs/swaps.md).
        for (network in listOf(Network.ETHEREUM, Network.SEPOLIA)) {
            val weth = BuiltInTokens.of(network).single { it.symbol == "WETH" }

            assertEquals(UniswapDeployments.of(network)?.deployment?.weth9, weth.address, network.displayName)
        }
    }
}
