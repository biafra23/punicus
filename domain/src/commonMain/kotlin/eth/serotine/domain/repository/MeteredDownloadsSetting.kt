package eth.serotine.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The port for the user's choice whether the heavy downloads, such as the log index's walk back
 * over the account history, may run on a metered connection ([ConnectionCost.METERED]). Opt-in:
 * until the user allows it they wait for an unmetered one (owner ruling, 2026-10-20). :data
 * keeps it in a file, so it survives a restart. Never throws.
 */
interface MeteredDownloadsSetting {
    /** The choice, re-emitted on each change, or that it cannot be read. Never throws. */
    fun observe(): Flow<MeteredDownloadsChoice>

    /** Allows the heavy downloads on a metered connection, or not; false when not saved, and nothing changed. */
    suspend fun allow(allowed: Boolean): Boolean
}

/** What [MeteredDownloadsSetting.observe] emits. */
sealed interface MeteredDownloadsChoice {
    /**
     * Whether the user [allowed] them, false while no choice was made; [setAside] when no choice is
     * left because the stored one was damaged and set aside.
     */
    data class Chosen(val allowed: Boolean, val setAside: Boolean = false) : MeteredDownloadsChoice

    /** A choice is stored but cannot be read: taken as not allowed, and nothing can be chosen until it can. */
    data object Unreadable : MeteredDownloadsChoice
}
