package eth.serotine.ui.debug

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.LogLevel
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Debug tab in Android Studio's preview pane, one preview per state it draws differently.
// Built from display-ready values, as the screen tests are, never from the ViewModel; the
// addresses and hashes are made up. DebugScreenPreviewsTest renders each one in
// debugScreenPreviews, at the end.

private const val ACCOUNT = "0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"

private val infoLines = listOf(
    DebugLogLine(6, "14:03:28.102", LogLevel.INFO, "Myotis · status: ok, 0 ms"),
    DebugLogLine(5, "14:03:27.913", LogLevel.INFO, "Myotis · callDetailed: ok, 212 ms"),
    DebugLogLine(4, "14:03:27.481", LogLevel.INFO, "Myotis · requestAccount: ok, 34 ms"),
    DebugLogLine(3, "14:03:25.020", LogLevel.INFO, "Myotis · getStorageProof: failed, 90012 ms"),
    DebugLogLine(2, "14:03:24.877", LogLevel.INFO, "Myotis · ens.resolveAddress: ok, 640 ms"),
    DebugLogLine(1, "14:03:24.006", LogLevel.INFO, "Myotis · status: ok, 1 ms"),
)

private val info = DebugUiState(LogLevel.INFO, lines = infoLines, recorded = infoLines.size)

private val debugLines = listOf(
    DebugLogLine(4, "14:03:27.481", LogLevel.INFO, "Myotis · requestAccount: ok, 34 ms"),
    DebugLogLine(
        3,
        "14:03:27.481",
        LogLevel.DEBUG,
        "Myotis · requestAccount(address=$ACCOUNT) → ProvenBalance(wei=250000000000000000, blockNumber=9412118)",
    ),
    DebugLogLine(
        2,
        "14:03:27.102",
        LogLevel.INFO,
        "Myotis · getTransactionCount: failed (IllegalStateException), 3 ms",
    ),
    DebugLogLine(
        1,
        "14:03:27.102",
        LogLevel.DEBUG,
        "Myotis · getTransactionCount(address=$ACCOUNT, block=pending) → threw IllegalStateException: no peer",
    ),
)

private val debug = DebugUiState(LogLevel.DEBUG, lines = debugLines, recorded = debugLines.size)

@Composable
private fun DebugPreview(state: DebugUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        DebugScreen(state = state, onLevel = {}, onFilter = {}, onClear = {})
    }
}

@Preview
@Composable
private fun DebugInfoPreview() = DebugPreview(info)

@Preview
@Composable
private fun DebugInfoDarkPreview() = DebugPreview(info, darkTheme = true)

/** At DEBUG: each request's line, and under it what it asked and got back. */
@Preview
@Composable
private fun DebugDetailPreview() = DebugPreview(debug)

@Preview
@Composable
private fun DebugEmptyPreview() = DebugPreview(DebugUiState())

/** A filter typed: the lines that contain it, out of all those recorded. */
@Preview
@Composable
private fun DebugFilteredPreview() = DebugPreview(
    info.copy(filter = "status", lines = infoLines.filter { "status" in it.text }),
)

/** A filter that no record contains. */
@Preview
@Composable
private fun DebugNoMatchPreview() = DebugPreview(info.copy(filter = "sendRaw", lines = emptyList()))

/** Every preview above, which DebugScreenPreviewsTest renders: a new preview goes in here too. */
internal val debugScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("info") { DebugInfoPreview() },
    ScreenPreview("info-dark") { DebugInfoDarkPreview() },
    ScreenPreview("detail") { DebugDetailPreview() },
    ScreenPreview("empty") { DebugEmptyPreview() },
    ScreenPreview("filtered") { DebugFilteredPreview() },
    ScreenPreview("no-match") { DebugNoMatchPreview() },
)
