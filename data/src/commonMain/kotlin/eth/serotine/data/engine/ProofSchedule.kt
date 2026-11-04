package eth.serotine.data.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * When one network's facts are proven again (docs/simple-ui.md, *Freshness and pull-to-refresh*):
 * at every verified head, as [ProvenReads] does by default; or, while [onDemand] (simple mode),
 * on a network that is not [live], only when asked ([refresh]) or once a proof is [MAX_HELD_SECONDS]
 * old. A live network keeps its per-head reads in either case: its block access lists say which
 * accounts a head touched, so only those are read again. One per network, shared by the
 * network's [ProvenReads].
 *
 * A proof held this way stays a proof: its reading keeps the block it anchors to and the time of
 * its head, so a screen can say how old it is, and it never stands for longer than
 * [MAX_HELD_SECONDS], the hour the owner allowed (owner's decisions, 2026-10-26). It is held only
 * for a subscriber that has had a proof made for it ([ProvenReads]): a new one (a screen opening,
 * the one-shot read of a quote, a review or a signature) gets a fresh proof, as every quote,
 * review and signature reads fresh state (`.claude/rules/trust.md`). Unlike a proof
 * [AddressActivity.unchangedSince] vouches for, a held one is not checked against a reorganisation
 * of its block: on a network without block access lists, for up to that hour, a value shown may
 * rest on a block a reorg has since replaced, as it may have changed for any other reason.
 */
internal class ProofSchedule(
    /** Whether the network's blocks up to `head` show account activity ([AddressActivity.hasAccountCoverage]). */
    private val live: (head: Long) -> Boolean,
) {
    private val onDemand = MutableStateFlow(false)
    private val generation = MutableStateFlow(0L)

    /** The refreshes asked for so far: a proof made before the last one is made again. */
    val refreshes: StateFlow<Long> = generation.asStateFlow()

    /** Proves on demand (simple mode) or at every head (Detailed view). */
    fun setOnDemand(onDemand: Boolean) {
        this.onDemand.value = onDemand
    }

    /** Asks for every fact of the network to be proven again now. */
    fun refresh() {
        generation.update { it + 1 }
    }

    /**
     * Whether a proof made at a head produced at [provenAt] (unix seconds; null when unknown),
     * after [provenRefreshes] refreshes, still stands at verified [head], produced at
     * [headTimestamp], without being made again: on demand, on a network that is not live, with
     * no refresh asked since and under [MAX_HELD_SECONDS] old.
     */
    fun holds(provenAt: Long?, provenRefreshes: Long, head: Long, headTimestamp: Long?): Boolean {
        if (!onDemand.value || provenRefreshes != generation.value) return false
        if (provenAt == null || headTimestamp == null || headTimestamp - provenAt >= MAX_HELD_SECONDS) return false
        return !live(head)
    }

    companion object {
        /** How long a held proof stands: an hour (owner's decisions, 2026-10-26; docs/simple-ui.md). */
        const val MAX_HELD_SECONDS = 3_600L
    }
}
