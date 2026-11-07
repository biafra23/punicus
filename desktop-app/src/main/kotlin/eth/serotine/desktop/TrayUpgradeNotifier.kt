package eth.serotine.desktop

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UpgradeNotice
import eth.serotine.domain.repository.UpgradeNotifier
import eth.serotine.ui.update.UpgradeNotificationText
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * [UpgradeNotifier] on the desktop: a message from the system tray, while the app runs, on a
 * desktop that has a tray (macOS, Windows and most Linux desktops; GNOME only with an
 * extension). The tray icon appears with the first notice and stays until the app quits.
 * Nothing wakes a closed desktop app: a reminder due meanwhile is shown at the next start,
 * from the record, and the window says it all along.
 */
class TrayUpgradeNotifier(private val network: Network) : UpgradeNotifier {

    /** Touched on the AWT event thread only. */
    private var icon: TrayIcon? = null

    override fun show(notice: UpgradeNotice) {
        EventQueue.invokeLater {
            try {
                if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return@invokeLater
                val text = UpgradeNotificationText.from(notice, network)
                val image = AppIcon.image
                if (image == null) {
                    // No icon to show in the tray (AppIcon): the window still says it.
                    println("Punicus: no tray notice (no app icon)")
                    return@invokeLater
                }
                // Scaled smoothly to the tray's own size: the icon is 512 px, and auto-sizing
                // scales it down with a coarse filter on some platforms.
                val size = SystemTray.getSystemTray().trayIconSize
                val mark = image.getScaledInstance(size.width, size.height, Image.SCALE_SMOOTH)
                val tray = icon ?: TrayIcon(mark, "Punicus").also {
                    it.isImageAutoSize = true
                    SystemTray.getSystemTray().add(it)
                    icon = it
                }
                tray.displayMessage(text.title, text.text, TrayIcon.MessageType.WARNING)
            } catch (e: Exception) {
                // A tray that refuses the icon, or a notice that cannot be put into words: the
                // window still says it.
                println("Punicus: no tray notice (${e::class.simpleName})")
            }
        }
    }

    /** Nothing to arm: reminders come while the app runs, or from the record at its next start. */
    override fun remindAt(at: Long?) = Unit
}
