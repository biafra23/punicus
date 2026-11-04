package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.NetworkUpgrades
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Feeds what peers report about upgrades into [notices] for as long as it runs, and shows the
 * reminders that fall due meanwhile, with [clock] (unix seconds) as now. A host runs it while
 * its engine is awake; the host's alarm covers the time in between ([UpgradeNotices.remind]).
 */
class FollowUpgradeReports(
    private val network: Network,
    private val upgrades: NetworkUpgrades,
    private val notices: UpgradeNotices,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    /**
     * Runs until cancelled, or until the engine stops reporting (one that is down, or none on
     * this device) with no reminder left to wait for.
     */
    suspend operator fun invoke() {
        upgrades.observeUpgrade(network).collectLatest { report ->
            while (true) {
                val next = notices.check(report) ?: break
                delay((next - clock()).coerceAtLeast(1).seconds)
            }
        }
    }
}
