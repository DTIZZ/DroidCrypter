package app.droidcrypter.engine.vc

import app.droidcrypter.engine.Secrets
import java.io.IOException
import java.util.zip.CRC32

/** Decrypted DroidCrypter volume header fields (offsets are relative to the 512-byte header). */
class VolumeHeader internal constructor(
    val version: Int,
    val minProgramVersion: Int,
    val hiddenVolumeSize: Long,
    val volumeSize: Long,
    val dataAreaOffset: Long,
    val dataAreaSize: Long,
    val flags: Int,
    val sectorSize: Int,
) {
    companion object {
        const val SIZE = 512
        const val SALT_SIZE = 64
        const val ENCRYPTED_OFFSET = 64
        const val MASTER_KEY_OFFSET = 256
        const val MASTER_KEY_SIZE = 256
        const val HIDDEN_HEADER_OFFSET = 65536L
        const val HEADER_GROUP_SIZE = 131072L
        const val MIN_FILE_SIZE = 2 * 65536L + 2 * 512L

        /**
         * Checks magic, version (≥ 5) and both CRCs on a decrypted header. Returns the parsed header,
         * or null if it isn't a valid decryption. Data-area bounds are checked separately.
         */
        internal fun parse(h: ByteArray): VolumeHeader? {
            if (h.size < SIZE) return null
            if (h[64] != 'V'.code.toByte() || h[65] != 'E'.code.toByte() ||
                h[66] != 'R'.code.toByte() || h[67] != 'A'.code.toByte()) return null
            val version = be16(h, 68)
            if (version < 5) return null
            if (crc(h, 256, 256) != be32(h, 72)) return null
            if (crc(h, 64, 188) != be32(h, 252)) return null
            val sector = be32(h, 128).toInt().let { if (it == 0) 512 else it }
            return VolumeHeader(
                version = version,
                minProgramVersion = be16(h, 70),
                hiddenVolumeSize = be64(h, 92),
                volumeSize = be64(h, 100),
                dataAreaOffset = be64(h, 108),
                dataAreaSize = be64(h, 116),
                flags = be32(h, 124).toInt(),
                sectorSize = sector,
            )
        }

        internal fun crc(b: ByteArray, off: Int, len: Int): Long = CRC32().apply { update(b, off, len) }.value

        internal fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        internal fun be32(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            return v
        }
        internal fun be64(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            return v
        }
        internal fun putBe16(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }
        internal fun putBe32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v ushr (24 - 8 * i)).toByte() }
        internal fun putBe64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (56 - 8 * i)).toByte() }
    }

    /** Validates the data area against the host file. Throws a user-facing [IOException]. */
    internal fun checkBounds(fileSize: Long) {
        val ok = dataAreaOffset > 0 && dataAreaSize > 0 &&
            dataAreaOffset % 512 == 0L && dataAreaSize % 512 == 0L &&
            dataAreaOffset <= fileSize && dataAreaSize <= fileSize - dataAreaOffset
        if (!ok) throw IOException("Header is valid but the data area is out of bounds (truncated file?)")
        if (sectorSize !in setOf(512, 1024, 2048, 4096)) {
            throw IOException("Unsupported sector size $sectorSize")
        }
    }
}

/** Result of a successful header decryption. Owns the cascade built from the master keys. */
internal class DecryptedHeader(
    val header: VolumeHeader,
    val suite: CipherSuite,
    val prf: Prf,
    val cascade: XtsCascade,
    val isHidden: Boolean,
)

internal object HeaderCrypto {
    /**
     * Tries to decrypt one 512-byte header [raw] with lazily-derived header keys: every suite
     * (cheapest first) for each PRF in [prfs]. Wipes all intermediates. Returns null if nothing matches.
     */
    fun tryDecrypt(raw: ByteArray, password: ByteArray, pim: Int, prfs: List<Prf>, isHidden: Boolean,
                   cancel: () -> Unit = {}): DecryptedHeader? {
        val salt = raw.copyOfRange(0, VolumeHeader.SALT_SIZE)
        val work = ByteArray(VolumeHeader.SIZE)
        try {
            val iterations = Prf.iterations(pim)
            for (prf in prfs) {
                cancel()
                Pbkdf2(prf, password, salt, iterations).use { kdf ->
                    for (suite in CipherSuite.trialOrder) {
                        cancel()
                        val key = kdf.bytes(suite.keyBytes)
                        try {
                            val headerCascade = suite.createCascade(key)
                            try {
                                System.arraycopy(raw, 0, work, 0, VolumeHeader.SIZE)
                                headerCascade.decrypt(work, VolumeHeader.ENCRYPTED_OFFSET, VolumeHeader.SIZE - VolumeHeader.ENCRYPTED_OFFSET, 0)
                                val parsed = VolumeHeader.parse(work)
                                if (parsed != null) {
                                    val master = suite.createCascade(work, VolumeHeader.MASTER_KEY_OFFSET)
                                    return DecryptedHeader(parsed, suite, prf, master, isHidden)
                                }
                            } finally {
                                headerCascade.destroy()
                            }
                        } finally {
                            Secrets.wipe(key)
                        }
                    }
                }
            }
            return null
        } finally {
            Secrets.wipe(salt, work)
        }
    }

    /**
     * Builds an encrypted 512-byte header: [salt] in bytes 0–63, then fields encrypted under the
     * header key derived with [prf] (data unit 0). Used by container creation (milestone 2).
     */
    fun buildHeader(salt: ByteArray, password: ByteArray, pim: Int, prf: Prf, suite: CipherSuite,
                    masterKeys: ByteArray, volumeSize: Long, dataOffset: Long, dataSize: Long): ByteArray {
        require(salt.size == VolumeHeader.SALT_SIZE && masterKeys.size == VolumeHeader.MASTER_KEY_SIZE)
        val h = ByteArray(VolumeHeader.SIZE)
        System.arraycopy(salt, 0, h, 0, 64)
        h[64] = 'V'.code.toByte(); h[65] = 'E'.code.toByte(); h[66] = 'R'.code.toByte(); h[67] = 'A'.code.toByte()
        VolumeHeader.putBe16(h, 68, 5)
        VolumeHeader.putBe16(h, 70, 0x010B)
        System.arraycopy(masterKeys, 0, h, 256, 256)
        VolumeHeader.putBe32(h, 72, VolumeHeader.crc(h, 256, 256))
        // creation/modification times (76/84) deliberately left 0; hidden size (92) 0.
        VolumeHeader.putBe64(h, 100, volumeSize)
        VolumeHeader.putBe64(h, 108, dataOffset)
        VolumeHeader.putBe64(h, 116, dataSize)
        VolumeHeader.putBe32(h, 124, 0)
        VolumeHeader.putBe32(h, 128, 512)
        VolumeHeader.putBe32(h, 252, VolumeHeader.crc(h, 64, 188))
        Pbkdf2(prf, password, salt, Prf.iterations(pim)).use { kdf ->
            val key = kdf.bytes(suite.keyBytes)
            try {
                val c = suite.createCascade(key)
                try { c.encrypt(h, 64, 448, 0) } finally { c.destroy() }
            } finally {
                Secrets.wipe(key)
            }
        }
        return h
    }
}

