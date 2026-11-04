package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TokenUnits
import kotlinx.coroutines.flow.Flow

/**
 * The port for ERC-20 balances, as the embedded light client proves them: the token
 * contract's own `balanceOf`, run as a verified call. :data implements it over the Myotis
 * engine (`eth.serotine.data.engine`), each network's by its own light client; nothing else may
 * answer it — no indexer, no token API (CLAUDE.md, *Trust*). The one older answer it gives is
 * the light client's own last proof, labelled as such ([TokenBalanceReading.LastKnown]).
 */
interface TokenBalances {
    /**
     * The balance [holder] has of the token at [token] on [network], re-emitted when the verified head moves or the
     * light client's state changes; consecutive equal readings are not repeated. A [network] no adapter serves here is
     * refused, never answered from another network ([ReadUnavailable.NO_ENGINE]). Never fails.
     */
    fun observeBalance(network: Network, holder: Address, token: Address): Flow<TokenBalanceReading>
}

/** One answer of [TokenBalances.observeBalance]. */
sealed interface TokenBalanceReading {

    /** The light client has no verified head yet, and no earlier proof of this balance. */
    data object Syncing : TokenBalanceReading

    /**
     * The light client has no verified head it can read at, and [units] is the balance
     * it last verified, from a call against the state of block [blockNumber] (null when the
     * engine did not say), at a verified head produced at [asOf] (unix seconds; null when
     * unknown). It may have changed since, as [BalanceReading.LastKnown] says, and is never a
     * balance to act on.
     */
    data class LastKnown(val units: TokenUnits, val blockNumber: Long?, val asOf: Long?) : TokenBalanceReading

    /**
     * [units] of the token, from a verified call against the state of block [blockNumber]
     * (null when the engine did not say which block it ran against), made at a verified head
     * produced at [provenAt] (unix seconds; null when unknown), as [BalanceReading.Verified] says.
     */
    data class Verified(val units: TokenUnits, val blockNumber: Long?, val provenAt: Long? = null) : TokenBalanceReading

    /**
     * The contract did not answer `balanceOf` as a token does: the verified call reverted or
     * returned something that is no amount. A verified answer, not a passing failure.
     */
    data object NotAToken : TokenBalanceReading

    /** No verified answer can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : TokenBalanceReading
}
