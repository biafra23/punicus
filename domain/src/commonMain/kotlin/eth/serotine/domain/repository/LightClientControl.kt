package eth.serotine.domain.repository

import eth.serotine.domain.model.Network

/**
 * The port that starts and stops each network's light client (docs/networks.md, *The engine
 * host*): one engine, one light client per network that runs. `FollowNetworks` decides what
 * each network should be doing and tells it here; :data implements it over the Myotis engine.
 * A network asked to [LightClientRun.RUN] starts (it may take a while, and say why it cannot);
 * one asked to be [LightClientRun.IDLE] or [LightClientRun.OFF] stops, and its reads say
 * [ReadUnavailable.NETWORK_OFF] and its status [LightClientStatus.Idle] or
 * [LightClientStatus.Off]. Never throws.
 */
interface LightClientControl {
    /** Brings [network]'s light client to [run]; the last call for a network is what it ends in. */
    fun set(network: Network, run: LightClientRun)
}

/** What a network's light client should be doing. */
enum class LightClientRun {
    /** Running: the network is on and an account or a screen needs it. */
    RUN,

    /** Stopped while the network is on: nothing needs it now. */
    IDLE,

    /** Stopped: the network is turned off in this wallet. */
    OFF,
}
