package eth.serotine.domain.repository

import eth.serotine.domain.model.Network

/**
 * The other way past a stale checkpoint (owner's request, 2026-11-14; CLAUDE.md, *Trust*): a fresh
 * finalized checkpoint from the network's public checkpoint-sync servers, in place of the stale one.
 * A light client parked on a checkpoint older than the weak-subjectivity bound
 * ([LightClientStatus.StaleAnchor]) can catch up from it on the user's word ([StaleAnchorConsent]),
 * or start over from a checkpoint the servers name. :data implements it over the Myotis engine's
 * `refreshAnchorFromCheckpointz`, which takes one only when every server that answers names the
 * same one, at least two operators among them, and a peer this device has known for a week or more
 * (or one pinned in the release) names it too. That checkpoint is the servers' word; everything
 * synced from it is verified as usual. It is never fetched without the user asking.
 */
interface FreshCheckpoints {

    /**
     * Asks [network]'s checkpoint-sync servers for a fresh checkpoint and, when they and a
     * long-known peer agree, re-anchors [network]'s light client on it. [useUnconfirmed] takes one
     * that no long-known peer could confirm: only on the user's explicit "use it anyway", asked
     * again each time. Suspends for the network round, up to about a minute. A light client that is
     * not parked on a stale checkpoint, or not running here, is refused, never re-anchored.
     */
    suspend fun fetchFreshCheckpoint(network: Network, useUnconfirmed: Boolean): FreshCheckpoint
}

/** What [FreshCheckpoints.fetchFreshCheckpoint] did. */
sealed interface FreshCheckpoint {

    /**
     * The light client starts over from the finalized checkpoint of [epoch], which every answering
     * server named, run by [operators] (their registrable domains), and which [confirmedBy] long-known
     * peers named too. [confirmedBy] is empty when the user took it unconfirmed.
     */
    data class Applied(val epoch: Long, val operators: List<String>, val confirmedBy: List<String>) : FreshCheckpoint

    /**
     * Nothing changed, for [reason], the engine's words: a server that named another checkpoint (the
     * outlier named), too few that answered, a peer that disagreed. [unconfirmed]: the servers agreed,
     * but no long-known peer could be reached to confirm it, so an attack on this device's
     * connection cannot be ruled out; only then may the user be offered "use it anyway".
     */
    data class Refused(val reason: String, val unconfirmed: Boolean = false) : FreshCheckpoint

    /** The light client is not refusing to sync: nothing was asked of the servers. */
    data object NotRefused : FreshCheckpoint
}
