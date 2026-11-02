package eth.serotine.domain.usecase

import eth.serotine.domain.repository.AmountsHiddenSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether the screens hide amounts (docs/wallet-ux.md, *Hiding amounts*). */
class ObserveAmountsHidden(private val setting: AmountsHiddenSetting) {
    operator fun invoke(): Flow<Boolean> = setting.observe()
}

/**
 * Hides amounts when they show, and shows them when they are hidden: a tap on the eye. Each toggle
 * flips what is stored when it runs, one at a time, so two quick taps hide and show again rather
 * than both saving the same. The turns are this instance's: give every screen the same one for them
 * to hold app-wide. False when it could not be saved, and nothing changed.
 */
class ToggleAmountsHidden(private val setting: AmountsHiddenSetting) {
    private val turns = Mutex()

    suspend operator fun invoke(): Boolean = turns.withLock { setting.set(!setting.observe().first()) }
}
