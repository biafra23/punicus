package eth.serotine.domain.repository

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.NftRef
import kotlinx.coroutines.flow.Flow

/**
 * The port for the NFTs the user added to an account by contract and token id (issue #246; owner
 * ruling, 2026-11-05: older NFTs are added by contract and id): those the log index cannot find,
 * received before the account's history starts. :data keeps them in a file, so they survive a restart.
 * An NFT is added only once the account was proven to hold it (`AddNft`), and is listed only while it
 * still is: this is the user's list of what to look at, never chain data.
 */
interface AddedNfts {

    /** The NFTs added to [account], in the order added, re-emitted on each change, or why they cannot be read. */
    fun observe(account: AccountId): Flow<AddedNftList>

    /** Adds [nft] to [account]; [TokenChange.AlreadyPresent] when it is added there already. Never throws. */
    suspend fun add(account: AccountId, nft: NftRef): TokenChange

    /** Takes [nft] off [account]'s list; [TokenChange.NotFound] when it is not on it. Never throws. */
    suspend fun remove(account: AccountId, nft: NftRef): TokenChange
}

/** What [AddedNfts.observe] emits. */
sealed interface AddedNftList {

    /** The [nfts] added, in the order added; [setAside] once a damaged file was set aside and the list started over. */
    data class Loaded(val nfts: List<NftRef>, val setAside: Boolean = false) : AddedNftList

    /** The stored list exists but cannot be read; nothing can be added or removed until it can. */
    data object Unreadable : AddedNftList
}
