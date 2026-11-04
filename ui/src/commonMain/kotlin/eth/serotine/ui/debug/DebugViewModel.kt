package eth.serotine.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.LogLevel
import eth.serotine.domain.model.LogRecord
import eth.serotine.domain.usecase.ClearDebugLog
import eth.serotine.domain.usecase.ObserveDebugLog
import eth.serotine.domain.usecase.SetDebugLogLevel
import eth.serotine.ui.platform.formatTimeOfDay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * The Debug tab's ViewModel: the debug log at the level chosen, filtered by the text typed, and
 * the intents that change the level, the filter and empty the log. The lines are made on
 * [background], never the main thread, and each record's line once: a busy log adds a record or
 * two a second to as many as it keeps.
 */
class DebugViewModel(
    observeDebugLog: ObserveDebugLog,
    private val setDebugLogLevel: SetDebugLogLevel,
    private val clearDebugLog: ClearDebugLog,
    /** A record's time, unix milliseconds, as the time of day it is shown with. */
    private val formatTime: (Long) -> String = ::formatTimeOfDay,
    background: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val filter = MutableStateFlow("")

    /**
     * The text typed, at once: the field shows this rather than [uiState]'s copy, which is made on
     * the background dispatcher and arrives after the next keystroke, moving the cursor back.
     */
    val filterText: StateFlow<String> = filter.asStateFlow()

    /** The lines last shown, by record id: read and replaced by [uiState]'s flow alone, one emission at a time. */
    private var made = emptyMap<Long, DebugLogLine>()

    val uiState: StateFlow<DebugUiState> =
        combine(observeDebugLog(), filter) { log, filter ->
            val known = made
            val lines = log.records.map { known[it.id] ?: line(it) }
            made = lines.associateBy { it.id }
            val needle = filter.trim()
            DebugUiState(
                level = log.level,
                filter = filter,
                lines = if (needle.isEmpty()) lines else lines.filter { it.text.contains(needle, ignoreCase = true) },
                recorded = lines.size,
            )
        }
            .flowOn(background)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugUiState())

    private fun line(record: LogRecord) =
        DebugLogLine(record.id, formatTime(record.epochMillis), record.level, "${record.source} · ${record.message}")

    /** Records at [level] and above from now on, and shows those. */
    fun setLevel(level: LogLevel) = setDebugLogLevel(level)

    /** Shows only the lines that contain [text], in any case; blank shows them all. */
    fun setFilter(text: String) {
        filter.value = text
    }

    /** Empties the log. */
    fun clear() = clearDebugLog()
}
