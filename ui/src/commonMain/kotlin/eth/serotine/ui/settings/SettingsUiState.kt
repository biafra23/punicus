package eth.serotine.ui.settings

import eth.serotine.ui.components.LightClientHealth
import eth.serotine.ui.components.StaleAnchorWarning

/**
 * Everything the Settings screen renders (docs/wallet-ux.md, screen 18; docs/networks.md, N16),
 * as far as the wallet has settings yet: the display [currency] ("EUR"), the [tokens] it knows
 * ("3 built in · 1 added"), the [networks] with their switches and light clients, whether the
 * screens are in Detailed view ([detailedView], docs/simple-ui.md; [detailedViewNotSaved] when
 * the last switch of it could not be saved; [detailedViewNote], what turning it off does beyond
 * the screens, null when nothing), whether the Debug tab is on ([debugScreen], null in
 * simple mode, where the tab and its switch are not offered; [debugScreenNotSaved] likewise),
 * and the app's [version]. The security settings join it with the features they belong to.
 * [signWhileSyncing] is lenient mode's switch (Settings → Advanced → *Sign while syncing*;
 * docs/lenient-mode.md, owner rulings, 2026-11-06), off by default; [signWhileSyncingNotSaved]
 * when its last switch could not be saved, and [signWhileSyncingNote] what turning it off leaves
 * in place while the outbox holds something (decision 10), null otherwise.
 * [turningOff] is the confirmation asked before a network with accounts is turned off (N20), null
 * while none is asked. [meteredDownloads] is whether the heavy downloads may use a metered
 * connection.
 *
 * [screenCaptureAllowed] is the debug build's switch for screen recordings of the protected
 * screens (`ScreenCaptureDebugSwitch`): whether it allows capture, or null where there is no
 * such switch, a release build or a host whose screens block nothing, and Settings shows no row.
 */
data class SettingsUiState(
    val networks: List<NetworkRow>,
    val version: String,
    val tokens: String = "",
    val currency: String = "",
    val screenCaptureAllowed: Boolean? = null,
    val debugScreen: Boolean? = null,
    val debugScreenNotSaved: Boolean = false,
    val detailedView: Boolean = false,
    val detailedViewNotSaved: Boolean = false,
    val detailedViewNote: String? = null,
    val turningOff: TurnOffConfirmation? = null,
    val signWhileSyncing: Boolean = false,
    val signWhileSyncingNotSaved: Boolean = false,
    val signWhileSyncingNote: String? = null,
    /** Null until the choice and the connection are read: the switch is then drawn off and greyed out. */
    val meteredDownloads: MeteredDownloadsItem? = null,
)

/**
 * The switch that allows the heavy downloads, such as the walk back over the account history, on
 * a metered connection (owner ruling, 2026-10-20): [on] when allowed, [text] what that means now
 * ("Off: … · Waiting now"), [simpleText] the same in simple mode's words (docs/simple-ui.md), and
 * [unavailableReason] when it cannot be changed (on this device, or while the choice cannot be
 * read), said on a tap of the greyed row; [simpleUnavailableReason] that in simple mode's words.
 */
data class MeteredDownloadsItem(
    val on: Boolean,
    val text: String,
    val unavailableReason: String? = null,
    val simpleText: String = text,
    val simpleUnavailableReason: String? = unavailableReason,
)

/** What the switch says while the heavy downloads are not allowed on a metered connection. */
internal const val METERED_DOWNLOADS_OFF = "Off: the account history is walked back only on Wi-Fi or another " +
    "unmetered connection"

/** What the switch says while they are. */
internal const val METERED_DOWNLOADS_ON = "On: the account history is walked back on any connection, mobile data too"

/**
 * One network's row in Settings → Networks (N16): its [name], whether it is [on], what it holds
 * and how its light client stands ([line]: "2 accounts · Verified at block 8,412,307 · 8 s ago",
 * "1 account · Off"), the light client's [health] for the dot, and [notSaved] when the last switch
 * of it could not be saved. [chainId] names the network to the switch; [testnet] marks Sepolia.
 * [staleAnchor] warns that its light client refuses a checkpoint past the weak-subjectivity
 * window, with the ways on: a fresh checkpoint, or catching up anyway (owner's requests,
 * 2026-11-14 and 2026-10-24), whether or not it has accounts: a screen's hold (Watch, Add token)
 * runs it too. [simpleLine] is what simple mode says instead of [line], with no block number
 * (docs/simple-ui.md, T1): "Up to date", "Test network · live", "Catching up…", "Can't check right
 * now", "Off". [checkpointNote] says, once a fresh checkpoint was applied on this screen, where
 * its light client starts over and on whose word (owner's request, 2026-11-14).
 */
data class NetworkRow(
    val chainId: Long,
    val name: String,
    val on: Boolean,
    val line: String,
    val health: LightClientHealth,
    val testnet: Boolean = false,
    val notSaved: Boolean = false,
    val staleAnchor: StaleAnchorWarning? = null,
    val simpleLine: String = line,
    val checkpointNote: String? = null,
)

/**
 * The question asked before a network with accounts is turned off (N20): its [title] ("Turn off
 * Gnosis Chain?"), what stays and what stops ([text]), and what waits until it is on again
 * ([waiting], null when nothing does). [chainId] names the network the answer turns off.
 */
data class TurnOffConfirmation(
    val chainId: Long,
    val title: String,
    val text: String,
    val waiting: String? = null,
)

/** [METERED_DOWNLOADS_OFF] in simple mode's words, which do not say how the history is read. */
internal const val METERED_DOWNLOADS_OFF_SIMPLE = "Off: older activity is checked only on Wi-Fi or another " +
    "unmetered connection"

/** [METERED_DOWNLOADS_ON] in simple mode's words. */
internal const val METERED_DOWNLOADS_ON_SIMPLE = "On: older activity is checked on any connection, mobile data too"
