package eth.serotine.domain.usecase

import eth.serotine.domain.repository.ConnectionCost
import eth.serotine.domain.repository.ConnectionMeter
import eth.serotine.domain.repository.MeteredDownloadsChoice
import eth.serotine.domain.repository.MeteredDownloadsSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the heavy downloads may run now: the user's [choice] for a metered connection and the
 * connection's [cost]. A heavy download is network work that can run to hundreds of megabytes
 * and that the wallet can do later without the user waiting on it, such as the log index's walk
 * back over the account history ([FollowAccountLogs]); a read the user is waiting on never is one.
 */
data class HeavyDownloads(val choice: MeteredDownloadsChoice, val cost: ConnectionCost) {

    /** Whether the user allowed them on a metered connection; an unreadable choice did not. */
    val allowedOnMetered: Boolean
        get() = (choice as? MeteredDownloadsChoice.Chosen)?.allowed == true

    /** True when they may run now: on any connection not reported as metered, and on a metered one when allowed. */
    val mayRun: Boolean
        get() = cost != ConnectionCost.METERED || allowedOnMetered
}

/** The [HeavyDownloads] now, re-emitted when the choice or the connection changes. */
class ObserveHeavyDownloads(
    private val meter: ConnectionMeter,
    private val setting: MeteredDownloadsSetting,
) {
    operator fun invoke(): Flow<HeavyDownloads> =
        combine(setting.observe(), meter.observe(), ::HeavyDownloads).distinctUntilChanged()
}

/** Allows the heavy downloads on a metered connection, or not; false when the choice could not be saved. */
class AllowHeavyDownloadsOnMetered(private val setting: MeteredDownloadsSetting) {
    suspend operator fun invoke(allowed: Boolean): Boolean = setting.allow(allowed)
}
