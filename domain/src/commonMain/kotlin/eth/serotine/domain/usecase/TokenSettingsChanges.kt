package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenChange
import eth.serotine.domain.repository.TokenMetadata
import eth.serotine.domain.repository.UserTokens
import kotlinx.coroutines.flow.first

/**
 * Adds a token by address (docs/wallet-ux.md, screen 20): only what its contract says, read
 * with verified calls, as the contract's claim; refused when the wallet knows the address
 * already, when it does not answer as a token, or when it cannot be read verified right now.
 * The token gets no bundled icon: it is drawn as a monogram.
 */
class AddToken(
    private val userTokens: UserTokens,
    private val metadata: TokenMetadata,
    private val knownTokens: ObserveKnownTokens,
) {
    suspend operator fun invoke(network: Network, address: Address): TokenAdd {
        knownTokens(network).first().tokens.firstOrNull { it.token.address == address }?.let {
            return TokenAdd.AlreadyKnown(it.token)
        }
        val said = when (val reading = metadata.observe(network, address).first()) {
            MetadataReading.Syncing -> return TokenAdd.NotVerified(null)
            is MetadataReading.Unavailable -> return TokenAdd.NotVerified(reading.reason)
            MetadataReading.NotAToken -> return TokenAdd.NotAToken
            is MetadataReading.Verified -> reading
        }
        val token = runCatching { Token(address, said.symbol, said.name, said.decimals, icon = null) }
            .getOrElse { return TokenAdd.NotAToken } // a blank symbol, decimals no amount can show
        return when (userTokens.add(network, token)) {
            TokenChange.Changed -> TokenAdd.Added(token)
            TokenChange.AlreadyPresent -> TokenAdd.AlreadyKnown(token)
            TokenChange.NotFound, TokenChange.NotSaved -> TokenAdd.NotSaved
        }
    }
}

/** The outcome of [AddToken]. */
sealed interface TokenAdd {
    /** [token] is added, as its contract describes itself. */
    data class Added(val token: Token) : TokenAdd

    /** The wallet knows the address already, as [token]; nothing changed. */
    data class AlreadyKnown(val token: Token) : TokenAdd

    /** The address does not answer as a token; nothing changed. */
    data object NotAToken : TokenAdd

    /** It cannot be read verified right now: still syncing ([reason] null), or for [reason]. */
    data class NotVerified(val reason: ReadUnavailable?) : TokenAdd

    /** The settings could not be saved; nothing changed. */
    data object NotSaved : TokenAdd
}

/** Hides a token from every account, or shows it again: a built-in one or an added one. */
class SetTokenHidden(private val userTokens: UserTokens) {
    suspend operator fun invoke(network: Network, address: Address, hidden: Boolean): TokenChange =
        userTokens.setHidden(network, address, hidden)
}

/** Takes a token the user added out of the wallet; a built-in one can only be hidden. */
class RemoveToken(private val userTokens: UserTokens) {
    suspend operator fun invoke(network: Network, address: Address): TokenChange = userTokens.remove(network, address)
}
