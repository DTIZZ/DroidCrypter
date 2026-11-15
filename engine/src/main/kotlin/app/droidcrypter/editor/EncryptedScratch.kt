package app.droidcrypter.editor

import app.droidcrypter.engine.Secrets
import app.droidcrypter.engine.vc.CipherSuite
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SeekableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.BitSet

/**
 * Encrypted scratch file for video editor exports (section 8.3):
 * - Temp file in app cache, **unlinked right after opening** (with fallback delete on close on
 *   Windows where open handles may prevent immediate unlink);
 * - Every byte goes through XTS-AES-256 under a random in-RAM key;
 * - Supports random writes, truncation, and zero-filled gaps (never-written 512-byte units are
 *   stored/read as encrypted zeros, so reading across gaps returns plain zeros and writing into
 *   gaps never leaks uninitialized ciphertext);
 * - Exposes a [SeekableByteChannel] for `Mp4Muxer` and positional `readAt` for `MediaDataSource`.
 */
class EncryptedScratch(cacheDir: File) : Closeable {
    private val file: File
    private val raf: RandomAccessFile
    private val key = ByteArray(CipherSuite.AES.keyBytes).also { Secrets.random.nextBytes(it) }
    private val cascade = CipherSuite.AES.createCascade(key).also { Secrets.wipe(key) }
    private val writtenUnits = BitSet()
    private val lock = Any()

    @Volatile
    private var closed = false

    var logicalSize: Long = 0L
        private set

    init {
        cacheDir.mkdirs()
        file = File(cacheDir, "scratch_${Secrets.randomHexId()}.tmp")
        raf = RandomAccessFile(file, "rw")
        // Unlink immediately after opening (POSIX deletes the directory entry while keeping the fd alive).
        try {
            Files.deleteIfExists(file.toPath())
        } catch (_: Throwable) {
            file.delete()
        }
    }

    /** Positional thread-safe read into [buf]. Returns bytes read, or -1 at EOF. */
    fun readAt(position: Long, buf: ByteArray, off: Int, len: Int): Int = synchronized(lock) {
        ensureOpen()
        if (position < 0 || off < 0 || len < 0 || len > buf.size - off) throw IndexOutOfBoundsException()
        if (position >= logicalSize) return -1
        if (len == 0) return 0
        val wantTotal = minOf(len.toLong(), logicalSize - position).toInt()
        var done = 0
        val unitBuf = ByteArray(UNIT)
        try {
            while (done < wantTotal) {
                val abs = position + done
                val uIdx = (abs / UNIT).toInt()
                val inUnit = (abs % UNIT).toInt()
                val take = minOf(wantTotal - done, UNIT - inUnit)
                readUnitPlaintextLocked(uIdx, unitBuf)
                System.arraycopy(unitBuf, inUnit, buf, off + done, take)
                done += take
            }
        } finally {
            Secrets.wipe(unitBuf)
        }
        return wantTotal
    }

    /** Positional thread-safe write from [buf]. Extends [logicalSize] and materializes any gap units as encrypted zeros. */
    fun writeAt(position: Long, buf: ByteArray, off: Int, len: Int): Unit = synchronized(lock) {
        ensureOpen()
        if (position < 0 || off < 0 || len < 0 || len > buf.size - off) throw IndexOutOfBoundsException()
        if (len == 0) {
            if (position > logicalSize) {
                materializeGapUnitsLocked(logicalSize, position)
                logicalSize = position
            }
            return
        }
        if (position > logicalSize) {
            materializeGapUnitsLocked(logicalSize, position)
        }
        var done = 0
        val unitBuf = ByteArray(UNIT)
        try {
            while (done < len) {
                val abs = position + done
                val uIdx = (abs / UNIT).toInt()
                val inUnit = (abs % UNIT).toInt()
                val take = minOf(len - done, UNIT - inUnit)
                if (inUnit != 0 || take != UNIT) {
                    readUnitPlaintextLocked(uIdx, unitBuf)
                }
                System.arraycopy(buf, off + done, unitBuf, inUnit, take)
                writeUnitPlaintextLocked(uIdx, unitBuf)
                done += take
            }
        } finally {
            Secrets.wipe(unitBuf)
        }
        if (position + len > logicalSize) {
            logicalSize = position + len
        }
    }

