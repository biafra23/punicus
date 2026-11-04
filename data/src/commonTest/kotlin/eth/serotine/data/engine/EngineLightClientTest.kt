package eth.serotine.data.engine

import eth.serotine.data.engine.EngineSnapshot.Phase
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.FinalizedBlock
import eth.serotine.domain.repository.LightClientStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class EngineLightClientTest {

    private val chain = FakeChain()

    private fun TestScope.client() = EngineLightClient(
        Network.SEPOLIA,
        chain,
        StandardTestDispatcher(testScheduler),
        POLL,
    )

    @Test
    fun followsAColdStartTheWayMyotisReadsIt() = runTest {
        val seen = mutableListOf<LightClientStatus>()
        val collecting = launch { client().observeStatus(Network.SEPOLIA).collect { seen += it } }
        fun step(snapshot: EngineSnapshot) {
            chain.snapshot = snapshot
            advanceTimeBy(POLL)
            runCurrent()
        }
        runCurrent()
        step(running(phase = Phase.STARTING))
        step(running(phase = Phase.SYNCING, hunting = true))
        step(running(phase = Phase.CATCHING_UP, start = 1450, current = 1451, target = 1454))
        step(running(phase = Phase.SYNCED, headAgeMs = Long.MAX_VALUE))
        step(running(phase = Phase.SYNCED, proofPeers = 0, head = 11_922_744))
        step(running(phase = Phase.SYNCED, head = 11_922_744, headTimestamp = 1_792_100_000))

        assertEquals(
            listOf(
                LightClientStatus.Starting,
                LightClientStatus.Bootstrapping(lookingForServers = true),
                // From the engine's own start period, not from whenever this subscriber came in.
                LightClientStatus.CatchingUp(done = 1, total = 4),
                LightClientStatus.WaitingForHead(headAgeSeconds = null),
                LightClientStatus.WaitingForProofPeers(headBlock = 11_922_744, headTimestamp = null),
                LightClientStatus.Synced(headBlock = 11_922_744, headTimestamp = 1_792_100_000, finalized = null),
            ),
            seen,
        )
        collecting.cancel()
    }

    @Test
    fun aSyncedStatusCarriesTheFinalizedBlockWithItsOwnTimestamp() = runTest {
        val seen = mutableListOf<LightClientStatus>()
        val header = FinalizedBlock(40, 1_792_099_032)
        chain.snapshot = running(head = 120, headTimestamp = 1_792_100_000, finalized = 40, finalizedHeader = header)
        val collecting = launch { client().observeStatus(Network.SEPOLIA).collect { seen += it } }
        runCurrent()
        // A newer finalized block whose header is not had yet: the one had stands.
        chain.snapshot = running(head = 121, headTimestamp = 1_792_100_012, finalized = 41, finalizedHeader = header)
        advanceTimeBy(POLL)
        runCurrent()
        // None had at all: none.
        chain.snapshot = running(head = 122, headTimestamp = 1_792_100_024, finalized = 41)
        advanceTimeBy(POLL)
        runCurrent()
        assertEquals(
            listOf<LightClientStatus>(
                LightClientStatus.Synced(120, 1_792_100_000, header),
                LightClientStatus.Synced(121, 1_792_100_012, header),
                LightClientStatus.Synced(122, 1_792_100_024, finalized = null),
            ),
            seen,
        )
        collecting.cancel()
    }

    @Test
    fun aStalledHeadSleepAndAStaleAnchorAreStatesToo() = runTest {
        chain.snapshot = running(headAgeMs = 150_000)
        assertEquals(
            LightClientStatus.WaitingForHead(headAgeSeconds = 150),
            client().observeStatus(Network.SEPOLIA).first(),
        )

        chain.snapshot = running(paused = true)
        assertEquals(LightClientStatus.Sleeping, client().observeStatus(Network.SEPOLIA).first())

        chain.snapshot = running(phase = Phase.STALE_ANCHOR, current = 1400, target = 1420)
        assertEquals(
            LightClientStatus.StaleAnchor(anchorAgePeriods = 20, boundPeriods = 13),
            client().observeStatus(Network.SEPOLIA).first(),
        )

        chain.snapshot = running(phase = Phase.CATCHING_UP, start = -1, current = 5, target = 9)
        assertEquals(LightClientStatus.CatchingUp(done = 0, total = 0), client().observeStatus(Network.SEPOLIA).first())
    }

    @Test
    fun aDownEngineOrNoneIsSaidOnceAndNotPolled() = runTest {
        chain.snapshot = EngineSnapshot.Down
        assertEquals(
            listOf<LightClientStatus>(LightClientStatus.Unavailable),
            client().observeStatus(Network.SEPOLIA).toList(),
        )
        assertEquals(
            listOf<LightClientStatus>(LightClientStatus.Unavailable),
            EngineLightClient(
                Network.SEPOLIA,
                null,
                StandardTestDispatcher(testScheduler),
                POLL,
            ).observeStatus(Network.SEPOLIA).toList(),
        )
    }

    @Test
    fun aFailedStartIsUnavailableButPolledOnForTheNextStart() = runTest {
        val seen = mutableListOf<LightClientStatus>()
        chain.snapshot = EngineSnapshot.Failed
        val collecting = launch { client().observeStatus(Network.SEPOLIA).collect { seen += it } }
        runCurrent()

        // Turned off and on again, the network starts this time.
        chain.snapshot = running(head = 5)
        advanceTimeBy(POLL)
        runCurrent()

        assertEquals(listOf(LightClientStatus.Unavailable, LightClientStatus.Synced(5, null, finalized = null)), seen)
        collecting.cancel()
    }

    @Test
    fun aFailedPollIsSkippedNotReported() = runTest {
        val seen = mutableListOf<LightClientStatus>()
        chain.failure = IllegalStateException("engine busy")
        val collecting = launch { client().observeStatus(Network.SEPOLIA).collect { seen += it } }
        runCurrent()

        chain.failure = null
        chain.snapshot = running(head = 5)
        advanceTimeBy(POLL)
        runCurrent()

        assertEquals(listOf<LightClientStatus>(LightClientStatus.Synced(5, null, finalized = null)), seen)
        collecting.cancel()
    }

    @Test
    fun aCancellationTheEngineThrowsSkipsThePollAndPollingGoesOn() = runTest {
        val seen = mutableListOf<LightClientStatus>()
        chain.failure = CancellationException("thrown by the engine's own code")
        val collecting = launch { client().observeStatus(Network.SEPOLIA).collect { seen += it } }
        runCurrent()

        chain.failure = null
        chain.snapshot = running(head = 5)
        advanceTimeBy(POLL)
        runCurrent()

        assertEquals(
            listOf<LightClientStatus>(LightClientStatus.Synced(5, null, finalized = null)),
            seen,
            "the strip did not freeze",
        )
        collecting.cancel()
    }

    private companion object {
        val POLL = 1.seconds
    }

    @Test
    fun aStoppedLightClientIsOffOrIdleAndStartsAgain() = runTest {
        chain.snapshot = EngineSnapshot.Stopped(off = true)
        assertEquals(LightClientStatus.Off, client().observeStatus(Network.SEPOLIA).first())
        chain.snapshot = EngineSnapshot.Stopped(off = false)
        assertEquals(LightClientStatus.Idle, client().observeStatus(Network.SEPOLIA).first())

        val seen = mutableListOf<LightClientStatus>()
        val collecting = launch { client().observeStatus(Network.SEPOLIA).collect { seen += it } }
        runCurrent()
        chain.snapshot = running(phase = Phase.STARTING)
        advanceTimeBy(POLL)
        runCurrent()
        collecting.cancel()
        assertEquals(listOf(LightClientStatus.Idle, LightClientStatus.Starting), seen, "a stop is not final")
    }
}
