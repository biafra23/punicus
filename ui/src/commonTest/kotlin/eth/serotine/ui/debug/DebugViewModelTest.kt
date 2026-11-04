package eth.serotine.ui.debug

import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.model.LogLevel
import eth.serotine.domain.usecase.ClearDebugLog
import eth.serotine.domain.usecase.ObserveDebugLog
import eth.serotine.domain.usecase.ObserveDebugTab
import eth.serotine.domain.usecase.SetDebugLogLevel
import eth.serotine.ui.FakeDebugLog
import eth.serotine.ui.MemoryDebugScreen
import eth.serotine.ui.MemoryInterfaceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DebugViewModelTest {

    private val log = FakeDebugLog()

    private fun TestScope.viewModel() = DebugViewModel(
        ObserveDebugLog(log),
        SetDebugLogLevel(log),
        ClearDebugLog(log),
        formatTime = { "t$it" },
        background = UnconfinedTestDispatcher(testScheduler),
    )

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun showsTheRecordsAtTheLevelNewestFirstAndChangesIt() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        log.records.value = listOf(
            log.record(1, message = "requestAccount: ok, 34 ms"),
            log.record(2, LogLevel.DEBUG, message = "requestAccount(address=0x…) → …"),
        )
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val info = viewModel.uiState.value
        assertEquals(LogLevel.INFO, info.level)
        assertEquals(listOf(DebugLogLine(1, "t1000", LogLevel.INFO, "Myotis · requestAccount: ok, 34 ms")), info.lines)
        assertEquals(1, info.recorded)

        viewModel.setLevel(LogLevel.DEBUG)
        runCurrent()
        assertEquals(LogLevel.DEBUG, log.level.value)
        assertEquals(listOf(2L, 1L), viewModel.uiState.value.lines.map { it.id })
    }

    @Test
    fun theFilterKeepsTheLinesThatContainItInAnyCase() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        log.records.value = listOf(
            log.record(1, message = "status: ok, 0 ms"),
            log.record(2, message = "getLogs: failed, 12 ms"),
        )
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.setFilter(" GETLOGS ")
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(" GETLOGS ", state.filter)
        assertEquals(listOf(2L), state.lines.map { it.id })
        assertEquals(2, state.recorded)

        viewModel.setFilter("")
        runCurrent()
        assertEquals(2, viewModel.uiState.value.lines.size)
    }

    @Test
    fun theFilterTextIsThereAtOnceWithoutTheUiStateCatchingUp() = runTest {
        val viewModel = viewModel()

        viewModel.setFilter("ge")
        viewModel.setFilter("get")

        assertEquals("get", viewModel.filterText.value)
    }

    @Test
    fun clearEmptiesTheLog() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        log.records.value = listOf(log.record(1))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.clear()
        runCurrent()

        assertTrue(viewModel.uiState.value.lines.isEmpty())
        assertEquals(0, viewModel.uiState.value.recorded)
    }

    @Test
    fun theTabFollowsTheSettingInDetailedViewOnly() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val setting = MemoryDebugScreen()
        val mode = MemoryInterfaceMode(InterfaceMode.DETAILED)
        val tab = DebugTabViewModel(ObserveDebugTab(setting, mode))
        runCurrent()
        assertEquals(false, tab.shown.value)

        setting.enabled.value = true
        runCurrent()
        assertEquals(true, tab.shown.value)

        mode.mode.value = InterfaceMode.SIMPLE
        runCurrent()
        assertEquals(false, tab.shown.value)
    }
}
