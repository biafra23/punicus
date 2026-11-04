package eth.serotine.domain.usecase

import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.model.LogLevel
import eth.serotine.domain.model.LogRecord
import eth.serotine.domain.repository.DebugLog
import eth.serotine.domain.repository.DebugScreenSetting
import eth.serotine.domain.repository.InterfaceModeSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Whether the Debug tab is on, as Settings and the bottom bar show it. */
class ObserveDebugScreen(private val setting: DebugScreenSetting) {
    operator fun invoke(): Flow<Boolean> = setting.observe()
}

/** Turns the Debug tab on or off; false when that could not be saved. [FollowDebugScreen] does the rest. */
class SetDebugScreen(private val setting: DebugScreenSetting) {
    suspend operator fun invoke(enabled: Boolean): Boolean = setting.set(enabled)
}

/**
 * Whether the bottom bar shows the Debug tab: its switch is on and the screens are in Detailed
 * view, the tab being Detailed view's alone (docs/simple-ui.md). Simple mode hides it, and keeps
 * the switch for when Detailed view is turned on again.
 */
class ObserveDebugTab(
    private val setting: DebugScreenSetting,
    private val mode: InterfaceModeSetting,
) {
    operator fun invoke(): Flow<Boolean> =
        combine(setting.observe(), mode.observe()) { on, view -> on && view == InterfaceMode.DETAILED }
            .distinctUntilChanged()
}

/**
 * Keeps the debug log to the Debug tab ([ObserveDebugTab]), for as long as it runs: the work no
 * screen owns, which a host runs for the life of its process. While the tab is shown the log
 * records; while it is not (its switch off, or simple mode) it records nothing, holds nothing
 * and is back at [LogLevel.INFO], so neither the work of recording nor what a debugging session
 * recorded in detail stays behind a tab no longer shown.
 * Runs until cancelled.
 */
class FollowDebugScreen(
    private val debugTab: ObserveDebugTab,
    private val log: DebugLog,
) {
    suspend operator fun invoke() {
        debugTab().collect { enabled ->
            log.setRecording(enabled)
            if (!enabled) {
                log.setLevel(LogLevel.INFO)
                log.clear()
            }
        }
    }
}

/**
 * The debug log as the Debug tab shows it: its level, and the records kept at that level and
 * above, newest first. A record made at a lower level before the level was raised is left out.
 */
class ObserveDebugLog(private val log: DebugLog) {
    operator fun invoke(): Flow<DebugLogView> = combine(log.observeLevel(), log.observeRecords()) { level, records ->
        DebugLogView(level, records.filter { it.level >= level }.reversed())
    }
}

/** Sets the level the debug log records at. */
class SetDebugLogLevel(private val log: DebugLog) {
    operator fun invoke(level: LogLevel) = log.setLevel(level)
}

/** Empties the debug log. */
class ClearDebugLog(private val log: DebugLog) {
    operator fun invoke() = log.clear()
}

/** The debug log's [level], and its [records] at that level and above, newest first. */
data class DebugLogView(val level: LogLevel, val records: List<LogRecord>)
