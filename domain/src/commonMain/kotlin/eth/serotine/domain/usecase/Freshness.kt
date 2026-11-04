package eth.serotine.domain.usecase

import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.InterfaceModeSetting
import eth.serotine.domain.repository.NetworkFreshness
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Whether a network's values update live (docs/simple-ui.md): its blocks carry block access lists. */
class ObserveNetworkLive(private val freshness: NetworkFreshness) {
    operator fun invoke(network: Network): Flow<Boolean> = freshness.observeLive(network)
}

/**
 * Proves the balances and prices of [networks][invoke] again now: what a screen asks when it
 * opens and when the user pulls it down to refresh (docs/simple-ui.md).
 */
class RefreshNetworkData(private val freshness: NetworkFreshness) {
    operator fun invoke(networks: Set<Network>) = networks.forEach { freshness.refresh(it) }
}

/**
 * Keeps the proving of values to the view the user chose, for as long as it runs: on demand in
 * simple mode, at every head in Detailed view (owner's decisions, 2026-10-26). The work no screen
 * owns, which a host runs for the life of its process. Runs until cancelled.
 */
class FollowInterfaceMode(
    private val setting: InterfaceModeSetting,
    private val freshness: NetworkFreshness,
) {
    suspend operator fun invoke() {
        setting.observe().distinctUntilChanged().collect { mode ->
            freshness.setOnDemand(mode == InterfaceMode.SIMPLE)
        }
    }
}
