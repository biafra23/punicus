package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network

/**
 * The port for the transaction inputs the light client verified last, kept across restarts: what
 * a lenient draft is built on while no head can be read (docs/lenient-mode.md, *What a draft is
 * built on*; owner rulings, 2026-11-06). Each value is public chain data, labelled with the block
 * and the time it was verified at; none is a reading of now, and nothing is sent on one: the
 * check before sending reads [TransactionInputs] fresh.
 *
 * :data keeps a value each time [TransactionInputs] answers one verified, on the same network, so
 * this port only reads. Every call names its network, and a value of one network never answers
 * another. Never throws.
 */
interface KeptTransactionInputs {

    /**
     * [account]'s count of mined transactions on [network] as last verified on this device
     * ([TransactionInputs.minedNonce]); null when none is kept, or it cannot be read.
     */
    suspend fun minedNonce(network: Network, account: Address): KeptNonce?

    /** [network]'s fee quote as last verified on this device ([TransactionInputs.fees]); null when none is kept. */
    suspend fun fees(network: Network): KeptFees?
}

/**
 * A count of mined transactions, [count], verified at the head [blockNumber], which was produced at
 * [asOf] (unix seconds; null when unknown). Every nonce below [count] was used on chain then; one
 * may have been used since.
 */
data class KeptNonce(val count: Long, val blockNumber: Long, val asOf: Long?) {
    init {
        require(count >= 0) { "a count of transactions is never negative" }
    }
}

/**
 * A fee quote, [quote], from the verified fee history up to [FeeQuote.blockNumber], read at a head
 * produced at [asOf] (unix seconds; null when unknown). The fees may have risen since.
 */
data class KeptFees(val quote: FeeQuote, val asOf: Long?)
