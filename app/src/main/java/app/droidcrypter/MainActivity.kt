package app.droidcrypter

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi
import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.engine.VolumeRegistry
import app.droidcrypter.service.MountService
import app.droidcrypter.settings.AppPreferences
import app.droidcrypter.ui.BrowserScreen
import app.droidcrypter.ui.CreationWizardScreen
import app.droidcrypter.ui.MediaPlayerScreen
import app.droidcrypter.ui.PhotoViewerScreen
import app.droidcrypter.ui.VaultListScreen
import app.droidcrypter.ui.DroidCrypterTheme
import app.droidcrypter.ui.VideoEditorScreen

sealed interface AppScreen {
    val activeVolumeId: String? get() = null

    data object VaultList : AppScreen
    data object CreateWizard : AppScreen

    data class Browser(
        val volume: MountedVolume,
        val currentDir: String = "/",
        val searchQuery: String? = null,
        /** File to scroll to and highlight: the photo/video the user just came back from. */
        val lastViewedPath: String? = null,
    ) : AppScreen {
        override val activeVolumeId: String get() = volume.id
    }

    data class PhotoViewer(
        val volume: MountedVolume,
        val returnDir: String,
        val images: List<FsNode>,
        val initialIndex: Int,
    ) : AppScreen {
        override val activeVolumeId: String get() = volume.id
    }

    data class MediaPlayer(
        val volume: MountedVolume,
        val returnDir: String,
        val playlist: List<FsNode>,
        val initialIndex: Int,
    ) : AppScreen {
        override val activeVolumeId: String get() = volume.id
    }

    data class VideoEditor(
        val volume: MountedVolume,
        val returnDir: String,
        val videoPath: String,
    ) : AppScreen {
        override val activeVolumeId: String get() = volume.id
    }
}

/**
 * Single-activity host (`FragmentActivity` for `BiometricPrompt`) with `FLAG_SECURE`,
 * `filterTouchesWhenObscured = true`, configuration-change retention, and automatic
 * return to the vault list whenever an open volume is locked (sections 4.5 & 7.1).
 */
@UnstableApi
class MainActivity : FragmentActivity() {

    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        // Section 4.5: set FLAG_SECURE BEFORE super.onCreate so no frame is ever unguarded.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.decorView.filterTouchesWhenObscured = true

        prefs = AppPreferences(this)

        setContent {
            DroidCrypterTheme {
                val settings by prefs.settings.collectAsState()
                val unlockedByContainerId = remember { mutableStateMapOf<String, MountedVolume>() }
                var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.VaultList) }
                var lastBrowserDir by remember { mutableStateOf("/") }
                // Open search query (null = search closed), restored when a viewer returns.
                var lastBrowserSearch by remember { mutableStateOf<String?>(null) }

                // Observe VolumeRegistry: if any volume is unmounted (via Lock, Lock all,
                // screen-off, or service timeout), drop it from unlockedByContainerId and
                // return any open screen for that volume to the vault list immediately.
                DisposableEffect(Unit) {
                    val listener: (List<MountedVolume>) -> Unit = { activeVolumes ->
                        runOnUiThread {
                            val activeIds = activeVolumes.map { it.id }.toSet()
                            val toRemove = unlockedByContainerId.entries
                                .filter { it.value.id !in activeIds }
                                .map { it.key }
                            for (cid in toRemove) {
                                unlockedByContainerId.remove(cid)
                            }
                            val openVolId = currentScreen.activeVolumeId
                            if (openVolId != null && openVolId !in activeIds) {
                                currentScreen = AppScreen.VaultList
                            }
                        }
                    }
                    VolumeRegistry.addChangeListener(listener)
                    onDispose {
                        VolumeRegistry.removeChangeListener(listener)
                    }
                }

