package eth.serotine.domain.repository

import eth.serotine.domain.model.SyncPolicy
import kotlinx.coroutines.flow.Flow

/**
 * The port for the policy the user chose in Settings → Advanced → *Sign while syncing*
 * (docs/lenient-mode.md). :data keeps it in a file of its own, so it survives a restart.
 */
interface SyncPolicySetting {
    /**
     * The policy, re-emitted on each change; [SyncPolicy.STRICT] while none is chosen or the
     * choice cannot be read. Never throws.
     */
    fun observe(): Flow<SyncPolicy>

    /** Chooses [policy]; false when that could not be saved, and nothing changed. Never throws. */
    suspend fun set(policy: SyncPolicy): Boolean
}
