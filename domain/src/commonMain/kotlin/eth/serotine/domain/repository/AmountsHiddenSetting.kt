package eth.serotine.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The port for whether the screens hide amounts: the closed eye at the top left of the screens that
 * show them (docs/wallet-ux.md, *Hiding amounts*). :data keeps it in a file of its own, so it
 * survives a restart.
 */
interface AmountsHiddenSetting {
    /** Whether amounts are hidden, re-emitted on each change; false while unset or unreadable. Never throws. */
    fun observe(): Flow<Boolean>

    /** Hides amounts, or shows them again; false when that could not be saved, and nothing changed. Never throws. */
    suspend fun set(hidden: Boolean): Boolean
}
