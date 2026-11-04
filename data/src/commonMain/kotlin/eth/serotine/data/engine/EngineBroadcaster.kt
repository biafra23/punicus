package eth.serotine.data.engine

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [Broadcaster] over the engine ([TransactingChain.sendRaw]): the engine judges a send on
 * verified state before it gossips it, and its verdict is passed on as it is. On [dispatcher];
 * a device without an engine ([chain] null), or an engine that fails, has not sent anything.
 */
class EngineBroadcaster internal constructor(
    private val served: Network,
    private val chain: TransactingChain?,
    private val dispatcher: CoroutineDispatcher,
) : Broadcaster {

    override suspend fun send(network: Network, raw: ByteArray): Broadcast {
        if (network != served) return Broadcast.Unavailable("no light client for $network on this device")
        val chain = chain ?: return Broadcast.Unavailable("no light client on this device")
        return withContext(dispatcher) {
            try {
                when (val answer = chain.sendRaw(raw)) {
                    is SendAnswer.Sent -> Broadcast.Sent(answer.hashHex)
                    is SendAnswer.Rejected -> Broadcast.Rejected(answer.detail)
                    is SendAnswer.Refused -> Broadcast.Refused(answer.detail)
                    is SendAnswer.Unavailable -> Broadcast.Unavailable(answer.detail)
                }
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive() // as for every engine call
                Broadcast.Unavailable("the light client failed (${e::class.simpleName})")
            }
        }
    }
}
