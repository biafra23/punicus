package eth.serotine.data.nfts

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.NftStandard
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddedNftList
import eth.serotine.domain.repository.AddedNfts
import eth.serotine.domain.repository.TokenChange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [AddedNfts] kept in the file at [path] (a [VersionedJsonFile]; issue #246): per account, the NFTs
 * the user added by contract and token id, in the order added. A damaged file is set aside and the
 * list starts empty, saying so ([AddedNftList.Loaded.setAside]); one that cannot be read is
 * [AddedNftList.Unreadable], and nothing changes until it can be read. An entry on a network this
 * version does not know is passed over, and the file kept aside as it was, so it is never lost.
 */
class FileAddedNfts(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AddedNfts {

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "added NFTs",
        formatVersion = FORMAT_VERSION,
        empty = emptyList<Pair<AccountId, NftRef>>(),
        decode = { _, text ->
            JSON.decodeFromString(StoredNfts.serializer(), text).nfts.mapNotNull { it.toEntry() }.distinct()
        },
        encode = { all ->
            JSON.encodeToString(StoredNfts.serializer(), StoredNfts(FORMAT_VERSION, all.map(::StoredNft)))
        },
        incomplete = { text, all -> JSON.decodeFromString(StoredNfts.serializer(), text).nfts.size > all.size },
    )

    override fun observe(account: AccountId): Flow<AddedNftList> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> AddedNftList.Loaded(
                read.value.filter { it.first == account }.map { it.second },
                read.damagedSetAside,
            )
            is VersionedJsonFile.Read.Unreadable -> AddedNftList.Unreadable
        }
    }

    override suspend fun add(account: AccountId, nft: NftRef): TokenChange = file.change(TokenChange.NotSaved) { all ->
        val entry = account to nft
        if (entry in all) Decision(TokenChange.AlreadyPresent) else Decision(TokenChange.Changed, all + entry)
    }

    override suspend fun remove(account: AccountId, nft: NftRef): TokenChange =
        file.change(TokenChange.NotSaved) { all ->
            val entry = account to nft
            if (entry !in all) Decision(TokenChange.NotFound) else Decision(TokenChange.Changed, all - entry)
        }

    /** The file's layout: [version] first, always written, then the [nfts]. */
    @Serializable
    private class StoredNfts(val version: Int, val nfts: List<StoredNft>)

    /** One added NFT: the account's network ([chain]) and [account], the [contract], the [id] in decimal, its standard. */
    @Serializable
    private class StoredNft(
        val chain: Long,
        val account: String,
        val contract: String,
        val id: String,
        val standard: String,
    ) {
        constructor(entry: Pair<AccountId, NftRef>) : this(
            entry.first.network.chainId,
            entry.first.address.hex,
            entry.second.collection.hex,
            entry.second.tokenId.toDecimal(),
            entry.second.standard.name,
        )

        /**
         * Throws on anything out of shape: the whole file is then damaged. Null on a network this version
         * does not know.
         */
        fun toEntry(): Pair<AccountId, NftRef>? {
            val network = Network.ofChainId(chain) ?: return null
            return AccountId(network, Address.of(account)) to
                NftRef(Address.of(contract), UInt256.of(id), NftStandard.valueOf(standard))
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json
    }
}
