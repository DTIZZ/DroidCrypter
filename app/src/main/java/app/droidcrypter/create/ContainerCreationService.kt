package app.droidcrypter.create

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import android.system.Os
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.droidcrypter.MainActivity
import app.droidcrypter.R
import app.droidcrypter.engine.FdBlockDevice
import app.droidcrypter.engine.Secrets
import app.droidcrypter.engine.VolumeSpace
import app.droidcrypter.engine.vc.CipherSuite
import app.droidcrypter.engine.vc.ContainerCreator
import app.droidcrypter.engine.vc.CreatePhase
import app.droidcrypter.engine.vc.CreateSpec
import app.droidcrypter.engine.vc.FormatFilesystem
import app.droidcrypter.engine.vc.Prf
import app.droidcrypter.settings.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory creation request. Passwords are NEVER placed in an Android `Intent` or Bundle.
 */
class CreationJobRequest(
    val targetUri: Uri,
    val sizeBytes: Long,
    val cipher: CipherSuite,
    val prf: Prf,
    val pim: Int,
    val filesystem: FormatFilesystem,
    val label: String,
    val quickFormat: Boolean,
    val enrollBiometric: Boolean,
    val passwordChars: CharArray,
) {
    fun wipePassword() {
        Secrets.wipe(passwordChars)
    }
}

sealed interface CreationUiState {
    data object Idle : CreationUiState
    data class Running(
        val targetUri: Uri,
        val phase: CreatePhase,
        val fraction: Float,
        val statusText: String,
    ) : CreationUiState

    data class Succeeded(
        val targetUri: Uri,
        val containerId: String,
        val enrollBiometric: Boolean,
        /** One-time password bytes held only in RAM when [enrollBiometric] is true; wiped as soon as enrolled or dismissed. */
        val pendingBiometricPasswordBytes: ByteArray?,
        val pendingBiometricPim: Int,
    ) : CreationUiState {
        fun wipePendingBiometricSecret() {
            if (pendingBiometricPasswordBytes != null) {
                Secrets.wipe(pendingBiometricPasswordBytes)
            }
        }
    }

    data class Cancelled(val message: String = "Container creation cancelled.") : CreationUiState
    data class Failed(val errorMessage: String) : CreationUiState
}

/**
 * Foreground service (`dataSync`) that formats a new DroidCrypter container in the background
 * with throttled progress notifications (~15/sec max) and safe partial-file cleanup on cancel/error (section 6.4).
 */
class ContainerCreationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelActiveCreation()
            return START_NOT_STICKY
        }

        val req = consumePendingRequest()
        if (req == null) {
            if (activeJob?.isActive != true) {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val initialNotif = buildProgressNotification("Preparing container...", 0f)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotif)
            }
        } catch (_: Throwable) {
        }

        val cancelFlag = AtomicBoolean(false)
        currentCancelFlag = cancelFlag

        activeJob = serviceScope.launch {
            var pwBytes: ByteArray? = null
            var biometricCopy: ByteArray? = null
            var createdOk = false
            try {
                pwBytes = Secrets.utf8Bytes(req.passwordChars)
                req.wipePassword()
                Secrets.validatePasswordLength(pwBytes)

                // Check host free space (>= sizeBytes + 64 MiB) before starting
                checkEnoughHostFreeSpace(contentResolver, req.targetUri, req.sizeBytes)

                val pfd = contentResolver.openFileDescriptor(req.targetUri, "rw")
                    ?: throw IOException("Could not open target URI for writing.")

                var lastNotifMillis = 0L
                FdBlockDevice.fromParcelFileDescriptor(
                    pfd = pfd,
                    writable = true,
                    fixedSize = req.sizeBytes,
                    resizeOnOpen = true,
                ).use { device ->
                    // Check free space on the open file descriptor as well
                    checkEnoughFdFreeSpace(pfd.fileDescriptor, req.sizeBytes)

                    val spec = CreateSpec(
                        cipher = req.cipher,
                        prf = req.prf,
                        pim = req.pim,
                        filesystem = req.filesystem,
                        label = req.label,
                        quickFormat = req.quickFormat,
                    )

                    ContainerCreator.create(
                        host = device,
                        password = pwBytes.copyOf(),
                        spec = spec,
                        onProgress = { phase, fraction ->
                            val status = formatPhaseText(phase, fraction)
                            _state.value = CreationUiState.Running(
                                targetUri = req.targetUri,
                                phase = phase,
                                fraction = fraction,
                                statusText = status,
                            )
                            val now = System.currentTimeMillis()
                            if (now - lastNotifMillis >= MIN_NOTIFICATION_INTERVAL_MS || fraction >= 1f) {
                                lastNotifMillis = now
                                val nm = getSystemService(NotificationManager::class.java)
                                nm?.notify(NOTIFICATION_ID, buildProgressNotification(status, fraction))
                            }
                        },
                        cancel = {
                            if (cancelFlag.get()) {
                                throw CancellationException("Container creation cancelled")
                            }
                        },
                    )
                }

                // Persist URI grant if possible and add to vault list
                try {
                    contentResolver.takePersistableUriPermission(
                        req.targetUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (_: Throwable) {
                }

                val saved = AppPreferences(this@ContainerCreationService)
                    .addContainerUri(req.targetUri.toString())

                if (req.enrollBiometric) {
                    biometricCopy = pwBytes?.copyOf()
                }
                createdOk = true
                _state.value = CreationUiState.Succeeded(
                    targetUri = req.targetUri,
                    containerId = saved.id,
                    enrollBiometric = req.enrollBiometric,
                    pendingBiometricPasswordBytes = biometricCopy,
                    pendingBiometricPim = req.pim,
                )
            } catch (ce: CancellationException) {
                if (biometricCopy != null) Secrets.wipe(biometricCopy)
                deletePartialTarget(contentResolver, req.targetUri)
                _state.value = CreationUiState.Cancelled()
            } catch (t: Throwable) {
                if (biometricCopy != null) Secrets.wipe(biometricCopy)
                deletePartialTarget(contentResolver, req.targetUri)
                if (cancelFlag.get()) {
                    _state.value = CreationUiState.Cancelled()
                } else {
                    _state.value = CreationUiState.Failed(t.message ?: "Container creation failed.")
                }
            } finally {
                req.wipePassword()
                if (pwBytes != null) Secrets.wipe(pwBytes)
                currentCancelFlag = null
                if (!createdOk && biometricCopy != null) {
                    Secrets.wipe(biometricCopy)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        cancelActiveCreation()
        super.onTimeout(startId)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Container Creation",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress while creating and formatting a DroidCrypter container"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildProgressNotification(statusText: String, fraction: Float): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cancelIntent = Intent(this, ContainerCreationService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPi = PendingIntent.getService(
            this,
            11,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val pct = (fraction.coerceIn(0f, 1f) * 1000).toInt()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Creating DroidCrypter container")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setProgress(1000, pct, false)
            .setContentIntent(contentPi)
            .addAction(0, "Cancel", cancelPi)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "droidcrypter_create_channel"
        const val NOTIFICATION_ID = 1002
        const val ACTION_CANCEL = "app.droidcrypter.action.CANCEL_CREATE"
        const val MIN_NOTIFICATION_INTERVAL_MS = 66L // ~15 updates/sec max
        const val MIN_CONTAINER_SIZE_BYTES = 2L * 1024L * 1024L // 2 MB minimum
        const val FREE_SPACE_HEADROOM_BYTES = 64L * 1024L * 1024L // 64 MB safety headroom

        private val lock = Any()
        private var pendingRequest: CreationJobRequest? = null

        @Volatile
        private var currentCancelFlag: AtomicBoolean? = null

        private val _state = MutableStateFlow<CreationUiState>(CreationUiState.Idle)
        val state: StateFlow<CreationUiState> = _state.asStateFlow()

        fun startCreation(context: Context, request: CreationJobRequest) {
            synchronized(lock) {
                pendingRequest?.wipePassword()
                pendingRequest = request
            }
            _state.value = CreationUiState.Running(
                targetUri = request.targetUri,
                phase = CreatePhase.DERIVING_KEYS,
                fraction = 0f,
                statusText = "Deriving header keys (1/2)...",
            )
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, ContainerCreationService::class.java)
            ContextCompat.startForegroundService(appCtx, intent)
        }

        fun cancelActiveCreation() {
            currentCancelFlag?.set(true)
        }

        fun resetState() {
            val cur = _state.value
            if (cur is CreationUiState.Succeeded) {
                cur.wipePendingBiometricSecret()
            }
            _state.value = CreationUiState.Idle
        }

        private fun consumePendingRequest(): CreationJobRequest? = synchronized(lock) {
            val r = pendingRequest
            pendingRequest = null
            r
        }

        fun formatPhaseText(phase: CreatePhase, fraction: Float): String {
            val pct = (fraction.coerceIn(0f, 1f) * 100).toInt()
            return when (phase) {
                CreatePhase.DERIVING_KEYS ->
                    if (fraction < 0.5f) "Deriving header keys (1/2)..." else "Deriving header keys (2/2)..."
                CreatePhase.WRITING_HEADERS -> "Writing volume headers..."
                CreatePhase.ENCRYPTING_FREE_SPACE -> "Formatting container ($pct%)..."
                CreatePhase.FORMATTING -> "Writing filesystem..."
                CreatePhase.VERIFYING -> "Verifying container..."
                CreatePhase.DONE -> "Done"
            }
        }

        fun checkEnoughHostFreeSpace(
            contentResolver: ContentResolver,
            targetUri: Uri,
            requestedSizeBytes: Long,
        ) {
            try {
                contentResolver.openFileDescriptor(targetUri, "r")?.use { pfd ->
                    checkEnoughFdFreeSpace(pfd.fileDescriptor, requestedSizeBytes)
                }
            } catch (ioe: IOException) {
                if (ioe.message?.contains("Not enough free storage space") == true) throw ioe
            } catch (_: Throwable) {
            }
        }

        fun checkEnoughFdFreeSpace(
            fd: java.io.FileDescriptor,
            requestedSizeBytes: Long,
        ) {
            val stat = try {
                Os.fstatvfs(fd)
            } catch (_: Throwable) {
                null
            } ?: return

            if (stat.f_bavail > 0L && stat.f_frsize > 0L) {
                val availBytes = stat.f_bavail * stat.f_frsize
                val required = requestedSizeBytes + FREE_SPACE_HEADROOM_BYTES
                if (availBytes < required) {
                    throw IOException(
                        "Not enough free storage space: need at least ${VolumeSpace.formatBytes(required)} " +
                            "(container + 64 MB safety margin), but only ${VolumeSpace.formatBytes(availBytes)} is available."
                    )
                }
            }
        }

        fun deletePartialTarget(contentResolver: ContentResolver, uri: Uri) {
            try {
                if (!DocumentsContract.deleteDocument(contentResolver, uri)) {
                    contentResolver.delete(uri, null, null)
                }
            } catch (_: Throwable) {
                try {
                    contentResolver.delete(uri, null, null)
                } catch (_: Throwable) {
                }
            }
        }
    }
}

