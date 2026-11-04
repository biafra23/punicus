package eth.serotine.data.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [storageProofLabel]: the block a beacon-verified storage proof is shown with, and the verified
 * anchor it is of; [accountProofAnchor], the anchor of the account proof code comes with.
 */
class StorageProofsTest {

    /**
     * A storage proof's slots and blocks as a real Sepolia engine answered them, on the owner's
     * phone, before Myotis #658 made the optimistic slot the head block's own (it was then the
     * signature slot, 11_378_991).
     */
    private fun label(
        method: String? = "stateRootMatch",
        matched: Long = 11_378_990,
        finalizedSlot: Long = 11_378_912,
        optimisticSlot: Long = 11_378_990,
        peerBlock: Long = 11_937_375,
        finalizedBlock: Long = 11_937_299,
    ) = storageProofLabel(method, matched, finalizedSlot, optimisticSlot, peerBlock, finalizedBlock)

    @Test
    fun aMatchAtOrBehindTheOptimisticSlotIsShownWithItsBlockAndAnchoredToTheSlotItMatched() {
        // A read proven at the head reports the head's own slot.
        assertEquals(ProofLabel(11_937_375, ProofAnchor.Attested(11_378_990)), label())
        // Three slots behind (missed slots between): the same.
        assertEquals(
            ProofLabel(11_937_352, ProofAnchor.Attested(11_378_965)),
            storageProofLabel(
                "stateRootMatch",
                matchedBeaconSlot = 11_378_965,
                finalizedSlot = 11_378_880,
                optimisticSlot = 11_378_968,
                peerBlockNumber = 11_937_352,
                finalizedBlockNumber = 11_937_268,
            ),
        )
    }

    @Test
    fun twoProofsWithOneBlockLabelButTwoMatchedSlotsAreTwoAnchors() {
        // The label may be the serving peer's report: equal labels prove nothing, the anchors tell.
        val owners = label(matched = 11_378_989)
        val threshold = label(matched = 11_378_990)
        assertEquals(owners?.blockNumber, threshold?.blockNumber)
        assertEquals(false, owners?.anchor == threshold?.anchor)
    }

    @Test
    fun aProofOfAHeadTheOptimisticOneMovedPastIsStillItsOwnBlock() {
        // Not tied to the optimistic block: the reading's one-anchor check and its retry handle that.
        assertEquals(11_937_372, label(matched = 11_378_988, peerBlock = 11_937_372)?.blockNumber)
    }

    @Test
    fun aMatchAtTheFinalizedSlotIsShownWithTheBlockItWasReadAt() {
        assertEquals(
            ProofLabel(11_937_299, ProofAnchor.Attested(11_378_912)),
            label(matched = 11_378_912, peerBlock = 11_937_299),
        )
    }

    @Test
    fun noSlotPicksOneOfTheResultsBlockNumbers() {
        // Sampled at other moments than the slots: an exact match is still the proof's own block,
        // and below the finalized one it is no label.
        assertNull(label(matched = 11_378_990, peerBlock = 1))
        assertNull(label(matched = 11_378_912, peerBlock = 11_937_298))
    }

    @Test
    fun aHeadTrailingTheFinalityStillLabelsAProofAtTheFinalizedSlot() {
        // From Gloas on the resolved optimistic head can trail the finalized one.
        assertEquals(
            ProofLabel(11_937_299, ProofAnchor.Attested(11_378_912)),
            label(matched = 11_378_912, optimisticSlot = 11_378_900, peerBlock = 11_937_299),
        )
        assertNull(label(matched = 11_378_913, optimisticSlot = 11_378_900), "above the resolved head")
    }

    @Test
    fun aHeaderChainWalkProvesThePeersBlock() {
        assertEquals(
            ProofLabel(11_937_300, ProofAnchor.Walked(11_937_300)),
            label(method = "headerChain", matched = 11_000_000, peerBlock = 11_937_300),
        )
        // The same block walked to a later head: one anchor, one state.
        assertEquals(
            label(method = "headerChain", matched = 11_000_000, peerBlock = 11_937_300)?.anchor,
            label(method = "headerChain", matched = 11_000_005, peerBlock = 11_937_300)?.anchor,
        )
    }

    @Test
    fun aMatchOutsideTheVerifiedRangeOrNoMatchIsNoLabel() {
        assertNull(label(matched = 11_378_911), "below the finalized slot")
        assertNull(label(matched = 11_378_991), "beyond the optimistic slot")
        assertNull(label(matched = -1), "no attestation matched")
    }

    @Test
    fun aFailedOrUnknownMatchOrAnImplausibleBlockIsNoLabel() {
        assertNull(label(method = null), "not tied to the beacon chain")
        assertNull(label(method = "somethingElse"))
        assertNull(label(peerBlock = 0))
        assertNull(label(peerBlock = 11_937_298), "below the finalized block")
        assertNull(label(method = "headerChain", peerBlock = 0))
    }

    @Test
    fun anAccountProofIsAnchoredOnlyWhenItMatchedAnAttestedSlot() {
        assertEquals(ProofAnchor.Attested(11_378_990), accountProofAnchor("stateRootMatch", 11_378_990, 11_937_375))
        assertEquals(ProofAnchor.Walked(11_937_300), accountProofAnchor("headerChain", 11_378_990, 11_937_300))
        assertNull(accountProofAnchor("stateRootMatch", -1, 11_937_375))
        assertNull(accountProofAnchor(null, 11_378_990, 11_937_375))
    }

    @Test
    fun anAccountCarriedByBlockAccessListsIsAnchoredAtItsAttestedSlot() {
        // Myotis 0.1.20: a watched account answered from verified BALs, its block's root attested.
        assertEquals(ProofAnchor.Attested(11_378_990), accountProofAnchor("blockAccessList", 11_378_990, 11_937_375))
        assertNull(accountProofAnchor("blockAccessList", -1, 11_937_375), "no attestation, no anchor")
        assertNull(label(method = "blockAccessList"), "a storage proof is never answered from BALs")
    }

    @Test
    fun anAnchorIsNotBeforeAnotherAsTheirMatchedSlotsSay() {
        assertEquals(true, ProofAnchor.Attested(20).notBefore(ProofAnchor.Attested(20)))
        assertEquals(true, ProofAnchor.Attested(21).notBefore(ProofAnchor.Attested(20)))
        assertEquals(false, ProofAnchor.Attested(19).notBefore(ProofAnchor.Attested(20)))
        assertEquals(true, ProofAnchor.Walked(8).notBefore(ProofAnchor.Walked(7)))
        assertEquals(false, ProofAnchor.Walked(6).notBefore(ProofAnchor.Walked(7)))
        assertNull(ProofAnchor.Walked(9).notBefore(ProofAnchor.Attested(20)), "nothing verified orders the two")
    }
}