    fun truncate(newSize: Long) = synchronized(lock) {
        ensureOpen()
        require(newSize >= 0) { "Negative truncate size" }
        if (newSize < logicalSize) {
            val firstDroppedUnit = ((newSize + UNIT - 1) / UNIT).toInt()
            val prevLastUnit = ((logicalSize + UNIT - 1) / UNIT).toInt()
            if (firstDroppedUnit < prevLastUnit) {
                writtenUnits.clear(firstDroppedUnit, prevLastUnit)
            }
            // Zero the tail of the partial unit at newSize if newSize isn't a multiple of UNIT.
            val rem = (newSize % UNIT).toInt()
            if (rem != 0) {
                val uIdx = (newSize / UNIT).toInt()
                if (writtenUnits.get(uIdx)) {
                    val unitBuf = ByteArray(UNIT)
                    try {
                        readUnitPlaintextLocked(uIdx, unitBuf)
                        unitBuf.fill(0, rem, UNIT)
                        writeUnitPlaintextLocked(uIdx, unitBuf)
                    } finally {
                        Secrets.wipe(unitBuf)
                    }
                }
            }
            logicalSize = newSize
            raf.setLength(firstDroppedUnit.toLong() * UNIT)
        } else if (newSize > logicalSize) {
            materializeGapUnitsLocked(logicalSize, newSize)
            logicalSize = newSize
        }
    }

    /** Materializes any never-written units in `[fromPos, toPos)` on disk as encrypted zeros. */
    private fun materializeGapUnitsLocked(fromPos: Long, toPos: Long) {
        val startUnit = (fromPos / UNIT).toInt()
        val endUnit = ((toPos + UNIT - 1) / UNIT).toInt()
        val zeroUnit = ByteArray(UNIT)
        try {
            for (u in startUnit until endUnit) {
                if (!writtenUnits.get(u)) {
                    zeroUnit.fill(0)
                    writeUnitPlaintextLocked(u, zeroUnit)
                }
            }
        } finally {
            Secrets.wipe(zeroUnit)
        }
    }

    private fun readUnitPlaintextLocked(uIdx: Int, outPlain: ByteArray) {
        if (!writtenUnits.get(uIdx)) {
            outPlain.fill(0)
            return
        }
        raf.seek(uIdx.toLong() * UNIT)
        raf.readFully(outPlain, 0, UNIT)
        cascade.decrypt(outPlain, 0, UNIT, uIdx.toLong())
    }

    private fun writeUnitPlaintextLocked(uIdx: Int, plain: ByteArray) {
        val enc = plain.copyOf(UNIT)
        try {
            cascade.encrypt(enc, 0, UNIT, uIdx.toLong())
            raf.seek(uIdx.toLong() * UNIT)
            raf.write(enc, 0, UNIT)
            writtenUnits.set(uIdx)
        } finally {
            Secrets.wipe(enc)
        }
    }

