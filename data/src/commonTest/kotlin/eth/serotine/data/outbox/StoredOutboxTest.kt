package eth.serotine.data.outbox

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.DraftBasis
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.QueueConflict
import eth.serotine.domain.repository.QueueRefusal
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.SentAction
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoredOutboxTest {

    private val alice = Address.of("0x" + "aa".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))
    private val usdc = Token(Address.of("0x" + "cc".repeat(20)), "USDC", "USD Coin", 6, icon = null)

    private fun queued(conflict: QueueConflict? = null, asset: TransferAsset = TransferAsset.Native) = QueuedTransaction(
        network = Network.GNOSIS,
        account = alice,
        id = "0x" + "11".repeat(32),
        action = SentAction.Transfer(bob, asset, UInt256.of(5)),
        level = FeeLevel.FAST,
        transaction = Eip1559Transaction(
            chainId = Network.GNOSIS.chainId,
            nonce = 7,
            maxPriorityFeePerGas = UInt256.of(3),
            maxFeePerGas = UInt256.of(23),
            gasLimit = 50_000,
            to = bob,
            value = UInt256.of(5),
            data = byteArrayOf(1, 2, 3),
        ),
        signed = if (conflict == null) SignedTransfer(byteArrayOf(2, 9, 9)) else null,
        maxCost = UInt256.of(1_150_005),
        signedAt = 1_800_000_000,
        builtOn = DraftBasis(90, 1_799_999_000, null, null, 80, null, 85, 1_799_999_500),
        conflict = conflict,
        confirmedAt = 1_800_003_700,
    )

    private fun assertSame(expected: QueuedTransaction, actual: QueuedTransaction) {
        // Eip1559Transaction has no value equality: its fields are compared one by one.
        assertEquals(expected.copy(transaction = actual.transaction), actual)
        val (e, a) = expected.transaction to actual.transaction
        assertEquals(listOf(e.chainId, e.nonce, e.gasLimit), listOf(a.chainId, a.nonce, a.gasLimit))
        assertEquals(listOf(e.maxPriorityFeePerGas, e.maxFeePerGas, e.value), listOf(a.maxPriorityFeePerGas, a.maxFeePerGas, a.value))
        assertEquals(e.to, a.to)
        assertContentEquals(e.data, a.data)
    }

    @Test
    fun aQueuedTransferComesBackAsItWasStored() {
        val stored = listOf(queued(), queued(asset = TransferAsset.Erc20Token(usdc)).copy(account = bob))

        val back = StoredOutbox.decode(StoredOutbox.encode(stored))!!

        assertEquals(2, back.size)
        stored.zip(back).forEach { (e, a) -> assertSame(e, a) }
    }

    @Test
    fun everyConflictComesBackWithoutItsSignedBytes() {
        val conflicts = listOf(
            QueueConflict.Refused(QueueRefusal.INFEASIBLE, "out of gas"),
            QueueConflict.NonceUsed(7, 8),
            QueueConflict.NonceGap(7, 6),
            QueueConflict.NeedsMoreGas(50_000, 60_000),
            QueueConflict.FeesRose(UInt256.of(99)),
            QueueConflict.InsufficientFunds,
            QueueConflict.Rejected("nonce too low"),
        )
        for (conflict in conflicts) {
            val back = StoredOutbox.decode(StoredOutbox.encode(listOf(queued(conflict))))!!.single()
            assertEquals(conflict, back.conflict)
            assertNull(back.signed)
        }
    }

    @Test
    fun aNewerOrDamagedContentDoesNotOpen() {
        assertNull(StoredOutbox.decode("""{"version":2,"queued":[]}""".encodeToByteArray()))
        assertNull(StoredOutbox.decode("not json".encodeToByteArray()))
    }
}
