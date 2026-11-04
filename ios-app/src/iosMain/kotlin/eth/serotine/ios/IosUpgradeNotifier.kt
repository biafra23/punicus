package eth.serotine.ios

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UpgradeNotice
import eth.serotine.domain.repository.UpgradeNotifier
import eth.serotine.ui.update.UpgradeNotificationText
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNNotificationTrigger
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * [UpgradeNotifier] on iOS: a local notification, which opens the app, and for a reminder due
 * later one the system delivers at that time, the app running or not. iOS runs no app code at a
 * set time, so that one cannot carry the reminder's own words: it says a reminder about the
 * upgrade peers announced is due and asks the user to open the app, where the screens say the
 * rest and the notice due then replaces it. Shown with the app in front too, as on Android.
 * Without the user's permission ([askPermission]), nothing is shown: the app's own screens still
 * say it.
 */
class IosUpgradeNotifier(private val network: Network) : UpgradeNotifier {

    private val center: UNUserNotificationCenter
        get() = UNUserNotificationCenter.currentNotificationCenter()

    /** Held here: the notification center keeps its delegate weakly. */
    private val inFront = ShowInFront()

    /**
     * Has notifications shown while the app is in front too, then asks the user, once, whether
     * the app may notify, so a network upgrade can be announced while it is closed. iOS asks only
     * the first time; later calls answer at once. At launch, before the first notice.
     */
    fun askPermission() {
        center.delegate = inFront
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ ->
            // Nothing to do with the answer: without the permission, notices are dropped.
        }
    }

    override fun show(notice: UpgradeNotice) {
        try {
            val text = UpgradeNotificationText.from(notice, network)
            // The reminder that stood for this notice is said now, with its own words.
            center.removeDeliveredNotificationsWithIdentifiers(listOf(reminderId))
            // One identifier per network: a newer notice of the network replaces the one before.
            post(noticeId, text.title, text.text, trigger = null)
        } catch (e: Exception) {
            // A notice that cannot be put into words or posted: the app's screens still say it.
            println("Serotine: no upgrade notification (${e::class.simpleName})")
        }
    }

    override fun remindAt(at: Long?) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(reminderId))
        if (at == null) return
        // Due already, or within the second: the app is running, and its next check shows it.
        val seconds = at - NSDate().timeIntervalSince1970.toLong()
        if (seconds <= 0) return
        val name = network.displayName
        post(
            reminderId,
            title = "Network upgrade reminder",
            body = "Peers announced a $name network upgrade that this version of Serotine does not support, and " +
                "a reminder about it is due. Open Serotine to see where it stands.",
            trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds.toDouble(), repeats = false),
        )
    }

    private fun post(id: String, title: String, body: String, trigger: UNNotificationTrigger?) {
        val content = UNMutableNotificationContent()
        content.setTitle(title)
        content.setBody(body)
        content.setSound(UNNotificationSound.defaultSound)
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(id, content, trigger)) { error ->
            // Refused (no permission, or the system's limit): the app's own screens still say it.
            if (error != null) println("Serotine: upgrade notification not posted (${error.code})")
        }
    }

    /**
     * Shows a notification that arrives while the app is in front as it would be shown otherwise:
     * iOS keeps it silent unless the delegate says so, and the notices are posted while the app
     * runs.
     */
    private class ShowInFront : NSObject(), UNUserNotificationCenterDelegateProtocol {
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) {
            withCompletionHandler(
                UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList or
                    UNNotificationPresentationOptionSound,
            )
        }
    }

    /** This network's notice: Sepolia keeps the identifier its notices had before there were networks. */
    private val noticeId: String = when (network) {
        Network.SEPOLIA -> NOTICE_ID
        Network.ETHEREUM, Network.GNOSIS -> "$NOTICE_ID-${network.chainId}"
    }

    /** This network's reminder, kept apart as [noticeId] is. */
    private val reminderId: String = "$noticeId$REMINDER_SUFFIX"

    private companion object {
        const val NOTICE_ID = "network-upgrade"
        const val REMINDER_SUFFIX = "-reminder"
    }
}
