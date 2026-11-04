package eth.serotine.ui.settings

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.ui.components.LightClientHealth.NOT_RUNNING
import eth.serotine.ui.components.LightClientHealth.NOT_VERIFIED
import eth.serotine.ui.components.LightClientHealth.STALE
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import eth.serotine.ui.components.StaleAnchorWarning
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Settings screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. SettingsScreenPreviewsTest renders each one in settingsScreenPreviews, at the end.
// The networks are made up: Ethereum and Gnosis Chain come with parts 4 and 5 of docs/networks.md.

private const val VERSION = "0.1.0"

private val ethereum =
    NetworkRow(1, "Ethereum", true, "2 accounts · Verified at block 23,614,077 · 8 s ago", VERIFIED)
private val gnosis =
    NetworkRow(100, "Gnosis Chain", true, "1 account · Verified at block 42,511,906 · 3 s ago", VERIFIED)
private val sepoliaOff = NetworkRow(11_155_111, "Sepolia", false, "1 account · Off", NOT_RUNNING, testnet = true)

/** Docs/networks.md's N16: a release build updated from the Sepolia-only release, Sepolia off. */
private val synced = SettingsUiState(
    listOf(ethereum, gnosis, sepoliaOff),
    VERSION,
    tokens = "11 built in · 1 added",
    currency = "EUR",
)

@Composable
private fun SettingsPreview(state: SettingsUiState, darkTheme: Boolean = false, connectionsOff: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SettingsScreen(
            state = state,
            onCurrency = {},
            onTokens = {},
            onSecurity = {},
            onScreenCaptureChange = {},
            onDebugScreenChange = {},
            onDetailedViewChange = {},
            connectionsOff = connectionsOff,
        )
    }
}

@Preview
@Composable
private fun SettingsSyncedPreview() = SettingsPreview(synced)

@Preview
@Composable
private fun SettingsSyncedDarkPreview() = SettingsPreview(synced, darkTheme = true)

/** A debug build's first start: Sepolia alone on, its light client starting. */
@Preview
@Composable
private fun SettingsStartingPreview() = SettingsPreview(
    SettingsUiState(
        listOf(
            NetworkRow(1, "Ethereum", false, "No account · Off", NOT_RUNNING),
            NetworkRow(100, "Gnosis Chain", false, "No account · Off", NOT_RUNNING),
            NetworkRow(
                11_155_111,
                "Sepolia",
                true,
                "1 account · Starting the light client…",
                NOT_VERIFIED,
                testnet = true,
            ),
        ),
        VERSION,
    ),
)

/** Synced, but Gnosis Chain's verified head too old to read at: its dot yellow. */
private val headTooOld = synced.copy(
    networks = listOf(
        ethereum,
        gnosis.copy(
            line = "1 account · Synced · the verified head is 4 min old, waiting for a fresh one",
            health = STALE,
        ),
        sepoliaOff,
    ),
)

@Preview
@Composable
private fun SettingsHeadTooOldPreview() = SettingsPreview(headTooOld)

@Preview
@Composable
private fun SettingsHeadTooOldDarkPreview() = SettingsPreview(headTooOld, darkTheme = true)

@Preview
@Composable
private fun SettingsUnreadablePreview() = SettingsPreview(
    synced.copy(
        tokens = "11 built in · added ones cannot be read",
        currency = "USD (your choice cannot be read)",
    ),
)

/** The debug build as it starts: its screen-recording switch off. */
@Preview
@Composable
private fun SettingsDebugBuildPreview() = SettingsPreview(synced.copy(screenCaptureAllowed = false))

/** The debug build, its screen-recording switch on. */
@Preview
@Composable
private fun SettingsDebugBuildCaptureAllowedPreview() = SettingsPreview(synced.copy(screenCaptureAllowed = true))

/** The Debug tab turned on, in Detailed view: the only view that offers it. */
@Preview
@Composable
private fun SettingsDebugScreenPreview() = SettingsPreview(
    synced.copy(detailedView = true, debugScreen = true, detailedViewNote = SettingsViewModel.DEBUG_TAB_GOES),
)

/** A switch of the Debug tab that could not be saved. */
@Preview
@Composable
private fun SettingsDebugScreenNotSavedPreview() =
    SettingsPreview(synced.copy(detailedView = true, debugScreen = false, debugScreenNotSaved = true))

