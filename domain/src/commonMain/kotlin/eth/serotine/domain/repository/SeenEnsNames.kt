package eth.serotine.domain.repository

import eth.serotine.domain.model.AccountId
import kotlinx.coroutines.flow.Flow

/**
 * The port for the ENS names the wallet has seen point to one of the user's accounts (issue #274):
 * per account, each name a verified resolution landed on it with, and the block it was seen at. A list
 * of candidates, never an answer: whether the account holds a name is proven again at the head each
 * time it is shown (`ObserveNfts`), so a name that has moved away only costs a call. Separate from the
 * name an account's label follows (`Account.ensName`). :data keeps it in a file, so it survives a
 * restart.
 */
interface SeenEnsNames {

    /** The names seen for [account], in the order first seen, re-emitted on each change, or why they cannot be read. */
    fun observe(account: AccountId): Flow<SeenEnsNameList>

    /**
     * Keeps [name] for [account]; [TokenChange.AlreadyPresent], and nothing written, when that name is
     * kept for it already (the block first seen stays). Never throws.
     */
    suspend fun remember(account: AccountId, name: SeenEnsName): TokenChange
}

/**
 * One name seen: [name] as resolved ("alice.eth", lower case), seen pointing to the account, or proven
 * held by it, at block [blockNumber] (null: not said).
 */
data class SeenEnsName(val name: String, val blockNumber: Long?)

/** What [SeenEnsNames.observe] emits. */
sealed interface SeenEnsNameList {

    /**
     * The [names] seen, in the order first seen; [setAside] once a damaged file was set aside and the list
     * started over.
     */
    data class Loaded(val names: List<SeenEnsName>, val setAside: Boolean = false) : SeenEnsNameList

    /** The stored list exists but cannot be read; nothing is remembered until it can. */
    data object Unreadable : SeenEnsNameList
}
