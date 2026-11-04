package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.data.swaps.CowDeployments
import eth.serotine.data.swaps.PinnedCow
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowDeployment
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.CowVenueReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedCowTest {

    private val chain = FakeChain()
    private val deployment = CowDeployment(
        settlement = Address.of("0x" + "90".repeat(20)),
        vaultRelayer = Address.of("0x" + "c9".repeat(20)),
    )
    private val codes = mapOf(deployment.settlement to "6000", deployment.vaultRelayer to "6010")
    private val pinned = PinnedCow(deployment, codes.mapValues { codeHash(it.value) })

    private fun TestScope.venue() = VerifiedCow(chain, StandardTestDispatcher(testScheduler), Network.SEPOLIA, pinned)

    private fun proveAll() {
        codes.forEach { (contract, code) -> chain.codes[contract] = ProvenCode(code, 100) }
    }

    @Test
    fun aDeploymentWhoseCodeHashesToItsPinsIsAVenueAndIsCheckedOnce() = runTest {
        chain.snapshot = running()
        proveAll()
        val cow = venue()
        assertEquals(CowVenueReading.Verified(deployment), cow.venue(Network.SEPOLIA))
        assertEquals(CowVenueReading.Verified(deployment), cow.venue(Network.SEPOLIA))
        assertEquals(2, chain.codeCalls, "a check that passed is remembered")
    }

    @Test
    fun otherCodeIsAMismatchThatOutranksAProofNotHad() = runTest {
        chain.snapshot = running()
        proveAll()
        chain.codes[deployment.vaultRelayer] = ProvenCode("6011", 100)
        assertEquals(CowVenueReading.CodeMismatch(deployment.vaultRelayer), venue().venue(Network.SEPOLIA))
        chain.codes.remove(deployment.settlement)
        assertEquals(CowVenueReading.CodeMismatch(deployment.vaultRelayer), venue().venue(Network.SEPOLIA))
    }

    @Test
    fun aCheckThatCouldNotBeProvenIsRunAgain() = runTest {
        chain.snapshot = running()
        val cow = venue()
        assertEquals(CowVenueReading.Unavailable(ReadUnavailable.UNPROVEN), cow.venue(Network.SEPOLIA))
        proveAll()
        assertEquals(CowVenueReading.Verified(deployment), cow.venue(Network.SEPOLIA))
        assertEquals(
            CowVenueReading.Unavailable(ReadUnavailable.NO_ENGINE),
            VerifiedCow(null, StandardTestDispatcher(testScheduler), Network.SEPOLIA, pinned).venue(Network.SEPOLIA),
        )
        assertEquals(
            CowVenueReading.NotPinned,
            VerifiedCow(chain, StandardTestDispatcher(testScheduler), Network.SEPOLIA, null).venue(Network.SEPOLIA),
        )
    }

    @Test
    fun aCallIsAVerifiedCallAtItsBlock() = runTest {
        chain.snapshot = running()
        val data = byteArrayOf(0xf6.toByte(), 0x98.toByte(), 0xda.toByte(), 0x25)
        chain.calls[deployment.settlement to data.toHex()] = CallOutcome.Returned("ab".repeat(32), 120)
        assertEquals(
            Simulation.Succeeded("ab".repeat(32), 120),
            venue().call(Network.SEPOLIA, deployment.settlement, data),
        )
        chain.calls.clear()
        assertEquals(
            Simulation.Unavailable(ReadUnavailable.UNPROVEN),
            venue().call(Network.SEPOLIA, deployment.settlement, data),
        )
    }

    @Test
    fun sepoliaPinsBothContractsEachWithACodeHash() {
        val sepolia = CowDeployments.of(Network.SEPOLIA)!!
        assertEquals(Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41"), sepolia.deployment.settlement)
        assertEquals(Address.of("0xc92e8bdf79f0507f65a392b0ab4667716bfe0110"), sepolia.deployment.vaultRelayer)
        sepolia.codeHashes.values.forEach { assertEquals(64, it.length) }
        assertFailsWith<IllegalArgumentException> { PinnedCow(deployment, codes.minus(deployment.vaultRelayer)) }
    }
}
