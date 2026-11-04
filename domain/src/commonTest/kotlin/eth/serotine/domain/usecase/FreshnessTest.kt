package eth.serotine.domain.usecase

import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.InterfaceModeSetting
import eth.serotine.domain.repository.NetworkFreshness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How fresh values are kept, over fake ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class FreshnessTest {

    private val freshness = object : NetworkFreshness {
        val live = MutableStateFlow(setOf(Network.SEPOLIA))
        val refreshed = mutableListOf<Network>()
        val onDemand = mutableListOf<Boolean>()

        override fun observeLive(network: Network): Flow<Boolean> =
            MutableStateFlow(network in live.value)

        override fun refresh(network: Network) {
            refreshed += network
        }

        override fun setOnDemand(onDemand: Boolean) {
            this.onDemand += onDemand
        }
    }

    private val mode = object : InterfaceModeSetting {
        val mode = MutableStateFlow(InterfaceMode.SIMPLE)
        override fun observe(): Flow<InterfaceMode> = mode
        override suspend fun set(mode: InterfaceMode): Boolean {
            this.mode.value = mode
            return true
        }
    }

    @Test
    fun aNetworkSaysWhetherItIsLive() = runTest {
        assertTrue(ObserveNetworkLive(freshness)(Network.SEPOLIA).first())
        assertEquals(false, ObserveNetworkLive(freshness)(Network.ETHEREUM).first())
    }

    @Test
    fun aRefreshAsksEachNetworkOnce() {
        RefreshNetworkData(freshness)(setOf(Network.ETHEREUM, Network.GNOSIS))
        assertEquals(setOf(Network.ETHEREUM, Network.GNOSIS), freshness.refreshed.toSet())
        assertEquals(2, freshness.refreshed.size)
    }

    @Test
    fun valuesAreProvenOnDemandInSimpleModeAndAtEveryHeadInDetailedView() = runTest {
        backgroundScope.launch { FollowInterfaceMode(mode, freshness)() }
        runCurrent()
        assertEquals(listOf(true), freshness.onDemand)

        mode.mode.value = InterfaceMode.DETAILED
        runCurrent()
        assertEquals(listOf(true, false), freshness.onDemand)
    }
}
