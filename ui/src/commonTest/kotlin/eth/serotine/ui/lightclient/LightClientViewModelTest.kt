package eth.serotine.ui.lightclient

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.ui.components.LightClientHealth
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class LightClientViewModelTest {

    private val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)
    private val lightClient = object : LightClient {
        override val datesFinalizedBlocks = true
        override fun observeStatus(network: Network): Flow<LightClientStatus> = status
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun followsTheLightClientFromStartToAVerifiedHead() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = LightClientViewModel(ObserveLightClient(lightClient), clock = { 1_000 })
        backgroundScope.launch { viewModel.item(Network.SEPOLIA).collect {} }

        assertEquals("Starting the light client…", viewModel.item(Network.SEPOLIA).value.text)
        assertEquals(LightClientHealth.NOT_VERIFIED, viewModel.item(Network.SEPOLIA).value.health)

        status.value = LightClientStatus.CatchingUp(done = 1, total = 4)
        runCurrent()
        assertEquals("Verifying sync committees · 1 of 4 periods", viewModel.item(Network.SEPOLIA).value.text)
        assertEquals(0.25f, viewModel.item(Network.SEPOLIA).value.progress)

        status.value = LightClientStatus.WaitingForHead(headAgeSeconds = 240)
        runCurrent()
        assertEquals(LightClientHealth.STALE, viewModel.item(Network.SEPOLIA).value.health)

        status.value = LightClientStatus.Synced(headBlock = 9_412_118, headTimestamp = 992, finalized = null)
        runCurrent()
        assertEquals("Verified at block 9,412,118 · 8 s ago", viewModel.item(Network.SEPOLIA).value.text)
        assertEquals(LightClientHealth.VERIFIED, viewModel.item(Network.SEPOLIA).value.health)
    }

    @Test
    fun stopsFollowingWithNoScreenAndAScreenOpenedLaterNeverStartsFromStarting() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = LightClientViewModel(ObserveLightClient(lightClient), clock = { 1_000 })
        val screen = backgroundScope.launch { viewModel.item(Network.SEPOLIA).collect {} }
        status.value = LightClientStatus.CatchingUp(done = 1, total = 4)
        runCurrent()
        screen.cancel()

        advanceTimeBy(6_000)
        assertEquals(0, status.subscriptionCount.value)
        // The last item stays until the next screen's first answer replaces it.
        assertEquals("Verifying sync committees · 1 of 4 periods", viewModel.item(Network.SEPOLIA).value.text)

        status.value = LightClientStatus.Synced(headBlock = 9_412_118, headTimestamp = 992, finalized = null)
        backgroundScope.launch { viewModel.item(Network.SEPOLIA).collect {} }
        runCurrent()
        assertEquals(1, status.subscriptionCount.value)
        assertEquals("Verified at block 9,412,118 · 8 s ago", viewModel.item(Network.SEPOLIA).value.text)
    }
}
