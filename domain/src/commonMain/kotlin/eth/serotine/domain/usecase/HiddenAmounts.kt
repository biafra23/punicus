package eth.serotine.domain.usecase

import eth.serotine.domain.repository.AmountsHiddenSetting
import kotlinx.coroutines.flow.Flow

/** Whether the screens hide amounts (docs/wallet-ux.md, *Hiding amounts*). */
class ObserveAmountsHidden(private val setting: AmountsHiddenSetting) {
    operator fun invoke(): Flow<Boolean> = setting.observe()
}

/** Hides amounts, or shows them again; false when that could not be saved. */
class SetAmountsHidden(private val setting: AmountsHiddenSetting) {
    suspend operator fun invoke(hidden: Boolean): Boolean = setting.set(hidden)
}
