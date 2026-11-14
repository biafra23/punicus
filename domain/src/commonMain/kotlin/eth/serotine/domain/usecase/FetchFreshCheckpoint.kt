package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.FreshCheckpoint
import eth.serotine.domain.repository.FreshCheckpoints
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import kotlinx.coroutines.flow.first

/**
 * Re-anchors a network's light client on a fresh checkpoint its checkpoint-sync servers and a
 * long-known peer agree on (owner's request, 2026-11-14; CLAUDE.md, *Trust*), on the user's word and
 * only while it is parked on a stale one: a light client that has resumed, or was never refused,
 * is left alone and the servers are not asked ([FreshCheckpoint.NotRefused]). [useUnconfirmed] is
 * the user's "use it anyway" for a checkpoint no long-known peer confirmed.
 */
class FetchFreshCheckpoint(private val lightClient: LightClient, private val checkpoints: FreshCheckpoints) {

    suspend operator fun invoke(network: Network, useUnconfirmed: Boolean = false): FreshCheckpoint =
        if (lightClient.observeStatus(network).first() is LightClientStatus.StaleAnchor) {
            checkpoints.fetchFreshCheckpoint(network, useUnconfirmed)
        } else {
            FreshCheckpoint.NotRefused
        }
}
