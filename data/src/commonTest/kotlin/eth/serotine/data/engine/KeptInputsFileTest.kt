package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeQuote
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.KeptFees
import eth.serotine.domain.repository.KeptNonce
import eth.serotine.domain.repository.NonceReading
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString

@OptIn(ExperimentalCoroutinesApi::class)
class KeptInputsFileTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-kept-inputs-${Random.nextLong().toULong()}")
    private val file = Path(directory, "kept-inputs.json")
    private val alice = Address.of("0x" + "aa".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))

    private fun TestScope.kept(maxKept: Int = KeptInputsFile.MAX_KEPT) =
        KeptInputsFile(file.toString(), StandardTestDispatcher(testScheduler), maxKept)

    private fun quote(base: Long, block: Long) = FeeQuote(
        nextBaseFee = UInt256.of(base),
        tips = mapOf(FeeLevel.SLOW to UInt256.of(1), FeeLevel.NORMAL to UInt256.of(2), FeeLevel.FAST to UInt256.of(3)),
        blockNumber = block,
    )

    private fun text() = SystemFileSystem.source(file).buffered().use { it.readString() }

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun aMinedCountAndAFeeQuoteAreKeptAcrossARestartEachUnderItsNetwork() = runTest {
        kept().saveNonce(Network.ETHEREUM, alice, count = 7, block = 900, asOf = 1_792_100_000)
        kept().saveFees(Network.ETHEREUM, quote(base = 40, block = 900), asOf = 1_792_100_000)

        val after = kept()
        assertEquals(KeptNonce(7, 900, 1_792_100_000), after.minedNonce(Network.ETHEREUM, alice))
        assertEquals(KeptFees(quote(base = 40, block = 900), 1_792_100_000), after.fees(Network.ETHEREUM))
        // Another network's, or another account's, is not this one's.
        assertNull(after.minedNonce(Network.GNOSIS, alice))
        assertNull(after.minedNonce(Network.ETHEREUM, bob))
        assertNull(after.fees(Network.SEPOLIA))
    }

    @Test
    fun anOlderBlocksAnswerNeverReplacesANewerOne() = runTest {
        val kept = kept()
        kept.saveNonce(Network.SEPOLIA, alice, count = 5, block = 200, asOf = 2)
        kept.saveNonce(Network.SEPOLIA, alice, count = 4, block = 150, asOf = 1)
        kept.saveFees(Network.SEPOLIA, quote(base = 10, block = 200), asOf = 2)
        kept.saveFees(Network.SEPOLIA, quote(base = 99, block = 150), asOf = 1)

        assertEquals(KeptNonce(5, 200, 2), kept.minedNonce(Network.SEPOLIA, alice))
        assertEquals(quote(base = 10, block = 200), kept.fees(Network.SEPOLIA)?.quote)
    }

    @Test
    fun anUnchangedCountOrARecentFeeQuoteIsNotWrittenAgainUntilItIsOld() = runTest {
        val kept = kept()
        kept.saveNonce(Network.SEPOLIA, alice, count = 5, block = 200, asOf = 2)
        kept.saveFees(Network.SEPOLIA, quote(base = 10, block = 200), asOf = 2)
        val written = text()

        kept.saveNonce(Network.SEPOLIA, alice, count = 5, block = 200 + KeptInputsFile.REWRITE_AFTER_BLOCKS - 1, asOf = 3)
        kept.saveFees(Network.SEPOLIA, quote(base = 11, block = 200 + KeptInputsFile.REWRITE_AFTER_BLOCKS - 1), asOf = 3)
        assertEquals(written, text(), "nothing written for a value this recent")

        // A changed count is written at once; an old one, or an old fee quote, is written again.
        kept.saveNonce(Network.SEPOLIA, alice, count = 6, block = 210, asOf = 4)
        assertEquals(KeptNonce(6, 210, 4), kept.minedNonce(Network.SEPOLIA, alice))
        kept.saveFees(Network.SEPOLIA, quote(base = 12, block = 200 + KeptInputsFile.REWRITE_AFTER_BLOCKS), asOf = 5)
        assertEquals(quote(base = 12, block = 200 + KeptInputsFile.REWRITE_AFTER_BLOCKS), kept.fees(Network.SEPOLIA)?.quote)
    }

    @Test
    fun theLeastRecentlyVerifiedCountIsDroppedFirst() = runTest {
        val kept = kept(maxKept = 2)
        val carol = Address.of("0x" + "cc".repeat(20))
        kept.saveNonce(Network.SEPOLIA, alice, count = 1, block = 10, asOf = null)
        kept.saveNonce(Network.SEPOLIA, bob, count = 1, block = 11, asOf = null)
        kept.saveNonce(Network.SEPOLIA, carol, count = 1, block = 12, asOf = null)

        assertNull(kept.minedNonce(Network.SEPOLIA, alice))
        assertEquals(KeptNonce(1, 11, null), kept.minedNonce(Network.SEPOLIA, bob))
        assertEquals(KeptNonce(1, 12, null), kept.minedNonce(Network.SEPOLIA, carol))
    }

    @Test
    fun aDamagedOrNewerFileKeepsNothing() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("not json") }
        assertNull(kept().minedNonce(Network.SEPOLIA, alice))

        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":99,"nonces":[],"fees":[]}""") }
        assertNull(kept().fees(Network.SEPOLIA))
    }

    @Test
    fun theEngineAdapterKeepsEachVerifiedMinedCountAndFeeQuoteWithItsHead() = runTest {
        val chain = FakeTransactingChain()
        chain.base.snapshot = running(head = 321, headTimestamp = 1_792_200_000)
        chain.mined = 9
        chain.feeHistory = """{"oldestBlock":"0x1","baseFeePerGas":["0x10","0x10"],"reward":[["0x1","0x2","0x3"]]}"""
        val kept = kept()
        val inputs = EngineTransactionInputs(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            kept = kept,
        )

        assertEquals(NonceReading.Read(9), inputs.minedNonce(Network.SEPOLIA, alice))
        val fees = inputs.fees(Network.SEPOLIA)

        assertEquals(KeptNonce(9, 321, 1_792_200_000), kept.minedNonce(Network.SEPOLIA, alice))
        assertTrue(fees is FeeReading.Quoted)
        assertEquals(KeptFees(fees.quote, 1_792_200_000), kept.fees(Network.SEPOLIA))
    }

    @Test
    fun nothingIsKeptWhileNoHeadCanBeRead() = runTest {
        val chain = FakeTransactingChain()
        chain.base.snapshot = running(phase = EngineSnapshot.Phase.CATCHING_UP)
        chain.mined = 9
        val kept = kept()
        val inputs = EngineTransactionInputs(Network.SEPOLIA, chain, StandardTestDispatcher(testScheduler), kept = kept)

        assertTrue(inputs.minedNonce(Network.SEPOLIA, alice) is NonceReading.Unavailable)
        assertNull(kept.minedNonce(Network.SEPOLIA, alice))
    }
}
