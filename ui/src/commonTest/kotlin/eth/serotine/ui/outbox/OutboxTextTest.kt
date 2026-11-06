package eth.serotine.ui.outbox

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.DraftBasis
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.usecase.QUEUED_UNATTENDED_SECONDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class OutboxTextTest {

    private val bob = Address.of("0x" + "bb".repeat(20))

    private fun queued(signedAt: Long) = QueuedTransaction(
        network = Network.SEPOLIA,
        account = Address.of("0x" + "aa".repeat(20)),
        id = "0x" + "11".repeat(32),
        action = SentAction.Transfer(bob, TransferAsset.Native, UInt256.of(5)),
        level = FeeLevel.NORMAL,
        transaction = Eip1559Transaction(Network.SEPOLIA.chainId, 7, UInt256.of(2), UInt256.of(22), 50_000, bob, UInt256.of(5)),
        signed = SignedTransfer(byteArrayOf(1, 2, 3)),
        maxCost = UInt256.of(1_100_005),
        signedAt = signedAt,
        builtOn = DraftBasis(1, null, null, null, 1, null, 1, null),
    )

    @Test
    fun aQueuedListIsDrawnAgainTheMomentOneOfItsTransactionsPassesItsHour() = runTest {
        val start = 1_800_000_000L
        val now = { start + testScheduler.currentTime / 1_000 }
        // Signed 10 s short of its hour.
        val entry = queued(signedAt = start - QUEUED_UNATTENDED_SECONDS + 10)
        val emitted = mutableListOf<List<QueuedTransaction>>()
        val job = launch { flowOf(listOf(entry)).redatedAtTheirHour(now).toList(emitted) }
        runCurrent()
        assertEquals(1, emitted.size)
        assertEquals(false, entry.pastItsHour(now()))

        advanceTimeBy(12.seconds)
        runCurrent()
        assertEquals(2, emitted.size, "drawn again once past its hour")
        assertEquals(true, entry.pastItsHour(now()))
        job.cancel()
    }
}
