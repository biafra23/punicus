package eth.serotine.data.transfers

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString

/** [FileTransactionHistory] against the port's contract, across restarts. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileTransactionHistoryTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-history-${Random.nextLong().toULong()}")
    private val file = Path(directory, "transaction-history.json")
    private val alice = Address.of("0x" + "aa".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))
    private val usdc = Token(Address.of("0x" + "cc".repeat(20)), "USDC", "USD Coin", 6, icon = "usdc")

    private fun TestScope.history() = FileTransactionHistory(file.toString(), StandardTestDispatcher(testScheduler))

    private fun transfer(nonce: Long, asset: TransferAsset = TransferAsset.Native) = SentTransfer(
        network = Network.SEPOLIA,
        account = alice,
        nonce = nonce,
        signed = SignedTransfer(byteArrayOf(2, nonce.toByte(), -1)),
        action = SentAction.Transfer(bob, asset, UInt256.of(1_500_000)),
        maxCost = UInt256.of(21_000L * 30_000_000_000L),
        sentAt = 1_800_000_000 + nonce,
    )

    private suspend fun TestScope.stored() = (history().observe().first() as HistoryList.Loaded).entries

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun noFileIsAnEmptyHistory() = runTest {
        assertEquals(emptyList(), stored())
    }

    @Test
    fun aRecordedTransactionAndEachOutcomeSurviveARestart() = runTest {
        val outcomes = listOf(
            SentOutcome.NotSeen,
            SentOutcome.Included(120, succeeded = false),
            SentOutcome.Finalized(121, succeeded = true),
            SentOutcome.NonceUsed,
        )
        val transfers = outcomes.indices.map { transfer(7L + it, TransferAsset.Erc20Token(usdc)) }
        transfers.zip(outcomes).forEach { (transfer, outcome) ->
            assertTrue(history().record(transfer))
            assertTrue(history().settle(transfer.hash, outcome))
        }

        assertEquals(transfers.zip(outcomes).map { (transfer, outcome) -> HistoryEntry(transfer, outcome) }, stored())
    }

    @Test
    fun aRecordAgainReplacesTheTransactionInPlaceAndKeepsItsOutcome() = runTest {
        val maybe = transfer(7).copy(maybeSent = true)
        history().record(maybe)
        history().record(transfer(8))
        history().settle(maybe.hash, SentOutcome.Included(120, succeeded = true))

        // A peer took it on a retry: the same hash, one entry, where it was.
        history().record(transfer(7))

        assertEquals(
            listOf(HistoryEntry(transfer(7), SentOutcome.Included(120, succeeded = true)), HistoryEntry(transfer(8))),
            stored(),
        )
    }

    @Test
    fun anOutcomeThatSaysLessThanTheOneRecordedIsRefusedAndTheFileKeepsTheBlock() = runTest {
        val sent = transfer(7)
        history().record(sent)
        assertTrue(history().settle(sent.hash, SentOutcome.Included(120, succeeded = true)))

        // A follower that read "no block seen" before the receipt came: its guess is refused.
        assertFalse(history().settle(sent.hash, SentOutcome.NonceUsed))
        assertFalse(history().settle(sent.hash, SentOutcome.NotSeen))

        assertEquals(listOf(HistoryEntry(sent, SentOutcome.Included(120, succeeded = true))), stored())
    }

    @Test
    fun anOutcomeOfATransactionNotInTheHistoryIsRefused() = runTest {
        assertFalse(history().settle(transfer(7).hash, SentOutcome.NonceUsed))
        assertEquals(emptyList(), stored())
    }

    @Test
    fun anEntryThatDoesNotDecodeIsPassedOverAndTheRestRead() = runTest {
        history().record(transfer(7))
        val good = SystemFileSystem.source(file).buffered().use { it.readString() }
            .substringAfter("\"entries\":[").substringBeforeLast("]}")
        val unknownOutcome = good.replace("\"not-seen\"", "\"vanished\"")
        val blockless = good.replace("{\"kind\":\"not-seen\"}", "{\"kind\":\"included\"}")
        SystemFileSystem.sink(file).buffered().use {
            it.writeString("""{"version":1,"entries":[$unknownOutcome,$good,$blockless]}""")
        }

        assertEquals(listOf(HistoryEntry(transfer(7))), stored())
        assertEquals(1, SystemFileSystem.list(directory).count { ".damaged-" in it.name }, "the file kept aside")
    }

    @Test
    fun aHistoryOfANewerFormatIsUnreadableAndLeftAlone() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":3,"entries":[]}""") }

        assertEquals(HistoryList.Unreadable, history().observe().first())
        assertFalse(history().record(transfer(7)))
    }
}
