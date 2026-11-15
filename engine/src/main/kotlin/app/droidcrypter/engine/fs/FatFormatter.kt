package app.droidcrypter.engine.fs

import app.droidcrypter.engine.BlockDevice
import app.droidcrypter.engine.Secrets
import java.io.IOException

/**
 * Formats a [BlockDevice] as FAT12, FAT16 or FAT32 following Windows' format rules:
 * - Tries FAT32 first and uses it if it yields ≥ 65,525 clusters
 *   (sectors per cluster by size: ≤ 260 MB → 1, ≤ 8 GB → 8, ≤ 16 GB → 16, ≤ 32 GB → 32, else 64;
 *    32 reserved sectors);
 * - otherwise FAT16 (4,085–65,524 clusters, 512 root entries) trying SPC 1–64;
 * - otherwise FAT12 (≤ 4,084 clusters).
 * Solves the FAT-size/cluster-count fixpoint and writes every metadata area explicitly so it works
 * on a quick-created container.
 */
object FatFormatter {
    const val BYTES_PER_SECTOR = 512

    fun format(dev: BlockDevice, label: String = "") {
        if (!dev.isWritable) throw IOException("This volume is read-only")
        val cleanLabel = sanitizeLabel(label)
        val totalSectors = dev.size / BYTES_PER_SECTOR
        if (totalSectors < 128) throw IOException("Volume is too small for FAT")

        val plan = planGeometry(dev.size, totalSectors)
        val serial = Secrets.random.nextInt()

        val bs = ByteArray(BYTES_PER_SECTOR)
        bs[0] = 0xEB.toByte(); bs[1] = 0x58.toByte(); bs[2] = 0x90.toByte()
        "MSDOS5.0".toByteArray(Charsets.US_ASCII).copyInto(bs, 3)
        Le.put16(bs, 11, BYTES_PER_SECTOR)
        bs[13] = plan.spc.toByte()
        Le.put16(bs, 14, plan.reservedSectors)
        bs[16] = 2 // numFats
        Le.put16(bs, 17, plan.rootEntries)
        if (totalSectors < 65536 && plan.fatBits != 32) {
            Le.put16(bs, 19, totalSectors.toInt())
        } else {
            Le.put32(bs, 32, totalSectors)
        }
        bs[21] = 0xF8.toByte() // fixed media
        if (plan.fatBits != 32) {
            Le.put16(bs, 22, plan.fatSectors.toInt())
        }
        Le.put16(bs, 24, 63) // sectors per track
        Le.put16(bs, 26, 255) // heads
        Le.put32(bs, 28, 0) // hidden sectors

        val bpbLabel = (cleanLabel.ifEmpty { "NO NAME" }).padEnd(11, ' ').take(11)
            .toByteArray(Charsets.US_ASCII)

        if (plan.fatBits == 32) {
            Le.put32(bs, 36, plan.fatSectors)
            Le.put16(bs, 40, 0) // ext flags
            Le.put16(bs, 42, 0) // fs version
            Le.put32(bs, 44, 2) // root cluster
            Le.put16(bs, 48, 1) // FSInfo sector
            Le.put16(bs, 50, 6) // backup boot sector
            bs[64] = 0x80.toByte()
            bs[66] = 0x29.toByte()
            Le.put32(bs, 67, serial.toLong() and 0xFFFFFFFFL)
            bpbLabel.copyInto(bs, 71)
            "FAT32   ".toByteArray(Charsets.US_ASCII).copyInto(bs, 82)
        } else {
            bs[36] = 0x80.toByte()
            bs[38] = 0x29.toByte()
            Le.put32(bs, 39, serial.toLong() and 0xFFFFFFFFL)
            bpbLabel.copyInto(bs, 43)
            val fsType = if (plan.fatBits == 12) "FAT12   " else "FAT16   "
            fsType.toByteArray(Charsets.US_ASCII).copyInto(bs, 54)
        }
        bs[510] = 0x55.toByte(); bs[511] = 0xAA.toByte()

        // 1. Write reserved sectors explicitly (boot sector, FSInfo, backup boot sector, zeros).
        val zeroSector = ByteArray(BYTES_PER_SECTOR)
        for (s in 0 until plan.reservedSectors) {
            dev.write(s.toLong() * BYTES_PER_SECTOR, zeroSector, 0, BYTES_PER_SECTOR)
        }
        dev.write(0, bs, 0, BYTES_PER_SECTOR)

        if (plan.fatBits == 32) {
            val fsInfo = ByteArray(BYTES_PER_SECTOR)
            Le.put32(fsInfo, 0, 0x41615252L)
            Le.put32(fsInfo, 484, 0x61417272L)
            // Cluster 2 is allocated to the root directory.
            Le.put32(fsInfo, 488, plan.clusterCount - 1)
            Le.put32(fsInfo, 492, 3)
            fsInfo[510] = 0x55.toByte(); fsInfo[511] = 0xAA.toByte()
            dev.write(1L * BYTES_PER_SECTOR, fsInfo, 0, BYTES_PER_SECTOR)
            // Backup boot region at sectors 6 and 7.
            dev.write(6L * BYTES_PER_SECTOR, bs, 0, BYTES_PER_SECTOR)
            dev.write(7L * BYTES_PER_SECTOR, fsInfo, 0, BYTES_PER_SECTOR)
        }

        // 2. Write both FAT mirrors explicitly (zeroed except entries 0, 1, and cluster 2 on FAT32).
        val fatBytes = plan.fatSectors * BYTES_PER_SECTOR
        val firstFatSector = ByteArray(BYTES_PER_SECTOR)
        when (plan.fatBits) {
            12 -> {
                // Entry 0 = 0xFF8, Entry 1 = 0xFFF -> 3 bytes: F8 FF FF
                firstFatSector[0] = 0xF8.toByte()
                firstFatSector[1] = 0xFF.toByte()
                firstFatSector[2] = 0xFF.toByte()
            }
            16 -> {
                Le.put16(firstFatSector, 0, 0xFFF8)
                Le.put16(firstFatSector, 2, 0xFFFF)
            }
            32 -> {
                Le.put32(firstFatSector, 0, 0x0FFFFFF8L)
                Le.put32(firstFatSector, 4, 0x0FFFFFFFL)
                Le.put32(firstFatSector, 8, 0x0FFFFFFFL) // cluster 2 = root dir EOC
            }
        }
        val chunk = ByteArray(minOf(fatBytes, 65536L).toInt())
        for (f in 0 until 2) {
            val base = (plan.reservedSectors + f * plan.fatSectors) * BYTES_PER_SECTOR
            dev.write(base, firstFatSector, 0, BYTES_PER_SECTOR)
            var off = BYTES_PER_SECTOR.toLong()
            while (off < fatBytes) {
                val n = minOf(chunk.size.toLong(), fatBytes - off).toInt()
                dev.write(base + off, chunk, 0, n)
                off += n
            }
        }

        // 3. Write the root directory area explicitly (with optional volume label entry).
        val rootDirOffset = (plan.reservedSectors + 2 * plan.fatSectors) * BYTES_PER_SECTOR
        val rootDirBytes = if (plan.fatBits == 32) {
            plan.spc * BYTES_PER_SECTOR
        } else {
            plan.rootEntries * 32
        }
        val rootBuf = ByteArray(rootDirBytes)
        if (cleanLabel.isNotEmpty()) {
            val padded = cleanLabel.padEnd(11, ' ').toByteArray(Charsets.US_ASCII)
            System.arraycopy(padded, 0, rootBuf, 0, 11)
            rootBuf[11] = 0x08 // ATTR_VOLUME_ID
            val (d, t) = FatFileSystem.encodeDosTime(System.currentTimeMillis())
            Le.put16(rootBuf, 22, t)
            Le.put16(rootBuf, 24, d)
        }
        dev.write(rootDirOffset, rootBuf, 0, rootBuf.size)
        dev.sync()
    }

