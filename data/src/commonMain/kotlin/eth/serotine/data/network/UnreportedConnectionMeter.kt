package eth.serotine.data.network

import eth.serotine.domain.repository.ConnectionCost
import eth.serotine.domain.repository.ConnectionMeter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * [ConnectionMeter] of a platform that does not report whether its connection is metered: the
 * desktop, whose JVM has no such API. It always says [ConnectionCost.NOT_REPORTED], so the heavy
 * downloads run on any connection there, as Myotis's desktop host runs them.
 */
class UnreportedConnectionMeter : ConnectionMeter {
    override fun observe(): Flow<ConnectionCost> = flowOf(ConnectionCost.NOT_REPORTED)
}
