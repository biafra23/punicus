package eth.serotine.data.engine

import eth.serotine.data.prices.ChainlinkFeeds
import eth.serotine.data.prices.UniswapPools
import eth.serotine.data.tokens.BuiltInTokens
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.LightClientRun
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.VenueReading
import eth.serotine.domain.usecase.FindUniswapRoute
import eth.serotine.domain.usecase.RouteFinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Ethereum's pins on the real engine and the real mainnet (docs/networks.md, part 4c): boots a
 * [MyotisHost] with Ethereum's light client, then proves through it a balance, every pinned
 * Chainlink feed (a verified price, at the pinned decimals), every pinned pool's average (a
 * verified price, marked as one), every built-in token (its contract's
 * own symbol and decimals, as listed), the Uniswap v3 deployment (each contract's code, against
 * its pin) with a route for 100 USDC, a Safe 1.4.1 proxy recognised as one (its proxy and
 * singleton against the pinned hashes), and an ENS name on mainnet's registry. Needs the
 * network and the engine's host library, so it is skipped unless run with
 * `-PmyotisLibraryDir=<dir with libmyotis_engine>`, e.g.
 * `./gradlew :data:desktopTest --tests '*MyotisHostEthereumTest*' -PmyotisLibraryDir=…`.
 */
class MyotisHostEthereumTest {

    private val ethereum = Network.ETHEREUM

