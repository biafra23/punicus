package eth.serotine.data.engine

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.BlockReading
import eth.serotine.domain.repository.BlockTransactions
import eth.serotine.domain.repository.HeadReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.CoroutineDispatcher

/**
 * [BlockTransactions] over the engine, for [served]: the head is the one reads can be verified at
 * ([EngineSnapshot.Running.readableHead]), and a block's transactions are the engine's verified
 * `getBlockByNumber` with full transactions ([TransactingChain.blockWithTransactions]), its body
 * checked by the engine against the header's transactions root. A block above that head is
 * [BlockReading.NotYet] without asking; an answer that cannot be read, or none, is
 * [ReadUnavailable.UNPROVEN] for that call, never an answer kept from before. Every call blocks on
 * [dispatcher], bounded by the engine.
 */
class EngineBlockTransactions internal constructor(
    private val served: Network,
    private val chain: TransactingChain?,
    private val dispatcher: CoroutineDispatcher,
) : BlockTransactions {

    override suspend fun head(network: Network): HeadReading {
        if (network != served) return HeadReading.Unavailable(ReadUnavailable.NO_ENGINE)
        return chain.askVerified(dispatcher, { HeadReading.Unavailable(it) }) { chain ->
            readableHead(chain)?.let { HeadReading.At(it) } ?: HeadReading.Unavailable(ReadUnavailable.UNPROVEN)
        }
    }

    override suspend fun transactions(network: Network, number: Long): BlockReading {
        if (network != served) return BlockReading.Unavailable(ReadUnavailable.NO_ENGINE)
        return chain.askVerified(dispatcher, { BlockReading.Unavailable(it) }) { chain ->
            val head = readableHead(chain) ?: return@askVerified BlockReading.Unavailable(ReadUnavailable.UNPROVEN)
            if (number > head) return@askVerified BlockReading.NotYet
            val json = chain.blockWithTransactions(number)
                ?: return@askVerified BlockReading.Unavailable(ReadUnavailable.UNPROVEN)
            // A malformed answer throws, which askVerified reads as unproven.
            EngineJson.blockTransactions(json, number)?.let { BlockReading.Read(it) } ?: BlockReading.NotYet
        }
    }

    private fun readableHead(chain: TransactingChain): Long? =
        (chain.snapshot() as? EngineSnapshot.Running)?.readableHead
}
