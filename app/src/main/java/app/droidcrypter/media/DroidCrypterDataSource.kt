package app.droidcrypter.media

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.droidcrypter.editor.EncryptedScratch
import app.droidcrypter.engine.FsFileHandle
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.engine.VolumeRegistry
import app.droidcrypter.provider.VolumeUris
import java.io.EOFException
import java.io.IOException

/**
 * Media3 [DataSource] that streams and seeks directly inside an unlocked [MountedVolume]
 * via `droidcrypter://<volumeId>/<path>` URIs (section 7.7).
 */
@UnstableApi
class DroidCrypterDataSource(
    private val volumeResolver: (String) -> MountedVolume? = { VolumeRegistry.get(it) },
) : BaseDataSource(/* isNetwork = */ false) {

    private var currentUri: Uri? = null
    private var handle: FsFileHandle? = null
    private var readPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        currentUri = uri
        transferInitializing(dataSpec)

        val parsed = VolumeUris.parseMediaUri(uri.toString())
        val volume = volumeResolver(parsed.volumeId)
            ?: throw IOException("Volume is locked")
        val h = volume.openFile(parsed.path)
        handle = h

        val fileSize = h.size
        if (dataSpec.position < 0L || dataSpec.position > fileSize) {
            try { h.close() } catch (_: Throwable) {}
            handle = null
            throw EOFException("Position ${dataSpec.position} out of bounds for size $fileSize")
        }

        readPosition = dataSpec.position
        val available = fileSize - dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, available)
        } else {
            available
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val h = handle ?: throw IOException("DataSource is not open")

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val n = h.read(readPosition, buffer, offset, toRead)
        if (n <= 0) {
            if (bytesRemaining != 0L && dataSpecHadExplicitLength) {
                throw EOFException("Unexpected EOF while reading encrypted stream")
            }
            return C.RESULT_END_OF_INPUT
        }

        readPosition += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    private val dataSpecHadExplicitLength: Boolean = false

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        val h = handle
        handle = null
        readPosition = 0L
        bytesRemaining = 0L
        try {
            h?.close()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(
        private val volumeResolver: (String) -> MountedVolume? = { VolumeRegistry.get(it) },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = DroidCrypterDataSource(volumeResolver)
    }
}

/**
 * [MediaDataSource] backed by an in-container [FsFileHandle] for `MediaExtractor` and `MediaMetadataRetriever`.
 */
class HandleMediaDataSource(
    private val handle: FsFileHandle,
    private val closeHandleOnClose: Boolean = true,
) : MediaDataSource() {

    override fun getSize(): Long = handle.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0L || position >= handle.size) return -1
        return handle.read(position, buffer, offset, size)
    }

    override fun close() {
        if (closeHandleOnClose) {
            handle.close()
        }
    }
}

/**
 * [MediaDataSource] backed by an [EncryptedScratch] file for post-export `MediaExtractor` validation.
 */
class ScratchMediaDataSource(
    private val scratch: EncryptedScratch,
) : MediaDataSource() {

    override fun getSize(): Long = scratch.logicalSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0L || position >= scratch.logicalSize) return -1
        return scratch.readAt(position, buffer, offset, size)
    }

    override fun close() {
        // Scratch lifecycle is owned by the exporter's use-block.
    }
}

