package eth.serotine.domain.repository

import eth.serotine.domain.model.Network
import kotlinx.coroutines.flow.Flow

/**
 * The port for which networks are on in this wallet (docs/networks.md, *Networks on and off*).
 * A network that is off runs no light client and reads nothing; its accounts stay in the list,
 * so it is a fact the screens show, never a reason to hide an account. :data keeps the setting in
 * a file. Which networks a fresh install starts with is the build's (a release build and a debug
 * build differ), and the composition root hands that to the adapter: the domain never asks which
 * build it is. Never throws.
 */
interface NetworkSettings {

    /**
     * The networks that are on, re-emitted on each change. Each network gets its build's default
     * the first time it is found unrecorded, and keeps the user's choice from then on. A stored
     * setting that cannot be read is answered with the build's defaults, without recording them,
     * until it can be read.
     */
    fun observe(): Flow<Set<Network>>

    /** Turns [network] on, or off ([on] false); false when that could not be saved. */
    suspend fun setEnabled(network: Network, on: Boolean): Boolean
}
