package eth.serotine.ui.settings

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.LightClientHealth.NOT_RUNNING
import eth.serotine.ui.components.LightClientHealth.NOT_VERIFIED
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Settings tab in simple mode (docs/simple-ui.md, T1) in Android Studio's preview pane, one
// preview per state it draws differently from Detailed view's. Built from display-ready values,
// never from the ViewModel. SimpleSettingsScreenPreviewsTest renders each one in
// simpleSettingsScreenPreviews, at the end.

private val ethereum = NetworkRow(
    1,
    "Ethereum",
    true,
    "2 accounts · Verified at block 23,614,077 · 8 s ago",
    VERIFIED,
    simpleLine = "Up to date",
)
private val gnosis = NetworkRow(
    100,
    "Gnosis Chain",
    true,
    "1 account · Catching up · block 42,511,906 of 42,511,960",
    NOT_VERIFIED,
    simpleLine = "Catching up…",
)
private val sepolia = NetworkRow(
    11_155_111,
    "Sepolia",
    true,
    "1 account · Verified at block 9,412,118 · 3 s ago",
    VERIFIED,
    testnet = true,
    simpleLine = "Test network · live",
)

private val simple = SettingsUiState(
    listOf(ethereum, gnosis, sepolia),
    "0.4.0",
    tokens = "18 built in · 0 added",
    currency = "EUR",
    meteredDownloads = meteredNotAllowed,
)

@Composable
private fun SimpleSettingsPreview(state: SettingsUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleSettingsScreen(
            state = state,
            onCurrency = {},
            onTokens = {},
            onSecurity = {},
            onScreenCaptureChange = {},
            onDebugScreenChange = {},
        )
    }
}

/** T1: every network's state in words, Detailed view off. */
@Preview
@Composable
private fun SimpleSettingsPreviewDefault() = SimpleSettingsPreview(simple)

@Preview
@Composable
private fun SimpleSettingsDarkPreview() = SimpleSettingsPreview(simple, darkTheme = true)

/** A network turned off, and one that cannot be checked right now. */
@Preview
@Composable
private fun SimpleSettingsOffPreview() = SimpleSettingsPreview(
    simple.copy(
        networks = listOf(
            ethereum.copy(health = NOT_VERIFIED, simpleLine = "Can't check right now"),
            gnosis.copy(on = false, line = "1 account · Off", health = NOT_RUNNING, simpleLine = "Off"),
            sepolia,
        ),
    ),
)

/** Lenient mode on, the same switch as in Detailed view: Sign while syncing. */
@Preview
@Composable
private fun SimpleSettingsSignWhileSyncingPreview() = SimpleSettingsPreview(simple.copy(signWhileSyncing = true))

/** On with a transaction queued: turning it off leaves that queued (decision 10). */
@Preview
@Composable
private fun SimpleSettingsSignWhileSyncingQueuedPreview() = SimpleSettingsPreview(
    simple.copy(
        signWhileSyncing = true,
        signWhileSyncingNote = "Turning it off leaves what is queued in place: the transaction waiting to be sent " +
            "stays queued, checked and sent, or discarded by you.",
    ),
)

/** A switch of it that could not be saved. */
@Preview
@Composable
private fun SimpleSettingsSignWhileSyncingNotSavedPreview() =
    SimpleSettingsPreview(simple.copy(signWhileSyncingNotSaved = true))

/** On a metered connection, the heavy downloads not allowed: in simple mode's words, waiting for Wi-Fi. */
@Preview
@Composable
private fun SimpleSettingsMeteredPausedPreview() =
    SimpleSettingsPreview(simple.copy(meteredDownloads = meteredPausedSample))

/** Every preview above, which SimpleSettingsScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleSettingsScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("default") { SimpleSettingsPreviewDefault() },
    ScreenPreview("default-dark") { SimpleSettingsDarkPreview() },
    ScreenPreview("off") { SimpleSettingsOffPreview() },
    ScreenPreview("sign-while-syncing") { SimpleSettingsSignWhileSyncingPreview() },
    ScreenPreview("sign-while-syncing-queued") { SimpleSettingsSignWhileSyncingQueuedPreview() },
    ScreenPreview("sign-while-syncing-not-saved") { SimpleSettingsSignWhileSyncingNotSavedPreview() },
    ScreenPreview("metered-paused") { SimpleSettingsMeteredPausedPreview() },
)
