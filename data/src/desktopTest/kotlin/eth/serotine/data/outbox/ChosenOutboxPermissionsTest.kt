package eth.serotine.data.outbox

import eth.serotine.data.files.fileThere
import eth.serotine.data.keys.FakeHardware
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.DraftBasis
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.SentAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A sealed file whose directory cannot be searched or listed (POSIX permissions; skipped where
 * they do not hold, as for root): the file system cannot say whether the file is there, so it is
 * taken as there. The outbox is unreadable, every send held back, its key never forgotten, and the
 * file never read as empty nor written over.
 */
class ChosenOutboxPermissionsTest {

    private val directory: File = Files.createTempDirectory("serotine-outbox-permissions").toFile()
    private val file = File(directory, "outbox.json")

    private fun permissions(mode: String) =
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString(mode))

    @AfterTest
    fun cleanUp() {
        permissions("rwx------")
        directory.deleteRecursively()
    }

    private fun assertHeldBack(mode: String) = runTest {
        file.writeBytes(ByteArray(8))
        permissions(mode)
        assumeTrue("permissions do not hold here", directory.list() == null || !file.exists())
        assertNull(fileThere(file.path), "the file system cannot say")
        var forgotten = 0
        val chosen = ChosenOutbox(
            file.path,
            { FakeHardware(claims = { error("does not answer") }) },
            StandardTestDispatcher(testScheduler),
            forgetKey = { forgotten++ },
        )

        assertEquals(OutboxList.Unreadable, chosen.observe().first())
        assertEquals(0, forgotten, "the key of a file that may be there is never forgotten")
        permissions("rwx------")
        assertTrue(file.exists(), "and the file is there still")
    }

    @Test
    fun aDirectoryThatCannotBeSearchedHoldsEverySendBack() = assertHeldBack("rw-------")

    @Test
    fun aDirectoryThatCannotBeReadOrSearchedHoldsEverySendBack() = assertHeldBack("---------")

    private val alice = Address.of("0x" + "aa".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))
    private val raw = ByteArray(40) { (it * 7 + 1).toByte() }

    private fun queued(account: Address) = QueuedTransaction(
        network = Network.SEPOLIA,
        account = account,
        id = SignedTransfer(raw).hash,
        action = SentAction.Transfer(bob, TransferAsset.Native, UInt256.of(5)),
        level = FeeLevel.NORMAL,
        transaction = Eip1559Transaction(Network.SEPOLIA.chainId, 7, UInt256.of(2), UInt256.of(22), 50_000, bob, UInt256.of(5)),
        signed = SignedTransfer(raw),
        maxCost = UInt256.of(1_100_005),
        signedAt = 1_800_000_000,
        builtOn = DraftBasis(90, 1, null, null, 80, 2, 85, 3),
    )

    /** With its seal key working (Android's Keystore is not in this directory), the file decides alone. */
    private fun assertSealedFileKept(mode: String) = runTest {
        val hardware = FakeHardware()
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertTrue(SealedOutboxFile(file.path, OutboxSeal(hardware), dispatcher).put(queued(alice)))
        val sealed = file.readBytes()
        permissions(mode)
        assumeTrue("permissions do not hold here", directory.list() == null || !file.exists())

        val outbox = SealedOutboxFile(file.path, OutboxSeal(hardware), dispatcher)
        assertEquals(OutboxList.Unreadable, outbox.observe().first(), "never read as nothing")
        assertFalse(outbox.put(queued(bob)), "nor written over")
        permissions("rwx------")
        assertContentEquals(sealed, file.readBytes())
        // Once the file system can say again, what it holds is read.
        assertEquals(listOf(alice), assertIs<OutboxList.Loaded>(outbox.observe().first()).queued.map { it.account })
    }

    @Test
    fun aSealedFileInADirectoryThatCannotBeSearchedIsNeverReadAsEmpty() = assertSealedFileKept("rw-------")

    @Test
    fun aSealedFileInADirectoryThatCannotBeReadIsNeverReadAsEmpty() = assertSealedFileKept("---------")

    @Test
    fun theFileSystemSaysWhatItKnows() {
        file.writeBytes(ByteArray(8))
        assertEquals(true, fileThere(file.path))
        assertEquals(false, fileThere(File(directory, "none.json").path))
        assertEquals(false, fileThere(File(directory, "none/none.json").path))
    }
}
