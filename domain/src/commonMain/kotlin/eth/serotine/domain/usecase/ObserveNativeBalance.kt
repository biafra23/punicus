package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.NativeBalances
import kotlinx.coroutines.flow.Flow

/** The verified native-currency balance of one account, as a stream the account list renders. */
class ObserveNativeBalance(private val balances: NativeBalances) {
    operator fun invoke(network: Network, address: Address): Flow<BalanceReading> =
        balances.observeBalance(network, address)
}
