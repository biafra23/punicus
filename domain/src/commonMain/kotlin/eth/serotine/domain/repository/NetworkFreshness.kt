package eth.serotine.domain.repository

import eth.serotine.domain.model.Network
import kotlinx.coroutines.flow.Flow

/**
 * The port for how fresh a network's verified values are kept (docs/simple-ui.md, *Freshness and
 * pull-to-refresh*). A network whose blocks carry block access lists (EIP-7928) updates its
 * values as they change: it is live. On one without, simple mode proves a value when its screen
 * opens and when the user refreshes, not at every head, and a proof stands for up to an hour
 * (owner's decisions, 2026-10-26). Detailed view proves at every head. Either way a value is
 * verified by the light client and dated by its proof; nothing here ever supplies one.
 */
interface NetworkFreshness {
    /**
     * Whether [network]'s values update live: its verified head's blocks carry block access
     * lists. False while that is not known: the network off, its light client not verifying.
     * Re-emitted on each change. Never fails.
     */
    fun observeLive(network: Network): Flow<Boolean>

    /**
     * Proves [network]'s balances and prices again now, for every screen that shows them. On a
     * network whose light client does not run, no proof is made now: a proof held from before it
     * stopped is proven again once it runs. Never fails.
     */
    fun refresh(network: Network)

    /**
     * Proves on demand ([onDemand], simple mode) on every network that is not live, or at every
     * verified head (Detailed view). At every head until it is first called. Never fails.
     */
    fun setOnDemand(onDemand: Boolean)
}
