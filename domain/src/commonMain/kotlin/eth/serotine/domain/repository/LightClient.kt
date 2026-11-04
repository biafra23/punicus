package eth.serotine.domain.repository

import eth.serotine.domain.model.Network
import kotlinx.coroutines.flow.Flow

/**
 * The port for the embedded light client's own state: how far it is from serving verified
 * reads, for the user to see. :data implements it over the Myotis engine
 * (`eth.serotine.data.engine`), one light client per network.
 */
interface LightClient {
    /**
     * The state of [network]'s light client, re-emitted when it changes; [LightClientStatus.Unavailable]
     * for a network none runs for here. Never fails.
     */
    fun observeStatus(network: Network): Flow<LightClientStatus>

    /**
     * Whether [LightClientStatus.Synced.finalized] can be had on this device at all: false where
     * the engine reads no block by number, so it stays null for good (Myotis 0.1.18's iOS module).
     * A CoW order's end is proven at a finalized block's own time, so none is offered where it is
     * false (docs/swaps.md, *Proving an order's end*). It has no default, so every adapter says
     * what it can.
     */
    val datesFinalizedBlocks: Boolean
}

/** One state of the light client, in the order a cold start passes through them. */
sealed interface LightClientStatus {

    /** The engine is starting and has no state yet. */
    data object Starting : LightClientStatus

    /**
     * Bootstrapping from the trusted checkpoint; [lookingForServers] while no peer serving
     * light-client data has been found.
     */
    data class Bootstrapping(val lookingForServers: Boolean) : LightClientStatus

    /**
     * Verifying the sync-committee handovers from the checkpoint to today: [done] of [total]
     * periods (about 27 hours of chain each). [total] is 0 while the distance is unknown.
     */
    data class CatchingUp(val done: Long, val total: Long) : LightClientStatus

    /**
     * Synced, but without a fresh verified head to read at: none built yet ([headAgeSeconds]
     * null), or the last one is [headAgeSeconds] old.
     */
    data class WaitingForHead(val headAgeSeconds: Long?) : LightClientStatus

    /**
     * Synced to [headBlock], but no connected peer serves the state proofs reads need.
     * [headTimestamp] is when that block was produced (unix seconds), null when unknown.
     */
    data class WaitingForProofPeers(val headBlock: Long, val headTimestamp: Long?) : LightClientStatus

    /**
     * Serving verified reads at the fresh verified head [headBlock], produced at
     * [headTimestamp] (unix seconds; null when unknown). [finalized] is the newest block the
     * beacon chain has finalized, with its own header's timestamp; null while there is none or
     * its header could not be read. It has no default, so every producer says what it knows: a
     * null keeps every CoW order's hold-back up until its end is proven.
     */
    data class Synced(
        val headBlock: Long,
        val headTimestamp: Long?,
        val finalized: FinalizedBlock?,
    ) : LightClientStatus

    /** Networking is off while the app is in the background; it resumes when the app returns. */
    data object Sleeping : LightClientStatus

    /**
     * Sync refused: the trust anchor is [anchorAgePeriods] sync-committee periods old, past
     * the [boundPeriods] weak-subjectivity bound (0 when the engine does not say).
     */
    data class StaleAnchor(val anchorAgePeriods: Long, val boundPeriods: Long) : LightClientStatus

    /** There is no light client for the network on this device, or it failed to start. */
    data object Unavailable : LightClientStatus

    /** The network is turned off in this wallet: its light client does not run (docs/networks.md). */
    data object Off : LightClientStatus

    /**
     * The network is on, but nothing needs it now (no account on it, no screen asking about
     * it): its light client starts once something does.
     */
    data object Idle : LightClientStatus
}

/**
 * A finalized block: block [number], whose verified header says it was produced at [timestamp]
 * (unix seconds). Its own timestamp, never its slot's time: the finalized checkpoint's slot may be
 * an empty one after the block's (docs/swaps.md, *Proving an order's end*). No reorganization can
 * replace it.
 */
data class FinalizedBlock(val number: Long, val timestamp: Long) {
    init {
        require(number >= 0) { "a block number is never negative" }
        require(timestamp >= 0) { "a block's time is never before 1970" }
    }
}
