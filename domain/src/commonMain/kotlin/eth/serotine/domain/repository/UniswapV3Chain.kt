package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UniswapV3Deployment

/**
 * The port for Uniswap v3 on chain (docs/swaps.md, *Uniswap*): the deployment pinned on a
 * network, given only once the light client has proven that each of its contracts carries the
 * runtime code pinned with it ([venue]), and verified calls of those contracts and of the pools
 * their factory returns ([call]). :data implements it over the Myotis engine with a pin table
 * per network (`eth.serotine.data.swaps`). Never throws.
 */
interface UniswapV3Chain {

    /**
     * The Uniswap v3 deployment pinned on [network], once the code of each of its contracts
     * (factory, QuoterV2, SwapRouter02, WETH9) is proven to hash to its pin; a contract whose
     * code does not is no venue, and an address from anywhere else is never one. A check that
     * passed may be remembered for as long as the app runs: none of these contracts can change
     * its code.
     */
    suspend fun venue(network: Network): VenueReading

    /**
     * [data] called on the contract at [to] on [network], from no sender, as a verified call at that network's light
     * client's head: what it returned or that it reverted, or why there is no verified answer. A [network] no adapter
     * serves here is refused, never answered from another network ([ReadUnavailable.NO_ENGINE]).
     */
    suspend fun call(network: Network, to: Address, data: ByteArray): Simulation
}

/** What [UniswapV3Chain.venue] answers. */
sealed interface VenueReading {

    /** [deployment]'s contracts carry their pinned code. */
    data class Verified(val deployment: UniswapV3Deployment) : VenueReading

    /** No Uniswap v3 deployment is pinned for the network. */
    data object NotPinned : VenueReading

    /** The code at [contract], one of the pinned contracts, is not the code pinned with it. */
    data class CodeMismatch(val contract: Address) : VenueReading

    /** The code could not be proven right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : VenueReading
}