/** Detailed view turned on (docs/simple-ui.md, T1): the Debug tab's switch appears under it. */
@Preview
@Composable
private fun SettingsDetailedViewPreview() = SettingsPreview(synced.copy(detailedView = true, debugScreen = false))

/** A switch of Detailed view that could not be saved. */
@Preview
@Composable
private fun SettingsDetailedViewNotSavedPreview() = SettingsPreview(synced.copy(detailedViewNotSaved = true))

/** On, with nothing needing it: no account on the network yet, so its light client waits. */
@Preview
@Composable
private fun SettingsIdlePreview() = SettingsPreview(
    synced.copy(
        networks = listOf(
            ethereum,
            gnosis.copy(
                line = "No account · Light client idle · it starts once an account needs it",
                health = NOT_RUNNING,
            ),
            sepoliaOff,
        ),
    ),
)

/** A network's switch that could not be saved. */
@Preview
@Composable
private fun SettingsNetworkNotSavedPreview() =
    SettingsPreview(synced.copy(networks = listOf(ethereum, gnosis, sepoliaOff.copy(notSaved = true))))

/** Docs/networks.md's N20: turning off Gnosis Chain, which has an account and a transfer not confirmed yet. */
@Preview
@Composable
private fun SettingsTurnOffPreview() = SettingsPreview(
    synced.copy(
        turningOff = TurnOffConfirmation(
            chainId = 100,
            title = "Turn off Gnosis Chain?",
            text = "Its account, Main, stays in your list. Its balances are not checked and nothing can be sent " +
                "from it until you turn Gnosis Chain on again.",
            waiting = "A transaction sent from it is not confirmed yet. Following it waits until Gnosis Chain is " +
                "on again; nothing is lost.",
        ),
    ),
)

/** Gnosis Chain refusing a checkpoint past its weak-subjectivity window, with "Catch up anyway…" (2026-10-24). */
@Preview
@Composable
private fun SettingsStaleAnchorPreview() = SettingsPreview(
    synced.copy(
        networks = listOf(
            ethereum,
            gnosis.copy(
                line = "No account · Not syncing: the last trusted checkpoint is 6 periods old, past the safe " +
                    "limit of 3",
                health = NOT_VERIFIED,
                staleAnchor = StaleAnchorWarning.of(Network.GNOSIS, LightClientStatus.StaleAnchor(6, 3)),
            ),
            sepoliaOff,
        ),
    ),
)

/** A build without a WalletConnect project ID: Connected dApps greyed out, a tap saying why. */
@Preview
@Composable
private fun SettingsConnectionsOffPreview() = SettingsPreview(synced, connectionsOff = true)

/** Every preview above, which SettingsScreenPreviewsTest renders: a new preview goes in here too. */
internal val settingsScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("synced") { SettingsSyncedPreview() },
    ScreenPreview("synced-dark") { SettingsSyncedDarkPreview() },
    ScreenPreview("starting") { SettingsStartingPreview() },
    ScreenPreview("unreadable") { SettingsUnreadablePreview() },
    ScreenPreview("head-too-old") { SettingsHeadTooOldPreview() },
    ScreenPreview("head-too-old-dark") { SettingsHeadTooOldDarkPreview() },
    ScreenPreview("debug-build") { SettingsDebugBuildPreview() },
    ScreenPreview("debug-build-capture-allowed") { SettingsDebugBuildCaptureAllowedPreview() },
    ScreenPreview("debug-screen") { SettingsDebugScreenPreview() },
    ScreenPreview("debug-screen-not-saved") { SettingsDebugScreenNotSavedPreview() },
    ScreenPreview("idle") { SettingsIdlePreview() },
    ScreenPreview("network-not-saved") { SettingsNetworkNotSavedPreview() },
    ScreenPreview("turn-off") { SettingsTurnOffPreview() },
    ScreenPreview("stale-anchor") { SettingsStaleAnchorPreview() },
    ScreenPreview("detailed-view") { SettingsDetailedViewPreview() },
    ScreenPreview("detailed-view-not-saved") { SettingsDetailedViewNotSavedPreview() },
    ScreenPreview("connections-off") { SettingsConnectionsOffPreview() },
)
