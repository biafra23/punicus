package eth.serotine.data.engine

import eth.serotine.data.prices.ChainlinkFeeds
import eth.serotine.data.tokens.BuiltInTokens
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.LightClientRun
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.VenueReading
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Gnosis Chain's pins on the real engine and the real chain (docs/networks.md, part 5): boots a
 * [MyotisHost] with Gnosis Chain's light client (the engine's `gnosis`), then proves through it a
 * balance, every pinned Chainlink feed (a verified price, at the pinned decimals), every built-in
 * token (its contract's own symbol and decimals, as listed) and a SafeL2 1.4.1 proxy recognised
 * as one (its proxy and singleton against the pinned hashes), and finds no Uniswap v3 deployment
 * pinned. Needs the network and the engine's host library, so it is skipped unless run with
 * `-PmyotisLibraryDir=<dir with libmyotis_engine>`, e.g.
 * `./gradlew :data:desktopTest --tests '*MyotisHostGnosisTest*' -PmyotisLibraryDir=…`.
 *
 * With engine 0.1.19-rc2 the release's embedded Gnosis anchor was already 6 sync-committee
 * periods old on 2026-10-23, past the bound of 3 (≈ 34 h at Gnosis's 11.4-hour periods), and the
 * light client refuses to sync (`StaleAnchor`; biafra23/myotis#703). The test then consents as a
 * user would on Accounts ([MyotisHost.syncPastStaleAnchor]), which also proves that path.
 */
class MyotisHostGnosisTest {

    private val gnosis = Network.GNOSIS

    @Test
    fun eachOfGnosisChainsPinsIsProvenOnIt(): Unit = runBlocking {
        assumeTrue("needs -PmyotisLibraryDir", System.getProperty("serotine.myotisLibraryDir") != null)
        // A fresh directory: a cold start from the embedded checkpoint, every phase shown.
        val node = MyotisHost.start(Files.createTempDirectory("myotis-gnosis").toFile())
        node.set(gnosis, LightClientRun.RUN)
        val started = System.nanoTime()
        fun at() = "${(System.nanoTime() - started) / 1_000_000_000}s"
        val progress = launch {
            node.lightClient.observeStatus(gnosis).collect {
                println("[${at()}] light client: $it")
                if (it is LightClientStatus.StaleAnchor) node.syncPastStaleAnchor(gnosis)
            }
        }
        try {
            // WXDAI's contract, which holds the xDAI it wraps: a balance on every Gnosis Chain state.
            val wxdai = Address.of("0xe91d153e0b41518a2ce8dd3d7944fa863463a97d")
            val balance = withTimeout(15.minutes) {
                node.nativeBalances.observeBalance(gnosis, wxdai).first {
                    it is BalanceReading.Verified ||
                        (it is BalanceReading.Unavailable && it.reason != ReadUnavailable.UNPROVEN)
                }
            }
            println("[${at()}] WXDAI's contract: $balance")
            assertIs<BalanceReading.Verified>(balance)

            for (feed in ChainlinkFeeds.of(gnosis)) {
                val reading = withTimeout(5.minutes) {
                    node.priceFeeds.observe(gnosis, feed.priceId).first {
                        it !is FeedReading.Syncing &&
                            !(it is FeedReading.Unavailable && it.reason == ReadUnavailable.UNPROVEN)
                    }
                }
                println("[${at()}] ${feed.priceId.id} / ${feed.quote.id}: $reading")
                assertIs<FeedReading.Verified>(reading, "${feed.priceId.id}'s feed")
            }

            for (token in BuiltInTokens.of(gnosis)) {
                val reading = withTimeout(5.minutes) {
                    node.tokenMetadata.observe(gnosis, token.address).first {
                        it !is MetadataReading.Syncing &&
                            !(it is MetadataReading.Unavailable && it.reason == ReadUnavailable.UNPROVEN)
                    }
                }
                println("[${at()}] ${token.symbol}: $reading")
                val said = assertIs<MetadataReading.Verified>(reading, token.symbol)
                assertEquals(token.symbol to token.decimals, said.symbol to said.decimals, "${token.symbol}'s contract")
            }

            // No venue pinned: Swap is greyed out on Gnosis Chain (open question 7).
            assertEquals(VenueReading.NotPinned, node.uniswap.venue(gnosis))

            // A SafeL2 1.4.1 proxy the canonical factory created at Gnosis block 48,904,733 (read 2026-10-23).
            val safe = Address.of("0xe5bd8043a55682958ca0e69bd0d39f7509805f8d")
            val kind = withTimeout(5.minutes) {
                node.addressKinds.observeKind(gnosis, safe).first {
                    it !is KindReading.Syncing &&
                        !(it is KindReading.Unavailable && it.reason == ReadUnavailable.UNPROVEN)
                }
            }
            println("[${at()}] $safe: $kind")
            assertIs<AddressKind.Safe>(assertIs<KindReading.Verified>(kind).kind, "a recognised Safe")
        } finally {
            progress.cancel()
            node.shutdown()
        }
    }
}