    @Test
    fun eachOfEthereumsPinsIsProvenOnMainnet(): Unit = runBlocking {
        assumeTrue("needs -PmyotisLibraryDir", System.getProperty("serotine.myotisLibraryDir") != null)
        // A fresh directory: a cold start from the embedded checkpoint, every phase shown.
        val node = MyotisHost.start(Files.createTempDirectory("myotis-mainnet").toFile())
        node.set(ethereum, LightClientRun.RUN)
        val started = System.nanoTime()
        fun at() = "${(System.nanoTime() - started) / 1_000_000_000}s"
        val progress = launch {
            node.lightClient.observeStatus(ethereum).collect { println("[${at()}] light client: $it") }
        }
        try {
            // The beacon deposit contract: a balance that exists on every mainnet state.
            val deposit = Address.of("0x00000000219ab540356cbb839cbe05303d7705fa")
            val balance = withTimeout(15.minutes) {
                node.nativeBalances.observeBalance(ethereum, deposit).first {
                    it is BalanceReading.Verified ||
                        (it is BalanceReading.Unavailable && it.reason != ReadUnavailable.UNPROVEN)
                }
            }
            println("[${at()}] deposit contract: $balance")
            assertIs<BalanceReading.Verified>(balance)

            for (feed in ChainlinkFeeds.of(ethereum)) {
                val reading = withTimeout(5.minutes) {
                    node.priceFeeds.observe(ethereum, feed.priceId).first {
                        it !is FeedReading.Syncing &&
                            !(it is FeedReading.Unavailable && it.reason == ReadUnavailable.UNPROVEN)
                    }
                }
                println("[${at()}] ${feed.priceId.id} / ${feed.quote.id}: $reading")
                assertIs<FeedReading.Verified>(reading, "${feed.priceId.id}'s feed")
            }

            // Each pinned pool's average, through the same port: a verified price, marked as an average.
            for (pool in UniswapPools.of(ethereum)) {
                val reading = withTimeout(5.minutes) {
                    node.priceFeeds.observe(ethereum, pool.priceId).first {
                        it !is FeedReading.Syncing &&
                            !(it is FeedReading.Unavailable && it.reason == ReadUnavailable.UNPROVEN)
                    }
                }
                println("[${at()}] ${pool.priceId.id} / ${pool.quote.id} (pool): $reading")
                val price = assertIs<FeedReading.Verified>(reading, "${pool.priceId.id}'s pool")
                assertEquals(true, price.poolAverage, "${pool.priceId.id} is priced by its pool")
            }

            for (token in BuiltInTokens.of(ethereum)) {
                val reading = withTimeout(5.minutes) {
                    node.tokenMetadata.observe(ethereum, token.address).first {
                        it !is MetadataReading.Syncing &&
                            !(it is MetadataReading.Unavailable && it.reason == ReadUnavailable.UNPROVEN)
                    }
                }
                println("[${at()}] ${token.symbol}: $reading")
                val said = assertIs<MetadataReading.Verified>(reading, token.symbol)
                assertEquals(token.symbol to token.decimals, said.symbol to said.decimals, "${token.symbol}'s contract")
            }

            val venue = withTimeout(5.minutes) {
                var reading: VenueReading
                do {
                    reading = node.uniswap.venue(ethereum)
                    if (reading == VenueReading.Unavailable(ReadUnavailable.UNPROVEN)) delay(2.seconds)
                } while (reading == VenueReading.Unavailable(ReadUnavailable.UNPROVEN))
                reading
            }
            println("[${at()}] Uniswap v3: $venue")
            assertIs<VenueReading.Verified>(venue, "the pins check against mainnet's code")

            val tokens = BuiltInTokens.of(ethereum).associateBy { it.symbol }
            val route = withTimeout(10.minutes) {
                var finding: RouteFinding
                do {
                    finding = FindUniswapRoute(node.uniswap, node.transactionInputs)(
                        ethereum,
                        TransferAsset.Erc20Token(tokens.getValue("USDC")),
                        TransferAsset.Erc20Token(tokens.getValue("WETH")),
                        UInt256.of(100_000_000),
                    )
                    val again = finding is RouteFinding.Unavailable && finding.reason == ReadUnavailable.UNPROVEN
                    if (again) delay(5.seconds)
                } while (again)
                finding
            }
            println("[${at()}] 100 USDC to WETH: $route")
            assertIs<RouteFinding.Found>(route)

            // A SafeL2 1.4.1 proxy its factory created at block 26,254,551 (read 2026-10-23).
            val safe = Address.of("0xe9f1880d600c36e874889d0fdbf6438c0e54ef34")
            val kind = withTimeout(5.minutes) {
                node.addressKinds.observeKind(ethereum, safe).first {
                    it !is KindReading.Syncing &&
                        !(it is KindReading.Unavailable && it.reason == ReadUnavailable.UNPROVEN)
                }
            }
            println("[${at()}] $safe: $kind")
            assertIs<AddressKind.Safe>(assertIs<KindReading.Verified>(kind).kind, "a recognised Safe")

            // diskordia.eth: a 1.1.1 proxy (SafeDeployments.kt's doc comment), migrated since by its
            // owners to the 1.3.0 L1 singleton above — recognised through the 1.1.1 pin alone.
            val oldSafe = Address.of("0x308686553a1eac2fe721ac8b814de638975a276e")
            val oldKind = withTimeout(5.minutes) {
                node.addressKinds.observeKind(ethereum, oldSafe).first {
                    it !is KindReading.Syncing &&
                        !(it is KindReading.Unavailable && it.reason == ReadUnavailable.UNPROVEN)
                }
            }
            println("[${at()}] $oldSafe: $oldKind")
            assertIs<AddressKind.Safe>(assertIs<KindReading.Verified>(oldKind).kind, "a recognised Safe")

            val name = withTimeout(5.minutes) {
                var resolution: NameResolution
                do {
                    resolution = node.names.resolve(ethereum, "vitalik.eth")
                    val again = resolution is NameResolution.Unavailable &&
                        resolution.reason == ReadUnavailable.UNPROVEN
                    if (again) delay(5.seconds)
                } while (again)
                resolution
            }
            println("[${at()}] vitalik.eth: $name")
            assertEquals(
                Address.of("0xd8da6bf26964af9d7eed9e03e53415d37aa96045"),
                assertIs<NameResolution.Resolved>(name).address,
                "mainnet ENS's registry",
            )
        } finally {
            progress.cancel()
            node.shutdown()
        }
    }
}
