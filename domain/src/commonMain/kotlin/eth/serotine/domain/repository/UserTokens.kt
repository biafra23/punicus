package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import kotlinx.coroutines.flow.Flow

/**
 * The port for the user's own token settings, per network: the tokens added by address and the
 * ones hidden. A token is an address on one network: one added or hidden on a network is so on
 * that network alone. :data keeps them in a file, so they survive a restart. Nothing here is chain data:
 * an added token's symbol, name and decimals are what its contract said when it was added.
 */
interface UserTokens {
    /** The settings of [network], re-emitted on each change, or why they cannot be read. Never throws. */
    fun observe(network: Network): Flow<TokenSettings>

    /**
     * Adds [token] on [network]; [TokenChange.AlreadyPresent] when its address is added there already. Never throws.
     */
    suspend fun add(network: Network, token: Token): TokenChange

    /**
     * Takes the added token at [address] on [network] out; [TokenChange.NotFound] when none is
     * added there. Never throws.
     */
    suspend fun remove(network: Network, address: Address): TokenChange

    /**
     * Hides the token at [address] on [network] from every account there, or shows it again
     * ([hidden] false); [TokenChange.AlreadyPresent] when it already is as asked. Never throws.
     */
    suspend fun setHidden(network: Network, address: Address, hidden: Boolean): TokenChange
}

/** What [UserTokens.observe] emits. */
sealed interface TokenSettings {
    /**
     * The [added] tokens, in the order added, and the [hidden] addresses; [setAside] once the
     * stored settings were damaged and set aside, so these started over.
     */
    data class Loaded(val added: List<Token>, val hidden: Set<Address>, val setAside: Boolean = false) : TokenSettings

    /** The stored settings exist but cannot be read; nothing can be changed until they can. */
    data object Unreadable : TokenSettings
}

/** The outcome of a [UserTokens] change. */
sealed interface TokenChange {
    /** Done. */
    data object Changed : TokenChange

    /** It already was so; nothing changed. */
    data object AlreadyPresent : TokenChange

    /** Nothing is there to change. */
    data object NotFound : TokenChange

    /** The settings could not be saved, or cannot be read; nothing changed. */
    data object NotSaved : TokenChange
}
