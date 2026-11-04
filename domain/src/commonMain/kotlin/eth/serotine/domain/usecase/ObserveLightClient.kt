package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import kotlinx.coroutines.flow.Flow

/** The embedded light client's state, as a stream Settings and the screens that depend on it show. */
class ObserveLightClient(private val lightClient: LightClient) {
    operator fun invoke(network: Network): Flow<LightClientStatus> = lightClient.observeStatus(network)
}
