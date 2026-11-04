package eth.serotine.ui

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.NetworkFreshness
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A [NetworkFreshness] whose [live] networks a test sets, recording each refresh asked; it ignores the view. */
internal class FakeFreshness(live: Set<Network> = emptySet()) : NetworkFreshness {
    val live = MutableStateFlow(live)
    val refreshed = mutableListOf<Network>()

    override fun observeLive(network: Network): Flow<Boolean> = live.map { network in it }

    override fun refresh(network: Network) {
        refreshed += network
    }

    override fun setOnDemand(onDemand: Boolean) = Unit
}
