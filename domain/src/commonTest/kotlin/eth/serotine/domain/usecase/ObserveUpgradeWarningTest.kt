package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade.Phase
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.LightClientStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveUpgradeWarningTest {

    private val upgrades = FakeNetworkUpgrades()
    private val known = FakeKnownUpgradeStore()
    private val lightClient = FakeLightClient()
    private val activated = glamsterdam.copy(phase = Phase.ACTIVE)
    private val beforeIt = glamsterdam.activationTime!! - DAY
    private val afterIt = glamsterdam.activationTime!! + DAY

    private fun observe(now: Long) = ObserveUpgradeWarning(Network.SEPOLIA, upgrades, known, lightClient) { now }()

    @Test
    fun nothingReportedOrRecordedIsNoWarning() = runTest {
        lightClient.status.value = LightClientStatus.Synced(100, null, finalized = null)
        assertEquals(UpgradeWarning.None, observe(beforeIt).first())
    }

    @Test
    fun anUpgradeAheadIsAheadWhateverTheLightClientDoes() = runTest {
        upgrades.report.value = glamsterdam
        val statuses = listOf(LightClientStatus.Synced(100, null, finalized = null), LightClientStatus.CatchingUp(1, 4))
        for (status in statuses) {
            lightClient.status.value = status
            assertEquals(UpgradeWarning.Ahead(glamsterdam), observe(beforeIt).first())
        }
    }

    @Test
    fun peersAloneNeverMakeTheAppClaimItStoppedVerifying() = runTest {
        upgrades.report.value = activated
        val verifying = listOf(
            LightClientStatus.Synced(100, null, finalized = null),
            LightClientStatus.WaitingForProofPeers(100, null),
            // Networking off on purpose, and no light client at all, say nothing either way.
            LightClientStatus.Sleeping,
            LightClientStatus.Unavailable,
        )
        for (status in verifying) {
            lightClient.status.value = status
            assertEquals(UpgradeWarning.Activated(activated), observe(afterIt).first(), "$status")
        }
    }

    @Test
    fun anActivatedUpgradeTheLightClientAgreesWithRequiresTheUpdate() = runTest {
        upgrades.report.value = activated
        val notVerifying = listOf(
            LightClientStatus.Starting,
            LightClientStatus.Bootstrapping(lookingForServers = true),
            LightClientStatus.CatchingUp(1, 4),
            LightClientStatus.WaitingForHead(headAgeSeconds = 90),
            LightClientStatus.StaleAnchor(anchorAgePeriods = 20, boundPeriods = 13),
        )
        for (status in notVerifying) {
            lightClient.status.value = status
            assertEquals(UpgradeWarning.UpdateRequired(activated), observe(afterIt).first(), "$status")
        }
    }

    @Test
    fun anActivationPeersConfirmedIsKnownBeforePeersReportItAgain() = runTest {
        known.record.value = KnownUpgrade(activated, emptySet())
        lightClient.status.value = LightClientStatus.Starting

        // Opened after the upgrade, before the engine heard any peer: the update, at once.
        assertEquals(UpgradeWarning.UpdateRequired(activated), observe(afterIt).first())

        // A light client that verifies fresh heads after it, with no peer reporting it: this
        // version follows the network, and the record alone says nothing.
        lightClient.status.value = LightClientStatus.Synced(100, null, finalized = null)
        assertEquals(UpgradeWarning.None, observe(afterIt).first())
    }

    @Test
    fun aRecordedTimeThatPassedOnTheClockAloneNeverLocksTheApp() = runTest {
        // Announced for a time that has passed, with no peer confirming it activated: the fork
        // may have moved, so a starting light client gets a banner, not the update screen.
        known.record.value = KnownUpgrade(glamsterdam, emptySet())
        lightClient.status.value = LightClientStatus.Starting

        assertEquals(UpgradeWarning.Ahead(glamsterdam), observe(beforeIt).first())
        assertEquals(UpgradeWarning.Activated(glamsterdam), observe(afterIt).first())

        lightClient.status.value = LightClientStatus.Synced(100, null, finalized = null)
        assertEquals(UpgradeWarning.None, observe(afterIt).first())
    }

    @Test
    fun theRecordGivesTheTimeAReportOnTheNewRulesLacks() = runTest {
        known.record.value = KnownUpgrade(glamsterdam, emptySet())
        upgrades.report.value = activated.copy(activationTime = null, forkId = null, reportingNetworks = 8)
        lightClient.status.value = LightClientStatus.CatchingUp(1, 4)

        assertEquals(UpgradeWarning.UpdateRequired(activated.copy(reportingNetworks = 8)), observe(afterIt).first())
    }

    @Test
    fun aReportOfAnotherTimeIsAnotherUpgradeAndWins() = runTest {
        known.record.value = KnownUpgrade(glamsterdam, emptySet())
        val moved = glamsterdam.copy(activationTime = glamsterdam.activationTime!! + 7 * DAY)
        upgrades.report.value = moved

        assertEquals(UpgradeWarning.Ahead(moved), observe(afterIt).first())
    }

    @Test
    fun theWarningChangesWhenTheActivationTimePassesWithNothingElseChanging() = runTest {
        upgrades.report.value = glamsterdam
        lightClient.status.value = LightClientStatus.WaitingForHead(headAgeSeconds = 120)
        val start = glamsterdam.activationTime!! - 60
        val seen = mutableListOf<UpgradeWarning>()
        backgroundScope.launch {
            ObserveUpgradeWarning(
                Network.SEPOLIA,
                upgrades,
                known,
                lightClient,
            ) { start + currentTime / 1_000 }().collect { seen += it }
        }
        runCurrent()
        assertEquals(listOf<UpgradeWarning>(UpgradeWarning.Ahead(glamsterdam)), seen)

        advanceTimeBy(60_001)
        assertEquals(listOf(UpgradeWarning.Ahead(glamsterdam), UpgradeWarning.UpdateRequired(glamsterdam)), seen)
    }
}
