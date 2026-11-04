package eth.serotine.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.LogLevel

/** Test tag of the debug log's list, for UI tests. */
const val DEBUG_LOG_TAG = "debug-log"

/** Binds [DebugScreen] to its ViewModel. */
@Composable
fun DebugRoute(viewModel: DebugViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filter by viewModel.filterText.collectAsStateWithLifecycle()
    DebugScreen(state.copy(filter = filter), viewModel::setLevel, viewModel::setFilter, viewModel::clear, modifier)
}

/**
 * The Debug tab as a pure function of [state]: the debug log, newest first, at the level chosen
 * through [onLevel], showing the lines that contain the text typed into [onFilter]; [onClear]
 * empties it. Every line can be selected and copied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    state: DebugUiState,
    onLevel: (LogLevel) -> Unit,
    onFilter: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Debug") },
                actions = {
                    // Never greyed out: clearing an empty log is no harm, and the log can hold
                    // records at a level the screen does not show.
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear the log")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Light client requests",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                LevelChoice(state.level, onLevel)
                Text(
                    when (state.level) {
                        LogLevel.INFO -> "Each request's name, result and time."
                        LogLevel.DEBUG -> "Each request's name, result and time, and what it asked and got back."
                    } + " Kept in memory on this device only, the newest first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.filter,
                    onValueChange = onFilter,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Filter") },
                    singleLine = true,
                    trailingIcon = if (state.filter.isEmpty()) {
                        null
                    } else {
                        {
                            IconButton(onClick = { onFilter("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear the filter")
                            }
                        }
                    },
                    supportingText = {
                        val records = if (state.recorded == 1) "record" else "records"
                        Text(
                            if (state.lines.size == state.recorded) {
                                "${state.recorded} $records"
                            } else {
                                "${state.lines.size} of ${state.recorded} $records"
                            },
                        )
                    },
                )
            }
            HorizontalDivider()
            if (state.lines.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                    Text(
                        if (state.recorded == 0) {
                            "Nothing recorded yet."
                        } else {
                            "No record contains “${state.filter.trim()}”."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LogLines(state.lines)
            }
        }
    }
}

/**
 * [lines], newest first, keyed by record: at the top of the list a newer line comes into view as
 * it arrives (the list stays at its first item), and scrolled down, the lines read stay put.
 */
@Composable
private fun LogLines(lines: List<DebugLogLine>) {
    SelectionContainer {
        LazyColumn(
            Modifier.fillMaxSize().testTag(DEBUG_LOG_TAG),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(lines, key = { it.id }) { line -> LogLine(line) }
        }
    }
}

@Composable
private fun LevelChoice(level: LogLevel, onLevel: (LogLevel) -> Unit) {
    val levels = listOf(LogLevel.INFO, LogLevel.DEBUG)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        levels.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = level == choice,
                onClick = { onLevel(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, levels.size),
                label = { Text(choice.name) },
            )
        }
    }
}

@Composable
private fun LogLine(line: DebugLogLine) {
    val detail = line.level == LogLevel.DEBUG
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "${line.time}  ${line.level.name}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (detail) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}
