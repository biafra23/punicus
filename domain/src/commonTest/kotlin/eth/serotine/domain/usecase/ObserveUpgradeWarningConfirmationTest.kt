package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientStatus
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Acceptance test of finding N4 of the security audit of 2026-11-01 (.claude/rules/trust.md,
 * *Network-upgrade warnings are peer reports*): the update screen needs BOTH an activation peers
 * CONFIRMED, "not merely an announced time passing", AND a light client that is not verifying. A
 * live report still in phase SCHEDULED whose announced time has passed on the clock is the former
 * leg unmet, whatever the light client's own state: the rule lists a stale head under the
 * light-client leg of the AND, not as a confirmation by peers. So the banner, never the screen
 * that replaces every other, for every status that is not verifying.
 *
 * The fix this test waits for keys confirmation on the report's phase. [ObserveUpgradeWarningTest]'s
 * `theWarningChangesWhenTheActivationTimePassesWithNothingElseChanging` pins the other reading for
 * the same shape (a live SCHEDULED report, its time passed, `WaitingForHead` → `UpdateRequired`),
 * so the fix changes that test too, and [ObserveUpgradeWarning]'s KDoc says which reading the rule
 * means. Which one is the owner's decision; this test encodes the rule's text. Ignored while the
 * finding is open; remove the @Ignore with the fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveUpgradeWarningConfirmationTest {

    private val upgrades = FakeNetworkUpgrades()
    private val known = FakeKnownUpgradeStore()
    private val lightClient = FakeLightClient()
    private val afterIt = glamsterdam.activationTime!! + DAY

    private fun observe(now: Long) = ObserveUpgradeWarning(Network.SEPOLIA, upgrades, known, lightClient) { now }()

    @Ignore // Audit 2026-11-01, N4: open.
    @Test
    fun aReportedTimeThatPassedIsNoConfirmationWhileTheLightClientStarts() = runTest {
        upgrades.report.value = glamsterdam // phase SCHEDULED; its announced time has passed at afterIt
        val notVerifying = listOf(
            LightClientStatus.Starting,
            LightClientStatus.Bootstrapping(lookingForServers = true),
            LightClientStatus.CatchingUp(1, 4),
            LightClientStatus.WaitingForHead(headAgeSeconds = 120),
            LightClientStatus.StaleAnchor(anchorAgePeriods = 20, boundPeriods = 13),
        )
        for (status in notVerifying) {
            lightClient.status.value = status
            assertEquals(
                UpgradeWarning.Activated(glamsterdam),
                observe(afterIt).first(),
                "$status: peers announced a time, they did not confirm an activation: a banner, not the update screen",
            )
        }
    }
}
