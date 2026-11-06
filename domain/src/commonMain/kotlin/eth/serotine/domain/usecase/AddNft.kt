package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.NftStandard
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddedNfts
import eth.serotine.domain.repository.NftContracts
import eth.serotine.domain.repository.NftHolding
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenChange

/** What [AddNft] did. */
sealed interface NftAddition {

    /** [nft] is added: the account was proven to hold [amount] of it. */
    data class Added(val nft: NftRef, val amount: UInt256) : NftAddition

    /** It is on the account's list already. */
    data object AlreadyAdded : NftAddition

    /** Nothing verified says the account holds it: neither `ownerOf` names it nor `balanceOf` is above zero. */
    data object NotHeld : NftAddition

    /** No verified answer right now, for [reason]: nothing was added. */
    data class Unavailable(val reason: ReadUnavailable) : NftAddition

    /** The list could not be saved, or cannot be read: nothing was added. */
    data object NotSaved : NftAddition
}

/**
 * Adds the token [UInt256] of the contract at an [Address] to an account's NFTs (issue #246; owner
 * ruling, 2026-11-05: older NFTs are added by contract and id), once the account is proven to hold it
 * at the head: as an ERC-721 when its `ownerOf` names the account, else as an ERC-1155 when its
 * `balanceOf` is above zero. Nothing is added on a contract's word alone, nor without a verified
 * answer.
 */
class AddNft(private val contracts: NftContracts, private val added: AddedNfts) {

    suspend operator fun invoke(account: AccountId, contract: Address, tokenId: UInt256): NftAddition {
        var unavailable: ReadUnavailable? = null
        for (standard in listOf(NftStandard.ERC721, NftStandard.ERC1155)) {
            val nft = NftRef(contract, tokenId, standard)
            when (val holding = contracts.holding(account.network, account.address, nft)) {
                is NftHolding.Held -> return when (added.add(account, nft)) {
                    TokenChange.Changed -> NftAddition.Added(nft, holding.amount)
                    TokenChange.AlreadyPresent -> NftAddition.AlreadyAdded
                    TokenChange.NotFound, TokenChange.NotSaved -> NftAddition.NotSaved
                }
                is NftHolding.NotHeld -> Unit
                is NftHolding.Unavailable -> unavailable = unavailable ?: holding.reason
            }
        }
        // One standard not answered could still be the one the account holds it by.
        return unavailable?.let { NftAddition.Unavailable(it) } ?: NftAddition.NotHeld
    }
}

/** Takes an added NFT off an account's list (issue #246). */
class RemoveAddedNft(private val added: AddedNfts) {
    suspend operator fun invoke(account: AccountId, nft: NftRef): TokenChange = added.remove(account, nft)
}
