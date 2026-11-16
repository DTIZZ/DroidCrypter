package app.droidcrypter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Shared plumbing for the app's `dataSync` foreground services ([MountService] and
 * [app.droidcrypter.create.ContainerCreationService]).
 */
internal object ForegroundServiceSupport {

    /**
     * Promotes [service] to a `dataSync` foreground service. Failures (e.g. a background-start
     * restriction) are swallowed: the service keeps running and simply isn't foreground.
     */
    fun startDataSync(service: Service, notificationId: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                service.startForeground(notificationId, notification)
            }
        } catch (_: Throwable) {
        }
    }

    /** Creates (or updates) a low-importance channel whose content is hidden on the lock screen. */
    fun ensurePrivateChannel(context: Context, id: String, name: String, description: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).apply {
            this.description = description
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }
}
