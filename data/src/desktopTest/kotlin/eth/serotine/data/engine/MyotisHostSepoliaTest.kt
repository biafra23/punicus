package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientRun
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.ReadUnavailable
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
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The real engine on the real Sepolia: boots a [MyotisHost] and waits for a verified
 * balance, and finds a Uniswap route, timed (docs/swaps.md: how long a quote takes). Needs
 * the network and the engine's host library, so it is skipped unless run with
 * `-PmyotisLibraryDir=<dir with libmyotis_engine>` (data/build.gradle.kts passes it on as
 * java.library.path), e.g.
 * `./gradlew :data:desktopTest --tests '*MyotisHostSepoliaTest*' -PmyotisLibraryDir=…`.
 */
class MyotisHostSepoliaTest {

    @Test
    fun aSyncedNodeReadsAVerifiedSepoliaBalance(): Unit = runBlocking {
        assumeTrue("needs -PmyotisLibraryDir", System.getProperty("serotine.myotisLibraryDir") != null)
        // A fresh directory: a cold start from the embedded checkpoint, every phase shown.
        val node = MyotisHost.start(Files.createTempDirectory("myotis-sepolia").toFile())
        node.set(Network.SEPOLIA, LightClientRun.RUN)
        val started = System.nanoTime()
        val progress = launch {
            node.lightClient.observeStatus(Network.SEPOLIA).collect {
                println("light client after ${(System.nanoTime() - started) / 1_000_000_000}s: $it")
            }
        }
        try {
            // The Sepolia deposit contract: a balance that exists on every Sepolia state.
            val address = Address.of("0x7f02c3e3c98b133055b8b348b2ac625669ed295d")
            val reading = withTimeout(10.minutes) {
                // Past "not verified" (a proof no peer has served yet); a refusal ends it at once.
                node.nativeBalances.observeBalance(Network.SEPOLIA, address).first {
                    it is BalanceReading.Verified ||
                        (it is BalanceReading.Unavailable && it.reason != ReadUnavailable.UNPROVEN)
                }
            }
            println("Sepolia balance of $address: $reading")
            assertIs<BalanceReading.Verified>(reading)
        } finally {
            progress.cancel()
            node.shutdown()
        }
    }

    @Test
    fun aSyncedNodeFindsAUniswapRouteForOneUsdc(): Unit = runBlocking {
        assumeTrue("needs -PmyotisLibraryDir", System.getProperty("serotine.myotisLibraryDir") != null)
        val node = MyotisHost.start(Files.createTempDirectory("myotis-sepolia").toFile())
        node.set(Network.SEPOLIA, LightClientRun.RUN)
        try {
            val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, icon = null)
            val link = Token(Address.of("0x779877a7b0d9e8603169ddbd7836e478b4624789"), "LINK", "ChainLink", 18, null)
            val find = FindUniswapRoute(node.uniswap, node.transactionInputs)
            val finding = withTimeout(10.minutes) {
                // Until the light client can verify reads: before that every read is "not verified".
                var finding: RouteFinding
                do {
                    val started = System.nanoTime()
                    finding = find(
                        Network.SEPOLIA,
                        TransferAsset.Erc20Token(usdc),
                        TransferAsset.Erc20Token(link),
                        UInt256.of(1_000_000),
                    )
                    println("after ${(System.nanoTime() - started) / 1_000_000} ms: $finding")
                    val again = finding is RouteFinding.Unavailable && finding.reason == ReadUnavailable.UNPROVEN
                    if (again) delay(5.seconds)
                } while (again)
                finding
            }
            assertIs<RouteFinding.Found>(finding)
        } finally {
            node.shutdown()
        }
    }
}
