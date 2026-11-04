package eth.serotine.data.engine

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class EngineNetworkUpgradesTest {

    private val chain = FakeChain()
    private val glamsterdam = NetworkUpgrade(NetworkUpgrade.Phase.SCHEDULED, 1_791_294_816, "0x6c1d9423", 5)

    private fun TestScope.upgrades(chain: VerifiedChain? = this@EngineNetworkUpgradesTest.chain) =
        EngineNetworkUpgrades(Network.SEPOLIA, chain, StandardTestDispatcher(testScheduler), POLL)

    @Test
    fun followsTheAdvisoryAsTheEngineReportsItAsleepIncluded() = runTest {
        val seen = mutableListOf<NetworkUpgrade?>()
        val collecting = launch { upgrades().observeUpgrade(Network.SEPOLIA).collect { seen += it } }
        fun step(snapshot: EngineSnapshot) {
            chain.snapshot = snapshot
            advanceTimeBy(POLL)
            runCurrent()
        }
        runCurrent() // booting: nothing reported
        step(running())
        step(running(upgrade = glamsterdam))
        step(running(upgrade = glamsterdam, head = 101)) // the same report: not repeated
        step(running(upgrade = glamsterdam.copy(reportingNetworks = 7), paused = true))
        step(running())

        assertEquals(listOf(null, glamsterdam, glamsterdam.copy(reportingNetworks = 7), null), seen)
        collecting.cancel()
    }

    @Test
    fun aDownEngineOrNoneReportsNothingOnceAndIsNotPolled() = runTest {
        chain.snapshot = EngineSnapshot.Down
        assertEquals(listOf<NetworkUpgrade?>(null), upgrades().observeUpgrade(Network.SEPOLIA).toList())
        assertEquals(1, chain.snapshots)
        assertEquals(listOf<NetworkUpgrade?>(null), upgrades(chain = null).observeUpgrade(Network.SEPOLIA).toList())
    }

    @Test
    fun aFailedPollIsSkippedNotReportedAsNone() = runTest {
        val seen = mutableListOf<NetworkUpgrade?>()
        chain.failure = IllegalStateException("engine busy")
        val collecting = launch { upgrades().observeUpgrade(Network.SEPOLIA).collect { seen += it } }
        runCurrent()

        chain.failure = null
        chain.snapshot = running(upgrade = glamsterdam)
        advanceTimeBy(POLL)
        runCurrent()

        assertEquals(listOf<NetworkUpgrade?>(glamsterdam), seen)
        collecting.cancel()
    }

    private companion object {
        val POLL = 4.seconds
    }
}
