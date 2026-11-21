package eth.serotine.data.walletconnect

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.AppHostResult
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.DappSiteMemory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [DappSiteMemory] kept in the file at [path] (a [VersionedJsonFile]): each dApp site with the accounts last approved
 * for it, sites and accounts only (issue #252; docs/walletconnect.md, *Remembered per site*). App-private like the
 * sessions' state: the host keeps the file out of backups and device transfers, as it does that state's (ruling 8).
 * A file that cannot be used is handled as [VersionedJsonFile] says, and reads as no memory: nothing is then
 * preselected. A site this version cannot read, or an account on a network it does not know, is passed over and the
 * file kept aside as it was, so it is never lost.
 */
class FileDappSites(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DappSiteMemory {

    private val file = VersionedJsonFile<Map<AppHost, List<AccountId>>>(
        path,
        dispatcher,
        what = "dApp sites",
        formatVersion = FORMAT_VERSION,
        empty = emptyMap(),
        decode = { _, text -> decoded(JSON.decodeFromString(StoredSites.serializer(), text)) },
        encode = { sites ->
            val stored = StoredSites(
                FORMAT_VERSION,
                sites.map { (site, accounts) ->
                    StoredSite(site.name, accounts.map { StoredAccount(it.network.chainId, it.address.hex) })
                },
            )
            JSON.encodeToString(StoredSites.serializer(), stored)
        },
        incomplete = { text, sites ->
            val stored = JSON.decodeFromString(StoredSites.serializer(), text)
            stored.sites.size > sites.size || stored.sites.sumOf { it.accounts.size } > sites.values.sumOf { it.size }
        },
    )

    override fun observe(): Flow<Map<AppHost, List<AccountId>>> = file.observe().map { read ->
        (read as? VersionedJsonFile.Read.Loaded)?.value.orEmpty()
    }

    override suspend fun remember(site: AppHost, accounts: List<AccountId>): Boolean =
        file.change(notSaved = false) { sites -> Decision(true, sites + (site to accounts.distinct())) }

    override suspend fun forget(account: AccountId): Boolean =
        file.change(notSaved = false) { sites ->
            if (sites.values.none { account in it }) {
                Decision(true)
            } else {
                Decision(true, sites.mapValues { (_, kept) -> kept - account }.filterValues { it.isNotEmpty() })
            }
        }

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredSites(val version: Int, val sites: List<StoredSite>)

    /** A [site] and the [accounts] last approved for it. */
    @Serializable
    private class StoredSite(val site: String, val accounts: List<StoredAccount>)

    /** An account: the address [address] on the network of [chain]. */
    @Serializable
    private class StoredAccount(val chain: Long, val address: String)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json

        /** What [stored] holds that this version reads; an address that is none damages the file. */
        fun decoded(stored: StoredSites): Map<AppHost, List<AccountId>> = stored.sites.mapNotNull { entry ->
            val site = (AppHost.parse(entry.site) as? AppHostResult.Host)?.host ?: return@mapNotNull null
            site to entry.accounts.mapNotNull { account ->
                Network.ofChainId(account.chain)?.let { AccountId(it, Address.of(account.address)) }
            }
        }.toMap()
    }
}
