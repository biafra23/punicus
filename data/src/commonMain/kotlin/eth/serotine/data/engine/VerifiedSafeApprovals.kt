package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.ApprovalReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeApprovals
import kotlinx.coroutines.CoroutineDispatcher

/**
 * [SafeApprovals] over the engine's verified reads: the Safe's own getter
 * `approvedHashes(owner, hash)`, run as a verified call ([VerifiedChain.call]) at the head and
 * labelled with the block the engine names. A word other than zero is an approval; an answer that
 * is no one word, or a revert, is no answer from a Safe ([ReadUnavailable.UNPROVEN]). Asked only
 * while the light client can verify reads at its head ([whyUnreadable]), on [dispatcher]; an
 * engine that fails, or a CancellationException its own code throws, is no answer, as for every
 * verified read. A device without an engine ([chain] null) answers [ReadUnavailable.NO_ENGINE].
 */
class VerifiedSafeApprovals internal constructor(
    private val served: Network,
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
) : SafeApprovals {

    override suspend fun approval(network: Network, safe: Address, owner: Address, hash: Digest): ApprovalReading {
        if (network != served) return ApprovalReading.Unavailable(ReadUnavailable.NO_ENGINE)
        return chain.askVerified(dispatcher, ApprovalReading::Unavailable) { verified ->
            when (val outcome = verified.call(safe, Abi.approvedHashes(owner, hash))) {
                is CallOutcome.Returned -> Abi.uint256(outcome.hex)
                    ?.let { ApprovalReading.Read(approved = it != "0", outcome.blockNumber) }
                    ?: ApprovalReading.Unavailable(ReadUnavailable.UNPROVEN)
                is CallOutcome.Reverted -> ApprovalReading.Unavailable(ReadUnavailable.UNPROVEN)
                CallOutcome.Refused -> ApprovalReading.Unavailable(ReadUnavailable.REFUSED)
                null -> ApprovalReading.Unavailable(ReadUnavailable.UNPROVEN)
            }
        }
    }
}
