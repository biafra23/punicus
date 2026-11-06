package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.NftStandard
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddedNftList
import eth.serotine.domain.repository.AddedNfts
import eth.serotine.domain.repository.NftCollectionReading
import eth.serotine.domain.repository.NftContracts
import eth.serotine.domain.repository.NftHolding
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [AddNft]: an NFT is added only once the account is proven to hold it (issue #246). */
class AddNftTest {

    private val alice = AccountId(Network.SEPOLIA, Address.of("0x" + "11".repeat(20)))
    private val contract = Address.of("0x" + "a7".repeat(20))
    private val id = UInt256.of(4021)
    private val saved = mutableListOf<NftRef>()

    private val added = object : AddedNfts {
        override fun observe(account: AccountId): Flow<AddedNftList> = flowOf(AddedNftList.Loaded(saved.toList()))

        override suspend fun add(account: AccountId, nft: NftRef): TokenChange {
            if (nft in saved) return TokenChange.AlreadyPresent
            saved += nft
            return TokenChange.Changed
        }

        override suspend fun remove(account: AccountId, nft: NftRef): TokenChange = TokenChange.NotFound
    }

    private fun contracts(answer: (NftStandard) -> NftHolding) = object : NftContracts {
        override suspend fun holding(network: Network, holder: Address, nft: NftRef): NftHolding = answer(nft.standard)

        override suspend fun collection(network: Network, contract: Address): NftCollectionReading =
            NftCollectionReading.Read(null, null, null)
    }

    @Test
    fun anErc721HeldIsAddedAsOne() = runTest {
        val add = AddNft(contracts { NftHolding.Held(UInt256.ONE, 9) }, added)
        val nft = NftRef(contract, id, NftStandard.ERC721)
        assertEquals(NftAddition.Added(nft, UInt256.ONE), add(alice, contract, id))
        assertEquals(listOf(nft), saved)
        assertEquals(NftAddition.AlreadyAdded, add(alice, contract, id))
    }

    @Test
    fun anErc1155HeldIsAddedAsOneWhenOwnerOfDoesNotNameTheAccount() = runTest {
        val add = AddNft(
            contracts { if (it == NftStandard.ERC721) NftHolding.NotHeld(9) else NftHolding.Held(UInt256.of(3), 9) },
            added,
        )
        val nft = NftRef(contract, id, NftStandard.ERC1155)
        assertEquals(NftAddition.Added(nft, UInt256.of(3)), add(alice, contract, id))
    }

    @Test
    fun nothingIsAddedWithoutAProof() = runTest {
        assertEquals(NftAddition.NotHeld, AddNft(contracts { NftHolding.NotHeld(9) }, added)(alice, contract, id))
        val unanswered = AddNft(
            contracts {
                if (it == NftStandard.ERC721) NftHolding.Unavailable(ReadUnavailable.UNPROVEN) else NftHolding.NotHeld(9)
            },
            added,
        )
        // One standard not answered could be the one it is held by: not "not held".
        assertEquals(NftAddition.Unavailable(ReadUnavailable.UNPROVEN), unanswered(alice, contract, id))
        assertEquals(emptyList(), saved)
    }
}
