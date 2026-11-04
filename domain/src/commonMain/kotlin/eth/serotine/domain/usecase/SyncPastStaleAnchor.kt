package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.StaleAnchorConsent
import kotlinx.coroutines.flow.first

/**
 * Syncs a network's light client past its weak-subjectivity bound on the user's word (owner's
 * request, 2026-10-24; CLAUDE.md, *Trust*), and only while it is parked there: the engine arms
 * the consent unconditionally, so a light client that has resumed or was never refused is left
 * alone (Myotis's `ChainHandle.acceptStaleAnchor`). The consent lasts until the app is closed.
 */
class SyncPastStaleAnchor(private val lightClient: LightClient, private val consent: StaleAnchorConsent) {

    suspend operator fun invoke(network: Network): StaleAnchorSync =
        if (lightClient.observeStatus(network).first() is LightClientStatus.StaleAnchor) {
            consent.syncPastStaleAnchor(network)
            StaleAnchorSync.Consented
        } else {
            StaleAnchorSync.NotRefused
        }
}

/** What [SyncPastStaleAnchor] did. */
sealed interface StaleAnchorSync {

    /** The light client was parked on a stale anchor, and now syncs past it for this run. */
    data object Consented : StaleAnchorSync

    /** The light client is not refusing to sync: nothing was asked of it. */
    data object NotRefused : StaleAnchorSync
}
