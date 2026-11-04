package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.KindReading
import kotlinx.coroutines.flow.Flow

/** What is at an address, as the light client proves it: the Watch screen's detection card. */
class ObserveAddressKind(private val kinds: AddressKinds) {
    operator fun invoke(network: Network, address: Address): Flow<KindReading> = kinds.observeKind(network, address)
}
