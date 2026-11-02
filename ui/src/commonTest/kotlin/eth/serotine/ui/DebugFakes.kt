package eth.serotine.ui

import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.model.LogLevel
import eth.serotine.domain.model.LogRecord
import eth.serotine.domain.repository.AmountsHiddenSetting
import eth.serotine.domain.repository.DebugLog
import eth.serotine.domain.repository.DebugScreenSetting
import eth.serotine.domain.repository.InterfaceModeSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A [DebugScreenSetting] in memory; [failSaves] makes a switch fail. */
internal class MemoryDebugScreen(enabled: Boolean = false) : DebugScreenSetting {
    val enabled = MutableStateFlow(enabled)
    var failSaves = false

    override fun observe(): Flow<Boolean> = enabled

    override suspend fun set(enabled: Boolean): Boolean {
        if (failSaves) return false
        this.enabled.value = enabled
        return true
    }
}

/** An [InterfaceModeSetting] in memory, simple mode as in every build; [failSaves] makes a choice fail. */
internal class MemoryInterfaceMode(mode: InterfaceMode = InterfaceMode.SIMPLE) : InterfaceModeSetting {
    val mode = MutableStateFlow(mode)
    var failSaves = false

    override fun observe(): Flow<InterfaceMode> = this.mode

    override suspend fun set(mode: InterfaceMode): Boolean {
        if (failSaves) return false
        this.mode.value = mode
        return true
    }
}

/** An [AmountsHiddenSetting] in memory: [hidden] as a test sets it, and as the app saves it unless [failSaves]. */
internal class MemoryAmountsHidden(hidden: Boolean = false) : AmountsHiddenSetting {
    val hidden = MutableStateFlow(hidden)
    var failSaves = false

    override fun observe(): Flow<Boolean> = this.hidden

    override suspend fun set(hidden: Boolean): Boolean {
        if (failSaves) return false
        this.hidden.value = hidden
        return true
    }
}

/** A [DebugLog] whose [records] and [level] a test sets. */
internal class FakeDebugLog : DebugLog {
    val records = MutableStateFlow<List<LogRecord>>(emptyList())
    val level = MutableStateFlow(LogLevel.INFO)

    override fun observeRecords(): Flow<List<LogRecord>> = records

    override fun observeLevel(): Flow<LogLevel> = level

    override fun setLevel(level: LogLevel) {
        this.level.value = level
    }

    override fun clear() {
        records.value = emptyList()
    }

    var recordingNow = true

    override fun setRecording(recording: Boolean) {
        recordingNow = recording
    }

    /** A record [id] from the engine, at [level], [epochMillis] its id in seconds. */
    fun record(id: Long, level: LogLevel = LogLevel.INFO, message: String = "request $id") =
        LogRecord(id, id * 1_000, level, "Myotis", message)
}
