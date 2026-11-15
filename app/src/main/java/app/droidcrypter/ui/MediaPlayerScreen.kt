package app.droidcrypter.ui

import android.net.Uri
import android.view.SurfaceView
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.droidcrypter.browse.FileClassification
import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.media.DroidCrypterDataSource
import app.droidcrypter.provider.VolumeUris

/**
 * In-memory video & audio player over [DroidCrypterDataSource] with folder playlist in browser order,
 * secure `SurfaceView` (`setSecure(true)`), Previous/Next, auto-advance, and `✂ Edit` button for
 * videos (sections 4.5 & 7.7).
 */
@UnstableApi
@Composable
fun MediaPlayerScreen(
    volume: MountedVolume,
    playlist: List<FsNode>,
    initialIndex: Int,
    onEditVideo: (String) -> Unit,
    /** Called with the path of the track on screen when leaving, so the list can return to it. */
    onClose: (lastViewedPath: String?) -> Unit,
) {
    val lastViewedPath = remember {
        mutableStateOf(playlist.getOrNull(initialIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0)))?.path)
    }
    BackHandler { onClose(lastViewedPath.value) }

    if (playlist.isEmpty()) {
        LaunchedEffect(Unit) { onClose(null) }
        return
    }

    val context = LocalContext.current
    var currentIndex by remember { mutableIntStateOf(initialIndex.coerceIn(0, playlist.lastIndex)) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    // Mirrors the PlayerView controller, so one tap shows or hides every control together.
    var controlsVisible by remember { mutableStateOf(true) }
    FullscreenSystemBars(fullscreen)
    BackHandler(enabled = fullscreen) { fullscreen = false }

    val exoPlayer = remember(volume.id, playlist) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DroidCrypterDataSource.Factory()))
            .build().apply {
                val items = playlist.map { node ->
                    val uri = Uri.parse(VolumeUris.formatMediaUri(volume.id, node.path))
                    MediaItem.Builder()
                        .setUri(uri)
                        .setMediaId(node.path)
                        .build()
                }
                setMediaItems(items, initialIndex.coerceIn(0, playlist.lastIndex), 0L)
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = exoPlayer.currentMediaItemIndex.coerceIn(0, playlist.lastIndex)
                playbackError = null
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.message ?: "Playback error (${error.errorCodeName})"
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    val currentNode = playlist[currentIndex.coerceIn(0, playlist.lastIndex)]
    SideEffect { lastViewedPath.value = currentNode.path }
    val isEditableVideo = FileClassification.isEditableVideo(currentNode.name) && volume.writeSupport == null

    fun skipTo(index: Int) {
        if (index in 0..playlist.lastIndex && index != currentIndex) {
            currentIndex = index
            exoPlayer.seekToDefaultPosition(index)
            exoPlayer.play()
        }
    }

    Scaffold(
        containerColor = VaultColors.Background,
        contentColor = VaultColors.TextPrimary,
        topBar = {
            if (!fullscreen) Surface(
                color = VaultColors.Surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                TextButton(
                    onClick = { onClose(currentNode.path) },
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = "Close media player" },
                ) {
                    Text("← Back", color = VaultColors.Primary)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = currentNode.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = {
                                if (currentIndex > 0) {
                                    val next = currentIndex - 1
                                    currentIndex = next
                                    exoPlayer.seekToDefaultPosition(next)
                                    exoPlayer.play()
                                }
                            },
                            enabled = currentIndex > 0,
                            modifier = Modifier
                                .size(32.dp)
                                .semantics { contentDescription = "Previous track" },
                        ) {
                            Text(
                                text = "◀",
                                color = if (currentIndex > 0) VaultColors.Primary else VaultColors.Outline,
                            )
                        }

                        Text(
                            text = "Track ${currentIndex + 1} of ${playlist.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.TextSecondary,
                        )

                        IconButton(
                            onClick = {
                                if (currentIndex < playlist.lastIndex) {
                                    val next = currentIndex + 1
                                    currentIndex = next
                                    exoPlayer.seekToDefaultPosition(next)
                                    exoPlayer.play()
                                }
                            },
                            enabled = currentIndex < playlist.lastIndex,
                            modifier = Modifier
                                .size(32.dp)
                                .semantics { contentDescription = "Next track" },
                        ) {
                            Text(
                                text = "▶",
                                color = if (currentIndex < playlist.lastIndex) VaultColors.Primary else VaultColors.Outline,
                            )
                        }
                    }
                }

                EnterFullscreenButton(onClick = { fullscreen = true })

                if (isEditableVideo) {
                    Button(
                        onClick = {
                            exoPlayer.pause()
                            onEditVideo(currentNode.path)
                        },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Open video editor for ${currentNode.name}" },
                        colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Primary),
                    ) {
                        Text("✂ Edit", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    },
    bottomBar = {
            // Playlist Prev / Next bar (full screen uses the overlay strip instead)
            if (!fullscreen) Surface(
                color = VaultColors.Surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            if (currentIndex > 0) {
                                val next = currentIndex - 1
                                currentIndex = next
                                exoPlayer.seekToDefaultPosition(next)
                                exoPlayer.play()
                            }
                        },
                        enabled = currentIndex > 0,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VaultColors.TextPrimary),
                    ) {
                        Text(
                            text = "⏮ Previous",
                            color = if (currentIndex > 0) VaultColors.TextPrimary else VaultColors.TextSecondary,
                        )
                    }

                    Text(
                        text = "${currentIndex + 1} / ${playlist.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.TextSecondary,
                    )

                    OutlinedButton(
                        onClick = {
                            if (currentIndex < playlist.lastIndex) {
                                val next = currentIndex + 1
                                currentIndex = next
                                exoPlayer.seekToDefaultPosition(next)
                                exoPlayer.play()
                            }
                        },
                        enabled = currentIndex < playlist.lastIndex,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VaultColors.TextPrimary),
                    ) {
                        Text(
                            text = "Next ⏭",
                            color = if (currentIndex < playlist.lastIndex) VaultColors.TextPrimary else VaultColors.TextSecondary,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding(),
                ),
        ) {
            if (playbackError != null) {
                Text(
                    text = playbackError!!,
                    color = VaultColors.Danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(VaultColors.SurfaceElevated)
                        .padding(12.dp),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            (videoSurfaceView as? SurfaceView)?.setSecure(true)
                            setControllerVisibilityListener(
                                PlayerView.ControllerVisibilityListener { visibility ->
                                    controlsVisible = visibility == View.VISIBLE
                                },
                            )
                        }
                    },
                    update = { view ->
                        view.player = exoPlayer
                        (view.videoSurfaceView as? SurfaceView)?.setSecure(true)
                    },
                )

                // PlayerView letterboxes the video (RESIZE_MODE_FIT), so in full screen it grows
                // to the screen edge on one axis and keeps its aspect ratio on the other.
                if (fullscreen && (controlsVisible || playbackError != null)) {
                    FullscreenOverlayBar(
                        title = currentNode.name,
                        position = "${currentIndex + 1} / ${playlist.size}",
                        hasPrevious = currentIndex > 0,
                        hasNext = currentIndex < playlist.lastIndex,
                        previousDescription = "Previous track",
                        nextDescription = "Next track",
                        onPrevious = { skipTo(currentIndex - 1) },
                        onNext = { skipTo(currentIndex + 1) },
                        onExitFullscreen = { fullscreen = false },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }
}

