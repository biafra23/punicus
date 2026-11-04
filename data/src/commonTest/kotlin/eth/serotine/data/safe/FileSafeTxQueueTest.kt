package eth.serotine.data.safe

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.SafeTxQueueList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

/** [FileSafeTxQueue] against the [eth.serotine.domain.repository.SafeTxQueue] contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileSafeTxQueueTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-queue-${Random.nextLong().toULong()}")
    private val file = Path(directory, "safe-queue.json")
    private val safe = Address.of("0x" + "5a".repeat(20))
    private val otherSafe = Address.of("0x" + "5b".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))
    private val usdc = Address.of("0x" + "cc".repeat(20))
    private val ownerA = Address.of("0x" + "11".repeat(20))
    private val ownerB = Address.of("0x" + "22".repeat(20))

    // Made-up signatures, of no key anyone holds.
    private val byA = SafeSignature.Ecdsa(ownerA, Signature("1".padStart(64, '0'), "2".padStart(64, '0'), 0))
    private val byB = SafeSignature.Ecdsa(ownerB, Signature("3".padStart(64, '0'), "4".padStart(64, '0'), 1))
    private val byAAgain = SafeSignature.Ecdsa(ownerA, Signature("5".padStart(64, '0'), "6".padStart(64, '0'), 0))

    private fun TestScope.queue() = FileSafeTxQueue(file.toString(), StandardTestDispatcher(testScheduler))

    private fun request(
        nonce: Long,
        on: Address = safe,
        signatures: List<SafeSignature.Ecdsa> = emptyList(),
    ) = SigningRequest.of(
        Network.SEPOLIA,
        on,
        SafeTx.call(usdc, UInt256.ZERO, Erc20.transferData(bob, UInt256.of(nonce + 1)), nonce),
        signatures,
    )

    private suspend fun TestScope.queued(of: Address = safe) =
        (queue().observe(AccountId(Network.SEPOLIA, of)).first() as SafeTxQueueList.Loaded).requests

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun aQueuedRequestSurvivesARestartWithItsSignatures() = runTest {
        val first = request(4, signatures = listOf(byA))
        val second = request(5)

        assertEquals(first, queue().save(first))
        assertEquals(second, queue().save(second))
        queue().save(request(0, on = otherSafe))

        assertEquals(listOf(first, second), queued())
        assertEquals(listOf(request(0, on = otherSafe)), queued(otherSafe))
    }

    @Test
    fun theSameTransactionGainsTheSignaturesItLacksAndKeepsThoseItHas() = runTest {
        queue().save(request(4, signatures = listOf(byA)))

        val merged = queue().save(request(4, signatures = listOf(byAAgain, byB)))

        assertEquals(request(4, signatures = listOf(byA, byB)), merged)
        assertEquals(listOf(merged), queued())
    }

    @Test
    fun aRemovedOrPassedRequestIsGoneAndOnlyThatSafesArePruned() = runTest {
        val store = queue()
        listOf(request(3), request(4), request(5), request(2, on = otherSafe)).forEach { store.save(it) }

        assertTrue(store.remove(request(5).hash))
        assertTrue(store.prune(AccountId(Network.SEPOLIA, safe), nonce = 4))

        assertEquals(listOf(request(4)), queued())
        assertEquals(listOf(request(2, on = otherSafe)), queued(otherSafe))
        assertTrue(store.remove(request(9).hash), "nothing to remove is no failure")
    }

    @Test
    fun aRequestThatNoLongerDecodesIsLeftOutAndTheOthersKeepTheirSignatures() = runTest {
        SystemFileSystem.createDirectories(directory)
        val codec = JsonSigningRequestCodec()
        val kept = request(5).withSignatures(listOf(byA, byB))
        // A request for a chain the app does not know is no request it keeps; the next one stays.
        val elsewhere = codec.encode(request(4)).replace("11155111", "10")
        SystemFileSystem.sink(file).buffered().use {
            it.writeString("""{"version":1,"requests":[$elsewhere,${codec.encode(kept)}]}""")
        }

        assertEquals(listOf(kept), queued())
    }

    @Test
    fun aRequestPassedOverIsCopiedAsideBeforeTheQueueIsRewritten() = runTest {
        SystemFileSystem.createDirectories(directory)
        val codec = JsonSigningRequestCodec()
        val kept = request(5).withSignatures(listOf(byA, byB))
        val elsewhere = codec.encode(request(4)).replace("11155111", "10")
        val text = """{"version":1,"requests":[$elsewhere,${codec.encode(kept)}]}"""
        SystemFileSystem.sink(file).buffered().use { it.writeString(text) }

        queue().save(request(6))
        queue().remove(kept.hash)

        // The request passed over survives in the copy, with the file it was read from; later
        // writes set nothing more aside.
        val aside = SystemFileSystem.list(directory).filter { ".damaged-" in it.name }
        assertEquals(1, aside.size)
        assertEquals(text, SystemFileSystem.source(aside.single()).buffered().use { it.readString() })
        assertEquals(listOf(request(6)), queued())
    }

    @Test
    fun aFileThatIsNoQueueIsSetAsideAndStartsEmpty() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":1,"requests":"none"}""") }

        assertEquals(emptyList(), queued())
    }

    @Test
    fun aNewerQueueIsLeftAloneAndNothingIsSaved() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":2,"requests":[]}""") }

        assertEquals(SafeTxQueueList.Unreadable, queue().observe(AccountId(Network.SEPOLIA, safe)).first())
        assertNull(queue().save(request(4)))
    }
}
