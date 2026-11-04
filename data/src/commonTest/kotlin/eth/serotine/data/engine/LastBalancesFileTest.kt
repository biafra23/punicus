package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
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

@OptIn(ExperimentalCoroutinesApi::class)
class LastBalancesFileTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-balances-${Random.nextLong().toULong()}")
    private val file = Path(directory, "last-balances.json")
    private val address = Address.of("0x" + "ab".repeat(20))
    private val chain = FakeChain()

    private fun TestScope.lastBalances(maxKept: Int = LastBalancesFile.MAX_KEPT) =
        LastBalancesFile(file.toString(), StandardTestDispatcher(testScheduler), maxKept)

    private fun native(lastBalances: LastBalancesFile) =
        lastBalances.kept<Address, Wei>(id = { "native:${it.hex}" }, units = { it.decimal }, parse = Wei::parse)

    private fun text() = SystemFileSystem.source(file).buffered().use { it.readString() }

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun aBalanceProvenBeforeARestartIsLastKnownAfterItUntilAHeadCanBeRead() = runTest {
        chain.snapshot = running(head = 9, headTimestamp = 1_792_100_000)
        chain.proofs[address] = ProvenBalance("5", 9)
        val before = VerifiedNativeBalances(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            POLL,
            lastBalances(),
        )
        assertEquals(
            BalanceReading.Verified(Wei.of("5"), 9, provenAt = 1_792_100_000),
            before.observeBalance(Network.SEPOLIA, address).first(),
        )

        // A new process: nothing proven in it yet, and the light client still catching up.
        chain.snapshot = running(phase = EngineSnapshot.Phase.CATCHING_UP)
        val after = VerifiedNativeBalances(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            POLL,
            lastBalances(),
        )
        assertEquals(
            BalanceReading.LastKnown(Wei.of("5"), 9, asOf = 1_792_100_000),
            after.observeBalance(Network.SEPOLIA, address).first(),
        )

        // Without a file, the same restart knows nothing.
        val forgetful = VerifiedNativeBalances(Network.SEPOLIA, chain, StandardTestDispatcher(testScheduler), POLL)
        assertEquals(BalanceReading.Syncing, forgetful.observeBalance(Network.SEPOLIA, address).first())
    }

    @Test
    fun anUnchangedBalanceIsNotWrittenAtEveryHead() = runTest {
        val kept = native(lastBalances())
        kept.save(address, EngineReading.LastKnown(Wei.of("5"), 9, 100))
        val written = text()

        kept.save(address, EngineReading.LastKnown(Wei.of("5"), 10, 112))
        assertEquals(written, text(), "same amount, a block later: not written")

        kept.save(address, EngineReading.LastKnown(Wei.of("5"), 9 + LastBalancesFile.REWRITE_AFTER_BLOCKS, 700))
        assertEquals(EngineReading.LastKnown(Wei.of("5"), 59, 700), native(lastBalances()).load(address))

        kept.save(address, EngineReading.LastKnown(Wei.of("6"), 60, 712))
        assertEquals(EngineReading.LastKnown(Wei.of("6"), 60, 712), native(lastBalances()).load(address))

        kept.save(address, EngineReading.LastKnown(Wei.of("7"), 58, 690))
        assertEquals(Wei.of("6"), native(lastBalances()).load(address)?.value, "an older proof never replaces one")
    }

    @Test
    fun onlyTheMostRecentlyProvenBalancesAreKept() = runTest {
        // A small cap, not MAX_KEPT: every save is a real write flushed to the disk twice, and
        // runTest's timeout is wall-clock time, so MAX_KEPT + 1 of them overran it where fsync is slow.
        val maxKept = 3
        val kept = native(lastBalances(maxKept))
        val addresses = (0 until maxKept + 1).map {
            Address.of("0x" + it.toString(16).padStart(40, '0'))
        }
        addresses.forEach { kept.save(it, EngineReading.LastKnown(Wei.of("1"), 9, null)) }

        val reread = native(lastBalances(maxKept))
        assertNull(reread.load(addresses.first()), "the oldest was let go")
        assertEquals(EngineReading.LastKnown(Wei.of("1"), 9, null), reread.load(addresses.last()))
    }

    @Test
    fun anEntryThatIsNoAmountIsNotUsedAndADamagedFileIsSetAside() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use {
            it.writeString(
                """{"version":1,"balances":[{"id":"native:${address.hex}","units":"-5","block":9,"asOf":null}]}""",
            )
        }
        assertNull(native(lastBalances()).load(address))

        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":1,"balances":[""") }
        assertNull(native(lastBalances()).load(address))
        assertTrue(SystemFileSystem.list(directory).any { it.name.contains("damaged") })
    }

    @Test
    fun aBalanceThatIsNoLongerOneIsForgotten() = runTest {
        val kept = lastBalances().kept<Address, Wei?>(
            id = { "token:${it.hex}" },
            units = { it?.decimal },
            parse = Wei::parse,
        )
        kept.save(address, EngineReading.LastKnown(Wei.of("5"), 9, null))
        kept.save(address, EngineReading.LastKnown(null, 10, null))

        val reread = lastBalances().kept<Address, Wei?>({ "token:${it.hex}" }, { it?.decimal }, Wei::parse)
        assertNull(reread.load(address))
        assertFalse(text().contains("\"5\""))
    }

    private companion object {
        val POLL = 4.seconds
    }

    @Test
    fun aBalanceKeptBeforeNetworksIsSepolias() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use {
            it.writeString(
                """{"version":1,"balances":[{"id":"native:${address.hex}","units":"5","block":9,"asOf":7}]}""",
            )
        }
        chain.snapshot = running(phase = EngineSnapshot.Phase.CATCHING_UP)
        val balances = VerifiedNativeBalances(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            POLL,
            lastBalances(),
        )

        assertEquals(
            BalanceReading.LastKnown(Wei.of("5"), 9, asOf = 7),
            balances.observeBalance(Network.SEPOLIA, address).first(),
        )
    }
}
