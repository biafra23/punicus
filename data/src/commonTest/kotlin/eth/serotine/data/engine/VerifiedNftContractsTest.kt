package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.NftStandard
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.NftCollectionReading
import eth.serotine.domain.repository.NftHolding
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [VerifiedNftContracts] over a fake verified chain (issue #246). */
@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedNftContractsTest {

    private val chain = FakeChain().apply { snapshot = running() }
    private val alice = Address.of("0x" + "11".repeat(20))
    private val bob = Address.of("0x" + "22".repeat(20))
    private val art = Address.of("0x" + "a7".repeat(20))
    private val erc721 = NftRef(art, UInt256.of(5), NftStandard.ERC721)
    private val erc1155 = NftRef(art, UInt256.of(7), NftStandard.ERC1155)

    private fun TestScope.contracts() = VerifiedNftContracts(chain, StandardTestDispatcher(testScheduler), Network.SEPOLIA)

    private fun word(address: Address) = "00".repeat(12) + address.hex.removePrefix("0x")
    private fun word(value: Long) = value.toString(16).padStart(64, '0')

    /** An ABI `string`: its offset, its length, its bytes padded to a word. */
    private fun string(text: String): String {
        val bytes = text.encodeToByteArray().toHex()
        return word(32) + word(text.length.toLong()) + bytes.padEnd(64, '0')
    }

    @Test
    fun theSelectorsAreTheStandards() {
        assertEquals("6352211e" + word(5), Abi.ownerOf(UInt256.of(5)).toHex())
        assertEquals("00fdd58e" + word(alice) + word(7), Abi.balanceOf(alice, UInt256.of(7)).toHex())
    }

    @Test
    fun anErc721IsHeldWhenItsOwnerIsTheHolder() = runTest {
        val call = art to Abi.ownerOf(UInt256.of(5)).toHex()
        chain.calls[call] = CallOutcome.Returned(word(alice), 120)
        assertEquals(NftHolding.Held(UInt256.ONE, 120), contracts().holding(Network.SEPOLIA, alice, erc721))
        assertEquals(NftHolding.NotHeld(120), contracts().holding(Network.SEPOLIA, bob, erc721))
        chain.calls[call] = CallOutcome.Reverted(121)
        assertEquals(NftHolding.NotHeld(121), contracts().holding(Network.SEPOLIA, alice, erc721), "burned")
        chain.calls[call] = CallOutcome.Returned("ff".repeat(32), 122)
        assertEquals(NftHolding.NotHeld(122), contracts().holding(Network.SEPOLIA, alice, erc721), "no address")
        chain.calls.remove(call)
        assertEquals(NftHolding.Unavailable(ReadUnavailable.UNPROVEN), contracts().holding(Network.SEPOLIA, alice, erc721))
    }

    @Test
    fun anErc1155IsHeldInTheAmountItsBalanceSays() = runTest {
        val call = art to Abi.balanceOf(alice, UInt256.of(7)).toHex()
        chain.calls[call] = CallOutcome.Returned(word(3), 120)
        assertEquals(NftHolding.Held(UInt256.of(3), 120), contracts().holding(Network.SEPOLIA, alice, erc1155))
        chain.calls[call] = CallOutcome.Returned(word(0), 121)
        assertEquals(NftHolding.NotHeld(121), contracts().holding(Network.SEPOLIA, alice, erc1155))
        chain.calls[call] = CallOutcome.Refused
        assertEquals(NftHolding.Unavailable(ReadUnavailable.REFUSED), contracts().holding(Network.SEPOLIA, alice, erc1155))
    }

    @Test
    fun aCollectionSaysItsNameAndSymbolEachMayBeMissing() = runTest {
        chain.calls[art to Abi.NAME.toHex()] = CallOutcome.Returned(string("Art"), 120)
        chain.calls[art to Abi.SYMBOL.toHex()] = CallOutcome.Reverted(120)
        assertEquals(NftCollectionReading.Read("Art", null, 120), contracts().collection(Network.SEPOLIA, art))
        chain.calls.remove(art to Abi.SYMBOL.toHex())
        assertEquals(
            NftCollectionReading.Unavailable(ReadUnavailable.UNPROVEN),
            contracts().collection(Network.SEPOLIA, art),
        )
    }

    @Test
    fun anotherNetworkIsNeverAnsweredHere() = runTest {
        assertEquals(
            NftHolding.Unavailable(ReadUnavailable.NO_ENGINE),
            contracts().holding(Network.ETHEREUM, alice, erc721),
        )
        assertEquals(
            NftCollectionReading.Unavailable(ReadUnavailable.NO_ENGINE),
            contracts().collection(Network.ETHEREUM, art),
        )
    }
}
