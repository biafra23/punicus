package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.TransactionInputs

/**
 * Asks for the fee history once and lets its answer go: what a screen that will quote a
 * transaction soon does when it opens. The light client builds that history on a first request
 * after a while, which takes it seconds; asked while the user composes, it is ready when the
 * review quotes. Nothing is quoted, kept or shown from it, and it never throws.
 */
class WarmFeeHistory(private val inputs: TransactionInputs) {
    suspend operator fun invoke(network: Network) {
        inputs.fees(network)
    }
}
