package eth.serotine.ui.lightclient

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.asItems
import eth.serotine.ui.components.epochSecondsNow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The light clients' status strips for the screens that draw one under their top bar
 * (docs/wallet-ux.md): one for the app, which `App` creates in the host's scope and hands to
 * those screens' routes, so each screen keeps its own UiState and ViewModel to what it does. A
 * screen draws the strip of the network it is about ([item]): an account's screen its account's
 * (docs/networks.md, *There is no current network*). It holds no platform reference and exposes
 * state only through [item].
 */
class LightClientViewModel(
    private val observeLightClient: ObserveLightClient,
    /** Now, in unix seconds: what the strip dates the head block against. */
    private val clock: () -> Long = ::epochSecondsNow,
) : ViewModel() {

    private val items = mutableMapOf<Network, StateFlow<LightClientItem>>()

    /** [network]'s strip as it is now; followed while a screen that draws it is shown. */
    fun item(network: Network): StateFlow<LightClientItem> = items.getOrPut(network) {
        observeLightClient(network).asItems(clock, network = network.displayName)
            .stateIn(
                scope = viewModelScope,
                // Each subscription polls the engine's status (every second, on the desktop and
                // Android adapters), so it stops 5 s after the last screen with the strip has gone.
                // It keeps its last item, though: a screen opened later starts from the strip as it
                // was, never from "Starting" again, and the poll's first answer, read at once,
                // replaces it.
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                // Simple mode draws no sync line from it: the first poll says whether there is one, and a
                // synced network's screens never flash one.
                initialValue = LightClientItem.from(LightClientStatus.Starting, now = 0).copy(simpleText = null),
            )
    }
}
