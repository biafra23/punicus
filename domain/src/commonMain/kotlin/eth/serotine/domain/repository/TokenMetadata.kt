package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import kotlinx.coroutines.flow.Flow

/**
 * The port for what a token contract says about itself — its `symbol()`, `name()` and
 * `decimals()` — as verified calls through the embedded light client. What a contract says is
 * its claim, nothing more (CLAUDE.md, *Data sources*): the light client proves the contract
 * said it, not that the token is what its name suggests.
 */
interface TokenMetadata {
    /**
     * What the contract at [token] on [network] says, once it can be read verified: [MetadataReading.Syncing] or
     * [MetadataReading.Unavailable] until then, then one answer, after which the flow ends. A [network] no adapter
     * serves here is refused, never answered from another network ([ReadUnavailable.NO_ENGINE]). Never fails.
     */
    fun observe(network: Network, token: Address): Flow<MetadataReading>
}

/** One answer of [TokenMetadata.observe]. */
sealed interface MetadataReading {

    /** The light client has no verified head yet. */
    data object Syncing : MetadataReading

    /** The contract says it is [symbol] ([name]) with [decimals], at block [blockNumber] (null: not said). */
    data class Verified(
        val symbol: String,
        val name: String,
        val decimals: Int,
        val blockNumber: Long?,
    ) : MetadataReading

    /** The address does not answer as a token (no code, a revert, or answers that are no token's). */
    data object NotAToken : MetadataReading

    /** No verified answer can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : MetadataReading
}
