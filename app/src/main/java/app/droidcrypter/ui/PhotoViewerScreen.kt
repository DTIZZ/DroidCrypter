package app.droidcrypter.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.droidcrypter.browse.RatingsCodec
import app.droidcrypter.browse.RatingsRegistry
import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.engine.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

enum class CanvasTheme(val label: String) {
    DARK("Dark"),
    GRID("Grid"),
    LIGHT("Light"),
}

/**
 * In-memory photo viewer with HorizontalPager, pinch-zoom (1x..8x), double-tap zoom (1x <-> 2.5x),
 * explicit Previous / Next navigation buttons, transparent PNG grid/light canvas support, encoded
 * byte wiping, and mutable Bitmap pixel zeroing (`eraseColor(0)`) as soon as a page leaves
 * composition (section 7.6).
 */
@Composable
fun PhotoViewerScreen(
    volume: MountedVolume,
    images: List<FsNode>,
    initialIndex: Int,
    showStars: Boolean,
    /** Called with the path of the image on screen when leaving, so the list can return to it. */
    onClose: (lastViewedPath: String?) -> Unit,
) {
    val lastViewedPath = remember {
        mutableStateOf(images.getOrNull(initialIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0)))?.path)
    }
    BackHandler { onClose(lastViewedPath.value) }

    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onClose(null) }
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, images.lastIndex),
        pageCount = { images.size },
    )
    val ratingsStore = remember(volume.id) { RatingsRegistry.getOrCreate(volume) }
    val ratingsMap by ratingsStore.ratingsFlow.collectAsState()
    var canvasTheme by remember { mutableStateOf(CanvasTheme.DARK) }
    var fullscreen by remember { mutableStateOf(false) }
    // In full screen a single tap on the image shows or hides the overlay strip.
    var overlayVisible by remember { mutableStateOf(true) }
    FullscreenSystemBars(fullscreen)
    BackHandler(enabled = fullscreen) { fullscreen = false }

    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val maxTargetSidePx = remember(config.screenWidthDp, config.screenHeightDp, density) {
        val maxScreenSidePx = with(density) {
            max(config.screenWidthDp.dp.roundToPx(), config.screenHeightDp.dp.roundToPx())
        }
        min(2 * maxScreenSidePx, 4096).coerceAtLeast(1024)
    }

    val currentNode = images[pagerState.currentPage.coerceIn(0, images.lastIndex)]
    SideEffect { lastViewedPath.value = currentNode.path }
    val displayedStars = RatingsCodec.displayedStars(ratingsMap[currentNode.path])
    val isWritable = volume.writeSupport == null

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
                        .semantics { contentDescription = "Close photo viewer" },
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
                                if (pagerState.currentPage > 0) {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                }
                            },
                            enabled = pagerState.currentPage > 0,
                            modifier = Modifier
                                .size(32.dp)
                                .semantics { contentDescription = "Previous image" },
                        ) {
                            Text(
                                text = "◀",
                                color = if (pagerState.currentPage > 0) VaultColors.Primary else VaultColors.Outline,
                            )
                        }

                        Text(
                            text = "${pagerState.currentPage + 1} of ${images.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.TextSecondary,
                        )

                        IconButton(
                            onClick = {
                                if (pagerState.currentPage < images.lastIndex) {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    }
                                }
                            },
                            enabled = pagerState.currentPage < images.lastIndex,
                            modifier = Modifier
                                .size(32.dp)
                                .semantics { contentDescription = "Next image" },
                        ) {
                            Text(
                                text = "▶",
                                color = if (pagerState.currentPage < images.lastIndex) VaultColors.Primary else VaultColors.Outline,
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Canvas background toggle for transparent PNGs (Dark -> Grid -> Light)
                    TextButton(
                        onClick = {
                            canvasTheme = when (canvasTheme) {
                                CanvasTheme.DARK -> CanvasTheme.GRID
                                CanvasTheme.GRID -> CanvasTheme.LIGHT
                                CanvasTheme.LIGHT -> CanvasTheme.DARK
                            }
                        },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                            .semantics {
                                contentDescription = "Canvas background mode: ${canvasTheme.label}"
                            },
                    ) {
                        Text(
                            text = canvasTheme.label,
                            color = VaultColors.TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }

                    EnterFullscreenButton(
                        onClick = {
                            overlayVisible = true
                            fullscreen = true
                        },
                    )

                    if (showStars) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            for (k in 1..3) {
                                val isLit = k <= displayedStars
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clickable(
                                            enabled = isWritable,
                                            role = Role.Button,
                                            onClick = { ratingsStore.toggleStar(currentNode.path, k) },
                                        )
                                        .semantics {
                                            contentDescription = "Rate ${currentNode.name} $k of 3 stars"
                                            stateDescription = if (isLit) "Selected" else "Not selected"
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = if (isLit) "★" else "☆",
                                        color = if (isLit) VaultColors.StarAmber else VaultColors.TextSecondary,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    },
    bottomBar = {
            // Bottom navigation bar for jumping back and forth
            if (images.size > 1 && !fullscreen) {
                Surface(
                    color = VaultColors.Surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (pagerState.currentPage > 0) {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                }
                            },
                            enabled = pagerState.currentPage > 0,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = VaultColors.TextPrimary),
                        ) {
                            Text(
                                text = "◀ Previous",
                                color = if (pagerState.currentPage > 0) VaultColors.TextPrimary else VaultColors.TextSecondary,
                            )
                        }

                        Text(
                            text = "${pagerState.currentPage + 1} / ${images.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VaultColors.TextSecondary,
                        )

                        OutlinedButton(
                            onClick = {
                                if (pagerState.currentPage < images.lastIndex) {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    }
                                }
                            },
                            enabled = pagerState.currentPage < images.lastIndex,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = VaultColors.TextPrimary),
                        ) {
                            Text(
                                text = "Next ▶",
                                color = if (pagerState.currentPage < images.lastIndex) VaultColors.TextPrimary else VaultColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding(),
                ),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { pageIdx ->
                val node = images[pageIdx]
                PhotoPage(
                    volume = volume,
                    node = node,
                    maxSidePx = maxTargetSidePx,
                    canvasTheme = canvasTheme,
                    onTap = { if (fullscreen) overlayVisible = !overlayVisible },
                )
            }

            // Images are drawn with ContentScale.Fit, so in full screen they grow to the screen
            // edge on one axis and keep their aspect ratio on the other.
            if (fullscreen && overlayVisible) {
                val page = pagerState.currentPage
                FullscreenOverlayBar(
                    title = currentNode.name,
                    position = "${page + 1} / ${images.size}",
                    hasPrevious = page > 0,
                    hasNext = page < images.lastIndex,
                    previousDescription = "Previous image",
                    nextDescription = "Next image",
                    onPrevious = {
                        if (page > 0) coroutineScope.launch { pagerState.animateScrollToPage(page - 1) }
                    },
                    onNext = {
                        if (page < images.lastIndex) {
                            coroutineScope.launch { pagerState.animateScrollToPage(page + 1) }
                        }
                    },
                    onExitFullscreen = { fullscreen = false },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

@Composable
private fun PhotoPage(
    volume: MountedVolume,
    node: FsNode,
    maxSidePx: Int,
    canvasTheme: CanvasTheme,
    onTap: () -> Unit,
) {
    val currentOnTap by rememberUpdatedState(onTap)
    var bitmap by remember(node.path) { mutableStateOf<Bitmap?>(null) }
    var loadError by remember(node.path) { mutableStateOf<String?>(null) }
    var isLoading by remember(node.path) { mutableStateOf(true) }

    var scale by remember(node.path) { mutableFloatStateOf(1f) }
    var offset by remember(node.path) { mutableStateOf(Offset.Zero) }

    // Decode into mutable Bitmap off the main thread; wipe encoded bytes in finally;
    // zero Bitmap pixels (`eraseColor(0)`) as soon as this page leaves composition!
    DisposableEffect(volume.id, node.path) {
        onDispose {
            val b = bitmap
            bitmap = null
            wipeMutableBitmap(b)
        }
    }

    LaunchedEffect(volume.id, node.path, maxSidePx) {
        isLoading = true
        loadError = null
        val decoded = withContext(Dispatchers.IO) {
            decodeMutableDownsampledBitmap(volume, node.path, maxSidePx)
        }
        decoded.onSuccess { bmp ->
            val prev = bitmap
            bitmap = bmp
            if (prev != null && prev !== bmp) {
                wipeMutableBitmap(prev)
            }
        }.onFailure { err ->
            loadError = err.message ?: "Could not decode image"
        }
        isLoading = false
    }

    val canvasBg = when (canvasTheme) {
        CanvasTheme.DARK -> VaultColors.Background
        CanvasTheme.LIGHT -> Color(0xFFF1F5F9)
        CanvasTheme.GRID -> Color(0xFF1E2630)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(canvasBg)
            .pointerInput(node.path) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            .pointerInput(node.path) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 8f)
                    scale = nextScale
                    offset = if (nextScale > 1.01f) {
                        Offset(
                            x = offset.x + pan.x,
                            y = offset.y + pan.y,
                        )
                    } else {
                        Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (canvasTheme == CanvasTheme.GRID) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = 32.dp.toPx()
                val cols = (size.width / step).toInt() + 1
                val rows = (size.height / step).toInt() + 1
                val gridColor = Color(0xFF283442)
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        if ((r + c) % 2 == 0) {
                            drawRect(
                                color = gridColor,
                                topLeft = Offset(c * step, r * step),
                                size = Size(step, step),
                            )
                        }
                    }
                }
            }
        }

        val currentBmp = bitmap
        when {
            isLoading -> CircularProgressIndicator(color = VaultColors.Primary)
            loadError != null -> Text(
                text = loadError!!,
                color = VaultColors.Danger,
                style = MaterialTheme.typography.bodyMedium,
            )
            currentBmp != null && !currentBmp.isRecycled -> {
                Image(
                    bitmap = currentBmp.asImageBitmap(),
                    contentDescription = node.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
        }
    }
}

private const val MAX_IMAGE_FILE_BYTES = 64 * 1024 * 1024 // 64 MiB cap per image in RAM

internal fun decodeMutableDownsampledBitmap(
    volume: MountedVolume,
    path: String,
    maxSidePx: Int,
): Result<Bitmap> = runCatching {
    volume.openFile(path).use { handle ->
        if (handle.size <= 0L) throw IllegalArgumentException("Image file is empty")
        if (handle.size > MAX_IMAGE_FILE_BYTES) {
            throw IllegalArgumentException("Image exceeds 64 MB in-RAM safety limit")
        }
        val encoded = ByteArray(handle.size.toInt())
        try {
            var pos = 0
            while (pos < encoded.size) {
                val n = handle.read(pos.toLong(), encoded, pos, encoded.size - pos)
                if (n <= 0) break
                pos += n
            }

            // 1. Bounds check
            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(encoded, 0, pos, boundsOpts)
            val w = boundsOpts.outWidth
            val h = boundsOpts.outHeight
            if (w <= 0 || h <= 0) {
                throw IllegalArgumentException("Unsupported or corrupted image format")
            }

            var sampleSize = 1
            while ((max(w, h) / sampleSize) > maxSidePx) {
                sampleSize *= 2
            }

            // 2. Decode directly into a mutable ARGB_8888 Bitmap
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            // Some PNGs (e.g. indexed 8-bit, 16-bit, or interlaced) fail when inMutable=true is set;
            // fall back to decoding without inMutable and copy if null is returned.
            val bmp = BitmapFactory.decodeByteArray(encoded, 0, pos, decodeOpts)
                ?: run {
                    val fallbackOpts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val fallbackBmp = BitmapFactory.decodeByteArray(encoded, 0, pos, fallbackOpts)
                        ?: throw IllegalArgumentException("Failed to decode image bytes")
                    val copy = fallbackBmp.copy(Bitmap.Config.ARGB_8888, true)
                    wipeMutableBitmap(fallbackBmp)
                    copy
                }

            if (bmp.isMutable) {
                bmp
            } else {
                val mutableCopy = bmp.copy(Bitmap.Config.ARGB_8888, true)
                wipeMutableBitmap(bmp)
                mutableCopy
            }
        } finally {
            // Wipe encoded plaintext image bytes immediately (section 7.6)
            Secrets.wipe(encoded)
        }
    }
}

internal fun wipeMutableBitmap(bmp: Bitmap?) {
    if (bmp == null || bmp.isRecycled) return
    try {
        if (bmp.isMutable) {
            bmp.eraseColor(0)
        }
    } catch (_: Throwable) {
    }
    try {
        bmp.recycle()
    } catch (_: Throwable) {
    }
}

