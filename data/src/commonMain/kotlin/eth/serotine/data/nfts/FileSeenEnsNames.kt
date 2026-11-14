package eth.serotine.data.nfts

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.SeenEnsName
import eth.serotine.domain.repository.SeenEnsNameList
import eth.serotine.domain.repository.SeenEnsNames
import eth.serotine.domain.repository.TokenChange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [SeenEnsNames] kept in the file at [path] (a [VersionedJsonFile]; issue #274): per account, the ENS
 * names seen pointing to it, in the order first seen, each with the block it was first seen at. A name
 * seen again is not written again. A damaged file is set aside and the list starts empty, saying so
 * ([SeenEnsNameList.Loaded.setAside]); one that cannot be read is [SeenEnsNameList.Unreadable], and
 * nothing is remembered until it can be read. An entry on a network this version does not know is
 * passed over, and the file kept aside as it was, so it is never lost.
 */
class FileSeenEnsNames(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SeenEnsNames {

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "ENS names seen",
        formatVersion = FORMAT_VERSION,
        empty = emptyList<Pair<AccountId, SeenEnsName>>(),
        decode = { _, text ->
            JSON.decodeFromString(StoredNames.serializer(), text).names.mapNotNull { it.toEntry() }
                .distinctBy { (account, seen) -> account to seen.name }
        },
        encode = { all ->
            JSON.encodeToString(StoredNames.serializer(), StoredNames(FORMAT_VERSION, all.map(::StoredName)))
        },
        incomplete = { text, all -> JSON.decodeFromString(StoredNames.serializer(), text).names.size > all.size },
    )

    override fun observe(account: AccountId): Flow<SeenEnsNameList> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> SeenEnsNameList.Loaded(
                read.value.filter { it.first == account }.map { it.second },
                read.damagedSetAside,
            )
            is VersionedJsonFile.Read.Unreadable -> SeenEnsNameList.Unreadable
        }
    }

    override suspend fun remember(account: AccountId, name: SeenEnsName): TokenChange =
        file.change(TokenChange.NotSaved) { all ->
            if (all.any { (kept, seen) -> kept == account && seen.name == name.name }) {
                Decision(TokenChange.AlreadyPresent)
            } else {
                Decision(TokenChange.Changed, all + (account to name))
            }
        }

    /** The file's layout: [version] first, always written, then the [names]. */
    @Serializable
    private class StoredNames(val version: Int, val names: List<StoredName>)

    /**
     * One name: the account's network ([chain]) and [account], the [name], and the [block] it was first seen
     * at (null: not said).
     */
    @Serializable
    private class StoredName(
        val chain: Long,
        val account: String,
        val name: String,
        val block: Long? = null,
    ) {
        constructor(entry: Pair<AccountId, SeenEnsName>) : this(
            entry.first.network.chainId,
            entry.first.address.hex,
            entry.second.name,
            entry.second.blockNumber,
        )

        /**
         * Throws on anything out of shape: the whole file is then damaged. Null on a network this version
         * does not know, which a newer version may write: passed over, and the file kept aside.
         */
        fun toEntry(): Pair<AccountId, SeenEnsName>? {
            val network = Network.ofChainId(chain) ?: return null
            require(name.isNotBlank()) { "an ENS name seen is not blank" }
            return AccountId(network, Address.of(account)) to SeenEnsName(name, block)
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json
    }
}
