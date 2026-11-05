package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.NftStandard
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.NftCollectionReading
import eth.serotine.domain.repository.NftContracts
import eth.serotine.domain.repository.NftHolding
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.CoroutineDispatcher

/**
 * [NftContracts] over the engine's verified calls ([VerifiedChain.call]) at the head of [network],
 * the one network its engine follows (issue #246): ERC-721's `ownerOf(id)`, ERC-1155's
 * `balanceOf(holder, id)`, and a collection's `name()` and `symbol()`, read as text the way a token's
 * are ([Abi.text]). Asked only while the light client can verify reads at its head
 * ([whyUnreadable]), on [dispatcher]; an engine that fails is no answer, as for every verified read.
 * A device without an engine ([chain] null) answers [ReadUnavailable.NO_ENGINE].
 */
class VerifiedNftContracts internal constructor(
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    private val network: Network,
) : NftContracts {

    override suspend fun holding(network: Network, holder: Address, nft: NftRef): NftHolding {
        if (network != this.network) return NftHolding.Unavailable(ReadUnavailable.NO_ENGINE)
        return chain.askVerified(dispatcher, NftHolding::Unavailable) { verified ->
            val data = when (nft.standard) {
                NftStandard.ERC721 -> Abi.ownerOf(nft.tokenId)
                NftStandard.ERC1155 -> Abi.balanceOf(holder, nft.tokenId)
            }
            when (val outcome = verified.call(nft.collection, data)) {
                is CallOutcome.Returned -> held(nft.standard, holder, outcome.hex)
                    ?.let { NftHolding.Held(it, outcome.blockNumber) }
                    ?: NftHolding.NotHeld(outcome.blockNumber)
                // A token burned or never minted: ownerOf reverts.
                is CallOutcome.Reverted -> NftHolding.NotHeld(outcome.blockNumber)
                CallOutcome.Refused -> NftHolding.Unavailable(ReadUnavailable.REFUSED)
                null -> NftHolding.Unavailable(ReadUnavailable.UNPROVEN)
            }
        }
    }

    override suspend fun collection(network: Network, contract: Address): NftCollectionReading {
        if (network != this.network) return NftCollectionReading.Unavailable(ReadUnavailable.NO_ENGINE)
        return chain.askVerified(dispatcher, NftCollectionReading::Unavailable) { verified ->
            val outcomes = mutableListOf<CallOutcome>()
            for (selector in listOf(Abi.NAME, Abi.SYMBOL)) {
                val outcome = verified.call(contract, selector)
                    ?: return@askVerified NftCollectionReading.Unavailable(ReadUnavailable.UNPROVEN)
                if (outcome == CallOutcome.Refused) {
                    return@askVerified NftCollectionReading.Unavailable(ReadUnavailable.REFUSED)
                }
                outcomes += outcome
            }
            val (name, symbol) = outcomes.map { (it as? CallOutcome.Returned)?.let { said -> text(said.hex) } }
            NftCollectionReading.Read(name, symbol, (outcomes.first() as? CallOutcome.Returned)?.blockNumber)
        }
    }

    /**
     * The amount [returnedHex] says [holder] holds, or null for none: for ERC-721 one when the owner it
     * names is [holder]; for ERC-1155 the balance when it is above zero.
     */
    private fun held(standard: NftStandard, holder: Address, returnedHex: String): UInt256? = when (standard) {
        NftStandard.ERC721 -> UInt256.ONE.takeIf { Abi.address(returnedHex) == holder }
        NftStandard.ERC1155 -> Abi.uint256(returnedHex)?.let(UInt256::parse)?.takeIf { it > UInt256.ZERO }
    }

    /** What a contract says as text, trimmed, or null when it says nothing as text. */
    private fun text(returnedHex: String): String? = Abi.text(returnedHex)?.trim()?.takeIf { it.isNotEmpty() }
}
