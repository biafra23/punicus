package eth.serotine.data.engine

import eth.serotine.domain.model.Network

/** Why a fresh checkpoint was not fetched for [network]: its light client is not running in this engine. */
internal fun notRunning(network: Network): String = "${network.displayName}'s light client is not running."

/** Why a second fetch for [network] was not started: one is running, asked from another screen. */
internal fun alreadyFetching(network: Network): String =
    "Punicus is already asking ${network.displayName}'s checkpoint servers"
