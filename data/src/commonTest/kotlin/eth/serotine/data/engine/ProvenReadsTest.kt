package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ProvenReadsTest {

    private val address = Address.of("0x" + "cd".repeat(20))
    private val chain = FakeChain()

    @Test
    fun aProofVouchedUnchangedIsDatedByTheHeadItWasVouchedAt() = runTest {
        val reads = ProvenReads<Address, String>(
            chain,
            StandardTestDispatcher(testScheduler),
            POLL,
            unchangedSince = { _, _, _, _ -> true },
        ) { verified, key ->
            verified.proveBalance(key)?.let { EngineReading.Proven(it.wei, it.blockNumber, "0xabc") }
        }
        chain.snapshot = running(head = 7, headTimestamp = 1_000)
        chain.proofs[address] = ProvenBalance("1", 7)
        val seen = mutableListOf<EngineReading<String>>()
        val collecting = launch { reads.observe(address).collect { seen += it } }
        runCurrent()

        chain.snapshot = running(head = 9, headTimestamp = 1_024)
        advanceTimeBy(POLL)
        runCurrent()

        assertEquals(1, chain.proofRequests.size, "vouched: not proven again")
        assertEquals(EngineReading.Proven("1", 7, "0xabc", provenAt = 1_024), seen.last(), "current as of head 9")
        collecting.cancel()
    }

    private companion object {
        val POLL = 4.seconds
    }
}
