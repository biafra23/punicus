package eth.serotine.domain.usecase

import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.repository.InterfaceModeSetting
import kotlinx.coroutines.flow.Flow

/** The view the screens are drawn in: simple mode, or Detailed view (docs/simple-ui.md). */
class ObserveInterfaceMode(private val setting: InterfaceModeSetting) {
    operator fun invoke(): Flow<InterfaceMode> = setting.observe()
}

/** Chooses the view; false when that could not be saved. */
class SetInterfaceMode(private val setting: InterfaceModeSetting) {
    suspend operator fun invoke(mode: InterfaceMode): Boolean = setting.set(mode)
}
