package eth.serotine.data.swaps

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.WaitingSwap
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
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileWaitingSwapsTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-waiting-${Random.nextLong().toULong()}")
    private val file = Path(directory, "waiting-swaps.json")

    private val main = AccountId(Network.SEPOLIA, Address.of("0x" + "11".repeat(20)))
    private val usdc = Address.of("0x" + "aa".repeat(20))
    private val link = Address.of("0x" + "bb".repeat(20))

    private fun TestScope.swaps() = FileWaitingSwaps(file.toString(), StandardTestDispatcher(testScheduler))

    private fun swap(sell: Address = usdc, venue: SwapVenue = SwapVenue.UNISWAP, step: Int = 2, steps: Int = 2) =
        WaitingSwap(
            account = main,
            sell = sell,
            buy = if (sell == usdc) null else usdc,
            amount = UInt256.of(40_000_000),
            tolerance = requireNotNull(SlippageTolerance.ofBasisPoints(75)),
            venue = venue,
            approval = "0x" + "01".repeat(32),
            step = step,
            steps = steps,
        )

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    private fun write(text: String) {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString(text) }
    }

    @Test
    fun aWaitingSwapSurvivesARestartUntilItIsLetGo() = runTest {
        assertEquals(emptyList(), swaps().observe().first())

        assertTrue(swaps().keep(swap()))
        assertTrue(swaps().keep(swap(sell = link, venue = SwapVenue.COW, step = 2, steps = 3)))
        assertEquals(
            listOf(swap(), swap(sell = link, venue = SwapVenue.COW, step = 2, steps = 3)),
            swaps().observe().first(),
        )

        assertTrue(swaps().drop(main, usdc))
        assertEquals(listOf(swap(sell = link, venue = SwapVenue.COW, step = 2, steps = 3)), swaps().observe().first())
    }

    @Test
    fun aNewerSwapOfTheSameAccountAndTokenTakesTheOldersPlace() = runTest {
        swaps().keep(swap())
        val newer = swap().copy(amount = UInt256.of(1), approval = "0x" + "02".repeat(32))
        swaps().keep(newer)
        assertEquals(listOf(newer), swaps().observe().first())
    }

    @Test
    fun oneThisBuildCannotReadIsLeftOutAndTheOthersKept() = runTest {
        val good = """{"chain":11155111,"account":"0x${"11".repeat(20)}","sell":"0x${"aa".repeat(20)}",""" +
            """"buy":null,"amount":"40000000","toleranceBasisPoints":75,"venue":"uniswap",""" +
            """"approval":"0x${"01".repeat(32)}","step":2,"steps":2}"""
        val unknownVenue = good.replace("\"uniswap\"", "\"elsewhere\"")
        val unknownChain = good.replace("11155111", "999")
        val ofAnotherType = good.replace("\"step\":2", "\"step\":\"two\"")
        val text = """{"version":1,"swaps":[$unknownVenue,$good,$unknownChain,$ofAnotherType]}"""
        write(text)

        assertEquals(listOf(swap()), swaps().observe().first())

        // What was passed over outlives the next write, in a copy of the file it was read from.
        assertTrue(swaps().drop(main, usdc))
        val aside = SystemFileSystem.list(directory).filter { ".damaged-" in it.name }
        assertEquals(1, aside.size)
        assertEquals(text, SystemFileSystem.source(aside.single()).buffered().use { it.readString() })
    }

    @Test
    fun aFileThatIsNoListIsSetAsideAndTheListStartsEmpty() = runTest {
        write("""{"version":1,"swaps":"none"}""")
        assertEquals(emptyList(), swaps().observe().first())
        assertTrue(SystemFileSystem.list(directory).any { it.name.contains("damaged") })
    }
}
