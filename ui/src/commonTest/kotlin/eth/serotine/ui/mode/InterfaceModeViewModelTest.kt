package eth.serotine.ui.mode

import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.repository.InterfaceModeSetting
import eth.serotine.domain.usecase.ObserveInterfaceMode
import eth.serotine.ui.MemoryInterfaceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class InterfaceModeViewModelTest {

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun theViewIsUnknownUntilReadThenFollowsTheSetting() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val reads = MutableSharedFlow<InterfaceMode>()
        val setting = object : InterfaceModeSetting {
            override fun observe(): Flow<InterfaceMode> = reads
            override suspend fun set(mode: InterfaceMode): Boolean = error("not used by this test")
        }
        val viewModel = InterfaceModeViewModel(ObserveInterfaceMode(setting))
        runCurrent()
        assertNull(viewModel.mode.value, "nothing read yet: no guess")

        reads.emit(InterfaceMode.DETAILED)
        assertEquals(InterfaceMode.DETAILED, viewModel.mode.value)

        reads.emit(InterfaceMode.SIMPLE)
        assertEquals(InterfaceMode.SIMPLE, viewModel.mode.value)
    }

    @Test
    fun simpleModeIsWhatAFreshSettingReads() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = InterfaceModeViewModel(ObserveInterfaceMode(MemoryInterfaceMode()))
        runCurrent()
        assertEquals(InterfaceMode.SIMPLE, viewModel.mode.value)
    }
}
