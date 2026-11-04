package eth.serotine.domain.repository

import eth.serotine.domain.model.InterfaceMode
import kotlinx.coroutines.flow.Flow

/**
 * The port for the view the user chose in Settings → Advanced (docs/simple-ui.md). :data keeps it
 * in a file of its own, so it survives a restart.
 */
interface InterfaceModeSetting {
    /**
     * The view, re-emitted on each change; [InterfaceMode.SIMPLE] while none is chosen or the
     * choice cannot be read. Never throws.
     */
    fun observe(): Flow<InterfaceMode>

    /** Chooses [mode]; false when that could not be saved, and nothing changed. Never throws. */
    suspend fun set(mode: InterfaceMode): Boolean
}
