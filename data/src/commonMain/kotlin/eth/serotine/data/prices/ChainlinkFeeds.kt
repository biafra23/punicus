package eth.serotine.data.prices

import eth.serotine.data.swaps.UniswapDeployments
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.FeedTable

/**
 * A Chainlink feed pinned for [priceId]: its proxy [address], the price id its answer is
 * quoted in ([quote]), the [decimals] it answers with and its [heartbeatSeconds], the longest
 * it goes without an update. The pins say what is expected; the contract is the source of
 * truth, and a feed whose decimals differ from the pin is no price (docs/wallet-ux.md, *Prices*).
 */
data class PinnedFeed(
    val priceId: PriceId,
    val address: Address,
    val quote: PriceId,
    val decimals: Int,
    val heartbeatSeconds: Long,
)

/**
 * The feed table per network (CLAUDE.md, *Data sources*: Chainlink aggregators read through
 * the light client). On Sepolia these are Chainlink's own Sepolia feeds, testnet feeds: the
 * prices they give are not market values.
 *
 * Sepolia's proxies, decimals and heartbeats from Chainlink's feed directory
 * (`reference-data-directory.vercel.app/feeds-ethereum-testnet-sepolia.json`), read on
 * 2026-10-17. Each feed was read the same day with verified calls through the Myotis engine at
 * Sepolia blocks 11,932,880 to 11,932,883: `description()` named the pair and `decimals()`
 * answered as pinned.
 *
 * Ethereum's from the same directory (`feeds-mainnet.json`), read on 2026-10-23: one feed per
 * built-in token and display currency that has one there, each X / USD but WBTC's, which is
 * WBTC / BTC and goes through BTC / USD. Each was read the same day at mainnet blocks 26,256,203
 * to 26,256,210 through two public nodes, which agreed, a debugging source only (CLAUDE.md,
 * *Data sources*): `description()` named the pair and `decimals()` answered 8. The light client
 * proves every answer it serves, and `MyotisHostEthereumTest` reads each feed through it.
 *
 * Gnosis Chain's from the same directory (`feeds-xdai-mainnet.json`), read on 2026-10-23, each
 * X / USD with a day's heartbeat. It has no xDAI / USD feed, so xDAI (and WXDAI) take DAI / USD's
 * answer, the ruling's fallback (owner ruling, 2026-10-22; docs/networks.md, open question 3):
 * xDAI is DAI bridged to Gnosis, redeemable one for one. It has no GBP feed. Each was read the
 * same day at Gnosis block 48,904,243 through two public Gnosis nodes, which agreed, a debugging
 * source only: `description()` named the pair and `decimals()` answered 8. The light client
 * proves every answer it serves, and `MyotisHostGnosisTest` reads each feed through it.
 */
object ChainlinkFeeds {

    /** The feeds pinned for [network]. */
    fun of(network: Network): List<PinnedFeed> = when (network) {
        Network.ETHEREUM -> ETHEREUM
        Network.GNOSIS -> GNOSIS
        Network.SEPOLIA -> SEPOLIA
    }

    private const val HOUR = 3_600L
    private const val DAY = 86_400L

