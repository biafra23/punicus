package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [NameResolver] over the engine's ENS resolution ([TransactingChain.resolveName]), against
 * beacon-finalized state only: an answer the engine did not verify there (a peer's head) is no
 * answer, and a name only an off-chain gateway answers is [NameResolution.OffchainOnly] (the
 * engine is given no gateway). The engine lower-cases a name and applies no other normalization
 * (no ENSIP-15, no UTS-46), so a name that looks like another but hashes to a different node — a
 * Cyrillic letter, a zero-width joiner — would resolve to whoever owns that node: until the
 * normalization exists, only ASCII letter-digit-hyphen labels are taken, the rest refused as
 * [NameResolution.NotAName]. On [dispatcher]; a device without an engine, or an engine that
 * fails, has no answer.
 */
class EngineNames internal constructor(
    private val served: Network,
    private val chain: TransactingChain?,
    private val dispatcher: CoroutineDispatcher,
) : NameResolver {

    override suspend fun resolve(network: Network, name: String): NameResolution {
        if (network != served) return NameResolution.Unavailable(ReadUnavailable.NO_ENGINE)
        val normalized = name.trim().lowercase()
        if (!looksLikeAName(normalized)) return NameResolution.NotAName
        val chain = chain ?: return NameResolution.Unavailable(ReadUnavailable.NO_ENGINE)
        return withContext(dispatcher) {
            try {
                whyUnreadable(chain.snapshot())?.let { return@withContext NameResolution.Unavailable(it) }
                val answer = chain.resolveName(normalized)
                when {
                    answer.offchain -> NameResolution.OffchainOnly
                    answer.error != null || !answer.verified -> NameResolution.Unavailable(ReadUnavailable.UNPROVEN)
                    answer.addressHex == null -> NameResolution.NoAddress
                    else -> Address.parse(answer.addressHex)
                        ?.let { NameResolution.Resolved(normalized, it, answer.blockNumber) }
                        ?: NameResolution.Unavailable(ReadUnavailable.UNPROVEN)
                }
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive() // as for every engine call
                NameResolution.Unavailable(ReadUnavailable.UNPROVEN)
            }
        }
    }

    // ASCII LDH labels: a-z, 0-9 and inner hyphens, at least two of them.
    private fun looksLikeAName(name: String): Boolean {
        val labels = name.split('.')
        return labels.size >= 2 && labels.all(LABEL::matches)
    }

    private companion object {
        val LABEL = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")
    }
}
