package app.droidcrypter.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.util.Clock
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.FsPaths
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.engine.Secrets
import app.droidcrypter.media.HandleMediaDataSource
import app.droidcrypter.media.ScratchMediaDataSource
import app.droidcrypter.media.DroidCrypterDataSource
import app.droidcrypter.provider.VaultDocumentsProvider
import app.droidcrypter.provider.VolumeUris
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ExportMode {
    FAST_STREAM_COPY,
    PRECISE_REENCODE,
}

enum class ExportStage {
    MUXING,
    VALIDATING,
    SAVING_TO_VAULT,
}

data class ExportProgress(
    val stage: ExportStage,
    val fraction: Float,
    val message: String,
)

data class VideoExportOutcome(
    val savedNode: FsNode,
    val mode: ExportMode,
    val snappedToKeyframes: Boolean,
    val validatedDurationUs: Long,
)

/**
 * Exports an edited video inside an unlocked [MountedVolume] using an XTS-AES-256 [EncryptedScratch]
 * file (unlinked immediately after opening), validates the output with [MediaExtractor], and streams
 * it into `<name>_edited.mp4` in the same folder without writing any plaintext to device storage (section 7.9).
 */
@UnstableApi
object VideoExporter {

    private const val MIN_SAMPLE_BUF_BYTES = 64 * 1024
    private const val DEFAULT_SAMPLE_BUF_BYTES = 1024 * 1024
    private const val MAX_SAMPLE_BUF_BYTES = 16 * 1024 * 1024

