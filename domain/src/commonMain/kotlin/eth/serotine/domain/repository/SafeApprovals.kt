package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network

/**
 * The port for the approvals a Safe's owners made on chain (`approveHash`), as the embedded
 * light client proves them: the Safe's own `approvedHashes(owner, hash)`, run as a verified call
 * at the head. What counts an owner's approval toward a transaction's threshold without a
 * signature passing between devices (docs/wallet-ux.md, *Co-signing a Safe transaction*). :data
 * implements it over the Myotis engine; nothing else may answer it (CLAUDE.md, *Trust*). Blocks
 * on the engine, bounded by it; never throws.
 */
interface SafeApprovals {
    /**
     * Whether [owner] approved the transaction hash [hash] on the Safe at [safe] on [network].
     * A [network] no adapter serves here is refused, never answered from another network ([ReadUnavailable.NO_ENGINE]).
     */
    suspend fun approval(network: Network, safe: Address, owner: Address, hash: Digest): ApprovalReading
}

/** The outcome of [SafeApprovals.approval]. */
sealed interface ApprovalReading {

    /**
     * [approved] or not, from a verified call against the state of block [blockNumber] (null when
     * the engine did not say which block it ran against).
     */
    data class Read(val approved: Boolean, val blockNumber: Long?) : ApprovalReading

    /** No verified answer right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : ApprovalReading
}
