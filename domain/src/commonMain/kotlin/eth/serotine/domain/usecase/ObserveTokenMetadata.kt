package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.TokenMetadata
import kotlinx.coroutines.flow.Flow

/** What the contract at an address says about itself, verified: the Add token screen's card. */
class ObserveTokenMetadata(private val metadata: TokenMetadata) {
    operator fun invoke(network: Network, token: Address): Flow<MetadataReading> = metadata.observe(network, token)
}
