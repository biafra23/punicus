package eth.serotine.domain.repository

import eth.serotine.domain.model.AccountId
import kotlinx.coroutines.flow.Flow

/**
 * The port for where each account's history starts (issue #85): the block from which its network's [LogIndex] follows
 * the account. A start is an account's, by its [AccountId]: the same address on two networks has two starts, each a
 * block of its own chain. An account is noted when it is added, with the time ([HistoryStart.Noted]);
 * `FollowAccountLogs` fixes the block once the light client has a head to measure it from ([HistoryStart.Fixed]), and
 * forgets the start once the account is gone and the index stopped following it. The history is checked from the fixed
 * block on and not before it: for an account not created here, that is the block it was added at (owner ruling,
 * 2026-10-20). Public data only. :data keeps it in a file. Never throws.
 */
interface HistoryStarts {

    /** Every start held, re-emitted on each change, or that they cannot be read. */
    fun observe(): Flow<HistoryStartList>

    /**
     * Notes that the account [id] was added to the wallet at [atSeconds] (unix seconds), in place of any
     * start held for it: an account added anew starts anew, and a start left from before was the
     * start of an account since removed. False when it could not be saved.
     */
    suspend fun note(id: AccountId, atSeconds: Long): Boolean

    /**
     * Fixes [id]'s start at [block], in place of a [HistoryStart.Noted] one or none; a
     * start fixed already is kept, as the index may have walked from it. False when it could
     * not be saved, or a start was fixed already.
     */
    suspend fun fix(id: AccountId, block: Long): Boolean

    /**
     * Forgets [id]'s start if it is still [start], and keeps one that replaced it meanwhile
     * (the account added anew). False when it could not be saved.
     */
    suspend fun forget(id: AccountId, start: HistoryStart): Boolean
}

/** What [HistoryStarts.observe] holds. */
sealed interface HistoryStartList {

    /** The [starts], by account. */
    data class Loaded(val starts: Map<AccountId, HistoryStart>) : HistoryStartList

    /** The starts cannot be read right now (a newer format, a file that cannot be read). */
    data object Unreadable : HistoryStartList
}

/** Where an account's history starts. */
sealed interface HistoryStart {

    /** The account was added at [atSeconds] (unix seconds); its block is not fixed yet. */
    data class Noted(val atSeconds: Long) : HistoryStart

    /** The history is followed from block [block] on. */
    data class Fixed(val block: Long) : HistoryStart {
        init {
            require(block >= 0) { "a block number is never negative" }
        }
    }
}
