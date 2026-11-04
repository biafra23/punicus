package eth.serotine.data.engine

import eth.serotine.data.swaps.CowDeployments
import eth.serotine.data.swaps.PinnedCow
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.CowVenueReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [CowChain] over the engine's verified reads, on [network], the one network its engine follows,
 * as `VerifiedUniswapV3` is for Uniswap: [pinned] (`CowDeployments` by default) is a venue once
 * the code of its settlement contract and of its vault relayer, proven against the light client's
 * state ([VerifiedChain.proveCode], both in parallel), hashes to its pin. A check that passed, or
 * proved code other than a pin's, is remembered for as long as this adapter lives; one that could
 * not be proven is run again by the next caller. Any other network has no venue here, and a call
 * on it is refused ([ReadUnavailable.NO_ENGINE]): this engine reads no other chain.
 *
 * A call is a verified call at the head ([VerifiedChain.call]), asked only while the light client
 * can verify reads at its head, on [dispatcher]. A device without an engine ([chain] null) answers
 * [ReadUnavailable.NO_ENGINE]. No off-chain service is behind it.
 */
class VerifiedCow internal constructor(
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    private val network: Network,
    private val pinned: PinnedCow? = CowDeployments.of(network),
) : CowChain {

    private val checking = Mutex()

    /** What [pinned]'s proven code settled, a venue or a mismatch, or null; read and written under [checking]. */
    private var settled: CowVenueReading? = null

    override suspend fun venue(network: Network): CowVenueReading {
        val pinned = pinned?.takeIf { network == this.network } ?: return CowVenueReading.NotPinned
        return checking.withLock {
            settled?.let { return@withLock it }
            check(pinned).also {
                if (it is CowVenueReading.Verified || it is CowVenueReading.CodeMismatch) settled = it
            }
        }
    }

    override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation =
        if (network != this.network) {
            Simulation.Unavailable(ReadUnavailable.NO_ENGINE)
        } else {
            chain.askVerified(dispatcher, Simulation::Unavailable) { it.call(to, data).toSimulation() }
        }

    /** Each of [pinned]'s contracts' code proven and hashed, in parallel; the first that is not its pin says why. */
    private suspend fun check(pinned: PinnedCow): CowVenueReading = coroutineScope {
        val readings = pinned.codeHashes.map { (contract, codeHash) ->
            async {
                val unavailable: (ReadUnavailable) -> CowVenueReading? = CowVenueReading::Unavailable
                chain.askVerified(dispatcher, unavailable) { verified ->
                    val code = verified.proveCode(contract)
                    when {
                        code == null -> CowVenueReading.Unavailable(ReadUnavailable.UNPROVEN)
                        code.codeHex.isEmpty() || codeHash(code.codeHex) != codeHash ->
                            CowVenueReading.CodeMismatch(contract)
                        else -> null
                    }
                }
            }
        }.awaitAll()
        // A mismatch outranks a proof that could not be had: it is a verified answer.
        readings.firstOrNull { it is CowVenueReading.CodeMismatch }
            ?: readings.firstOrNull { it != null }
            ?: CowVenueReading.Verified(pinned.deployment)
    }
}
