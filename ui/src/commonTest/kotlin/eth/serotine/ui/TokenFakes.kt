package eth.serotine.ui

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.repository.TokenChange
import eth.serotine.domain.repository.TokenMetadata
import eth.serotine.domain.repository.TokenSettings
import eth.serotine.domain.repository.UserTokens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * [UserTokens] held in memory, per network: [settings] are Sepolia's, the network the tests' tokens
 * are on, each other network's apart. [failSaves] makes every change fail, and setting [settings]
 * to [TokenSettings.Unreadable] makes Sepolia's unreadable.
 */
internal class MemoryUserTokens : UserTokens {
    val settings = MutableStateFlow<TokenSettings>(TokenSettings.Loaded(emptyList(), emptySet()))
    var failSaves = false

    private val others = mutableMapOf<Network, MutableStateFlow<TokenSettings>>()

    private fun of(network: Network): MutableStateFlow<TokenSettings> =
        if (network == Network.SEPOLIA) {
            settings
        } else {
            others.getOrPut(network) { MutableStateFlow(TokenSettings.Loaded(emptyList(), emptySet())) }
        }

    override fun observe(network: Network): Flow<TokenSettings> = of(network)

    override suspend fun add(network: Network, token: Token): TokenChange {
        val current = of(network).value as? TokenSettings.Loaded ?: return TokenChange.NotSaved
        if (failSaves) return TokenChange.NotSaved
        if (current.added.any { it.address == token.address }) return TokenChange.AlreadyPresent
        of(network).value = current.copy(added = current.added + token)
        return TokenChange.Changed
    }

    override suspend fun remove(network: Network, address: Address): TokenChange {
        val current = of(network).value as? TokenSettings.Loaded ?: return TokenChange.NotSaved
        if (failSaves) return TokenChange.NotSaved
        if (current.added.none { it.address == address }) return TokenChange.NotFound
        of(network).value =
            TokenSettings.Loaded(current.added.filterNot { it.address == address }, current.hidden - address)
        return TokenChange.Changed
    }

    override suspend fun setHidden(network: Network, address: Address, hidden: Boolean): TokenChange {
        val current = of(network).value as? TokenSettings.Loaded ?: return TokenChange.NotSaved
        if (failSaves) return TokenChange.NotSaved
        if ((address in current.hidden) == hidden) return TokenChange.AlreadyPresent
        of(network).value = current.copy(hidden = if (hidden) current.hidden + address else current.hidden - address)
        return TokenChange.Changed
    }
}

/** [TokenMetadata] whose reading per token a test sets; [MetadataReading.Syncing] until then. */
internal class FakeMetadata : TokenMetadata {
    private val readings = mutableMapOf<Address, MutableStateFlow<MetadataReading>>()

    fun of(token: Address) = readings.getOrPut(token) { MutableStateFlow(MetadataReading.Syncing) }

    override fun observe(network: Network, token: Address): Flow<MetadataReading> = of(token)
}

/** A [TokenCatalog] of the built-in [tokens]. */
internal class ListCatalog(private vararg val tokens: Token, private val network: Network = Network.SEPOLIA) :
    TokenCatalog {
    /** [tokens] on [network], the one they are built in on; none on any other. */
    override fun observeTokens(network: Network): Flow<List<Token>> =
        flowOf(if (network == this.network) tokens.toList() else emptyList())
}
