package eth.serotine.data.engine

/**
 * How a storage proof the engine verified against the beacon chain is labelled, or null when it
 * may not be used: what `MyotisHost`'s `proveSlot` reads off a `StorageProofResult`, over its plain
 * values so that the decision is tested on its own. The label has two parts, each with its own
 * job ([ProofLabel]): the block a reading is shown with, and the verified anchor the proof is of,
 * which is what tells two proofs of one state from two of different states.
 *
 * - [verifyMethod] null: nothing tied it to the beacon chain.
 * - `headerChain`: the walk from the proof's block up to an attested one proves that block,
 *   [peerBlockNumber], itself; the anchor is that block ([ProofAnchor.Walked]), not the slot it
 *   was walked to, so two walks of one block from two heads are one state.
 * - `stateRootMatch`: the anchor is [matchedBeaconSlot], the attested block's own slot whose
 *   state root the proof matched, and the block shown is [peerBlockNumber], the block of the root
 *   the account proof was read at, as balances and code are shown. The match must lie from the
 *   [finalizedSlot] up to the head the reads resolved, the later of it and the [optimisticSlot]
 *   (from Gloas on the optimistic one can trail the finalized one while a newer head waits for
 *   its execution header). The result's own block numbers are sampled at another moment than its
 *   slots, so no slot here picks one of them (`StorageProofResult`'s KDoc, Myotis #658); the
 *   [finalizedBlockNumber] only bounds the block shown from below. That number is shown, never
 *   relied on: whether two proofs are of one state is the anchor's to say. A block of 0, or one
 *   below the finalized block, is no label.
 * - Any other match (none, below the finalized slot, above the resolved head): null.
 */
internal fun storageProofLabel(
    verifyMethod: String?,
    matchedBeaconSlot: Long,
    finalizedSlot: Long,
    optimisticSlot: Long,
    peerBlockNumber: Long,
    finalizedBlockNumber: Long,
): ProofLabel? {
    val block = when {
        verifyMethod == null -> return null
        // First: a header-chain walk ends at an attested slot that is not the proof's block.
        verifyMethod == HEADER_CHAIN -> {
            if (peerBlockNumber <= 0) return null
            return ProofLabel(peerBlockNumber, ProofAnchor.Walked(peerBlockNumber))
        }
        verifyMethod != STATE_ROOT_MATCH -> return null
        matchedBeaconSlot in finalizedSlot..maxOf(finalizedSlot, optimisticSlot) ->
            peerBlockNumber.takeIf { it >= finalizedBlockNumber } ?: return null
        else -> return null
    }
    if (block <= 0 || matchedBeaconSlot < 0) return null
    return ProofLabel(block, ProofAnchor.Attested(matchedBeaconSlot))
}

/**
 * The verified anchor of an account proof (`AccountProofResult`, what code is proven with): its
 * matched attested slot for a `stateRootMatch`, the block a header-chain walk proved
 * ([blockNumber], the walk's own) for a `headerChain`, or null for no match.
 */
internal fun accountProofAnchor(verifyMethod: String?, matchedBeaconSlot: Long, blockNumber: Long): ProofAnchor? =
    when {
        verifyMethod == STATE_ROOT_MATCH && matchedBeaconSlot >= 0 -> ProofAnchor.Attested(matchedBeaconSlot)
        verifyMethod == HEADER_CHAIN && blockNumber > 0 -> ProofAnchor.Walked(blockNumber)
        else -> null
    }

/** A storage proof's label: the [blockNumber] a reading is shown with, and the verified [anchor] it is of. */
internal data class ProofLabel(val blockNumber: Long, val anchor: ProofAnchor)

private const val HEADER_CHAIN = "headerChain"
private const val STATE_ROOT_MATCH = "stateRootMatch"
