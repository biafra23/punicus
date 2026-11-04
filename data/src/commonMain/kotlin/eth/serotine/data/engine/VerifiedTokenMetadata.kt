package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenMetadata
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [TokenMetadata] over a verified light client: a contract's `symbol()`, `name()` and
 * `decimals()` as verified calls ([VerifiedChain.call]), polled through [ProvenReads] until
 * they are answered. A token's description, and a refusal, are then kept for the life of the
 * process (the [MAX_ANSWERED] most recent): read once, not at every head. `symbol()` and
 * `decimals()` must answer; `name()` may be missing (some tokens have none) and is then the
 * symbol. A revert, an answer that is no text or no small number, or decimals no amount can
 * be shown at, is a verified "not a token" — ending the flow, but not kept: the contract may
 * not be deployed yet at the head it was read at, so the next subscriber asks again.
 */
class VerifiedTokenMetadata internal constructor(
    private val served: Network,
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
) : TokenMetadata {

    private val reads = ProvenReads<Address, MetadataReading>(chain, dispatcher, pollInterval) { verified, token ->
        // In order, each ending the read: unproven (null), or refused, which is for good — so a
        // refused call is not followed by the others, and is not taken for an unproven one.
        val outcomes = mutableListOf<CallOutcome>()
        for (selector in listOf(Abi.DECIMALS, Abi.SYMBOL, Abi.NAME)) {
            val outcome = verified.call(token, selector) ?: return@ProvenReads null
            if (outcome == CallOutcome.Refused) return@ProvenReads EngineReading.Unavailable(ReadUnavailable.REFUSED)
            outcomes += outcome
        }
        val (decimals, symbol, name) = outcomes
        val block = (decimals as? CallOutcome.Returned)?.blockNumber
        EngineReading.Proven(described(decimals, symbol, name, block), block ?: UNKNOWN_BLOCK)
    }

    /** The answers kept, per token, the most recent last. */
    private val answered = mutableMapOf<Address, MetadataReading>()
    private val answeredLock = Mutex()

    override fun observe(network: Network, token: Address): Flow<MetadataReading> = flow {
        if (network != served) {
            emit(MetadataReading.Unavailable(ReadUnavailable.NO_ENGINE))
            return@flow
        }
        answeredLock.withLock { answered[token] }?.let {
            emit(it)
            return@flow
        }
        val readings = reads.observe(token)
            .map { reading ->
                when (reading) {
                    EngineReading.Syncing, is EngineReading.LastKnown -> MetadataReading.Syncing
                    is EngineReading.Proven -> reading.value
                    is EngineReading.Unavailable -> MetadataReading.Unavailable(reading.reason)
                }
            }
            .onEach { if (it.isKept()) keep(token, it) }
            .transformWhile { reading ->
                emit(reading)
                !reading.isFinal()
            }
        emitAll(readings)
    }

    private suspend fun keep(token: Address, reading: MetadataReading) = answeredLock.withLock {
        answered.remove(token) // re-inserted last: the map's order is the order of answering
        answered[token] = reading
        if (answered.size > MAX_ANSWERED) answered.remove(answered.keys.first())
    }

    /** An answer that ends the flow: a description, "not a token", or a refusal. */
    private fun MetadataReading.isFinal() = this == MetadataReading.NotAToken || isKept()

    /** An answer kept for the next subscriber: one that cannot change while the process runs. */
    private fun MetadataReading.isKept() =
        this is MetadataReading.Verified || this == MetadataReading.Unavailable(ReadUnavailable.REFUSED)

    private fun described(
        decimals: CallOutcome,
        symbol: CallOutcome,
        name: CallOutcome,
        block: Long?,
    ): MetadataReading {
        val places = (decimals as? CallOutcome.Returned)?.let { Abi.uint256(it.hex) }?.toIntOrNull()
            ?.takeIf { it in 0..Token.MAX_DECIMALS } ?: return MetadataReading.NotAToken
        val said = (symbol as? CallOutcome.Returned)?.let { Abi.text(it.hex) }?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return MetadataReading.NotAToken
        val called = (name as? CallOutcome.Returned)?.let { Abi.text(it.hex) }?.trim()
            ?.takeIf { it.isNotEmpty() } ?: said
        return MetadataReading.Verified(said, called, places, block)
    }

    private companion object {
        /** What [ProvenReads] is given for a call whose block the engine did not name. */
        const val UNKNOWN_BLOCK = -1L

        /** Answers kept: far more tokens than a wallet lists, and the addresses typed into Add token. */
        const val MAX_ANSWERED = 256
    }
}
