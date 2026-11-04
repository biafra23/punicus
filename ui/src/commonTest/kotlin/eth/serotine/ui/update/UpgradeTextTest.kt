package eth.serotine.ui.update

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.UpgradeNotice
import eth.serotine.domain.usecase.UpgradeWarning
import kotlin.test.Test
import kotlin.test.assertEquals

class UpgradeTextTest {

    private val glamsterdam = NetworkUpgrade(NetworkUpgrade.Phase.SCHEDULED, 1_791_294_816, "0x6c1d9423", 5)
    private val placed = NetworkUpgrade(NetworkUpgrade.Phase.ACTIVE, activationTime = null, forkId = null, 1)
    private val date: (Long) -> String = { "<$it>" }

    private fun banner(warning: UpgradeWarning) = UpgradeBannerItem.from(warning, Network.SEPOLIA, date)

    @Test
    fun noWarningIsNoBanner() {
        assertEquals(null, banner(UpgradeWarning.None))
    }

    @Test
    fun anUpgradeAheadIsAHeadsUpWithItsDateThatSaysPeersReportIt() {
        assertEquals(
            UpgradeBannerItem(
                "Network upgrade ahead on Sepolia: update Serotine",
                "Peers announce a Sepolia network upgrade on <1791294816> that this version of Serotine does not " +
                    "support. Update the app before it activates, or Serotine stops verifying at the upgrade.",
                "Reported by peers in 5 networks, not verified · fork id 0x6c1d9423",
                urgent = false,
            ),
            banner(UpgradeWarning.Ahead(glamsterdam)),
        )
    }

    @Test
    fun anActivatedUpgradeTheLightClientDoesNotContradictIsNotUrgent() {
        assertEquals(
            UpgradeBannerItem(
                "Network upgrade reported on Sepolia: update Serotine",
                "Peers report that Sepolia upgraded to rules this version of Serotine does not support. Update " +
                    "the app to keep using your wallet.",
                "Reported by peers in 1 network, not verified · fork id unknown",
                urgent = false,
            ),
            banner(UpgradeWarning.Activated(placed)),
        )
    }

    @Test
    fun aTimeThatPassedOnTheClockIsNotSaidToBeAnUpgradePeersReport() {
        val due = banner(UpgradeWarning.Activated(glamsterdam))!!
        assertEquals("Network upgrade due on Sepolia: update Serotine", due.title)
        assertEquals(
            "Peers announced a Sepolia network upgrade on <1791294816> that this version of Serotine does not " +
                "support, and that time has passed. Update the app to keep using your wallet.",
            due.text,
        )
        val notice = UpgradeNotice(UpgradeNotice.Kind.ACTIVATED, glamsterdam)
        assertEquals(due.text, UpgradeNotificationText.from(notice, Network.SEPOLIA, date).text)
    }

    @Test
    fun onlyARequiredUpdateSaysTheAppIsNotVerifying() {
        val item = banner(UpgradeWarning.UpdateRequired(glamsterdam.copy(phase = NetworkUpgrade.Phase.ACTIVE)))!!
        assertEquals(true, item.urgent)
        assertEquals(
            "Peers report that Sepolia upgraded on <1791294816> to rules this version of Serotine does not " +
                "support, and its light client is not verifying the chain. Update the app to keep using your wallet.",
            item.text,
        )
    }

    @Test
    fun aRequiredUpdateFromAScheduledReportSaysPeersAnnouncedItNotThatTheyReportIt() {
        // A live report of a scheduled upgrade whose time passed, and a light client not verifying.
        val item = banner(UpgradeWarning.UpdateRequired(glamsterdam))!!
        assertEquals(
            "Peers announced a Sepolia network upgrade on <1791294816> that this version of Serotine does not " +
                "support. That time has passed, and its light client is not verifying the chain. Update the app " +
                "to keep using your wallet.",
            item.text,
        )
    }

    @Test
    fun eachNoticeHasItsOwnTitle() {
        fun text(kind: UpgradeNotice.Kind, upgrade: NetworkUpgrade = glamsterdam) =
            UpgradeNotificationText.from(UpgradeNotice(kind, upgrade), Network.SEPOLIA, date)
        val ahead = "Peers announce a Sepolia network upgrade on <1791294816> that this version of Serotine does " +
            "not support. Update the app before it activates."

        assertEquals(
            UpgradeNotificationText("Update Serotine before the network upgrade", ahead),
            text(UpgradeNotice.Kind.ANNOUNCED),
        )
        assertEquals(
            UpgradeNotificationText("Sepolia upgrades within a week", ahead),
            text(UpgradeNotice.Kind.WEEK_AHEAD),
        )
        assertEquals(
            UpgradeNotificationText("Sepolia upgrades within a day", ahead),
            text(UpgradeNotice.Kind.DAY_AHEAD),
        )
        assertEquals(
            UpgradeNotificationText(
                "Update Serotine",
                "Peers report that Sepolia upgraded to rules this version of Serotine does not support. Update the " +
                    "app to keep using your wallet.",
            ),
            text(UpgradeNotice.Kind.ACTIVATED, placed),
        )
    }
}
