package eth.serotine.domain.usecase

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
import kotlinx.coroutines.flow.onSubscription

/**
 * [UserTokens] held in memory; [failSaves] makes every change fail, and setting [settings] to
 * [TokenSettings.Unreadable] makes them unreadable.
 */
internal class MemoryUserTokens : UserTokens {
    val settings = MutableStateFlow<TokenSettings>(TokenSettings.Loaded(emptyList(), emptySet()))
    var failSaves = false

    private val loaded get() = settings.value as? TokenSettings.Loaded

    override fun observe(network: Network): Flow<TokenSettings> = settings

    override suspend fun add(network: Network, token: Token): TokenChange {
        val current = loaded ?: return TokenChange.NotSaved
        if (failSaves) return TokenChange.NotSaved
        if (current.added.any { it.address == token.address }) return TokenChange.AlreadyPresent
        settings.value = current.copy(added = current.added + token)
        return TokenChange.Changed
    }

    override suspend fun remove(network: Network, address: Address): TokenChange {
        val current = loaded ?: return TokenChange.NotSaved
        if (failSaves) return TokenChange.NotSaved
        if (current.added.none { it.address == address }) return TokenChange.NotFound
        settings.value =
            TokenSettings.Loaded(current.added.filterNot { it.address == address }, current.hidden - address)
        return TokenChange.Changed
    }

    override suspend fun setHidden(network: Network, address: Address, hidden: Boolean): TokenChange {
        val current = loaded ?: return TokenChange.NotSaved
        if (failSaves) return TokenChange.NotSaved
        if ((address in current.hidden) == hidden) return TokenChange.AlreadyPresent
        settings.value = current.copy(hidden = if (hidden) current.hidden + address else current.hidden - address)
        return TokenChange.Changed
    }
}

/**
 * [TokenMetadata] whose reading per token a test sets; [MetadataReading.Syncing] until then.
 * [asked] counts the subscriptions.
 */
internal class FakeMetadata : TokenMetadata {
    private val readings = mutableMapOf<Address, MutableStateFlow<MetadataReading>>()
    var asked = 0

    fun of(token: Address) = readings.getOrPut(token) { MutableStateFlow(MetadataReading.Syncing) }

    override fun observe(network: Network, token: Address): Flow<MetadataReading> = of(token).onSubscription { asked++ }
}

/** A [TokenCatalog] of the built-in [tokens]. */
internal class ListCatalog(private vararg val tokens: Token) : TokenCatalog {
    override fun observeTokens(network: Network): Flow<List<Token>> = flowOf(tokens.toList())
}