    suspend fun exportEditedVideo(
        context: Context,
        volume: MountedVolume,
        sourcePath: String,
        edl: EditDecisionList,
        mode: ExportMode,
        isCancelled: () -> Boolean = { false },
        onProgress: (ExportProgress) -> Unit = {},
    ): VideoExportOutcome {
        val writeReason = volume.writeSupport
        if (writeReason != null) throw IOException(writeReason)

        val retained = edl.retainedRanges
        if (retained.isEmpty() || edl.outputDurationUs <= 0L) {
            throw IOException("Nothing left to export: the entire video is cut.")
        }

        val parentDir = FsPaths.parent(sourcePath)
        val srcName = FsPaths.name(sourcePath)
        val dot = srcName.lastIndexOf('.')
        val baseName = if (dot > 0) srcName.substring(0, dot) else srcName
        val desiredOutputName = "${baseName}_edited.mp4"

        return EncryptedScratch(context.cacheDir).use { scratch ->
            // Phase 1: Mux / re-encode into EncryptedScratch
            val (snapped, expectedMuxDurationUs) = when (mode) {
                ExportMode.FAST_STREAM_COPY -> runFastStreamCopy(
                    volume = volume,
                    sourcePath = sourcePath,
                    retainedRanges = retained,
                    scratch = scratch,
                    isCancelled = isCancelled,
                    onProgress = onProgress,
                )
                ExportMode.PRECISE_REENCODE -> {
                    runPreciseReencode(
                        context = context,
                        volume = volume,
                        sourcePath = sourcePath,
                        retainedRanges = retained,
                        scratch = scratch,
                        isCancelled = isCancelled,
                        onProgress = onProgress,
                    )
                    false to edl.outputDurationUs
                }
            }

            if (isCancelled()) throw IOException("Export cancelled")

            // Phase 2: Validate output inside EncryptedScratch before touching the container
            onProgress(ExportProgress(ExportStage.VALIDATING, 0.90f, "Validating exported video..."))
            val actualDurationUs = validateScratchOutput(
                scratch = scratch,
                expectedDurationUs = expectedMuxDurationUs,
                edlDurationUs = edl.outputDurationUs,
            )

            if (isCancelled()) throw IOException("Export cancelled")

            // Phase 3: Stream validated EncryptedScratch into the container
            val totalSize = scratch.logicalSize
            if (volume.freeBytes < totalSize) {
                throw IOException("Not enough free space inside the container to save $desiredOutputName")
            }

            val savedNode = volume.createFile(
                dir = parentDir,
                name = desiredOutputName,
                size = totalSize,
                source = { pos, buf, off, len ->
                    if (isCancelled()) throw IOException("Export cancelled")
                    var done = 0
                    while (done < len) {
                        val r = scratch.readAt(pos + done, buf, off + done, len - done)
                        if (r <= 0) throw IOException("Unexpected EOF reading encrypted scratch at ${pos + done}")
                        done += r
                    }
                },
                onProgress = { written ->
                    val frac = if (totalSize <= 0L) 1f else (written.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
                    onProgress(
                        ExportProgress(
                            stage = ExportStage.SAVING_TO_VAULT,
                            fraction = 0.90f + 0.10f * frac,
                            message = "Saving into vault (${(frac * 100).toInt()}%)...",
                        )
                    )
                },
            )

            VaultDocumentsProvider.notifyChildDocumentsChanged(context, volume.id, parentDir)
            VaultDocumentsProvider.notifyRootsChanged(context)

            VideoExportOutcome(
                savedNode = savedNode,
                mode = mode,
                snappedToKeyframes = snapped,
                validatedDurationUs = actualDurationUs,
            )
        }
    }

    /**
     * Fast stream-copy engine: `MediaExtractor` -> Media3 `Mp4Muxer` over [EncryptedScratch].
     * Snaps each retained range's video start forward to the next keyframe (`SEEK_TO_NEXT_SYNC`),
     * aligns audio, and interleaves tracks in decode order by earliest output presentation timestamp.
     */
    private fun runFastStreamCopy(
        volume: MountedVolume,
        sourcePath: String,
        retainedRanges: List<UsRange>,
        scratch: EncryptedScratch,
        isCancelled: () -> Boolean,
        onProgress: (ExportProgress) -> Unit,
    ): Pair<Boolean, Long> {
        val videoHandle = volume.openFile(sourcePath)
        val audioHandle = volume.openFile(sourcePath)
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()

        var snappedAny = false
        var totalExpectedUs = 0L

        try {
            videoExtractor.setDataSource(HandleMediaDataSource(videoHandle, closeHandleOnClose = false))
            audioExtractor.setDataSource(HandleMediaDataSource(audioHandle, closeHandleOnClose = false))

            var videoTrackIdx = -1
            var audioTrackIdx = -1
            var videoMediaFormat: MediaFormat? = null
            var audioMediaFormat: MediaFormat? = null

            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (videoTrackIdx < 0 && mime.startsWith("video/")) {
                    videoTrackIdx = i
                    videoMediaFormat = fmt
                } else if (audioTrackIdx < 0 && mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    audioMediaFormat = fmt
                }
            }

            if (videoTrackIdx < 0 || videoMediaFormat == null) {
                throw IOException("Source file has no supported video track")
            }

            videoExtractor.selectTrack(videoTrackIdx)
            if (audioTrackIdx >= 0) {
                audioExtractor.selectTrack(audioTrackIdx)
            }

            val videoMaxInput = getClampedMaxInputSize(videoMediaFormat)
            val audioMaxInput = if (audioMediaFormat != null) getClampedMaxInputSize(audioMediaFormat) else MIN_SAMPLE_BUF_BYTES
            val sampleArray = ByteArray(maxOf(videoMaxInput, audioMaxInput))
            val sampleBuf = ByteBuffer.wrap(sampleArray)

            val muxerOutput = scratch.asSeekableMuxerOutput()
            val muxer = Mp4Muxer.Builder(muxerOutput).build()

            try {
                val videoFormat: Format = MediaFormatUtil.createFormatFromMediaFormat(videoMediaFormat)
                val videoToken: Int = muxer.addTrack(videoFormat)
                val audioToken: Int? = if (audioMediaFormat != null) {
                    val af: Format = MediaFormatUtil.createFormatFromMediaFormat(audioMediaFormat)
                    muxer.addTrack(af)
                } else {
                    null
                }

                var outOffsetUs = 0L
                var lastVideoOutUs = -1L
                var lastAudioOutUs = -1L
                val totalRangesDurationUs = retainedRanges.sumOf { it.durationUs }.coerceAtLeast(1L)
                var processedDurationUs = 0L

                for (range in retainedRanges) {
                    if (isCancelled()) throw IOException("Export cancelled")

                    // Snap video start forward to next keyframe (section 7.9)
                    videoExtractor.seekTo(range.startUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
                    var firstVideoTimeUs = videoExtractor.sampleTime
                    if (firstVideoTimeUs < 0L || firstVideoTimeUs >= range.endUs) {
                        // Fallback to previous sync if no keyframe exists inside [startUs, endUs)
                        videoExtractor.seekTo(range.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        firstVideoTimeUs = videoExtractor.sampleTime
                    }
                    if (firstVideoTimeUs < 0L || firstVideoTimeUs >= range.endUs) {
                        processedDurationUs += range.durationUs
                        continue
                    }
                    if (kotlin.math.abs(firstVideoTimeUs - range.startUs) > 5_000L) {
                        snappedAny = true
                    }

                    val segStartUs = firstVideoTimeUs
                    val segEndUs = range.endUs
                    if (audioToken != null) {
                        audioExtractor.seekTo(segStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        while (audioExtractor.sampleTime in 0L until segStartUs) {
                            if (!audioExtractor.advance()) break
                        }
                    }

                    var segMaxOutUs = outOffsetUs

                    while (true) {
                        if (isCancelled()) throw IOException("Export cancelled")

                        val vTime = videoExtractor.sampleTime
                        val vValid = vTime in 0L until segEndUs
                        val aTime = if (audioToken != null) audioExtractor.sampleTime else -1L
                        val aValid = audioToken != null && aTime in segStartUs until segEndUs

                        if (!vValid && !aValid) break

                        val writeVideoNext = when {
                            vValid && !aValid -> true
                            !vValid && aValid -> false
                            else -> vTime <= aTime
                        }

                        if (writeVideoNext) {
                            sampleBuf.clear()
                            val size = videoExtractor.readSampleData(sampleBuf, 0)
                            if (size < 0) {
                                videoExtractor.advance()
                                continue
                            }
                            var pts = (vTime - segStartUs).coerceAtLeast(0L) + outOffsetUs
                            if (pts <= lastVideoOutUs) pts = lastVideoOutUs + 1L
                            lastVideoOutUs = pts
                            if (pts > segMaxOutUs) segMaxOutUs = pts

                            val flags = mapExtractorFlags(videoExtractor.sampleFlags)
                            val info = BufferInfo(pts, size, flags)
                            sampleBuf.position(0)
                            sampleBuf.limit(size)
                            muxer.writeSampleData(videoToken, sampleBuf, info)
                            videoExtractor.advance()

                            val doneInSeg = (vTime - segStartUs).coerceIn(0L, range.durationUs)
                            val frac = ((processedDurationUs + doneInSeg).toFloat() / totalRangesDurationUs.toFloat()).coerceIn(0f, 1f)
                            onProgress(
                                ExportProgress(
                                    stage = ExportStage.MUXING,
                                    fraction = frac * 0.88f,
                                    message = "Stream-copying video (${(frac * 100).toInt()}%)...",
                                )
                            )
                        } else if (audioToken != null) {
                            sampleBuf.clear()
                            val size = audioExtractor.readSampleData(sampleBuf, 0)
                            if (size < 0) {
                                audioExtractor.advance()
                                continue
                            }
                            var pts = (aTime - segStartUs).coerceAtLeast(0L) + outOffsetUs
                            if (pts <= lastAudioOutUs) pts = lastAudioOutUs + 1L
                            lastAudioOutUs = pts
                            if (pts > segMaxOutUs) segMaxOutUs = pts

                            val flags = mapExtractorFlags(audioExtractor.sampleFlags)
                            val info = BufferInfo(pts, size, flags)
                            sampleBuf.position(0)
                            sampleBuf.limit(size)
                            muxer.writeSampleData(audioToken, sampleBuf, info)
                            audioExtractor.advance()
                        }
                    }

                    val segActualDur = (segEndUs - segStartUs).coerceAtLeast(0L)
                    outOffsetUs = maxOf(outOffsetUs + segActualDur, segMaxOutUs + 1_000L)
                    totalExpectedUs += segActualDur
                    processedDurationUs += range.durationUs
                }
            } finally {
                Secrets.wipe(sampleArray)
                try { muxer.close() } catch (_: Throwable) {}
                try { muxerOutput.close() } catch (_: Throwable) {}
            }
        } finally {
            try { videoExtractor.release() } catch (_: Throwable) {}
            try { audioExtractor.release() } catch (_: Throwable) {}
            try { videoHandle.close() } catch (_: Throwable) {}
            try { audioHandle.close() } catch (_: Throwable) {}
        }

        return snappedAny to totalExpectedUs.coerceAtLeast(1L)
    }

    /**
     * Precise re-encode engine: Media3 [Transformer] with clipped [MediaItem] sequence over
     * [DroidCrypterDataSource] and a custom [Muxer.Factory] writing through [Mp4Muxer] into [EncryptedScratch].
     */
    private suspend fun runPreciseReencode(
        context: Context,
        volume: MountedVolume,
        sourcePath: String,
        retainedRanges: List<UsRange>,
        scratch: EncryptedScratch,
        isCancelled: () -> Boolean,
        onProgress: (ExportProgress) -> Unit,
    ) {
        val mediaUri = Uri.parse(VolumeUris.formatMediaUri(volume.id, sourcePath))
        val editedItems = retainedRanges.map { range ->
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionUs(range.startUs)
                .setEndPositionUs(range.endUs)
                .build()
            val item = MediaItem.Builder()
                .setUri(mediaUri)
                .setClippingConfiguration(clip)
                .build()
            EditedMediaItem.Builder(item).build()
        }
        val sequence = EditedMediaItemSequence.Builder(editedItems).build()
        val composition = Composition.Builder(listOf(sequence)).build()

        val defaultMuxerFactory = DefaultMuxer.Factory()
        val scratchMuxerFactory = object : Muxer.Factory {
            override fun create(path: String): Muxer {
                val muxerOutput = scratch.asSeekableMuxerOutput()
                val delegate = Mp4Muxer.Builder(muxerOutput).build()
                return object : Muxer {
                    override fun addTrack(format: Format): Int = delegate.addTrack(format)

                    override fun writeSampleData(
                        trackId: Int,
                        byteBuffer: ByteBuffer,
                        bufferInfo: BufferInfo,
                    ) {
                        delegate.writeSampleData(trackId, byteBuffer, bufferInfo)
                    }

                    override fun addMetadataEntry(metadataEntry: Metadata.Entry) {
                        try {
                            delegate.addMetadataEntry(metadataEntry)
                        } catch (_: IllegalArgumentException) {
                            // Silently drop unsupported metadata entries (section 7.9).
                        } catch (_: Throwable) {
                        }
                    }

                    override fun close() {
                        try {
                            delegate.close()
                        } finally {
                            try { muxerOutput.close() } catch (_: Throwable) {}
                        }
                    }
                }
            }

            override fun getSupportedSampleMimeTypes(trackType: Int) =
                defaultMuxerFactory.getSupportedSampleMimeTypes(trackType)
        }

        val transformerThread = HandlerThread("droidcrypter-transformer").apply { start() }
        val handler = Handler(transformerThread.looper)
        val finished = AtomicBoolean(false)

        try {
            val assetLoaderFactory = ExoPlayerAssetLoader.Factory(
                context.applicationContext,
                DefaultDecoderFactory.Builder(context.applicationContext).build(),
                Clock.DEFAULT,
                DefaultMediaSourceFactory(DroidCrypterDataSource.Factory()),
            )

            var transformerRef: Transformer? = null

            // Poll progress while running
            val pollThread = Thread {
                val holder = ProgressHolder()
                while (!finished.get()) {
                    if (isCancelled()) {
                        val t = transformerRef
                        if (t != null) {
                            handler.post {
                                try { t.cancel() } catch (_: Throwable) {}
                            }
                        }
                        break
                    }
                    handler.post {
                        val t = transformerRef ?: return@post
                        if (finished.get()) return@post
                        val state = t.getProgress(holder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val frac = (holder.progress / 100f).coerceIn(0f, 1f)
                            onProgress(
                                ExportProgress(
                                    stage = ExportStage.MUXING,
                                    fraction = frac * 0.88f,
                                    message = "Re-encoding video (${holder.progress}%)...",
                                )
                            )
                        }
                    }
                    try {
                        Thread.sleep(120L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }.apply { isDaemon = true }

            suspendCancellableCoroutine<Unit> { cont ->
                handler.post {
                    try {
                        val transformer = Transformer.Builder(context.applicationContext)
                            .setLooper(transformerThread.looper)
                            .setAssetLoaderFactory(assetLoaderFactory)
                            .setMuxerFactory(scratchMuxerFactory)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (finished.compareAndSet(false, true)) {
                                        cont.resume(Unit)
                                    }
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException,
                                ) {
                                    if (finished.compareAndSet(false, true)) {
                                        cont.resumeWithException(
                                            IOException(
                                                "Re-encode failed: ${exportException.message ?: exportException.errorCodeName}",
                                                exportException,
                                            )
                                        )
                                    }
                                }
                            })
                            .build()
                        transformerRef = transformer
                        // Path string is ignored by scratchMuxerFactory, which writes strictly to EncryptedScratch.
                        transformer.start(composition, "memory://encrypted_scratch.mp4")
                        pollThread.start()
                    } catch (t: Throwable) {
                        if (finished.compareAndSet(false, true)) {
                            cont.resumeWithException(t)
                        }
                    }
                }

                cont.invokeOnCancellation {
                    if (finished.compareAndSet(false, true)) {
                        handler.post {
                            try { transformerRef?.cancel() } catch (_: Throwable) {}
                        }
                    }
                }
            }
        } finally {
            finished.set(true)
            try {
                transformerThread.quitSafely()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Validates that [scratch] is non-empty, parses as a valid MP4 with a video track in [MediaExtractor],
     * and has a duration within `max(0.5 s, 5 %)` of the expected output duration (section 7.9).
     */
    private fun validateScratchOutput(
        scratch: EncryptedScratch,
        expectedDurationUs: Long,
        edlDurationUs: Long,
    ): Long {
        if (scratch.logicalSize <= 0L) {
            throw IOException("Export produced an empty output file")
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ScratchMediaDataSource(scratch))
            var hasVideo = false
            var maxDurationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    hasVideo = true
                }
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    val d = fmt.getLong(MediaFormat.KEY_DURATION)
                    if (d > maxDurationUs) maxDurationUs = d
                }
            }
            if (!hasVideo) {
                throw IOException("Export validation failed: output contains no video track")
            }
            val matchesMuxExpected = TimelineMath.isDurationWithinTolerance(expectedDurationUs, maxDurationUs)
            val matchesEdlExpected = TimelineMath.isDurationWithinTolerance(edlDurationUs, maxDurationUs)
            if (maxDurationUs <= 0L || (!matchesMuxExpected && !matchesEdlExpected)) {
                throw IOException(
                    "Export validation failed: output duration (${TimelineMath.formatTimeUs(maxDurationUs)}) " +
                        "differs from expected (${TimelineMath.formatTimeUs(expectedDurationUs)})"
                )
            }
            return maxDurationUs
        } finally {
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    private fun getClampedMaxInputSize(format: MediaFormat): Int {
        val raw = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            try { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) } catch (_: Throwable) { DEFAULT_SAMPLE_BUF_BYTES }
        } else {
            DEFAULT_SAMPLE_BUF_BYTES
        }
        return raw.coerceIn(MIN_SAMPLE_BUF_BYTES, MAX_SAMPLE_BUF_BYTES)
    }

    private fun mapExtractorFlags(sampleFlags: Int): Int {
        var flags = 0
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    private fun EncryptedScratch.asSeekableMuxerOutput(): SeekableMuxerOutput {
        val ch = asChannel()
        return object : SeekableMuxerOutput {
            override fun isOpen(): Boolean = ch.isOpen

            override fun close() {
                ch.close()
            }

            override fun write(src: ByteBuffer): Int = ch.write(src)

            override fun getPosition(): Long = ch.position()

            override fun setPosition(position: Long) {
                ch.position(position)
            }

            override fun getSize(): Long = ch.size()

            override fun truncate(size: Long) {
                ch.truncate(size)
            }
        }
    }
}