    private val ETHEREUM = listOf(
        usd("ETH", "0x5f4ec3df9cbd43714fe2740f5e3616155c5b8419", HOUR),
        usd("BTC", "0xf4030086522a5beea4988f8ca5b36dbc97bee88c", HOUR),
        usd("USDC", "0x8fffffd4afb6115b954bd326cbe7b4ba576818f6", 82_800),
        usd("USDT", "0x3e7d1eab13ad0104d2750b8863b489d65364e32d", DAY),
        usd("DAI", "0xaed0c38402a5d19df6e4c03f4e2dced6e29c1ee9", HOUR),
        usd("LINK", "0x2c1d072e956affc0d435cb7ac38ef18d24d9127c", HOUR),
        usd("UNI", "0x553303d460ee0afb37edff9be42922d8ff63220e", HOUR),
        usd("AAVE", "0x547a514d5e3769680ce22b2361c10ea13619e8a9", HOUR),
        usd("EURC", "0x04f84020fdf10d9ee64d1dcc2986edf2f556da11", DAY),
        usd("PYUSD", "0x8f1df6d7f2db73eece86a18b4381f4707b918fb1", DAY),
        usd("GHO", "0x3f12643d3f6f874d39c2a4c9f2cd6f2dbac877fc", DAY),
        PinnedFeed(PriceId("WBTC"), Address.of("0xfdfd9c85ad200c506cf9e21f1fd8dd01932fbb23"), PriceId("BTC"), 8, DAY),
        usd("EUR", "0xb49f677943bc038e9857d61e7d053caa2c1734c1", DAY),
        usd("GBP", "0x5c0ab2d9b5a7ed9f470386e82bb36a3613cdd4b5", DAY),
        usd("CHF", "0x449d117117838ffa61263b61da6301aa2a88b13a", DAY),
        usd("JPY", "0xbce206cae7f0ec07b545edde332a47c2f75bbeb3", DAY),
    )

    private val GNOSIS = listOf(
        // DAI / USD, for xDAI: Gnosis has no xDAI / USD feed.
        usd("XDAI", "0x678df3415fc31947da4324ec63212874be5a82f8", DAY),
        usd("ETH", "0xa767f745331d267c7751297d982b050c93985627", DAY),
        usd("BTC", "0x6c1d7e76ef7304a40e8456ce883bc56d3dea3f7d", DAY),
        usd("USDC", "0x26c31ac71010af62e6b486d1132e266d6298857d", DAY),
        usd("USDT", "0x68811d7df835b1c33e6eeae8e7c141ef48d48cc7", DAY),
        usd("GNO", "0x22441d81416430a54336ab28765abd31a792ad37", DAY),
        usd("EUR", "0xab70bcb260073d036d1660201e9d5405f5829b7a", DAY),
        usd("CHF", "0xfb00261af80adb1629d3869e377ae1eec7be659f", DAY),
        usd("JPY", "0x2afb993c670c01e9da1550c58e8039c1d8b8a317", DAY),
    )

    private val SEPOLIA = listOf(
        usd("ETH", "0x694aa1769357215de4fac081bf1f309adc325306", HOUR),
        usd("BTC", "0x1b44f3514812d835eb1bdb0acb33d3fa3351ee43", HOUR),
        usd("LINK", "0xc59e3633baac79493d908e63626716e204a45edf", HOUR),
        usd("USDC", "0xa2f78ab2355fe2f984d808b5cee7fd0a93d5270e", DAY),
        usd("EUR", "0x1a81afb8146aeffcfc5e50e8479e826e7d55b910", DAY),
        usd("GBP", "0x91fab41f5f3be955963a986366edacff1aaeaa83", DAY),
        usd("JPY", "0x8a6af2b75f23831adc973ce6288e5329f63d86c6", DAY),
    )

    /** An X / USD feed answering with 8 decimals, as every one pinned here does. */
    private fun usd(id: String, address: String, heartbeatSeconds: Long) =
        PinnedFeed(PriceId(id), Address.of(address), PriceId.USD, decimals = 8, heartbeatSeconds)
}

/**
 * [FeedTable] of [ChainlinkFeeds] and, for a price id no feed is pinned for, [UniswapPools] on a
 * network that pins Uniswap (as the engine's pool adapter reads them): which prices are pinned
 * where, read from the tables alone, no engine. A feed always wins over a pool (owner ruling,
 * 2026-10-29, #205).
 */
class PinnedFeedTable : FeedTable {
    override fun quotes(network: Network): Map<PriceId, PriceId> {
        val pools = if (UniswapDeployments.of(network) == null) emptyList() else UniswapPools.of(network)
        return pools.associate { it.priceId to it.quote } +
            ChainlinkFeeds.of(network).associate { it.priceId to it.quote }
    }
}
