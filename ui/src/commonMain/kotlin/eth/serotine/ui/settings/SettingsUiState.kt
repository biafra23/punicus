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
 * [turningOff] is the confirmation asked before a network with accounts is turned off (N20), null
 * while none is asked.
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
)

/**
 * One network's row in Settings → Networks (N16): its [name], whether it is [on], what it holds
 * and how its light client stands ([line]: "2 accounts · Verified at block 8,412,307 · 8 s ago",
 * "1 account · Off"), the light client's [health] for the dot, and [notSaved] when the last switch
 * of it could not be saved. [chainId] names the network to the switch; [testnet] marks Sepolia.
 * [staleAnchor] warns that its light client refuses a checkpoint past the weak-subjectivity
 * window, with the way to catch up anyway (owner's request, 2026-10-24), whether or not it has
 * accounts: a screen's hold (Watch, Add token) runs it too. [simpleLine] is what simple mode says
 * instead of [line], with no block number (docs/simple-ui.md, T1): "Up to date", "Test network ·
 * live", "Catching up…", "Can't check right now", "Off".
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
