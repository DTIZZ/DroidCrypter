package app.droidcrypter.engine

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import java.io.EOFException
import java.io.IOException

/**
 * On-device read-write [BlockDevice] over a SAF descriptor using positional `pread`/`pwrite`/`fsync`
 * (SAF "rw" descriptors can't be used through one-directional stream channels).
 */
class FdBlockDevice(
    private val pfd: ParcelFileDescriptor,
    override val isWritable: Boolean,
    explicitSize: Long? = null,
) : BlockDevice {
    override val size: Long = (explicitSize ?: pfd.statSize).also {
        if (it < 0) throw IOException("Container size unknown")
    }

    @Volatile
    private var closed = false

    override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int) {
        ensureOpen()
        checkRange(pos, len, size)
        var done = 0
        try {
            while (done < len) {
                val n = Os.pread(pfd.fileDescriptor, buf, off + done, len - done, pos + done)
                if (n <= 0) throw EOFException("Unexpected end of container file")
                done += n
            }
        } catch (e: ErrnoException) {
            throw IOException("Read failed: ${e.message}", e)
        }
    }

    override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) {
        ensureOpen()
        if (!isWritable) throw IOException("This volume is read-only")
        checkRange(pos, len, size)
        var done = 0
        try {
            while (done < len) {
                val n = Os.pwrite(pfd.fileDescriptor, buf, off + done, len - done, pos + done)
                if (n <= 0) throw IOException("Write failed")
                done += n
            }
        } catch (e: ErrnoException) {
            throw IOException("Write failed: ${e.message}", e)
        }
    }

    override fun sync() {
        ensureOpen()
        if (!isWritable) return
        try {
            Os.fsync(pfd.fileDescriptor)
        } catch (e: ErrnoException) {
            throw IOException("Sync failed: ${e.message}", e)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pfd.close()
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Volume is locked")
    }

    companion object {
        fun fromParcelFileDescriptor(
            pfd: ParcelFileDescriptor,
            writable: Boolean,
            fixedSize: Long? = null,
            resizeOnOpen: Boolean = false,
        ): FdBlockDevice {
            if (resizeOnOpen && fixedSize != null && writable) {
                try {
                    Os.ftruncate(pfd.fileDescriptor, fixedSize)
                } catch (_: ErrnoException) {
                    // Some SAF providers grow on pwrite rather than ftruncate; pwrite up to fixedSize works too.
                }
            }
            return FdBlockDevice(pfd = pfd, isWritable = writable, explicitSize = fixedSize)
        }
    }
}
