package eth.serotine.ios

import eth.serotine.domain.repository.HeldConflict
import eth.serotine.domain.repository.HeldSafeSignature
import eth.serotine.domain.repository.OutboxNotifier
import eth.serotine.domain.repository.QueueConflict
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.ui.outbox.OutboxNotificationText
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * [OutboxNotifier] on iOS (docs/lenient-mode.md, *The outbox*): a local notification, which opens
 * the app, one per queued transaction or held Safe signature (what is said of it next replaces the one
 * before). The
 * permission is the one [IosUpgradeNotifier.askPermission] asks for; without it nothing is shown,
 * and the app's own screens still say it. It says the transfer and its recipient, or the Safe
 * transaction and its Safe, never the signed bytes nor a signature.
 */
class IosOutboxNotifier : OutboxNotifier {

    override fun sent(queued: QueuedTransaction, hash: String) = post(queued.id, OutboxNotificationText.sent(queued))

    override fun conflict(queued: QueuedTransaction, conflict: QueueConflict) =
        post(queued.id, OutboxNotificationText.conflict(queued, conflict))

    override fun heldChecked(held: HeldSafeSignature) =
        post("held-" + held.hash.hex, OutboxNotificationText.heldChecked(held))

    override fun heldConflict(held: HeldSafeSignature, conflict: HeldConflict) =
        post("held-" + held.hash.hex, OutboxNotificationText.heldConflict(held, conflict))

    /** Posts [text] under [id], a queued transaction's or a held signature's: what is said of it next replaces it. */
    private fun post(id: String, text: OutboxNotificationText) {
        try {
            val content = UNMutableNotificationContent()
            content.setTitle(text.title)
            content.setBody(text.text)
            content.setSound(UNNotificationSound.defaultSound)
            val request = UNNotificationRequest.requestWithIdentifier("$ID_PREFIX$id", content, trigger = null)
            UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { error ->
                if (error != null) println("Serotine: outbox notification not posted (${error.code})")
            }
        } catch (e: Exception) {
            println("Serotine: no outbox notification (${e::class.simpleName})")
        }
    }

    private companion object {
        const val ID_PREFIX = "serotine-outbox-"
    }
}
