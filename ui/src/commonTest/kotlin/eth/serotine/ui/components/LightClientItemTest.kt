package eth.serotine.ui.components

import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.ui.components.LightClientHealth.NOT_VERIFIED
import eth.serotine.ui.components.LightClientHealth.STALE
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import kotlin.test.Test
import kotlin.test.assertEquals

class LightClientItemTest {

    @Test
    fun eachPhaseOfAColdStartHasATextAndAProgress() {
        assertEquals(
            LightClientItem("Starting the light client…", null, true, NOT_VERIFIED),
            item(LightClientStatus.Starting),
        )
        assertEquals(
            LightClientItem(
                "Bootstrapping from the trusted checkpoint · looking for light-client servers",
                null,
                true,
                NOT_VERIFIED,
            ),
            item(LightClientStatus.Bootstrapping(lookingForServers = true)),
        )
        assertEquals(
            LightClientItem("Verifying sync committees · 3 of 4 periods", 0.75f, true, NOT_VERIFIED),
            item(LightClientStatus.CatchingUp(done = 3, total = 4)),
        )
        assertEquals(
            LightClientItem("Synced · waiting for the first verified head", null, true, NOT_VERIFIED),
            item(LightClientStatus.WaitingForHead(headAgeSeconds = null)),
        )
        assertEquals(
            LightClientItem(
                "Synced to block 11,922,744 · looking for peers that serve state proofs",
                null,
                true,
                STALE,
            ),
            item(LightClientStatus.WaitingForProofPeers(headBlock = 11_922_744, headTimestamp = null)),
        )
        assertEquals(
            LightClientItem("Verified at block 11,922,744 · 8 s ago", null, false, VERIFIED),
            item(LightClientStatus.Synced(headBlock = 11_922_744, headTimestamp = NOW - 8, finalized = null)),
        )
    }

    @Test
    fun theHeadIsDatedRelativeToNowWhenItsTimeIsKnown() {
        fun synced(timestamp: Long?) =
            LightClientItem.from(LightClientStatus.Synced(42, timestamp, finalized = null), NOW).text
        assertEquals("Verified at block 42", synced(null))
        assertEquals("Verified at block 42 · just now", synced(NOW))
        assertEquals("Verified at block 42 · 59 s ago", synced(NOW - 59))
        assertEquals("Verified at block 42 · 5 min ago", synced(NOW - 300))
        assertEquals("Verified at block 42 · 3 h ago", synced(NOW - 3 * 3_600))
        assertEquals(
            "Synced to block 42 · 12 s ago · looking for peers that serve state proofs",
            LightClientItem.from(LightClientStatus.WaitingForProofPeers(42, NOW - 12), NOW).text,
        )
    }

    /** [status] as Detailed view's strip draws it: simple mode's line is tested on its own, below. */
    private fun item(status: LightClientStatus) = LightClientItem.from(status, NOW).copy(simpleText = null)

    /** Simple mode's sync line for [status] on Ethereum. */
    private fun simple(status: LightClientStatus, decidedHere: Boolean = false) =
        LightClientItem.from(status, NOW, decidedHere, network = "Ethereum").simpleText

    private companion object {
        const val NOW = 1_792_100_000L
    }

    @Test
    fun openEndedWaitsHaveNoFakeProgress() {
        assertEquals(
            LightClientItem("Verifying sync committees", null, true, NOT_VERIFIED),
            item(LightClientStatus.CatchingUp(done = 0, total = 0)),
        )
        assertEquals(
            LightClientItem("Finishing sync", null, true, NOT_VERIFIED),
            item(LightClientStatus.CatchingUp(done = 4, total = 4)),
        )
        assertEquals(
            LightClientItem("Synced · the verified head is 3 min old, waiting for a fresh one", null, true, STALE),
            item(LightClientStatus.WaitingForHead(headAgeSeconds = 185)),
        )
    }

    @Test
    fun sleepRefusalsAndAbsenceAreSaidPlainly() {
        assertEquals(
            LightClientItem("Light client asleep while the app is in the background", null, false, STALE),
            item(LightClientStatus.Sleeping),
        )
        assertEquals(
            LightClientItem(
                "Not syncing: the last trusted checkpoint is 20 periods old, past the safe limit of 13 · " +
                    "decide in Settings → Networks",
                null,
                false,
                NOT_VERIFIED,
            ),
            item(LightClientStatus.StaleAnchor(anchorAgePeriods = 20, boundPeriods = 13)),
        )
        assertEquals(
            LightClientItem(
                "Not syncing: the last trusted checkpoint is 20 periods old · decide in Settings → Networks",
                null,
                false,
                NOT_VERIFIED,
            ),
            item(LightClientStatus.StaleAnchor(anchorAgePeriods = 20, boundPeriods = 0)),
        )
        assertEquals(
            LightClientItem("No light client on this device", null, false, NOT_VERIFIED),
            item(LightClientStatus.Unavailable),
        )
    }

    @Test
    fun simpleModesSyncLineSaysWhatKeepsTheNetworkFromVerifiedReads() {
        assertEquals("Syncing Ethereum · starting", simple(LightClientStatus.Starting))
        assertEquals("Syncing Ethereum · starting", simple(LightClientStatus.Bootstrapping(lookingForServers = true)))
        assertEquals("Syncing Ethereum · 2 of 6 periods", simple(LightClientStatus.CatchingUp(done = 2, total = 6)))
        assertEquals("Syncing Ethereum", simple(LightClientStatus.CatchingUp(done = 0, total = 0)))
        assertEquals("Syncing Ethereum", simple(LightClientStatus.CatchingUp(done = 6, total = 6)))
        assertEquals(
            "Syncing Ethereum · waiting for its first block",
            simple(LightClientStatus.WaitingForHead(headAgeSeconds = null)),
        )
        assertEquals(
            "Waiting for a new Ethereum block · the last is 3 min old",
            simple(LightClientStatus.WaitingForHead(headAgeSeconds = 185)),
        )
        assertEquals(
            "Looking for Ethereum peers that can prove balances",
            simple(LightClientStatus.WaitingForProofPeers(headBlock = 11_922_744, headTimestamp = null)),
        )
        assertEquals(
            "Ethereum isn't syncing: its checkpoint is too old · decide in Settings → Networks",
            simple(LightClientStatus.StaleAnchor(anchorAgePeriods = 20, boundPeriods = 13)),
        )
        assertEquals(
            "Ethereum isn't syncing: its checkpoint is too old",
            simple(LightClientStatus.StaleAnchor(anchorAgePeriods = 20, boundPeriods = 13), decidedHere = true),
        )
        assertEquals("No light client for Ethereum on this device", simple(LightClientStatus.Unavailable))
    }

    @Test
    fun simpleModeDrawsNoSyncLineWhileNothingNeedsSaying() {
        listOf(
            LightClientStatus.Synced(headBlock = 11_922_744, headTimestamp = NOW - 8, finalized = null),
            LightClientStatus.Sleeping,
            LightClientStatus.Off,
            LightClientStatus.Idle,
        ).forEach { status -> assertEquals(null, simple(status), "$status") }
    }
}
