package eth.serotine.data.tokens

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.TokenChange
import eth.serotine.domain.repository.TokenSettings
import eth.serotine.domain.repository.UserTokens
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [UserTokens] kept in the file at [path] (a [VersionedJsonFile]): per network, the tokens added
 * by address, as their contracts described themselves when added, and the hidden addresses.
 * A damaged file is set aside and the settings start empty, saying so
 * ([TokenSettings.Loaded.setAside]); one that cannot be read is [TokenSettings.Unreadable],
 * and nothing changes until it can be read.
 *
 * Format 2 records each token's network by its chain id, and the hidden ones as entries of their
 * own with theirs. Everything in a format-1 file was written when Serotine worked on Sepolia
 * alone, so it reads as Sepolia's (docs/networks.md, *Files*). An entry on a network this version
 * does not know is passed over and the file kept aside as it was, so it is never lost.
 */
class FileUserTokens(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UserTokens {

    /** Every network's settings: the [added] tokens in the order added, and the [hidden] ones. */
    private data class All(val added: List<Pair<Network, Token>>, val hidden: Set<Pair<Network, Address>>) {
        fun of(network: Network) = TokenSettings.Loaded(
            added.filter { it.first == network }.map { it.second },
            hidden.filterTo(LinkedHashSet()) { it.first == network }.mapTo(LinkedHashSet()) { it.second },
        )
    }

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "token settings",
        formatVersion = FORMAT_VERSION,
        empty = All(emptyList(), emptySet()),
        decode = { version, text ->
            val stored = JSON.decodeFromString(StoredSettings.serializer(), text)
            All(
                stored.added.mapNotNull { it.toToken(version) }.distinctBy {
                    (network, token) -> network to token.address },
                (
                    stored.hidden.map { Network.SEPOLIA to Address.of(it) }.takeIf { version < 2 }.orEmpty() +
                        stored.hiddenOn.mapNotNull { it.toHidden() }
                    ).toCollection(LinkedHashSet()),
            )
        },
        encode = { all ->
            val stored = StoredSettings(
                FORMAT_VERSION,
                all.added.map { (network, token) -> StoredToken(network, token) },
                hiddenOn = all.hidden.map { (network, address) -> StoredHidden(network.chainId, address.hex) },
            )
            JSON.encodeToString(StoredSettings.serializer(), stored)
        },
        incomplete = { text, all ->
            val stored = JSON.decodeFromString(StoredSettings.serializer(), text)
            stored.added.size + stored.hidden.size + stored.hiddenOn.size > all.added.size + all.hidden.size
        },
    )

    override fun observe(network: Network): Flow<TokenSettings> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> read.value.of(network).copy(setAside = read.damagedSetAside)
            is VersionedJsonFile.Read.Unreadable -> TokenSettings.Unreadable
        }
    }

    override suspend fun add(network: Network, token: Token): TokenChange = file.change(TokenChange.NotSaved) { all ->
        if (all.added.any { (on, added) -> on == network && added.address == token.address }) {
            Decision(TokenChange.AlreadyPresent)
        } else {
            Decision(TokenChange.Changed, all.copy(added = all.added + (network to token)))
        }
    }

    override suspend fun remove(network: Network, address: Address): TokenChange =
        file.change(TokenChange.NotSaved) { all ->
            if (all.added.none { (on, added) -> on == network && added.address == address }) {
                Decision(TokenChange.NotFound)
            } else {
                // A removed token is not hidden any more either: nothing of it is left behind.
                Decision(
                    TokenChange.Changed,
                    All(
                        all.added.filterNot { (on, added) -> on == network && added.address == address },
                        all.hidden - (network to address),
                    ),
                )
            }
        }

    override suspend fun setHidden(network: Network, address: Address, hidden: Boolean): TokenChange =
        file.change(TokenChange.NotSaved) { all ->
            val key = network to address
            if ((key in all.hidden) == hidden) {
                Decision(TokenChange.AlreadyPresent)
            } else {
                Decision(TokenChange.Changed, all.copy(hidden = if (hidden) all.hidden + key else all.hidden - key))
            }
        }

    /**
     * The file's layout. [version] comes first and is always written. [hidden] is format 1's
     * (Sepolia's addresses); [hiddenOn], format 2's, names each one's network.
     */
    @Serializable
    private class StoredSettings(
        val version: Int,
        val added: List<StoredToken>,
        val hidden: List<String> = emptyList(),
        val hiddenOn: List<StoredHidden> = emptyList(),
    )

    @Serializable
    private class StoredToken(
        val address: String,
        val symbol: String,
        val name: String,
        val decimals: Int,
        /** The token's network's chain id; absent before format 2, whose tokens are all Sepolia's. */
        val chain: Long? = null,
    ) {
        constructor(network: Network, token: Token) :
            this(token.address.hex, token.symbol, token.name, token.decimals, network.chainId)

        /**
         * Throws on anything [Token] would not accept: the whole file is then damaged. Null for a
         * token on a network this version does not know.
         */
        fun toToken(version: Int): Pair<Network, Token>? {
            val network = when {
                version < 2 && chain == null -> Network.SEPOLIA
                chain == null -> error("a token without a network")
                else -> Network.ofChainId(chain) ?: return null
            }
            return network to Token(Address.of(address), symbol, name, decimals, icon = null)
        }
    }

    /** A hidden token: the one at [address] on the network of [chain]. */
    @Serializable
    private class StoredHidden(val chain: Long, val address: String) {
        /** Null for one on a network this version does not know. */
        fun toHidden(): Pair<Network, Address>? = Network.ofChainId(chain)?.let { it to Address.of(address) }
    }

    private companion object {
        /** 2 since tokens per network: each entry records its chain id. */
        const val FORMAT_VERSION = 2
        val JSON = Json
    }
}
