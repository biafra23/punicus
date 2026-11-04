package eth.serotine.domain.repository

import eth.serotine.domain.model.Network

/**
 * The user's consent to a light client syncing past its weak-subjectivity bound (owner's
 * request, 2026-10-24; CLAUDE.md, *Trust*). A light client whose best trust anchor is older than
 * the bound refuses to sync ([LightClientStatus.StaleAnchor]): catching up from it means trusting
 * sync committees whose validators may have left since, and whose old keys can no longer be
 * punished. :data implements it over the Myotis engine's `acceptStaleAnchor`.
 */
interface StaleAnchorConsent {

    /**
     * Lets [network]'s light client sync forward from the stale anchor it is parked on, for this
     * run of the app only: never stored, so a restart that finds the anchor still stale refuses
     * again. Returns at once; the light client's status says when the sync resumes. A network
     * whose light client is not running here is left as it is.
     */
    fun syncPastStaleAnchor(network: Network)
}
