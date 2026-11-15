package app.droidcrypter.ui

import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.droidcrypter.editor.EditDecisionList
import app.droidcrypter.editor.EdlHistory
import app.droidcrypter.editor.ExportMode
import app.droidcrypter.editor.ExportProgress
import app.droidcrypter.editor.ThumbnailCache
import app.droidcrypter.editor.TimelineMath
import app.droidcrypter.editor.UsRange
import app.droidcrypter.editor.VideoExportOutcome
import app.droidcrypter.editor.VideoExporter
import app.droidcrypter.engine.ContainerVolume
import app.droidcrypter.engine.FsPaths
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.media.DroidCrypterDataSource
import app.droidcrypter.provider.VolumeUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@UnstableApi
@Composable
fun VideoEditorScreen(
    volume: MountedVolume,
    videoPath: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var durationUs by remember(videoPath) { mutableLongStateOf(0L) }
    var playheadUs by remember(videoPath) { mutableLongStateOf(0L) }
    var isPlaying by remember(videoPath) { mutableStateOf(false) }

    var edl by remember(videoPath) { mutableStateOf(EditDecisionList(0L)) }
    val history = remember(videoPath) { EdlHistory(EditDecisionList(0L)) }
    var historyRevision by remember { mutableIntStateOf(0) }

    var markStartUs by remember(videoPath) { mutableStateOf<Long?>(null) }
    var markEndUs by remember(videoPath) { mutableStateOf<Long?>(null) }

    var previewEdited by remember { mutableStateOf(true) }
    var isCompact by remember { mutableStateOf(false) }
    var pxPerSec by remember { mutableFloatStateOf(TimelineMath.DEFAULT_PX_PER_SEC) }

    var exportedCurrentEdl by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var editCutIndex by remember { mutableStateOf<Int?>(null) }

    var showExportDialog by remember { mutableStateOf(false) }
    var exportMode by remember { mutableStateOf(ExportMode.FAST_STREAM_COPY) }
    var exportProgress by remember { mutableStateOf<ExportProgress?>(null) }
    var exportCancelFlag by remember { mutableStateOf<AtomicBoolean?>(null) }
    var exportStatusMessage by remember { mutableStateOf<String?>(null) }

    val thumbnailCache = remember(volume.id, videoPath) {
        ThumbnailCache(volume = volume, videoPath = videoPath)
    }
    val thumbGen by thumbnailCache.generation.collectAsState()

    val exoPlayer = remember(volume.id, videoPath) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DroidCrypterDataSource.Factory()))
            .build().apply {
                val uri = Uri.parse(VolumeUris.formatMediaUri(volume.id, videoPath))
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            }
    }

    DisposableEffect(exoPlayer, thumbnailCache) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && exoPlayer.duration > 0L) {
                    val durUs = exoPlayer.duration * 1_000L
                    if (durationUs != durUs) {
                        durationUs = durUs
                        if (edl.sourceDurationUs == 0L) {
                            val initEdl = EditDecisionList(durUs)
                            edl = initEdl
                            history.reset(initEdl)
                            historyRevision++
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            thumbnailCache.close()
        }
    }

    // Poll playhead & skip removed ranges when previewEdited is ON
    LaunchedEffect(exoPlayer, previewEdited, edl) {
        while (true) {
            val posUs = (exoPlayer.currentPosition.coerceAtLeast(0L)) * 1_000L
            playheadUs = posUs
            if (previewEdited && edl.hasCuts) {
                val jumpToUs = edl.skipRemovedAt(posUs)
                if (jumpToUs != posUs) {
                    if (durationUs > 0L && jumpToUs >= durationUs) {
                        exoPlayer.pause()
                    } else {
                        exoPlayer.seekTo(jumpToUs / 1_000L)
                        playheadUs = jumpToUs
                    }
                }
            }
            delay(30L)
        }
    }

    fun requestLeave() {
        if (edl.hasCuts && !exportedCurrentEdl) {
            showDiscardDialog = true
        } else {
            onClose()
        }
    }

    BackHandler {
        requestLeave()
    }

    fun pushEdl(next: EditDecisionList) {
        edl = history.push(next)
        exportedCurrentEdl = false
        historyRevision++
    }

    val videoFileName = remember(videoPath) { FsPaths.name(videoPath) }

    Scaffold(
        containerColor = VaultColors.Background,
        contentColor = VaultColors.TextPrimary,
        topBar = {
            Surface(
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
                        onClick = { requestLeave() },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Close video editor" },
                    ) {
                        Text("← Back", color = VaultColors.Primary)
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = videoFileName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Original: ${TimelineMath.formatTimeUs(edl.sourceDurationUs)} · " +
                                "Removed: ${TimelineMath.formatTimeUs(edl.removedDurationUs)} · " +
                                "Final: ${TimelineMath.formatTimeUs(edl.outputDurationUs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.TextSecondary,
                        )
                    }

                    TextButton(
                        onClick = { isCompact = !isCompact },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics {
                                contentDescription = "Toggle compact editor layout"
                                stateDescription = if (isCompact) "Compact layout" else "Full layout"
                            },
                    ) {
                        Text(if (isCompact) "Full UI" else "Compact", color = VaultColors.Primary)
                    }

                    Button(
                        onClick = { showExportDialog = true },
                        enabled = edl.outputDurationUs > 0L && exportProgress == null && volume.writeSupport == null,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Primary),
                    ) {
                        Text("Export", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {

        HorizontalDivider(color = VaultColors.Outline)

        if (exportStatusMessage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VaultColors.SurfaceElevated)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = exportStatusMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColors.Primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { exportStatusMessage = null },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Text("Dismiss")
                }
            }
            HorizontalDivider(color = VaultColors.Outline)
        }

        // ---- Video Preview ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isCompact) 170.dp else 230.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        (videoSurfaceView as? SurfaceView)?.setSecure(true)
                    }
                },
                update = { view ->
                    view.player = exoPlayer
                    (view.videoSurfaceView as? SurfaceView)?.setSecure(true)
                },
            )
        }

        // ---- Transport & Preview Edited Toggle ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VaultColors.Surface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Playhead: ${TimelineMath.formatTimeUs(playheadUs)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = VaultColors.Primary,
            )

            Row(
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Switch) { previewEdited = !previewEdited }
                    .padding(horizontal = 6.dp)
                    .semantics {
                        contentDescription = "Preview edited video skipping cut regions"
                        stateDescription = if (previewEdited) "On" else "Off"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Preview edited",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Switch(
                    checked = previewEdited,
                    onCheckedChange = null,
                )
            }
        }

        HorizontalDivider(color = VaultColors.Outline)

        // ---- Zoomable Scrollable Thumbnail Timeline (section 7.8) ----
        val scrollState = rememberScrollState()
        var viewportWidthPx by remember { mutableIntStateOf(600) }
        val slotWidthDp = 56.dp
        val slotWidthPx = with(density) { slotWidthDp.toPx() }.coerceAtLeast(24f)

        val totalWidthPx = TimelineMath.timelineWidthPx(durationUs, pxPerSec)
        val totalWidthDp = with(density) { totalWidthPx.toDp() }.coerceAtLeast(280.dp)

        // Request visible thumbnail buckets + 1 slot on each side
        LaunchedEffect(durationUs, pxPerSec, scrollState.value, viewportWidthPx) {
            if (durationUs > 0L) {
                val visibleBuckets = TimelineMath.visibleSlotTimestampsUs(
                    durationUs = durationUs,
                    pxPerSec = pxPerSec,
                    slotWidthPx = slotWidthPx,
                    scrollPx = scrollState.value.toFloat(),
                    viewportWidthPx = viewportWidthPx.toFloat(),
                )
                thumbnailCache.requestTimestamps(visibleBuckets)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(VaultColors.SurfaceElevated)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { pxPerSec = TimelineMath.zoomOut(pxPerSec) },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Zoom out timeline" },
                    ) {
                        Text("− Zoom")
                    }
                    OutlinedButton(
                        onClick = { pxPerSec = TimelineMath.zoomIn(pxPerSec) },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Zoom in timeline" },
                    ) {
                        Text("+ Zoom")
                    }
                }

                val pendingMarkText = when {
                    markStartUs != null && markEndUs != null ->
                        "Mark: ${TimelineMath.formatTimeUs(markStartUs!!)} – ${TimelineMath.formatTimeUs(markEndUs!!)}"
                    markStartUs != null ->
                        "Start: ${TimelineMath.formatTimeUs(markStartUs!!)}"
                    markEndUs != null ->
                        "End: ${TimelineMath.formatTimeUs(markEndUs!!)}"
                    else -> "Tap timeline to seek"
                }
                Text(
                    text = pendingMarkText,
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColors.StarAmber,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .onSizeChanged { viewportWidthPx = it.width.coerceAtLeast(1) }
                    .horizontalScroll(scrollState),
            ) {
                Box(
                    modifier = Modifier
                        .width(totalWidthDp)
                        .fillMaxHeight()
                        .background(VaultColors.Background)
                        .pointerInput(durationUs, pxPerSec) {
                            detectTapGestures { tapOffset ->
                                val targetUs = TimelineMath.xToTimeUs(tapOffset.x, pxPerSec)
                                    .coerceIn(0L, durationUs.coerceAtLeast(0L))
                                playheadUs = targetUs
                                exoPlayer.seekTo(targetUs / 1_000L)
                            }
                        },
                ) {
                    // Render cached thumbnails for visible slots
                    val visibleSlotCount = (totalWidthPx / slotWidthPx).toInt().coerceAtLeast(1)
                    val firstSlot = ((scrollState.value / slotWidthPx).toInt() - 1).coerceAtLeast(0)
                    val lastSlot = (((scrollState.value + viewportWidthPx) / slotWidthPx).toInt() + 1)
                        .coerceAtMost(visibleSlotCount)

                    // Trigger re-read when thumbGen increments
                    val unusedGen = thumbGen
                    if (unusedGen >= 0 && durationUs > 0L) {
                        for (slot in firstSlot..lastSlot) {
                            val centerX = (slot + 0.5f) * slotWidthPx
                            val tUs = TimelineMath.xToTimeUs(centerX, pxPerSec).coerceIn(0L, durationUs)
                            val bmp = thumbnailCache.getCached(tUs)
                            if (bmp != null && !bmp.isRecycled) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .offset { IntOffset((slot * slotWidthPx).roundToInt(), 16) }
                                        .width(slotWidthDp)
                                        .height(60.dp),
                                )
                            }
                        }
                    }

                    // Overlay ruler, cut regions, pending mark, and playhead
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Cut ranges (red overlay)
                        for (cut in edl.removedRanges) {
                            val x1 = TimelineMath.timeUsToX(cut.startUs, pxPerSec)
                            val x2 = TimelineMath.timeUsToX(cut.endUs, pxPerSec)
                            drawRect(
                                color = VaultColors.Danger.copy(alpha = 0.48f),
                                topLeft = Offset(x1, 0f),
                                size = Size((x2 - x1).coerceAtLeast(2f), size.height),
                            )
                        }

                        // Pending mark range (amber overlay)
                        val ms = markStartUs
                        val me = markEndUs
                        if (ms != null && me != null) {
                            val lo = minOf(ms, me)
                            val hi = maxOf(ms, me)
                            val x1 = TimelineMath.timeUsToX(lo, pxPerSec)
                            val x2 = TimelineMath.timeUsToX(hi, pxPerSec)
                            drawRect(
                                color = VaultColors.StarAmber.copy(alpha = 0.35f),
                                topLeft = Offset(x1, 0f),
                                size = Size((x2 - x1).coerceAtLeast(2f), size.height),
                            )
                        } else if (ms != null) {
                            val x = TimelineMath.timeUsToX(ms, pxPerSec)
                            drawLine(
                                color = VaultColors.StarAmber,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 3f,
                            )
                        } else if (me != null) {
                            val x = TimelineMath.timeUsToX(me, pxPerSec)
                            drawLine(
                                color = VaultColors.StarAmber,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 3f,
                            )
                        }

                        // Playhead line (cyan)
                        val playX = TimelineMath.timeUsToX(playheadUs, pxPerSec)
                        drawLine(
                            color = VaultColors.Primary,
                            start = Offset(playX, 0f),
                            end = Offset(playX, size.height),
                            strokeWidth = 4f,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = VaultColors.Outline)

        // ---- Mark Start / Mark End / Add Cut / Undo / Redo / Clear All ----
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(VaultColors.Surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = { markStartUs = playheadUs },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Mark Start")
            }

            OutlinedButton(
                onClick = { markEndUs = playheadUs },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Mark End")
            }

            Button(
                onClick = {
                    val s = markStartUs ?: return@Button
                    val e = markEndUs ?: return@Button
                    if (s != e) {
                        // EditDecisionList.addCut automatically swaps if s > e and merges overlaps!
                        pushEdl(edl.addCut(s, e))
                        markStartUs = null
                        markEndUs = null
                    }
                },
                enabled = markStartUs != null && markEndUs != null && markStartUs != markEndUs,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Danger),
            ) {
                Text("✂ Add Cut", fontWeight = FontWeight.SemiBold)
            }

            val canUndo = remember(historyRevision) { history.canUndo }
            val canRedo = remember(historyRevision) { history.canRedo }

            TextButton(
                onClick = {
                    if (history.canUndo) {
                        edl = history.undo()
                        exportedCurrentEdl = false
                        historyRevision++
                    }
                },
                enabled = canUndo,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Undo cut change" },
            ) {
                Text("↶ Undo")
            }

            TextButton(
                onClick = {
                    if (history.canRedo) {
                        edl = history.redo()
                        exportedCurrentEdl = false
                        historyRevision++
                    }
                },
                enabled = canRedo,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Redo cut change" },
            ) {
                Text("↷ Redo")
            }

            if (edl.hasCuts) {
                TextButton(
                    onClick = { pushEdl(edl.clearAll()) },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Text("Clear all", color = VaultColors.Danger)
                }
            }
        }

        HorizontalDivider(color = VaultColors.Outline)

        // ---- Cut List ----
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(
                top = 6.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (edl.removedRanges.isEmpty()) {
                item(key = "no_cuts") {
                    Text(
                        text = "No cuts added yet. Seek to a position, tap \"Mark Start\" and \"Mark End\", then tap \"✂ Add Cut\" to remove a range.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.TextSecondary,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            } else {
                itemsIndexed(edl.removedRanges) { idx, cut ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = VaultColors.Surface,
                            contentColor = VaultColors.TextPrimary,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, VaultColors.Outline, RoundedCornerShape(10.dp)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Cut #${idx + 1}: ${TimelineMath.formatTimeUs(cut.startUs)} – ${TimelineMath.formatTimeUs(cut.endUs)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = VaultColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Removes ${TimelineMath.formatTimeUs(cut.durationUs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VaultColors.TextSecondary,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(
                                    onClick = {
                                        playheadUs = cut.startUs
                                        exoPlayer.seekTo(cut.startUs / 1_000L)
                                    },
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                        .semantics { contentDescription = "Jump to cut ${idx + 1}" },
                                 ) {
                                    Text("Jump", color = VaultColors.Primary)
                                }

                                TextButton(
                                    onClick = { editCutIndex = idx },
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                        .semantics { contentDescription = "Edit cut ${idx + 1} timestamps" },
                                ) {
                                    Text("Edit")
                                }

                                TextButton(
                                    onClick = { pushEdl(edl.removeCutAt(idx)) },
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                        .semantics { contentDescription = "Delete cut ${idx + 1}" },
                                ) {
                                    Text("Delete", color = VaultColors.Danger)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

    // ---- Edit Cut Timestamp Dialog (`HH:MM:SS.mmm`) ----
    val editingIdx = editCutIndex
    if (editingIdx != null && editingIdx in edl.removedRanges.indices) {
        val currentCut = edl.removedRanges[editingIdx]
        var startStr by remember(currentCut) { mutableStateOf(TimelineMath.formatTimeUs(currentCut.startUs)) }
        var endStr by remember(currentCut) { mutableStateOf(TimelineMath.formatTimeUs(currentCut.endUs)) }
        var editErr by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { editCutIndex = null },
            title = { Text("Edit Cut #${editingIdx + 1}") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = startStr,
                        onValueChange = {
                            startStr = it
                            editErr = null
                        },
                        label = { Text("Start (HH:MM:SS.mmm)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = endStr,
                        onValueChange = {
                            endStr = it
                            editErr = null
                        },
                        label = { Text("End (HH:MM:SS.mmm)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (editErr != null) {
                        Text(editErr!!, color = VaultColors.Danger, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sUs = TimelineMath.parseTimeUs(startStr)
                        val eUs = TimelineMath.parseTimeUs(endStr)
                        if (sUs == null || eUs == null) {
                            editErr = "Enter valid timestamps in HH:MM:SS.mmm, MM:SS.mmm, or seconds."
                            return@Button
                        }
                        if (sUs == eUs) {
                            editErr = "Start and end timestamps cannot be identical."
                            return@Button
                        }
                        pushEdl(edl.updateCutAt(editingIdx, sUs, eUs))
                        editCutIndex = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Primary),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editCutIndex = null }) {
                    Text("Cancel")
                }
            },
            containerColor = VaultColors.Surface,
        )
    }

    // ---- Export Dialog (Fast vs. Precise + Outer Volume Hidden-Volume Warning) ----
    if (showExportDialog) {
        val isOuterVolume = (volume as? ContainerVolume)?.isHidden == false
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export edited video to vault") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Output will be encrypted inside an unlinked XTS-AES-256 scratch file, validated, and saved into the current folder as <name>_edited.mp4 (original file is untouched).",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.TextSecondary,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = exportMode == ExportMode.FAST_STREAM_COPY,
                            onClick = { exportMode = ExportMode.FAST_STREAM_COPY },
                            label = { Text("Fast (stream copy)") },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        )
                        FilterChip(
                            selected = exportMode == ExportMode.PRECISE_REENCODE,
                            onClick = { exportMode = ExportMode.PRECISE_REENCODE },
                            label = { Text("Precise (re-encode)") },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        )
                    }

                    Text(
                        text = if (exportMode == ExportMode.FAST_STREAM_COPY) {
                            "Fast mode copies compressed packets without re-encoding. Each retained segment's start is snapped forward to the next keyframe."
                        } else {
                            "Precise mode re-encodes the retained ranges via Media3 Transformer for frame-exact cut points."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )

                    if (isOuterVolume) {
                        Text(
                            text = "Hidden-volume note: If this container holds a hidden volume, writing new files into the outer volume can overwrite the hidden volume area.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.StarAmber,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportDialog = false
                        exoPlayer.pause()
                        val cancelToken = AtomicBoolean(false)
                        exportCancelFlag = cancelToken
                        val chosenMode = exportMode
                        val currentEdl = edl
                        scope.launch {
                            try {
                                val outcome: VideoExportOutcome = withContext(Dispatchers.IO) {
                                    VideoExporter.exportEditedVideo(
                                        context = context,
                                        volume = volume,
                                        sourcePath = videoPath,
                                        edl = currentEdl,
                                        mode = chosenMode,
                                        isCancelled = { cancelToken.get() },
                                        onProgress = { exportProgress = it },
                                    )
                                }
                                exportedCurrentEdl = true
                                val snapNote = if (outcome.snappedToKeyframes) {
                                    " (start points snapped to nearest keyframes)"
                                } else {
                                    ""
                                }
                                exportStatusMessage = "Saved ${outcome.savedNode.name}$snapNote."
                            } catch (t: Throwable) {
                                exportStatusMessage = t.message ?: "Export failed."
                            } finally {
                                exportProgress = null
                                exportCancelFlag = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Primary),
                ) {
                    Text("Start Export")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = VaultColors.Surface,
        )
    }

    // ---- Export Progress Dialog ----
    val expProg = exportProgress
    if (expProg != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting edited video") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(expProg.message, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { expProg.fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = VaultColors.Primary,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { exportCancelFlag?.set(true) },
                ) {
                    Text("Cancel", color = VaultColors.Danger)
                }
            },
            containerColor = VaultColors.Surface,
        )
    }

    // ---- Discard Cuts Confirmation Dialog ----
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard cuts?") },
            text = {
                Text(
                    "You have un-exported cuts on the timeline. Because edit sessions are never persisted to disk, leaving now will discard them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardDialog = false
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Danger),
                ) {
                    Text("Discard & Leave")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep Editing")
                }
            },
            containerColor = VaultColors.Surface,
        )
    }
}

