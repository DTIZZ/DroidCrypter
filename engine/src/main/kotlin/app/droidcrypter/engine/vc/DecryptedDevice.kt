package app.droidcrypter.engine.vc

import app.droidcrypter.engine.BlockDevice
import app.droidcrypter.engine.Secrets
import app.droidcrypter.engine.checkRange
import java.io.IOException

/**
 * Plaintext view of a DroidCrypter data area. XTS data units are 512 bytes, numbered from the start of
 * the host file (absolute offset / 512). Reads decrypt the enclosing units in chunks of up to 1 MiB
 * into a scratch buffer that is wiped afterwards; writes re-encrypt every touched unit (with
 * read-modify-write only for partial edge units) and are serialised.
 */
internal class DecryptedDevice(
    private val host: BlockDevice,
    private val dataOffset: Long,
    override val size: Long,
    private val cascade: XtsCascade,
    writable: Boolean,
) : BlockDevice {
    override val isWritable: Boolean = writable && host.isWritable
    private val writeLock = Any()

    @Volatile
    private var closed = false

    override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int) {
        ensureOpen()
        checkRange(pos, len, size)
        var done = 0
        while (done < len) {
            val abs = dataOffset + pos + done
            val alignedStart = abs and UNIT_MASK.inv()
            val lead = (abs - alignedStart).toInt()
            val want = minOf(len - done, CHUNK - lead)
            val alignedLen = ((lead + want + UNIT - 1) / UNIT) * UNIT
            val scratch = ByteArray(alignedLen)
            try {
                host.readFully(alignedStart, scratch, 0, alignedLen)
                decrypt(scratch, alignedLen, alignedStart)
                System.arraycopy(scratch, lead, buf, off + done, want)
            } finally {
                Secrets.wipe(scratch)
            }
            done += want
        }
    }

    override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) {
        ensureOpen()
        if (!isWritable) throw IOException("This volume is read-only")
        checkRange(pos, len, size)
        synchronized(writeLock) {
            var done = 0
            while (done < len) {
                val abs = dataOffset + pos + done
                val alignedStart = abs and UNIT_MASK.inv()
                val lead = (abs - alignedStart).toInt()
                val want = minOf(len - done, CHUNK - lead)
                val alignedLen = ((lead + want + UNIT - 1) / UNIT) * UNIT
                val scratch = ByteArray(alignedLen)
                try {
                    if (lead != 0) readUnit(alignedStart, scratch, 0)
                    val tailUnitOff = alignedLen - UNIT
                    if ((lead + want) % UNIT != 0 && (tailUnitOff != 0 || lead == 0)) {
                        readUnit(alignedStart + tailUnitOff, scratch, tailUnitOff)
                    }
                    System.arraycopy(buf, off + done, scratch, lead, want)
                    cascade.encrypt(scratch, 0, alignedLen, alignedStart / UNIT)
                    host.write(alignedStart, scratch, 0, alignedLen)
                } finally {
                    Secrets.wipe(scratch)
                }
                done += want
            }
        }
    }

    /** Reads and decrypts one unit at absolute [abs] into [dst] at [dstOff]. */
    private fun readUnit(abs: Long, dst: ByteArray, dstOff: Int) {
        host.readFully(abs, dst, dstOff, UNIT)
        try {
            cascade.decrypt(dst, dstOff, UNIT, abs / UNIT)
        } catch (e: IllegalStateException) {
            throw IOException("Volume is locked")
        }
    }

    private fun decrypt(scratch: ByteArray, len: Int, abs: Long) {
        try {
            cascade.decrypt(scratch, 0, len, abs / UNIT)
        } catch (e: IllegalStateException) {
            throw IOException("Volume is locked")
        }
    }

    override fun sync() {
        ensureOpen()
        host.sync()
    }

    override fun close() {
        if (closed) return
        closed = true
        cascade.destroy()
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Volume is locked")
    }

    companion object {
        const val UNIT = 512
        private const val UNIT_MASK = 511L
        private const val CHUNK = 1 shl 20
    }
}

