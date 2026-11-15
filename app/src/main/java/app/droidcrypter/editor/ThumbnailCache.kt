package app.droidcrypter.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.media.HandleMediaDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/**
 * On-demand 100 ms bucketed video frame thumbnail cache held strictly in RAM (section 7.8).
 * Evicted and released bitmaps have their pixel buffers wiped (`eraseColor(0)`) immediately.
 */
class ThumbnailCache(
    private val volume: MountedVolume,
    private val videoPath: String,
    private val maxCacheBytes: Int = 12 * 1024 * 1024, // 12 MiB RAM cap
    private val targetHeightPx: Int = 96,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val decodeMutex = Mutex()
    private val lock = Any()
    private var closed = false

    private val lru = object : LinkedHashMap<Long, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?): Boolean {
            if (eldest != null && currentBytes > maxCacheBytes && size > 4) {
                val bmp = eldest.value
                currentBytes = (currentBytes - bmp.allocationByteCount).coerceAtLeast(0)
                wipeBitmap(bmp)
                return true
            }
            return false
        }
    }
    private var currentBytes = 0
    private val inFlight = HashSet<Long>()

    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun getCached(timestampUs: Long): Bitmap? = synchronized(lock) {
        if (closed) return null
        val key = TimelineMath.quantize100Ms(timestampUs)
        lru[key]?.takeIf { !it.isRecycled }
    }

    fun requestTimestamps(timestampsUs: List<Long>) {
        val toFetch = mutableListOf<Long>()
        synchronized(lock) {
            if (closed) return
            for (raw in timestampsUs) {
                val q = TimelineMath.quantize100Ms(raw)
                if (!lru.containsKey(q) && q !in inFlight) {
                    inFlight.add(q)
                    toFetch.add(q)
                }
            }
        }
        if (toFetch.isEmpty()) return

        scope.launch {
            decodeMutex.withLock {
                if (closed) return@withLock
                var retriever: MediaMetadataRetriever? = null
                var handleOpened = false
                try {
                    val handle = volume.openFile(videoPath)
                    handleOpened = true
                    retriever = MediaMetadataRetriever().apply {
                        setDataSource(HandleMediaDataSource(handle, closeHandleOnClose = true))
                    }
                    for (bucketUs in toFetch) {
                        if (closed) break
                        val decoded = try {
                            retriever.getFrameAtTime(
                                bucketUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            )
                        } catch (_: Throwable) {
                            null
                        }
                        if (decoded != null) {
                            val mutableCopy = scaleToMutable(decoded, targetHeightPx)
                            if (mutableCopy !== decoded) {
                                wipeBitmap(decoded)
                            }
                            synchronized(lock) {
                                inFlight.remove(bucketUs)
                                if (closed) {
                                    wipeBitmap(mutableCopy)
                                } else {
                                    val prev = lru.put(bucketUs, mutableCopy)
                                    if (prev != null && prev !== mutableCopy) {
                                        currentBytes = (currentBytes - prev.allocationByteCount).coerceAtLeast(0)
                                        wipeBitmap(prev)
                                    }
                                    currentBytes += mutableCopy.allocationByteCount
                                    // Trigger LRU eviction if needed
                                    while (currentBytes > maxCacheBytes && lru.size > 4) {
                                        val firstKey = lru.keys.firstOrNull() ?: break
                                        val removed = lru.remove(firstKey)
                                        if (removed != null) {
                                            currentBytes = (currentBytes - removed.allocationByteCount).coerceAtLeast(0)
                                            wipeBitmap(removed)
                                        }
                                    }
                                    _generation.value += 1
                                }
                            }
                        } else {
                            synchronized(lock) {
                                inFlight.remove(bucketUs)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    synchronized(lock) {
                        inFlight.removeAll(toFetch.toSet())
                    }
                } finally {
                    if (handleOpened) {
                        try {
                            retriever?.release()
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        val toWipe: List<Bitmap>
        synchronized(lock) {
            if (closed) return
            closed = true
            inFlight.clear()
            toWipe = lru.values.toList()
            lru.clear()
            currentBytes = 0
        }
        scope.cancel()
        for (bmp in toWipe) {
            wipeBitmap(bmp)
        }
    }

    companion object {
        private fun scaleToMutable(src: Bitmap, targetH: Int): Bitmap {
            val h = targetH.coerceAtLeast(32)
            val w = ((src.width.toFloat() / src.height.coerceAtLeast(1).toFloat()) * h)
                .toInt()
                .coerceIn(32, 512)
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            return if (scaled.isMutable && scaled.config == Bitmap.Config.ARGB_8888) {
                scaled
            } else {
                val copy = scaled.copy(Bitmap.Config.ARGB_8888, true)
                if (scaled !== src) {
                    wipeBitmap(scaled)
                }
                copy
            }
        }

        fun wipeBitmap(bitmap: Bitmap?) {
            if (bitmap == null || bitmap.isRecycled) return
            try {
                if (bitmap.isMutable) {
                    bitmap.eraseColor(0)
                }
            } catch (_: Throwable) {
            }
            try {
                bitmap.recycle()
            } catch (_: Throwable) {
            }
        }
    }
}

