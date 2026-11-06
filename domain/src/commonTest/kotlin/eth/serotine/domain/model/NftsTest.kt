package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [Erc1155Transfers] and [KnownNftCollections] (issue #246). */
class NftsTest {

    private val token = Address.of("0x" + "7c".repeat(20))
    private val operator = Address.of("0x" + "0f".repeat(20))
    private val alice = Address.of("0x" + "11".repeat(20))
    private val bob = Address.of("0x" + "22".repeat(20))

    private fun topic(address: Address) = "0x" + "00".repeat(12) + address.hex.removePrefix("0x")
    private fun word(value: Long) = value.toString(16).padStart(64, '0')
    private fun topics(kind: String) = listOf(kind, topic(operator), topic(alice), topic(bob))

    @Test
    fun theEventTopicsAreTheHashesOfTheirSignatures() {
        assertEquals(
            Erc1155Transfers.TRANSFER_SINGLE,
            "0x" + keccak256("TransferSingle(address,address,address,uint256,uint256)".encodeToByteArray()).toHex(),
        )
        assertEquals(
            Erc1155Transfers.TRANSFER_BATCH,
            "0x" + keccak256("TransferBatch(address,address,address,uint256[],uint256[])".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun aTransferSingleIsOneMove() {
        val data = "0x" + word(7) + word(3)
        assertEquals(
            listOf(Erc1155Transfer(token, alice, bob, UInt256.of(7), UInt256.of(3))),
            Erc1155Transfers.decode(token, topics(Erc1155Transfers.TRANSFER_SINGLE), data),
        )
    }

    @Test
    fun aTransferBatchIsOneMovePerId() {
        // ids at 0x40: [7, 8]; values at 0xa0: [1, 2].
        val data = "0x" + word(0x40) + word(0xa0) + word(2) + word(7) + word(8) + word(2) + word(1) + word(2)
        assertEquals(
            listOf(
                Erc1155Transfer(token, alice, bob, UInt256.of(7), UInt256.of(1)),
                Erc1155Transfer(token, alice, bob, UInt256.of(8), UInt256.of(2)),
            ),
            Erc1155Transfers.decode(token, topics(Erc1155Transfers.TRANSFER_BATCH), data),
        )
    }

    @Test
    fun aBatchOutOfTheEncodersLayoutIsNoMove() {
        fun batch(vararg words: Long) =
            Erc1155Transfers.decode(token, topics(Erc1155Transfers.TRANSFER_BATCH), "0x" + words.joinToString("") { word(it) })
        // Arrays of different lengths.
        assertNull(batch(0x40, 0xa0, 2, 7, 8, 1, 1))
        // The values' offset pointing elsewhere.
        assertNull(batch(0x40, 0x40, 1, 7, 1, 1))
        // Trailing bytes.
        assertNull(batch(0x40, 0x80, 1, 7, 1, 1, 0))
        // The well-formed one, for contrast.
        assertEquals(1, batch(0x40, 0x80, 1, 7, 1, 1)?.size)
    }

    @Test
    fun anythingElseIsNoMove() {
        val single = Erc1155Transfers.TRANSFER_SINGLE
        val amounts = "0x" + word(7) + word(3)
        assertNull(Erc1155Transfers.decode(token, listOf(TokenTransfers.TRANSFER, topic(alice), topic(bob)), "0x" + word(1)))
        assertNull(Erc1155Transfers.decode(token, topics(single).dropLast(1), amounts))
        assertNull(Erc1155Transfers.decode(token, topics(single), "0x" + word(7)))
        val notAnAddress = "0x" + "ff".repeat(32)
        assertNull(Erc1155Transfers.decode(token, listOf(single, topic(operator), notAnAddress, topic(bob)), amounts))
    }

    @Test
    fun ensIsBuiltInWhereItIsDeployedAndNowhereElse() {
        val registrar = Address.of("0x57f1887a8bf19b14fc0df6fd9b2acc9af147ea85")
        assertEquals(KnownNftKind.ENS_REGISTRAR, KnownNftCollections.find(Network.SEPOLIA, registrar)?.kind)
        assertEquals(KnownNftKind.ENS_REGISTRAR, KnownNftCollections.find(Network.ETHEREUM, registrar)?.kind)
        assertEquals(
            KnownNftKind.ENS_NAME_WRAPPER,
            KnownNftCollections.find(Network.SEPOLIA, Address.of("0x0635513f179d50a207757e05759cbd106d7dfce8"))?.kind,
        )
        assertNull(KnownNftCollections.find(Network.ETHEREUM, Address.of("0x0635513f179d50a207757e05759cbd106d7dfce8")))
        assertEquals(emptyList(), KnownNftCollections.of(Network.GNOSIS))
    }
}
