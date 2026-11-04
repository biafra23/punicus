package eth.serotine.domain.usecase

import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.model.LogLevel
import eth.serotine.domain.model.LogRecord
import eth.serotine.domain.repository.DebugLog
import eth.serotine.domain.repository.DebugScreenSetting
import eth.serotine.domain.repository.InterfaceModeSetting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Debug tab's switch and its log, over fake ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugLogsTest {

    private val log = MemoryLog()
    private val setting = MemorySetting()
    private val mode = MemoryMode(InterfaceMode.DETAILED)

    @Test
    fun theLogShowsTheRecordsAtItsLevelAndAboveNewestFirst() = runTest {
        log.records.value = listOf(record(1, LogLevel.INFO), record(2, LogLevel.DEBUG), record(3, LogLevel.INFO))

        val info = ObserveDebugLog(log)().first()
        assertEquals(LogLevel.INFO, info.level)
        assertEquals(listOf(3L, 1L), info.records.map { it.id })

        SetDebugLogLevel(log)(LogLevel.DEBUG)
        assertEquals(listOf(3L, 2L, 1L), ObserveDebugLog(log)().first().records.map { it.id })
    }

    @Test
    fun clearingEmptiesTheLog() = runTest {
        log.records.value = listOf(record(1, LogLevel.INFO))

        ClearDebugLog(log)()

        assertTrue(ObserveDebugLog(log)().first().records.isEmpty())
    }

    @Test
    fun theSwitchIsSavedOrSaysItWasNot() = runTest {
        assertTrue(SetDebugScreen(setting)(true))
        assertTrue(ObserveDebugScreen(setting)().first())

        setting.failSaves = true
        assertFalse(SetDebugScreen(setting)(false))
        assertTrue(ObserveDebugScreen(setting)().first())
    }

    @Test
    fun theLogRecordsWhileTheTabIsOnAndIsEmptiedAndBackAtInfoOnceItIsOff() = runTest {
        backgroundScope.launch { FollowDebugScreen(ObserveDebugTab(setting, mode), log)() }
        runCurrent()
        assertFalse(log.recordingNow, "off from the start: nothing recorded")

        setting.enabled.value = true
        runCurrent()
        assertTrue(log.recordingNow)
        log.level.value = LogLevel.DEBUG
        log.records.value = listOf(record(1, LogLevel.DEBUG))

        setting.enabled.value = false
        runCurrent()
        assertFalse(log.recordingNow)
        assertEquals(LogLevel.INFO, log.level.value)
        assertTrue(log.records.value.isEmpty())
    }

    @Test
    fun theTabShowsOnlyInDetailedViewAndTheLogFollowsIt() = runTest {
        setting.enabled.value = true
        mode.mode.value = InterfaceMode.SIMPLE
        backgroundScope.launch { FollowDebugScreen(ObserveDebugTab(setting, mode), log)() }
        runCurrent()
        assertFalse(ObserveDebugTab(setting, mode)().first(), "simple mode hides it, its switch on")
        assertFalse(log.recordingNow)

        mode.mode.value = InterfaceMode.DETAILED
        runCurrent()
        assertTrue(ObserveDebugTab(setting, mode)().first(), "the switch was kept")
        assertTrue(log.recordingNow)
        log.records.value = listOf(record(1, LogLevel.INFO))

        mode.mode.value = InterfaceMode.SIMPLE
        runCurrent()
        assertFalse(log.recordingNow)
        assertTrue(log.records.value.isEmpty())
        assertTrue(setting.enabled.value, "the switch itself stays on")
    }

    private fun record(id: Long, level: LogLevel) = LogRecord(id, 1_000 * id, level, "Myotis", "request $id")

    private class MemoryLog : DebugLog {
        val records = MutableStateFlow<List<LogRecord>>(emptyList())
        val level = MutableStateFlow(LogLevel.INFO)
        var recordingNow = true

        override fun observeRecords(): Flow<List<LogRecord>> = records
        override fun observeLevel(): Flow<LogLevel> = level
        override fun setLevel(level: LogLevel) {
            this.level.value = level
        }
        override fun clear() {
            records.value = emptyList()
        }
        override fun setRecording(recording: Boolean) {
            recordingNow = recording
        }
    }

    private class MemoryMode(initial: InterfaceMode) : InterfaceModeSetting {
        val mode = MutableStateFlow(initial)

        override fun observe(): Flow<InterfaceMode> = mode
        override suspend fun set(mode: InterfaceMode): Boolean {
            this.mode.value = mode
            return true
        }
    }

    private class MemorySetting : DebugScreenSetting {
        val enabled = MutableStateFlow(false)
        var failSaves = false

        override fun observe(): Flow<Boolean> = enabled
        override suspend fun set(enabled: Boolean): Boolean {
            if (failSaves) return false
            this.enabled.value = enabled
            return true
        }
    }
}
