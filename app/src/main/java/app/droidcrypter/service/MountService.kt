package app.droidcrypter.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import app.droidcrypter.MainActivity
import app.droidcrypter.R
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.engine.VolumeRegistry
import app.droidcrypter.provider.VaultDocumentsProvider
import app.droidcrypter.settings.AppPreferences

/**
 * Foreground service (`dataSync`) that keeps the process alive while at least one DroidCrypter volume
 * is unlocked, listens for `ACTION_SCREEN_OFF`, and locks all volumes on screen-off (when enabled),
 * task removal, or `dataSync` timeout (sections 4.4 & 7.11).
 */
class MountService : Service() {

    private lateinit var prefs: AppPreferences
    private var receiverRegistered = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && prefs.settings.value.lockOnScreenOff) {
                lockAllAndStop()
            }
        }
    }

    private val registryListener: (List<MountedVolume>) -> Unit = { volumes ->
        VaultDocumentsProvider.notifyRootsChanged(this)
        if (volumes.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, buildNotification(volumes.size))
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        ForegroundServiceSupport.ensurePrivateChannel(
            this,
            CHANNEL_ID,
            "Unlocked Vaults",
            "Shows when DroidCrypter vaults are unlocked in memory",
        )

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
        }
        receiverRegistered = true

        VolumeRegistry.addChangeListener(registryListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LOCK_ALL) {
            lockAllAndStop()
            return START_NOT_STICKY
        }

        val count = VolumeRegistry.all().size
        if (count == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        ForegroundServiceSupport.startDataSync(this, NOTIFICATION_ID, buildNotification(count))
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        lockAllAndStop()
        super.onTaskRemoved(rootIntent)
    }

    /** Android 14 short-service timeout callback (kept for completeness). */
    override fun onTimeout(startId: Int) {
        lockAllAndStop()
        super.onTimeout(startId)
    }

    /**
     * Android 15+ calls this (not the one-arg overload) when the `dataSync` foreground-service time
     * limit runs out. Without it the system kills the app with an ANR-style crash instead of the
     * clean lock (before-close hooks, provider notification) this service promises.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        lockAllAndStop()
        super.onTimeout(startId, fgsType)
    }

    override fun onDestroy() {
        VolumeRegistry.removeChangeListener(registryListener)
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenOffReceiver)
            } catch (_: Throwable) {
            }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Locks every volume (wiping master keys), tells the file picker, and stops the service. */
    private fun lockAllAndStop() {
        VolumeRegistry.unmountAll()
        VaultDocumentsProvider.notifyRootsChanged(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @OptIn(UnstableApi::class) // MainActivity is @UnstableApi (Media3)
    private fun buildNotification(unlockedCount: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val lockIntent = Intent(this, MountService::class.java).apply {
            action = ACTION_LOCK_ALL
        }
        val lockPi = PendingIntent.getService(
            this,
            2,
            lockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$unlockedCount volume(s) unlocked")
            .setContentText("Decrypted on demand. Tap Lock all when done.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPi)
            .addAction(0, "Lock all", lockPi)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "droidcrypter_mount_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_LOCK_ALL = "app.droidcrypter.action.LOCK_ALL"

        fun syncWithRegistry(context: Context) {
            val appCtx = context.applicationContext
            VaultDocumentsProvider.notifyRootsChanged(appCtx)
            if (VolumeRegistry.all().isNotEmpty()) {
                val intent = Intent(appCtx, MountService::class.java)
                try {
                    ContextCompat.startForegroundService(appCtx, intent)
                } catch (_: Throwable) {
                }
            } else {
                try {
                    appCtx.stopService(Intent(appCtx, MountService::class.java))
                } catch (_: Throwable) {
                }
            }
        }
    }
}
