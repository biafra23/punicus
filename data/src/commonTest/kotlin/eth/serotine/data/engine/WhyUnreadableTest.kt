package eth.serotine.data.engine

import eth.serotine.data.engine.EngineSnapshot.Phase
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Why reads cannot be verified, from the engine's state: a light client on its way to a verified head
 * is still syncing, said as such (docs/simple-ui.md, *A read refused while syncing says so*); one synced
 * but without a fresh head or a proof peer could not be verified this time.
 */
class WhyUnreadableTest {

    @Test
    fun aLightClientOnItsWayToAVerifiedHeadIsStillSyncing() {
        assertEquals(ReadUnavailable.SYNCING, whyUnreadable(EngineSnapshot.Booting))
        listOf(Phase.STARTING, Phase.SYNCING, Phase.CATCHING_UP).forEach { phase ->
            assertEquals(ReadUnavailable.SYNCING, whyUnreadable(running(phase = phase)), "$phase")
        }
        assertEquals(
            ReadUnavailable.SYNCING,
            whyUnreadable(running(head = 0, headAgeMs = Long.MAX_VALUE)),
            "synced, waiting for its first verified head",
        )
    }

    @Test
    fun oneSyncedButWithoutAFreshHeadOrAProofPeerIsNotSyncing() {
        assertEquals(ReadUnavailable.UNPROVEN, whyUnreadable(running(headAgeMs = 60_000)))
        assertEquals(ReadUnavailable.UNPROVEN, whyUnreadable(running(proofPeers = 0)))
        assertEquals(ReadUnavailable.UNPROVEN, whyUnreadable(running(paused = true)))
    }

    @Test
    fun theOtherReasonsAreAsBefore() {
        assertNull(whyUnreadable(running()), "synced, awake, a fresh head and a proof peer: readable")
        assertEquals(ReadUnavailable.STALE_ANCHOR, whyUnreadable(running(phase = Phase.STALE_ANCHOR)))
        assertEquals(ReadUnavailable.NO_ENGINE, whyUnreadable(EngineSnapshot.Down))
        assertEquals(ReadUnavailable.NO_ENGINE, whyUnreadable(EngineSnapshot.Failed))
        assertEquals(ReadUnavailable.NETWORK_OFF, whyUnreadable(EngineSnapshot.Stopped(off = true)))
    }
}
