package eth.serotine.ui.debug

import eth.serotine.domain.model.LogLevel

/**
 * Everything the Debug tab renders: the debug log's [level], the [filter] typed, the [lines]
 * that match it, newest first, and how many records the log holds at that level ([recorded]),
 * so the screen can tell an empty log from a filter that matches nothing. [DebugRoute] shows
 * `DebugViewModel.filterText` in place of [filter], which is made off the main thread and lags the typing.
 */
data class DebugUiState(
    val level: LogLevel = LogLevel.INFO,
    val filter: String = "",
    val lines: List<DebugLogLine> = emptyList(),
    val recorded: Int = 0,
)

/**
 * One record of the debug log as a line: its [time] of day ("14:03:27.481"), its [level], and
 * its [text], the source and what it says ("Myotis · requestAccount: ok, 34 ms"). [id] keeps
 * the line's place in a list as newer ones come in above it.
 */
data class DebugLogLine(
    val id: Long,
    val time: String,
    val level: LogLevel,
    val text: String,
)
