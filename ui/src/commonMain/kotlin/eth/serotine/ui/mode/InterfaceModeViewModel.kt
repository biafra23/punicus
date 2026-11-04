package eth.serotine.ui.mode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.usecase.ObserveInterfaceMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The view the screens are drawn in, as the user set it in Settings (docs/simple-ui.md): one for
 * the app, which `App` creates in the host's scope and hands to every Route as [LocalInterfaceMode].
 */
class InterfaceModeViewModel(observeInterfaceMode: ObserveInterfaceMode) : ViewModel() {
    /** The view; null until the setting has been read, so no screen is drawn twice at the start. */
    val mode: StateFlow<InterfaceMode?> = observeInterfaceMode().stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
