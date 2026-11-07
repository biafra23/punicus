package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [TokenTransfers.decode] on the ERC-20 and ERC-721 shapes of `Transfer`. */
class TokenTransfersTest {

    private val token = Address.of("0x" + "7c".repeat(20))
    private val alice = Address.of("0x" + "11".repeat(20))
    private val bob = Address.of("0x" + "22".repeat(20))

    private fun topic(address: Address) = "0x" + "00".repeat(12) + address.hex.removePrefix("0x")
    private fun word(value: Long) = value.toString(16).padStart(64, '0')

    @Test
    fun anErc20TransferHasItsAmountInTheDataAndAnErc721OneItsIdIndexed() {
        val topics = listOf(TokenTransfers.TRANSFER, topic(alice), topic(bob))
        val erc20 = TokenTransfers.decode(token, topics, "0x" + word(5))
        assertEquals(TokenTransfer.Fungible(token, alice, bob, UInt256.of(5)), erc20)

        val id = "0x" + word(42)
        val erc721 = TokenTransfers.decode(token, listOf(TokenTransfers.TRANSFER, topic(alice), topic(bob), id), "0x")
        assertEquals(TokenTransfer.Nft(token, alice, bob, UInt256.of(42)), erc721)
    }

    @Test
    fun anyOtherShapeIsNoTransfer() {
        val both = listOf(TokenTransfers.TRANSFER, topic(alice), topic(bob))

        assertNull(TokenTransfers.decode(token, both, "0x"), "an ERC-20 transfer without its amount")
        assertNull(TokenTransfers.decode(token, both + ("0x" + word(1)), "0x" + word(1)), "an id and an amount")
        assertNull(TokenTransfers.decode(token, listOf(TokenTransfers.TRANSFER, topic(alice)), "0x" + word(1)))
        assertNull(TokenTransfers.decode(token, both.drop(1), "0x" + word(1)), "another event")
        val notAnAddress = "0x" + "ff".repeat(32)
        val unaddressed = listOf(TokenTransfers.TRANSFER, notAnAddress, topic(bob))
        assertNull(TokenTransfers.decode(token, unaddressed, "0x" + word(1)))
    }

    @Test
    fun theProtocolsLogOfAnEthTransferIsReadAsEth() {
        val topics = listOf(TokenTransfers.TRANSFER, topic(alice), topic(bob))

        val eth = TokenTransfers.decode(TokenTransfers.ETH, topics, "0x" + word(500_000_000_000_000_000))
        val usdc = TokenTransfers.decode(token, topics, "0x" + word(5))

        assertEquals(TokenTransfer.Fungible(TokenTransfers.ETH, alice, bob, UInt256.of(500_000_000_000_000_000)), eth)
        assertTrue(eth!!.isEth)
        assertFalse(usdc!!.isEth)
    }
}
