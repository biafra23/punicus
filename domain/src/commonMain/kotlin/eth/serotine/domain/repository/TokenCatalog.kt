package eth.serotine.domain.repository

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import kotlinx.coroutines.flow.Flow

/**
 * The built-in token list of each network (CLAUDE.md, *Data sources*: pinned per network, each
 * checked against its contract when listed). A token is its network's: a symbol is never taken
 * as the same token on another network. Nothing is fetched from a remote
 * list. The tokens the user adds and hides are [UserTokens]'.
 */
interface TokenCatalog {
    /** The built-in tokens of [network], re-emitted when the set changes. Never fails. */
    fun observeTokens(network: Network): Flow<List<Token>>
}
