package eth.serotine.domain.repository

import eth.serotine.domain.model.AccountId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The port for each Safe's state as the light client verified it last, kept across restarts: what a
 * lenient Safe draft is built on while no head can be read (docs/lenient-mode.md, *Safe transactions
 * in lenient mode*; owner rulings, 2026-11-06, rule change 4). Each value is public chain data, its
 * nonce, owners and threshold labelled with the block and the time they were verified at; none is a
 * reading of now, and nothing is shown to another owner or sent on one: the check at a fresh head
 * reads the Safe again ([AddressKinds]).
 *
 * :data keeps a Safe each time [AddressKinds] proves one, on the same network, so this port only
 * reads. Every call names the Safe's network, and a Safe of one network never answers another.
 * Never throws.
 */
interface KeptSafes {

    /**
     * The Safe [safe] (its network and address) as last verified on this device, re-emitted when
     * that changes; null when none is kept (never verified here, no longer a Safe when last read,
     * or the store cannot be read).
     */
    fun observe(safe: AccountId): Flow<KeptSafe?>

    companion object {
        /** Keeps nothing: every lenient Safe draft waits for the light client. */
        val None: KeptSafes = object : KeptSafes {
            override fun observe(safe: AccountId): Flow<KeptSafe?> = flowOf(null)
        }
    }
}

/**
 * A Safe's [state] (its owners, threshold and nonce among it), verified at the block [blockNumber],
 * which a verified head produced at [asOf] (unix seconds; null when unknown) proved. Its nonce may
 * have been used since, and its owners changed.
 */
data class KeptSafe(val state: SafeState, val blockNumber: Long, val asOf: Long?)
