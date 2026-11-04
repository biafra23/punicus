package eth.serotine.data.engine

import eth.serotine.data.swaps.PinnedUniswapV3
import eth.serotine.data.swaps.UniswapDeployments
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [UniswapV3Chain] over the engine's verified reads, on [network], the one network its engine
 * follows: [pinned] (that network's pinned deployment, `UniswapDeployments` by default) is a
 * venue once the code of each of its contracts, proven against the light client's state
 * ([VerifiedChain.proveCode], the four in parallel), hashes to its pin. A check that passed, or
 * proved code other than a pin's ([VenueReading.CodeMismatch]), is remembered for as long as this
 * adapter lives: deployed code does not change, and a pin that is wrong stays wrong until the
 * build that carries it is replaced. One check runs at a time: callers that come while one runs
 * wait for it, and share its answer when it settled; after one that could not be proven, each
 * runs its own, since a proof missing a moment ago may be had now. Any other network has no venue
 * here ([VenueReading.NotPinned]): this engine reads no other chain.
 *
 * A call is a verified call at the head ([VerifiedChain.call]), labelled with the block the
 * engine names. Asked only while the light client can verify reads at its head
 * ([whyUnreadable]), on [dispatcher]; an engine that fails, or a CancellationException its own
 * code throws, is no answer, as for every verified read. A device without an engine ([chain]
 * null) answers [ReadUnavailable.NO_ENGINE].
 */
class VerifiedUniswapV3 internal constructor(
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    private val network: Network,
    private val pinned: PinnedUniswapV3? = UniswapDeployments.of(network),
) : UniswapV3Chain {

    private val checking = Mutex()

    /** What [pinned]'s proven code settled, a venue or a mismatch, or null; read and written under [checking]. */
    private var settled: VenueReading? = null

    override suspend fun venue(network: Network): VenueReading {
        val pinned = pinned?.takeIf { network == this.network } ?: return VenueReading.NotPinned
        return checking.withLock {
            settled?.let { return@withLock it }
            check(pinned).also { if (it is VenueReading.Verified || it is VenueReading.CodeMismatch) settled = it }
        }
    }

    override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation =
        if (network != this.network) {
            Simulation.Unavailable(ReadUnavailable.NO_ENGINE)
        } else {
            chain.askVerified(dispatcher, Simulation::Unavailable) { it.call(to, data).toSimulation() }
        }

    /** Each of [pinned]'s contracts' code proven and hashed, in parallel; the first that is not its pin says why. */
    private suspend fun check(pinned: PinnedUniswapV3): VenueReading = coroutineScope {
        val readings = pinned.codeHashes.map { (contract, codeHash) ->
            async {
                chain.askVerified<VerifiedChain, VenueReading?>(dispatcher, VenueReading::Unavailable) { verified ->
                    val code = verified.proveCode(contract)
                    when {
                        code == null -> VenueReading.Unavailable(ReadUnavailable.UNPROVEN)
                        code.codeHex.isEmpty() || codeHash(code.codeHex) != codeHash ->
                            VenueReading.CodeMismatch(contract)
                        else -> null
                    }
                }
            }
        }.awaitAll()
        // A mismatch outranks a proof that could not be had: it is a verified answer.
        readings.firstOrNull { it is VenueReading.CodeMismatch }
            ?: readings.firstOrNull { it != null }
            ?: VenueReading.Verified(pinned.deployment)
    }
}
