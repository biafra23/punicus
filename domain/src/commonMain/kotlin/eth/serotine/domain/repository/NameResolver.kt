package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network

/**
 * The port for ENS names (docs/wallet-ux.md, screen 7): a name's address, resolved by the
 * embedded engine against beacon-finalized state and nothing fresher, since the freshest state
 * would be a peer's claim (CLAUDE.md, *Trust*). :data implements it over the Myotis engine.
 * Blocks on the engine, bounded by it; never throws.
 */
interface NameResolver {
    /**
     * The address [name] (as typed: `vitalik.eth`) points to in [network]'s registry, never
     * another network's. A [network] no adapter serves here is refused ([ReadUnavailable.NO_ENGINE]).
     */
    suspend fun resolve(network: Network, name: String): NameResolution
}

/** The outcome of [NameResolver.resolve]. */
sealed interface NameResolution {

    /** [name] points to [address] in the finalized state of [blockNumber]. */
    data class Resolved(val name: String, val address: Address, val blockNumber: Long) : NameResolution

    /** The name exists without an address record, or does not exist: a verified answer. */
    data object NoAddress : NameResolution

    /** [name] is not an ENS name this resolver can look up (empty labels, characters it does not take). */
    data object NotAName : NameResolution

    /**
     * The name's resolver answers only through an off-chain gateway (CCIP-Read, EIP-3668): an
     * off-chain source no ruling allows, and a request that would tell the gateway the name and
     * the device's address. Not resolvable here; asking again changes nothing.
     */
    data object OffchainOnly : NameResolution

    /** No verified answer right now (finalized state not served, the light client not synced). */
    data class Unavailable(val reason: ReadUnavailable) : NameResolution
}
