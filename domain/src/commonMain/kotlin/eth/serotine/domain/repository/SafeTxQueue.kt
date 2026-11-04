package eth.serotine.domain.repository

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.SigningRequest
import kotlinx.coroutines.flow.Flow

/**
 * The port for the Safe transactions waiting for their owners (docs/wallet-ux.md, screens 16 and
 * 17): each as a [SigningRequest], the raw transaction and the signatures collected so far, kept
 * across restarts on this device and nowhere else. Public data only: a Safe transaction and its
 * owners' signatures of it are not secret, and no key is ever in them. Who signed what is checked
 * against the chain before anything is saved here (the use cases that accept a signature); the
 * queue keeps what it is given. :data keeps it in a file. Never throws.
 */
interface SafeTxQueue {

    /**
     * The requests queued for the Safe [safe] (its network and address), in the order they were first saved, re-emitted
     * on each change; or that the queue cannot be read.
     */
    fun observe(safe: AccountId): Flow<SafeTxQueueList>

    /**
     * Saves [request]: a request with the same [SigningRequest.hash] already queued gets the
     * signatures of [request] it does not carry yet (one per owner, the earlier kept), any other
     * is added. Returns the request as it is queued now, or null when it could not be saved.
     */
    suspend fun save(request: SigningRequest): SigningRequest?

    /** Takes the request whose hash is [hash] out of the queue; false when the queue could not be saved. */
    suspend fun remove(hash: Digest): Boolean

    /**
     * Forgets the requests for the Safe [safe] whose nonce is below [nonce], its verified
     * nonce: each of those nonces is used on chain, by that transaction or another, so none of
     * them can ever run. False when the queue could not be saved.
     */
    suspend fun prune(safe: AccountId, nonce: Long): Boolean
}

/** What [SafeTxQueue.observe] holds. */
sealed interface SafeTxQueueList {

    /** The [requests] queued. */
    data class Loaded(val requests: List<SigningRequest>) : SafeTxQueueList

    /** The queue cannot be read right now (a newer format, a file that cannot be read). */
    data object Unreadable : SafeTxQueueList
}
