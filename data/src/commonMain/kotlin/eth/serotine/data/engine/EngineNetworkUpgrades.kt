package eth.serotine.data.engine

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.repository.NetworkUpgrades
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.time.Duration

/**
 * [NetworkUpgrades] over the engine's upgrade advisory (`StatusSnapshot.upgradeAdvisory`),
 * polled every [pollInterval] by each subscriber. Myotis derives it from two detectors that
 * judge what peers announce against the build's fork schedule (the execution layer's
 * EIP-2124 fork ids, the consensus layer's ENR and Status fork digests), and reports it in
 * every lifecycle state, asleep included; it is peer data, never verified. A device without
 * an engine ([chain] null) and an engine that is down answer null once and stop; an engine
 * still starting reports none; a poll the engine fails is skipped.
 */
class EngineNetworkUpgrades internal constructor(
    private val served: Network,
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    private val pollInterval: Duration,
) : NetworkUpgrades {

    override fun observeUpgrade(network: Network): Flow<NetworkUpgrade?> {
        if (network != served) return flowOf(null)
        val chain = chain ?: return flowOf(null)
        return chain.snapshots(pollInterval)
            .map { (it as? EngineSnapshot.Running)?.upgrade }
            .distinctUntilChanged()
            .flowOn(dispatcher)
    }
}
