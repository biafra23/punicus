package eth.serotine.ui.update

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.UpgradeNotice
import eth.serotine.domain.usecase.UpgradeWarning
import eth.serotine.ui.platform.formatDateTime

// How a network upgrade this version does not support is put into words, the same on every
// surface: the banner on Accounts, the update screen, and the notifications the hosts show. Peers
// report it and nothing verifies it, so each text says that peers report it, and only the
// update screen, which needs the light client's own agreement, says the app is not verifying.

/** Where every Serotine release is published (README, *Releases*): what "update" leads to. */
const val RELEASES_URL = "https://github.com/biafra23/serotine/releases"

/**
 * An upgrade warning as a banner shows it, display-ready: [urgent] when this version can no
 * longer follow the network.
 */
data class UpgradeBannerItem(val title: String, val text: String, val details: String, val urgent: Boolean) {
    companion object {
        /** The banner for [warning] on [network], dated with [formatTime]; null when there is nothing to say. */
        fun from(warning: UpgradeWarning, network: Network, formatTime: (Long) -> String): UpgradeBannerItem? =
            when (warning) {
                UpgradeWarning.None -> null
                is UpgradeWarning.Ahead -> UpgradeBannerItem(
                    "Network upgrade ahead on ${network.displayName}: update Serotine",
                    "Peers announce a ${network.displayName} network upgrade${warning.upgrade.onDate(formatTime)} " +
                        "that this version of Serotine does not support. Update the app before it activates, " +
                        "or Serotine stops verifying at the upgrade.",
                    detailsOf(warning.upgrade),
                    urgent = false,
                )
                is UpgradeWarning.Activated -> UpgradeBannerItem(
                    if (warning.upgrade.reportedActive) {
                        "Network upgrade reported on ${network.displayName}: update Serotine"
                    } else {
                        "Network upgrade due on ${network.displayName}: update Serotine"
                    },
                    activatedText(warning.upgrade, network, formatTime),
                    detailsOf(warning.upgrade),
                    urgent = false,
                )
                is UpgradeWarning.UpdateRequired -> UpgradeBannerItem(
                    "Update required to follow ${network.displayName}",
                    updateRequiredText(warning.upgrade, network, formatTime),
                    detailsOf(warning.upgrade),
                    urgent = true,
                )
            }
    }
}

/** An upgrade notice as a notification shows it, display-ready. */
data class UpgradeNotificationText(val title: String, val text: String) {
    companion object {
        /** The notification for [notice] on [network], dated with [formatTime]. */
        fun from(
            notice: UpgradeNotice,
            network: Network,
            formatTime: (Long) -> String = ::formatDateTime,
        ): UpgradeNotificationText {
            val name = network.displayName
            val on = notice.upgrade.onDate(formatTime)
            val ahead = "Peers announce a $name network upgrade$on that this version of Serotine does not " +
                "support. Update the app before it activates."
            return when (notice.kind) {
                UpgradeNotice.Kind.ANNOUNCED ->
                    UpgradeNotificationText("Update Serotine before the network upgrade", ahead)
                UpgradeNotice.Kind.WEEK_AHEAD -> UpgradeNotificationText("$name upgrades within a week", ahead)
                UpgradeNotice.Kind.DAY_AHEAD -> UpgradeNotificationText("$name upgrades within a day", ahead)
                UpgradeNotice.Kind.ACTIVATED ->
                    UpgradeNotificationText("Update Serotine", activatedText(notice.upgrade, network, formatTime))
            }
        }
    }
}

/**
 * An upgrade that has activated: as peers report it, or, when only its announced time has
 * passed on the clock, as announced, since no peer has said it happened.
 */
private fun activatedText(upgrade: NetworkUpgrade, network: Network, formatTime: (Long) -> String): String {
    val name = network.displayName
    val happened = if (upgrade.reportedActive) {
        "Peers report that $name upgraded${upgrade.onDate(formatTime)} to rules this version of Serotine does " +
            "not support."
    } else {
        "Peers announced a $name network upgrade${upgrade.onDate(formatTime)} that this version of Serotine " +
            "does not support, and that time has passed."
    }
    return "$happened Update the app to keep using your wallet."
}

/** Whether peers reported the upgrade active, rather than its time passing on the clock. */
private val NetworkUpgrade.reportedActive: Boolean
    get() = phase == NetworkUpgrade.Phase.ACTIVE

/**
 * What the update screen says: the one text that may say the app is not verifying. As in
 * [activatedText], "peers report" only when they did: a live report of a scheduled upgrade
 * whose time has passed also requires the update, and then peers announced it and the clock
 * passed it, while the light client's own failure is the evidence.
 */
internal fun updateRequiredText(upgrade: NetworkUpgrade, network: Network, formatTime: (Long) -> String): String {
    val name = network.displayName
    val happened = if (upgrade.reportedActive) {
        "Peers report that $name upgraded${upgrade.onDate(formatTime)} to rules this version of Serotine does " +
            "not support, and its light client is not verifying the chain."
    } else {
        "Peers announced a $name network upgrade${upgrade.onDate(formatTime)} that this version of Serotine " +
            "does not support. That time has passed, and its light client is not verifying the chain."
    }
    return "$happened Update the app to keep using your wallet."
}

/** Who reports [upgrade], and that nothing verifies it; its fork id when known. */
internal fun detailsOf(upgrade: NetworkUpgrade): String {
    val networks = upgrade.reportingNetworks
    val peers = "Reported by peers in $networks ${if (networks == 1) "network" else "networks"}, not verified"
    return "$peers · fork id ${upgrade.forkId ?: "unknown"}"
}

/** " on <date>" for a sentence, or nothing when no time was announced. */
private fun NetworkUpgrade.onDate(formatTime: (Long) -> String): String =
    activationTime?.let { " on ${formatTime(it)}" }.orEmpty()