    /** Returns a [FileChannel] (which also implements [SeekableByteChannel]) over this encrypted scratch. */
    fun asChannel(): FileChannel = object : FileChannel() {
        private var pos = 0L

        private fun checkOpen() {
            if (!isOpen || closed) throw ClosedChannelException()
        }

        override fun position(): Long = synchronized(lock) {
            checkOpen()
            pos
        }

        override fun position(newPosition: Long): FileChannel = synchronized(lock) {
            checkOpen()
            require(newPosition >= 0)
            pos = newPosition
            this
        }

        override fun size(): Long = synchronized(lock) {
            checkOpen()
            logicalSize
        }

        override fun read(dst: ByteBuffer): Int = synchronized(lock) {
            checkOpen()
            if (!dst.hasRemaining()) return 0
            val tmp = ByteArray(minOf(dst.remaining(), 65536))
            try {
                val n = readAt(pos, tmp, 0, tmp.size)
                if (n < 0) return -1
                dst.put(tmp, 0, n)
                pos += n
                n
            } finally {
                Secrets.wipe(tmp)
            }
        }

        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = synchronized(lock) {
            checkOpen()
            var total = 0L
            for (i in offset until (offset + length)) {
                val buf = dsts[i]
                while (buf.hasRemaining()) {
                    val n = read(buf)
                    if (n < 0) return if (total == 0L) -1L else total
                    if (n == 0) break
                    total += n
                }
            }
            total
        }

        override fun read(dst: ByteBuffer, position: Long): Int = synchronized(lock) {
            checkOpen()
            require(position >= 0)
            if (!dst.hasRemaining()) return 0
            val tmp = ByteArray(minOf(dst.remaining(), 65536))
            try {
                val n = readAt(position, tmp, 0, tmp.size)
                if (n < 0) return -1
                dst.put(tmp, 0, n)
                n
            } finally {
                Secrets.wipe(tmp)
            }
        }

        override fun write(src: ByteBuffer): Int = synchronized(lock) {
            checkOpen()
            val rem = src.remaining()
            if (rem == 0) return 0
            val tmp = ByteArray(minOf(rem, 65536))
            var total = 0
            try {
                while (src.hasRemaining()) {
                    val chunk = minOf(src.remaining(), tmp.size)
                    src.get(tmp, 0, chunk)
                    writeAt(pos, tmp, 0, chunk)
                    pos += chunk
                    total += chunk
                }
            } finally {
                Secrets.wipe(tmp)
            }
            total
        }

        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = synchronized(lock) {
            checkOpen()
            var total = 0L
            for (i in offset until (offset + length)) {
                total += write(srcs[i])
            }
            total
        }

        override fun write(src: ByteBuffer, position: Long): Int = synchronized(lock) {
            checkOpen()
            require(position >= 0)
            val rem = src.remaining()
            if (rem == 0) return 0
            val tmp = ByteArray(minOf(rem, 65536))
            var total = 0
            var curPos = position
            try {
                while (src.hasRemaining()) {
                    val chunk = minOf(src.remaining(), tmp.size)
                    src.get(tmp, 0, chunk)
                    writeAt(curPos, tmp, 0, chunk)
                    curPos += chunk
                    total += chunk
                }
            } finally {
                Secrets.wipe(tmp)
            }
            total
        }

        override fun truncate(size: Long): FileChannel = synchronized(lock) {
            checkOpen()
            this@EncryptedScratch.truncate(size)
            if (pos > size) pos = size
            this
        }

        override fun force(metaData: Boolean) {
            synchronized(lock) {
                checkOpen()
                try { raf.fd.sync() } catch (_: Throwable) {}
            }
        }

        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = synchronized(lock) {
            checkOpen()
            var remaining = count
            var cur = position
            var transferred = 0L
            val buf = ByteBuffer.allocate(minOf(count.coerceAtLeast(1L), 65536L).toInt())
            while (remaining > 0L) {
                buf.clear()
                if (remaining < buf.capacity()) buf.limit(remaining.toInt())
                val r = read(buf, cur)
                if (r <= 0) break
                buf.flip()
                while (buf.hasRemaining()) {
                    target.write(buf)
                }
                cur += r
                remaining -= r
                transferred += r
            }
            transferred
        }

        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = synchronized(lock) {
            checkOpen()
            var remaining = count
            var cur = position
            var transferred = 0L
            val buf = ByteBuffer.allocate(minOf(count.coerceAtLeast(1L), 65536L).toInt())
            while (remaining > 0L) {
                buf.clear()
                if (remaining < buf.capacity()) buf.limit(remaining.toInt())
                val r = src.read(buf)
                if (r <= 0) break
                buf.flip()
                write(buf, cur)
                cur += r
                remaining -= r
                transferred += r
            }
            transferred
        }

        override fun map(mode: MapMode?, position: Long, size: Long): MappedByteBuffer {
            throw UnsupportedOperationException("Memory mapping EncryptedScratch is prohibited")
        }

        override fun lock(position: Long, size: Long, shared: Boolean): FileLock {
            throw UnsupportedOperationException("Locking EncryptedScratch is not needed")
        }

        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = null

        override fun implCloseChannel() {
            // Scratch lifecycle is owned by the outer EncryptedScratch.use block.
        }
    }

    /**
     * Returns a [FileOutputStream] whose [FileOutputStream.getChannel] returns [asChannel]
     * so Media3 `Mp4Muxer.Builder(FileOutputStream)` writes every byte through XTS-AES-256.
     */
    fun asFileOutputStream(): FileOutputStream {
        val ch = asChannel()
        return object : FileOutputStream(FileDescriptor()) {
            override fun getChannel(): FileChannel = ch

            override fun write(b: Int) {
                val single = ByteBuffer.wrap(byteArrayOf(b.toByte()))
                ch.write(single)
            }

            override fun write(b: ByteArray) {
                ch.write(ByteBuffer.wrap(b))
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                ch.write(ByteBuffer.wrap(b, off, len))
            }

            override fun flush() {
                // Handled by EncryptedScratch
            }

            override fun close() {
                ch.close()
            }
        }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Encrypted scratch is closed")
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            cascade.destroy()
            writtenUnits.clear()
            try { raf.setLength(0) } catch (_: Throwable) {}
            try { raf.close() } catch (_: Throwable) {}
            try { Files.deleteIfExists(file.toPath()) } catch (_: Throwable) { file.delete() }
        }
    }

    companion object {
        const val UNIT = 512
    }
}
