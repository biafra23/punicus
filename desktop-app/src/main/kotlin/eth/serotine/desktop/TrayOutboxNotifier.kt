package eth.serotine.desktop

import eth.serotine.domain.repository.HeldConflict
import eth.serotine.domain.repository.HeldSafeSignature
import eth.serotine.domain.repository.OutboxNotifier
import eth.serotine.domain.repository.QueueConflict
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.ui.outbox.OutboxNotificationText
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * [OutboxNotifier] on the desktop (docs/lenient-mode.md, *The outbox*): a message from the system
 * tray, while the app runs, on a desktop that has a tray, as [TrayUpgradeNotifier] shows upgrade
 * notices. The outbox runs only while the app runs, so nothing is missed by a closed app: the
 * window says the rest. It says the transfer and its recipient, or a held Safe signature's Safe
 * transaction and its Safe, never the signed bytes nor a signature.
 */
class TrayOutboxNotifier : OutboxNotifier {

    /** Touched on the AWT event thread only. */
    private var icon: TrayIcon? = null

    override fun sent(queued: QueuedTransaction, hash: String) =
        show(OutboxNotificationText.sent(queued), TrayIcon.MessageType.INFO)

    override fun conflict(queued: QueuedTransaction, conflict: QueueConflict) =
        show(OutboxNotificationText.conflict(queued, conflict), TrayIcon.MessageType.WARNING)

    override fun heldChecked(held: HeldSafeSignature) =
        show(OutboxNotificationText.heldChecked(held), TrayIcon.MessageType.INFO)

    override fun heldConflict(held: HeldSafeSignature, conflict: HeldConflict) =
        show(OutboxNotificationText.heldConflict(held, conflict), TrayIcon.MessageType.WARNING)

    private fun show(text: OutboxNotificationText, type: TrayIcon.MessageType) {
        EventQueue.invokeLater {
            try {
                if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return@invokeLater
                val image = AppIcon.image ?: return@invokeLater
                val size = SystemTray.getSystemTray().trayIconSize
                val tray = icon ?: TrayIcon(image.getScaledInstance(size.width, size.height, Image.SCALE_SMOOTH), "Punicus").also {
                    it.isImageAutoSize = true
                    SystemTray.getSystemTray().add(it)
                    icon = it
                }
                tray.displayMessage(text.title, text.text, type)
            } catch (e: Exception) {
                // A tray that refuses the icon: the window still says it.
                println("Punicus: no tray notice of the outbox (${e::class.simpleName})")
            }
        }
    }
}
