package eth.serotine.domain.usecase

import eth.serotine.domain.model.SyncPolicy
import eth.serotine.domain.repository.SyncPolicySetting
import kotlinx.coroutines.flow.Flow

/** Whether a transaction may be signed while the light client syncs (docs/lenient-mode.md). */
class ObserveSyncPolicy(private val setting: SyncPolicySetting) {
    operator fun invoke(): Flow<SyncPolicy> = setting.observe()
}

/** Chooses the policy; false when that could not be saved. */
class SetSyncPolicy(private val setting: SyncPolicySetting) {
    suspend operator fun invoke(policy: SyncPolicy): Boolean = setting.set(policy)
}