                when (val screen = currentScreen) {
                    is AppScreen.VaultList -> {
                        VaultListScreen(
                            activity = this@MainActivity,
                            prefs = prefs,
                            settings = settings,
                            unlockedByContainerId = unlockedByContainerId,
                            onContainerUnlocked = { containerId, vol ->
                                unlockedByContainerId[containerId] = vol
                            },
                            onContainerLocked = { containerId ->
                                val vol = unlockedByContainerId.remove(containerId)
                                if (vol != null) {
                                    VolumeRegistry.unmount(vol.id)
                                    MountService.syncWithRegistry(this@MainActivity)
                                }
                            },
                            onLockAll = {
                                unlockedByContainerId.clear()
                                VolumeRegistry.unmountAll()
                                MountService.syncWithRegistry(this@MainActivity)
                                currentScreen = AppScreen.VaultList
                            },
                            onBrowseVolume = { vol ->
                                lastBrowserDir = "/"
                                lastBrowserSearch = null
                                currentScreen = AppScreen.Browser(vol, "/")
                            },
                            onOpenCreateWizard = {
                                currentScreen = AppScreen.CreateWizard
                            },
                        )
                    }

                    is AppScreen.CreateWizard -> {
                        CreationWizardScreen(
                            activity = this@MainActivity,
                            onFinished = {
                                currentScreen = AppScreen.VaultList
                            },
                        )
                    }

                    is AppScreen.Browser -> {
                        BrowserScreen(
                            volume = screen.volume,
                            prefs = prefs,
                            settings = settings,
                            initialDir = screen.currentDir,
                            initialSearchQuery = screen.searchQuery,
                            lastViewedPath = screen.lastViewedPath,
                            onDirChanged = { dir -> lastBrowserDir = dir },
                            onSearchChanged = { query -> lastBrowserSearch = query },
                            onOpenPhotoViewer = { images, idx ->
                                currentScreen = AppScreen.PhotoViewer(
                                    volume = screen.volume,
                                    returnDir = lastBrowserDir,
                                    images = images,
                                    initialIndex = idx,
                                )
                            },
                            onOpenMediaPlayer = { playlist, idx ->
                                currentScreen = AppScreen.MediaPlayer(
                                    volume = screen.volume,
                                    returnDir = lastBrowserDir,
                                    playlist = playlist,
                                    initialIndex = idx,
                                )
                            },
                            onOpenVideoEditor = { videoPath ->
                                currentScreen = AppScreen.VideoEditor(
                                    volume = screen.volume,
                                    returnDir = lastBrowserDir,
                                    videoPath = videoPath,
                                )
                            },
                            onLockVolume = {
                                VolumeRegistry.unmount(screen.volume.id)
                                MountService.syncWithRegistry(this@MainActivity)
                                currentScreen = AppScreen.VaultList
                            },
                            onCloseBrowser = {
                                currentScreen = AppScreen.VaultList
                            },
                        )
                    }

                    is AppScreen.PhotoViewer -> {
                        PhotoViewerScreen(
                            volume = screen.volume,
                            images = screen.images,
                            initialIndex = screen.initialIndex,
                            showStars = settings.showStars,
                            onClose = { lastViewed ->
                                currentScreen = AppScreen.Browser(
                                    screen.volume, screen.returnDir, lastBrowserSearch, lastViewed,
                                )
                            },
                        )
                    }

                    is AppScreen.MediaPlayer -> {
                        MediaPlayerScreen(
                            volume = screen.volume,
                            playlist = screen.playlist,
                            initialIndex = screen.initialIndex,
                            onEditVideo = { videoPath ->
                                currentScreen = AppScreen.VideoEditor(
                                    volume = screen.volume,
                                    returnDir = screen.returnDir,
                                    videoPath = videoPath,
                                )
                            },
                            onClose = { lastViewed ->
                                currentScreen = AppScreen.Browser(
                                    screen.volume, screen.returnDir, lastBrowserSearch, lastViewed,
                                )
                            },
                        )
                    }

                    is AppScreen.VideoEditor -> {
                        VideoEditorScreen(
                            volume = screen.volume,
                            videoPath = screen.videoPath,
                            onClose = {
                                currentScreen = AppScreen.Browser(
                                    screen.volume, screen.returnDir, lastBrowserSearch, screen.videoPath,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
