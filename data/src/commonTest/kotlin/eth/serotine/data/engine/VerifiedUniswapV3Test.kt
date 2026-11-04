package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.data.swaps.PinnedUniswapV3
import eth.serotine.data.swaps.UniswapDeployments
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedUniswapV3Test {

    private val chain = FakeChain()
    private val deployment = UniswapV3Deployment(
        factory = Address.of("0x" + "fa".repeat(20)),
        quoterV2 = Address.of("0x" + "0e".repeat(20)),
        swapRouter02 = Address.of("0x" + "50".repeat(20)),
        weth9 = Address.of("0x" + "ff".repeat(20)),
    )

    /** Each contract's code: a byte of its own, so each hash differs. */
    private val codes = with(deployment) { listOf(factory, quoterV2, swapRouter02, weth9) }
        .mapIndexed { i, contract -> contract to "60${i}0" }
        .toMap()
    private val pinned = PinnedUniswapV3(deployment, codes.mapValues { codeHash(it.value) })

    private fun TestScope.venue() =
        VerifiedUniswapV3(chain, StandardTestDispatcher(testScheduler), Network.SEPOLIA, pinned)

    private fun proveAll() {
        codes.forEach { (contract, code) -> chain.codes[contract] = ProvenCode(code, 100) }
    }

    @Test
    fun aDeploymentWhoseCodeHashesToItsPinsIsAVenueAndIsCheckedOnce() = runTest {
        chain.snapshot = running()
        proveAll()
        val uniswap = venue()

        assertEquals(VenueReading.Verified(deployment), uniswap.venue(Network.SEPOLIA))
        assertEquals(4, chain.codeCalls)
        assertEquals(VenueReading.Verified(deployment), uniswap.venue(Network.SEPOLIA))
        assertEquals(4, chain.codeCalls, "a check that passed is remembered")
    }

    @Test
    fun otherCodeOrNoneIsAMismatchAndIsRemembered() = runTest {
        chain.snapshot = running()
        proveAll()
        chain.codes[deployment.quoterV2] = ProvenCode("6001", 100)
        val uniswap = venue()
        assertEquals(VenueReading.CodeMismatch(deployment.quoterV2), uniswap.venue(Network.SEPOLIA))
        val calls = chain.codeCalls
        assertEquals(VenueReading.CodeMismatch(deployment.quoterV2), uniswap.venue(Network.SEPOLIA))
        assertEquals(calls, chain.codeCalls, "proven code other than the pin's cannot change: not proven again")

        chain.codes[deployment.quoterV2] = ProvenCode("", 100)
        assertEquals(VenueReading.CodeMismatch(deployment.quoterV2), venue().venue(Network.SEPOLIA))
    }

    @Test
    fun aCheckThatCouldNotBeProvenIsRunAgain() = runTest {
        chain.snapshot = running()
        proveAll()
        chain.codes.remove(deployment.weth9)
        val uniswap = venue()
        assertEquals(VenueReading.Unavailable(ReadUnavailable.UNPROVEN), uniswap.venue(Network.SEPOLIA))

        proveAll()
        assertEquals(VenueReading.Verified(deployment), uniswap.venue(Network.SEPOLIA))
    }

    @Test
    fun codeThatCannotBeProvenIsNoAnswer() = runTest {
        chain.snapshot = running()
        proveAll()
        chain.codes.remove(deployment.weth9)
        assertEquals(VenueReading.Unavailable(ReadUnavailable.UNPROVEN), venue().venue(Network.SEPOLIA))

        chain.snapshot = running(phase = EngineSnapshot.Phase.SYNCING)
        assertEquals(VenueReading.Unavailable(ReadUnavailable.SYNCING), venue().venue(Network.SEPOLIA))
        chain.snapshot = EngineSnapshot.Down
        assertEquals(VenueReading.Unavailable(ReadUnavailable.NO_ENGINE), venue().venue(Network.SEPOLIA))
        assertEquals(
            VenueReading.Unavailable(ReadUnavailable.NO_ENGINE),
            VerifiedUniswapV3(null, StandardTestDispatcher(testScheduler), Network.SEPOLIA, pinned)
                .venue(Network.SEPOLIA),
        )
    }

    @Test
    fun aNetworkWithNoPinIsNoVenue() = runTest {
        assertEquals(
            VenueReading.NotPinned,
            VerifiedUniswapV3(chain, StandardTestDispatcher(testScheduler), Network.SEPOLIA, pinned = null)
                .venue(Network.SEPOLIA),
        )
    }

    @Test
    fun concurrentFirstChecksShareOneProofOfEachContract() = runTest {
        chain.snapshot = running()
        proveAll()
        val uniswap = venue()
        val readings = List(3) { async { uniswap.venue(Network.SEPOLIA) } }.awaitAll()
        assertEquals(List(3) { VenueReading.Verified(deployment) }, readings)
        assertEquals(4, chain.codeCalls)
    }

    @Test
    fun aMismatchOutranksAProofThatCouldNotBeHad() = runTest {
        chain.snapshot = running()
        proveAll()
        chain.codes.remove(deployment.factory)
        chain.codes[deployment.weth9] = ProvenCode("6001", 100)
        assertEquals(VenueReading.CodeMismatch(deployment.weth9), venue().venue(Network.SEPOLIA))
    }

    @Test
    fun aCallIsAVerifiedCallAtItsBlock() = runTest {
        chain.snapshot = running()
        val data = byteArrayOf(0x1a, 0x68, 0x65, 0x02)
        val pool = Address.of("0x" + "99".repeat(20))
        chain.calls[pool to data.toHex()] = CallOutcome.Returned("00".repeat(32), 120)
        assertEquals(Simulation.Succeeded("00".repeat(32), 120), venue().call(Network.SEPOLIA, pool, data))

        chain.calls[pool to data.toHex()] = CallOutcome.Reverted(121)
        assertEquals(Simulation.Reverted(121), venue().call(Network.SEPOLIA, pool, data))
        chain.calls[pool to data.toHex()] = CallOutcome.Refused
        assertEquals(Simulation.Refused, venue().call(Network.SEPOLIA, pool, data))
        chain.calls.clear()
        assertEquals(Simulation.Unavailable(ReadUnavailable.UNPROVEN), venue().call(Network.SEPOLIA, pool, data))

        chain.failure = IllegalStateException("the engine failed")
        assertEquals(Simulation.Unavailable(ReadUnavailable.UNPROVEN), venue().call(Network.SEPOLIA, pool, data))
    }

    @Test
    fun sepoliaPinsItsFourContractsEachWithACodeHash() {
        val sepolia = UniswapDeployments.of(Network.SEPOLIA)!!
        assertEquals(Address.of("0x0227628f3f023bb0b980b67d528571c95c6dac1c"), sepolia.deployment.factory)
        assertEquals(Address.of("0xed1f6473345f45b75f8179591dd5ba1888cf2fb3"), sepolia.deployment.quoterV2)
        assertEquals(Address.of("0x3bfa4769fb09eefc5a80d6e87c3b9c650f7ae48e"), sepolia.deployment.swapRouter02)
        assertEquals(Address.of("0xfff9976782d46cc05630d1f6ebab18b2324d6b14"), sepolia.deployment.weth9)
        assertEquals(4, sepolia.codeHashes.size)
        sepolia.codeHashes.values.forEach { assertEquals(64, it.length) }
        assertFailsWith<IllegalArgumentException> { PinnedUniswapV3(deployment, codes.minus(deployment.weth9)) }
    }
}
