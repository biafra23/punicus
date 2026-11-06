package eth.serotine.data.outbox

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.DraftBasis
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.HeldConflict
import eth.serotine.domain.repository.HeldRefusal
import eth.serotine.domain.repository.HeldSafeSignature
import eth.serotine.domain.repository.SafeDraftBasis
import eth.serotine.domain.repository.QueueConflict
import eth.serotine.domain.repository.QueueRefusal
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.SentAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoredOutboxTest {

    private fun held(): HeldSafeSignature {
        val safe = Address.of("0x" + "5a".repeat(20))
        val request = SigningRequest.of(
            Network.SEPOLIA,
            safe,
            SafeTx.call(bob, UInt256.of(5), ByteArray(0), nonce = 12),
        )
        return HeldSafeSignature(
            request = request,
            owner = alice,
            signature = Signature("1" + "0".repeat(62) + "7", "2".padStart(64, '0'), recoveryId = 1),
            proposed = true,
            signedAt = 1_800_000_000,
            builtOn = SafeDraftBasis(90, 1_799_999_000, 91, null),
        )
    }

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

    @Test
    fun aQueuedTransferComesBackAsItWasStored() {
        val stored = listOf(queued(), queued(asset = TransferAsset.Erc20Token(usdc)).copy(account = bob))

        val back = (StoredOutbox.decode(StoredOutbox.encode(stored)) as StoredOutbox.Decoding.Read).queued

        assertEquals(2, back.size)
        // Eip1559Transaction has value equality, its data included.
        assertEquals(stored, back)
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
            val back = (StoredOutbox.decode(StoredOutbox.encode(listOf(queued(conflict)))) as StoredOutbox.Decoding.Read)
                .queued.single()
            assertEquals(conflict, back.conflict)
            assertNull(back.signed)
        }
    }

    @Test
    fun aNewerContentIsToldFromADamagedOne() {
        assertEquals(StoredOutbox.Decoding.Newer, StoredOutbox.decode("""{"version":3,"queued":[],"x":1}""".encodeToByteArray()))
        assertEquals(StoredOutbox.Decoding.Damaged, StoredOutbox.decode("not json".encodeToByteArray()))
        assertEquals(StoredOutbox.Decoding.Damaged, StoredOutbox.decode("""{"version":1,"queued":[{}]}""".encodeToByteArray()))
    }

    @Test
    fun aHeldSafeSignatureComesBackAsItWasStoredItsConflictsWithoutTheSignature() {
        val held = held()
        val read = StoredOutbox.decode(StoredOutbox.encode(listOf(queued()), listOf(held))) as StoredOutbox.Decoding.Read
        assertEquals(listOf(held), read.held)
        assertEquals(listOf(queued()), read.queued)

        val conflicts = listOf(
            HeldConflict.NonceUsed(13),
            HeldConflict.NonceTaken,
            HeldConflict.NonceGap(11),
            HeldConflict.NotAnOwner,
            HeldConflict.Refused(HeldRefusal.NEEDS_RECOVERY_MODULE),
        )
        for (conflict in conflicts) {
            val stripped = held.copy(signature = null, conflict = conflict)
            val back = (StoredOutbox.decode(StoredOutbox.encode(emptyList(), listOf(stripped))) as StoredOutbox.Decoding.Read)
                .held.single()
            assertEquals(stripped, back)
            assertNull(back.signature)
        }
    }

    @Test
    fun anOutboxOfTheFirstVersionReadsAsHoldingNoSignature() {
        val v1 = """{"version":1,"queued":[]}"""
        assertEquals(StoredOutbox.Decoding.Read(emptyList(), emptyList()), StoredOutbox.decode(v1.encodeToByteArray()))
    }

    @Test
    fun aMarkedSendingSurvivesTheRoundTrip() {
        val sending = queued().copy(sending = true)
        val back = (StoredOutbox.decode(StoredOutbox.encode(listOf(sending))) as StoredOutbox.Decoding.Read).queued.single()
        assertEquals(true, back.sending)
    }
}
