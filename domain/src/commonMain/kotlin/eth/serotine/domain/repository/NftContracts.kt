package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.UInt256

/**
 * The port for what NFT contracts say on chain (issue #246), as verified calls at the light
 * client's head: whether an address holds a token, and what a collection calls itself. :data
 * implements it over the Myotis engine (`eth.serotine.data.engine`), each network's by its own
 * light client; nothing else may answer it: no indexer, no NFT API, no metadata server (CLAUDE.md,
 * *Trust*; owner ruling, 2026-11-05, #246). Never throws.
 */
interface NftContracts {

    /**
     * Whether [holder] holds [nft] on [network] at the head: for ERC-721 its `ownerOf(id)` names
     * [holder]; for ERC-1155 its `balanceOf(holder, id)` is above zero, and that is the amount. A
     * call that reverts (a token burned or never minted) or answers out of shape is
     * [NftHolding.NotHeld]: nothing verified says [holder] holds it. A [network] no adapter serves
     * here is refused, never answered from another network ([ReadUnavailable.NO_ENGINE]).
     */
    suspend fun holding(network: Network, holder: Address, nft: NftRef): NftHolding

    /**
     * What the contract at [contract] on [network] calls itself: its `name()` and `symbol()`, each
     * null when it does not answer as text. What a contract says is its claim, nothing more. A
     * [network] no adapter serves here is refused ([ReadUnavailable.NO_ENGINE]).
     */
    suspend fun collection(network: Network, contract: Address): NftCollectionReading
}

/** What [NftContracts.holding] answers. */
sealed interface NftHolding {

    /** Held: [amount] of it (one for ERC-721), from a call against the state of [blockNumber] (null: not said). */
    data class Held(val amount: UInt256, val blockNumber: Long?) : NftHolding

    /** Not held, or nothing verified says it is, at [blockNumber] (null: not said). */
    data class NotHeld(val blockNumber: Long?) : NftHolding

    /** No verified answer right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : NftHolding
}

/** What [NftContracts.collection] answers. */
sealed interface NftCollectionReading {

    /** The contract says it is [name] ([symbol]), each null when it says nothing as text, at [blockNumber]. */
    data class Read(val name: String?, val symbol: String?, val blockNumber: Long?) : NftCollectionReading

    /** No verified answer right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : NftCollectionReading
}
