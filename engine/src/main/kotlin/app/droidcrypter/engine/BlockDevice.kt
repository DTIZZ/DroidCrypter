package app.droidcrypter.engine

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Random-access storage. Writes outside [size] are rejected. */
interface BlockDevice : Closeable {
    val size: Long
    val isWritable: Boolean
    fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int)
    fun write(pos: Long, buf: ByteArray, off: Int, len: Int)
    fun sync()
}

/** Throws if [pos, pos+len) isn't inside a device of [size] bytes. */
fun checkRange(pos: Long, len: Int, size: Long) {
    if (pos < 0 || len < 0 || pos > size || len > size - pos) {
        throw IOException("Access outside the device (pos=$pos, len=$len, size=$size)")
    }
}

/** [BlockDevice] over a [FileChannel]. Used by host tests and read-only opens on device. */
class FileChannelBlockDevice(
    private val channel: FileChannel,
    override val isWritable: Boolean,
    private val ownsChannel: Boolean = true,
) : BlockDevice {
    override val size: Long = channel.size()

    @Volatile
    private var closed = false

    override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int) {
        ensureOpen()
        checkRange(pos, len, size)
        val bb = ByteBuffer.wrap(buf, off, len)
        var p = pos
        while (bb.hasRemaining()) {
            val n = channel.read(bb, p)
            if (n < 0) throw EOFException("Unexpected end of container file")
            p += n
        }
    }

    override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) {
        ensureOpen()
        if (!isWritable) throw IOException("This volume is read-only")
        checkRange(pos, len, size)
        val bb = ByteBuffer.wrap(buf, off, len)
        var p = pos
        while (bb.hasRemaining()) p += channel.write(bb, p)
    }

    override fun sync() {
        ensureOpen()
        if (isWritable) channel.force(true)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (ownsChannel) channel.close()
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Volume is locked")
    }

    companion object {
        fun open(file: File, writable: Boolean): FileChannelBlockDevice {
            val ch = if (writable) {
                FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)
            } else {
                FileChannel.open(file.toPath(), StandardOpenOption.READ)
            }
            return FileChannelBlockDevice(ch, writable)
        }
    }
}

