package eth.serotine.data.outbox

import eth.serotine.data.keys.FakeHardware
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.DraftBasis
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.HeldSafeSignature
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.SafeDraftBasis
import eth.serotine.domain.repository.SentAction
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.write

@OptIn(ExperimentalCoroutinesApi::class)
class SealedOutboxFileTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-outbox-${Random.nextLong().toULong()}")
    private val file = Path(directory, "outbox.json")
    private val alice = Address.of("0x" + "aa".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))
    private val raw = ByteArray(40) { (it * 7 + 1).toByte() }
    private val safe = Address.of("0x" + "5a".repeat(20))

    private fun TestScope.outbox(hardware: FakeHardware = FakeHardware()) =
        SealedOutboxFile(file.toString(), OutboxSeal(hardware), StandardTestDispatcher(testScheduler))

    private fun queued(account: Address = alice) = QueuedTransaction(
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

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun whatIsQueuedIsSealedAndComesBackAfterARestart() = runTest {
        assertTrue(outbox().put(queued()))

        val bytes = SystemFileSystem.source(file).buffered().use { it.readByteArray() }
        val hex = raw.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertFalse(bytes.decodeToString().contains(hex), "the signed bytes are not in the file in the clear")
        assertFalse(bytes.decodeToString().contains(alice.hex.removePrefix("0x")), "nor the account")

        val after = assertIs<OutboxList.Loaded>(outbox().observe().first())
        assertEquals(listOf(queued().id), after.queued.map { it.id })
        assertEquals(queued().signed, after.queued.single().signed)
        assertFalse(after.lost)
    }

    @Test
    fun oneQueuedPerAccountAndAnEmptyOutboxHasNoFile() = runTest {
        val outbox = outbox()
        outbox.put(queued())
        outbox.put(queued().copy(signedAt = 1_800_000_500))
        outbox.put(queued(account = bob))
        assertEquals(2, (outbox.observe().first() as OutboxList.Loaded).queued.size)

        outbox.remove(queued().accountId)
        outbox.remove(queued(account = bob).accountId)
        assertFalse(SystemFileSystem.exists(file))
    }

    private fun held(nonce: Long = 12): HeldSafeSignature = HeldSafeSignature(
        request = SigningRequest.of(Network.SEPOLIA, safe, SafeTx.call(bob, UInt256.of(5), ByteArray(0), nonce)),
        owner = alice,
        signature = Signature("1" + "0".repeat(62) + "7", "2".padStart(64, '0'), recoveryId = 1),
        proposed = false,
        signedAt = 1_800_000_000,
        builtOn = SafeDraftBasis(90, 1_799_999_000),
    )

    @Test
    fun aHeldSafeSignatureIsSealedBesideWhatIsQueuedOnePerSafeAndNonce() = runTest {
        val outbox = outbox()
        assertTrue(outbox.hold(held()))
        val bytes = SystemFileSystem.source(file).buffered().use { it.readByteArray() }
        assertFalse(bytes.decodeToString().contains("1" + "0".repeat(62) + "7"), "the signature is not in the clear")
        outbox.put(queued())
        // Another at the same Safe and nonce takes its place; one at the next nonce does not.
        val other = held().copy(request = SigningRequest.of(Network.SEPOLIA, safe, SafeTx.call(alice, UInt256.ONE, ByteArray(0), 12)))
        outbox.hold(other)
        outbox.hold(held(nonce = 13))

        val after = assertIs<OutboxList.Loaded>(outbox().observe().first())
        assertEquals(listOf(other, held(nonce = 13)), after.held)
        assertEquals(1, after.queued.size, "a held signature takes no account's place")

        outbox.release(other.safe, other.hash)
        outbox.release(held(nonce = 13).safe, held(nonce = 13).hash)
        outbox.remove(queued().accountId)
        assertFalse(SystemFileSystem.exists(file), "an outbox with nothing queued or held has no file")
    }

    @Test
    fun aHeldSignatureIsLostWithItsSealKey() = runTest {
        outbox().hold(held())
        val read = assertIs<OutboxList.Loaded>(outbox(FakeHardware(key = 9)).observe().first())
        assertEquals(emptyList(), read.held)
        assertTrue(read.lost)
    }

    @Test
    fun aFileWhoseKeyIsGoneIsDeletedAndSaidOnceAsLost() = runTest {
        outbox().put(queued())

        // The key store reset: its key is gone, and a new one would be made.
        val after = outbox(FakeHardware(key = 7, provisioned = { false }))
        val read = assertIs<OutboxList.Loaded>(after.observe().first())
        assertEquals(emptyList(), read.queued)
        assertTrue(read.lost)
        assertFalse(SystemFileSystem.exists(file))

        // Said until acknowledged, a restart included.
        assertTrue(outbox().observe().first().let { it is OutboxList.Loaded && it.lost })
        assertTrue(after.acknowledgeLost())
        assertFalse((outbox().observe().first() as OutboxList.Loaded).lost)
    }

    @Test
    fun aFileSealedUnderAnotherKeyDoesNotOpenAndIsLost() = runTest {
        outbox().put(queued())

        val read = assertIs<OutboxList.Loaded>(outbox(FakeHardware(key = 9)).observe().first())
        assertEquals(emptyList(), read.queued)
        assertTrue(read.lost)
    }

    @Test
    fun aKeyStoreThatFailsToOpenLeavesTheFileAsItIsAndUnreadable() = runTest {
        outbox().put(queued())

        // The key is there, but the hardware fails this time: nothing is concluded, nothing deleted.
        val failing = outbox(FakeHardware(mixed = { error("the hardware is busy") }))
        assertEquals(OutboxList.Unreadable, failing.observe().first())
        assertTrue(SystemFileSystem.exists(file))

        // A key store that will not say whether its key exists: the same.
        val silent = outbox(FakeHardware(provisioned = { error("the key store will not say") }))
        assertEquals(OutboxList.Unreadable, silent.observe().first())
        assertTrue(SystemFileSystem.exists(file))

        // It opens again once the hardware answers.
        assertEquals(1, (outbox().observe().first() as OutboxList.Loaded).queued.size)
    }

    @Test
    fun aKeyStoreThatFailsWhileChosenNeverHidesASealedFileBehindMemory() = runTest {
        outbox().put(queued())
        val chosen = ChosenOutbox(
            file.toString(),
            { FakeHardware(claims = { error("the key store does not answer") }) },
            StandardTestDispatcher(testScheduler),
        )
        assertEquals(OutboxList.Unreadable, chosen.observe().first())
        assertFalse(chosen.put(queued(account = bob)))
        assertTrue(SystemFileSystem.exists(file))
    }

    @Test
    fun aFileThatOpensButThisVersionCannotReadIsKeptAsItIs() = runTest {
        SystemFileSystem.createDirectories(directory)
        val sealed = OutboxSeal(FakeHardware()).seal("""{"version":1,"queued":[{"id":"x"}]}""".encodeToByteArray())!!
        SystemFileSystem.sink(file).buffered().use { it.write(sealed) }

        assertEquals(OutboxList.Unreadable, outbox().observe().first())
        assertTrue(SystemFileSystem.exists(file), "authenticated content is never deleted as damaged")
    }

    @Test
    fun aKeyStoreThatFailedWhileChosenIsAskedAgainAtTheNextUse() = runTest {
        outbox().put(queued())
        var asks = 0
        val chosen = ChosenOutbox(
            file.toString(),
            { if (++asks == 1) error("the key store is busy") else FakeHardware() },
            StandardTestDispatcher(testScheduler),
        )
        assertEquals(OutboxList.Unreadable, chosen.observe().first())
        assertEquals(1, (chosen.observe().first() as OutboxList.Loaded).queued.size)
    }

    @Test
    fun aSealThatFailsSavesNothing() = runTest {
        val failing = FakeHardware(mixed = { error("the hardware refused") })
        assertFalse(outbox(failing).put(queued()))
        assertFalse(SystemFileSystem.exists(file))
    }

    @Test
    fun aSoftwareKeyStoreKeepsTheOutboxInMemoryOnly() = runTest {
        val chosen = ChosenOutbox(
            file.toString(),
            { FakeHardware(claims = { KeyProtection.SOFTWARE_KEY_STORE }) },
            StandardTestDispatcher(testScheduler),
        )
        assertTrue(chosen.put(queued()))
        assertEquals(1, (chosen.observe().first() as OutboxList.Loaded).queued.size)
        assertFalse(SystemFileSystem.exists(file), "nothing written")

        val none = ChosenOutbox(file.toString(), null, StandardTestDispatcher(testScheduler))
        assertTrue(none.put(queued()))
        assertFalse(SystemFileSystem.exists(file))
    }

    @Test
    fun aKeyThatNeverOpensTheFileHereIsSaidAsLostAndANewOneIsMade() = runTest {
        outbox().put(queued())
        var copied = true
        var forgotten = 0
        val chosen = ChosenOutbox(
            file.toString(),
            // A Mac's enclave state made on another Mac: never opens here, until it is forgotten.
            { if (copied) throw OutboxKeyGone("copied from another Mac") else FakeHardware(provisioned = { false }) },
            StandardTestDispatcher(testScheduler),
            forgetKey = { forgotten++; copied = false },
        )

        val read = assertIs<OutboxList.Loaded>(chosen.observe().first())
        assertEquals(emptyList(), read.queued)
        assertTrue(read.lost, "said as lost, never unreadable for good")
        assertFalse(SystemFileSystem.exists(file))
        assertEquals(1, forgotten)

        // A new key seals what is queued next, and the loss is said until acknowledged.
        assertTrue(chosen.put(queued(account = bob)))
        assertTrue(SystemFileSystem.exists(file), "sealed in the file again")
        assertTrue(chosen.acknowledgeLost())
        assertFalse((chosen.observe().first() as OutboxList.Loaded).lost)
    }

    @Test
    fun aLossFoundWhereNoHardwareIsLeftIsSaidInMemory() = runTest {
        outbox().put(queued())
        var stateThere = true
        val chosen = ChosenOutbox(
            file.toString(),
            // Made on another Mac, and no enclave usable on this one: nothing seals here after it.
            { if (stateThere) throw OutboxKeyGone("no Secure Enclave opens it") else error("no Secure Enclave") },
            StandardTestDispatcher(testScheduler),
            forgetKey = { stateThere = false },
        )

        assertTrue((chosen.observe().first() as OutboxList.Loaded).lost)
        assertFalse(SystemFileSystem.exists(file))
        assertTrue(chosen.put(queued(account = bob)), "in memory only")
        assertFalse(SystemFileSystem.exists(file))
        assertTrue(chosen.acknowledgeLost())
        assertFalse((chosen.observe().first() as OutboxList.Loaded).lost)
        assertFalse(SystemFileSystem.exists(Path("$file.lost")), "acknowledged for good")
    }

    @Test
    fun anUnreadableOutboxIsSaidToItsObserverOnceItOpens() = runTest {
        outbox().put(queued())
        var asks = 0
        val chosen = ChosenOutbox(
            file.toString(),
            // Busy, then a key store that would not say whether its key exists, then one that answers.
            {
                when (++asks) {
                    1 -> error("the key store is busy")
                    2 -> FakeHardware(provisioned = { error("the key store will not say") })
                    else -> FakeHardware()
                }
            },
            StandardTestDispatcher(testScheduler),
        )
        val seen = mutableListOf<OutboxList>()
        val watching = backgroundScope.launch { chosen.observe().collect { seen += it } }

        runCurrent()
        assertEquals(listOf<OutboxList>(OutboxList.Unreadable), seen)
        advanceTimeBy(UNREADABLE_RETRY + 1.seconds)
        runCurrent()
        assertEquals(OutboxList.Unreadable, seen.last(), "nothing concluded from a key store that will not say")
        advanceTimeBy(UNREADABLE_RETRY + 1.seconds)
        runCurrent()
        assertEquals(1, (seen.last() as OutboxList.Loaded).queued.size)
        assertTrue(SystemFileSystem.exists(file))
        watching.cancel()
    }
}
