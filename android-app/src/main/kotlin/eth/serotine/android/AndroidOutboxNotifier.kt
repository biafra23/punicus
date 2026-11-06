package eth.serotine.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import eth.serotine.domain.repository.OutboxNotifier
import eth.serotine.domain.repository.QueueConflict
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.ui.outbox.OutboxNotificationText

/**
 * [OutboxNotifier] on Android (docs/lenient-mode.md, *The outbox*): a notification on the "Queued
 * transactions" channel, one per queued transaction (its send or its conflict replaces the one
 * before), which opens the app. It says the transfer in token units and its recipient, never the
 * signed bytes. Without the notification permission (Android 13 and later), it is dropped: the
 * app's own screens still say it.
 */
class AndroidOutboxNotifier(private val context: Context) : OutboxNotifier {

    override fun sent(queued: QueuedTransaction, hash: String) = show(queued, OutboxNotificationText.sent(queued))

    override fun conflict(queued: QueuedTransaction, conflict: QueueConflict) =
        show(queued, OutboxNotificationText.conflict(queued, conflict))

    private fun show(queued: QueuedTransaction, text: OutboxNotificationText) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.outbox_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.outbox_channel_description) },
            )
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_upgrade)
                .setContentTitle(text.title)
                .setContentText(text.text)
                .setStyle(Notification.BigTextStyle().bigText(text.text))
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            // One id per queued transaction: its send or its conflict replaces what was said of it before.
            manager.notify(NOTIFICATION_BASE + (queued.id.hashCode() and 0xffff), notification)
        } catch (e: Exception) {
            // The permission revoked in between, or a notification that cannot be put into words:
            // the app's screens still say it.
        }
    }

    private companion object {
        const val CHANNEL_ID = "outbox"

        /** Above the upgrade notices' ids (1 to 3), so neither ever replaces the other. */
        const val NOTIFICATION_BASE = 0x10000
    }
}