    internal data class GeometryPlan(
        val fatBits: Int,
        val spc: Int,
        val reservedSectors: Int,
        val rootEntries: Int,
        val fatSectors: Long,
        val clusterCount: Long,
    )

    internal fun planGeometry(sizeBytes: Long, totalSectors: Long): GeometryPlan {
        // 1. Try FAT32 first: sectors per cluster by size:
        // ≤ 260 MB → 1, ≤ 8 GB → 8, ≤ 16 GB → 16, ≤ 32 GB → 32, else 64; 32 reserved sectors.
        val spc32 = when {
            sizeBytes <= 260L * 1024 * 1024 -> 1
            sizeBytes <= 8L * 1024 * 1024 * 1024 -> 8
            sizeBytes <= 16L * 1024 * 1024 * 1024 -> 16
            sizeBytes <= 32L * 1024 * 1024 * 1024 -> 32
            else -> 64
        }
        val fat32 = solveFixpoint(totalSectors, spc32, reserved = 32, rootEntries = 0, bits = 32)
        if (fat32.clusterCount >= 65_525) return fat32

        // 2. Otherwise try FAT16 (4,085–65,524 clusters, 512 root entries) trying SPC 1..64.
        var spc = 1
        while (spc <= 64) {
            val cand = solveFixpoint(totalSectors, spc, reserved = 1, rootEntries = 512, bits = 16)
            if (cand.clusterCount in 4_085..65_524) return cand
            spc *= 2
        }

        // 3. Otherwise FAT12 (≤ 4,084 clusters), trying SPC 1..64.
        spc = 1
        while (spc <= 64) {
            val cand = solveFixpoint(totalSectors, spc, reserved = 1, rootEntries = 512, bits = 12)
            if (cand.clusterCount in 1..4_084) return cand
            spc *= 2
        }
        throw IOException("Cannot find a valid FAT geometry for ${sizeBytes} bytes")
    }

    private fun solveFixpoint(
        totalSectors: Long,
        spc: Int,
        reserved: Int,
        rootEntries: Int,
        bits: Int,
    ): GeometryPlan {
        val rootDirSectors = (rootEntries * 32L + BYTES_PER_SECTOR - 1) / BYTES_PER_SECTOR
        var fatSectors = 1L
        repeat(32) {
            val dataSectors = (totalSectors - reserved - rootDirSectors - 2 * fatSectors).coerceAtLeast(0)
            val clusters = dataSectors / spc
            val bytesNeeded = when (bits) {
                12 -> ((clusters + 2) * 3 + 1) / 2
                16 -> (clusters + 2) * 2
                else -> (clusters + 2) * 4
            }
            val nextFatSectors = maxOf(1L, (bytesNeeded + BYTES_PER_SECTOR - 1) / BYTES_PER_SECTOR)
            if (nextFatSectors == fatSectors) {
                return GeometryPlan(bits, spc, reserved, rootEntries, fatSectors, clusters)
            }
            fatSectors = nextFatSectors
        }
        val dataSectors = (totalSectors - reserved - rootDirSectors - 2 * fatSectors).coerceAtLeast(0)
        return GeometryPlan(bits, spc, reserved, rootEntries, fatSectors, dataSectors / spc)
    }

    internal fun sanitizeLabel(label: String): String {
        val trimmed = label.trim().take(11).uppercase()
        val sb = StringBuilder(trimmed.length)
        for (c in trimmed) {
            if (c.code in 0x20..0x7E && c !in "\"*+,./:;<=>?[\\]|") sb.append(c)
        }
        return sb.toString().trimEnd()
    }
}

