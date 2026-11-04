package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * [ProvenReads] on real threads: readers of one key at one head, arriving while its proof is
 * being fetched (a blocking engine call), share that one fetch.
 */
class ProvenReadsSingleFlightTest {

    private val address = Address.of("0x" + "ab".repeat(20))

    /** A chain whose balance proof blocks until [release]; it counts the proofs asked for. */
    private class BlockingChain : VerifiedChain {
        val asked = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun snapshot(): EngineSnapshot = running(head = 9)

        override fun proveBalance(address: Address): ProvenBalance? {
            asked.incrementAndGet()
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "never released" }
            return ProvenBalance("5", 9)
        }

        override fun proveCode(address: Address): ProvenCode? = null

        override fun call(to: Address, data: ByteArray): CallOutcome? = null

        override fun proveSlot(address: Address, slot: Long, holder: Address?): ProvenSlot? = null

        override fun storageAt(address: Address, slot: ByteArray): String? = null
    }

    @Test
    fun readersArrivingDuringAFetchShareIt() = runBlocking {
        val chain = BlockingChain()
        val balances = VerifiedNativeBalances(Network.SEPOLIA, chain, Dispatchers.IO, 1.seconds)

        val readers = (1..4).map { async(Dispatchers.IO) { balances.observeBalance(Network.SEPOLIA, address).first() } }
        // The first fetch is under way; the other readers find it in flight.
        check(chain.started.await(10, TimeUnit.SECONDS))
        Thread.sleep(200)
        chain.release.countDown()
        val readings = withTimeout(10_000) { readers.awaitAll() }

        assertEquals(1, chain.asked.get(), "one proof for one key at one head")
        assertEquals(1, readings.toSet().size)
    }
}
