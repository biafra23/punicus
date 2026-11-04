package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowDeployment
import eth.serotine.domain.model.Network

/**
 * The port for CoW Protocol's contracts on chain (docs/swaps.md, *An order, step by step*): the
 * deployment pinned on a network, given only once the light client has proven that each of its
 * contracts carries the runtime code pinned with it ([venue]), and verified calls of them
 * ([call]). :data implements it over the Myotis engine with a pin table per network. No
 * off-chain service is behind it: CoW's order API is [CowApi]. Never throws.
 */
interface CowChain {

    /**
     * The CoW deployment pinned on [network], once the code of its settlement contract and of its
     * vault relayer is proven to hash to its pin; a contract whose code does not is no venue. A
     * check that passed may be remembered for as long as the app runs: neither contract can
     * change its code.
     */
    suspend fun venue(network: Network): CowVenueReading

    /**
     * [data] called on the contract at [to] on [network], from no sender, as a verified call at
     * that network's light client's head: what it returned or that it reverted, or why there is no
     * verified answer.
     */
    suspend fun call(network: Network, to: Address, data: ByteArray): Simulation
}

/** What [CowChain.venue] answers. */
sealed interface CowVenueReading {

    /** [deployment]'s contracts carry their pinned code. */
    data class Verified(val deployment: CowDeployment) : CowVenueReading

    /** No CoW deployment is pinned for the network. */
    data object NotPinned : CowVenueReading

    /** The code at [contract], one of the pinned contracts, is not the code pinned with it. */
    data class CodeMismatch(val contract: Address) : CowVenueReading

    /** The code could not be proven right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : CowVenueReading
}
