package app.droidcrypter.engine.fs

import app.droidcrypter.engine.BlockDevice
import app.droidcrypter.engine.Secrets
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Formats a [BlockDevice] as exFAT per Microsoft's specification:
 * - Main and backup boot regions (12 sectors each) with boot checksum;
 * - One FAT, allocation bitmap, compressed up-case table, and root directory at the start of the
 *   cluster heap (with volume label entry);
 * - Cluster size: ≤ 256 MB → 4 KB, ≤ 32 GB → 32 KB, else 128 KB;
 * - Refuses volumes that yield < 16 clusters.
 */
object ExFatFormatter {
    const val BYTES_PER_SECTOR = 512
    const val BPS_SHIFT = 9

    /** Compressed standard exFAT up-case table (5,836 bytes, checksum 0xE619D30D). */
    val COMPRESSED_UPCASE: ByteArray by lazy { buildCompressedUpcase() }
    val UPCASE_CHECKSUM: Long by lazy { tableChecksum(COMPRESSED_UPCASE) }

    fun format(dev: BlockDevice, label: String = "") {
        if (!dev.isWritable) throw IOException("This volume is read-only")
        val cleanLabel = label.trim().take(11)
        val totalSectors = dev.size / BYTES_PER_SECTOR
        val clusterSize = when {
            dev.size <= 256L * 1024 * 1024 -> 4096
            dev.size <= 32L * 1024 * 1024 * 1024 -> 32768
            else -> 131072
        }
        val spc = clusterSize / BYTES_PER_SECTOR
        val spcShift = Integer.numberOfTrailingZeros(spc)

        val fatOffsetSectors = 24L
        var fatLengthSectors = 1L
        var heapOffsetSectors = 32L
        var clusterCount = 0L
        repeat(16) {
            heapOffsetSectors = alignUp(fatOffsetSectors + fatLengthSectors, spc.toLong())
            clusterCount = ((totalSectors - heapOffsetSectors) / spc).coerceAtLeast(0)
            val neededFatBytes = (clusterCount + 2) * 4
            val nextFatSectors = maxOf(1L, (neededFatBytes + BYTES_PER_SECTOR - 1) / BYTES_PER_SECTOR)
            if (nextFatSectors == fatLengthSectors) return@repeat
            fatLengthSectors = nextFatSectors
        }
        heapOffsetSectors = alignUp(fatOffsetSectors + fatLengthSectors, spc.toLong())
        clusterCount = ((totalSectors - heapOffsetSectors) / spc).coerceAtLeast(0)
        if (clusterCount < 16) {
            throw IOException("Volume is too small for exFAT (fewer than 16 clusters)")
        }

        val bitmapBytes = (clusterCount + 7) / 8
        val bitmapClusters = ((bitmapBytes + clusterSize - 1) / clusterSize).toInt()
        val upcaseBytes = COMPRESSED_UPCASE.size.toLong()
        val upcaseClusters = ((upcaseBytes + clusterSize - 1) / clusterSize).toInt()
        val rootClusters = 1
        val totalMetaClusters = bitmapClusters + upcaseClusters + rootClusters
        if (clusterCount < totalMetaClusters + 2) {
            throw IOException("Volume is too small for exFAT metadata")
        }

        val bitmapStartCluster = 2L
        val upcaseStartCluster = bitmapStartCluster + bitmapClusters
        val rootCluster = upcaseStartCluster + upcaseClusters

        // 1. Build and write the 12-sector main and backup boot regions.
        val serial = Secrets.random.nextInt()
        val bootRegion = ByteArray(12 * BYTES_PER_SECTOR)
        bootRegion[0] = 0xEB.toByte(); bootRegion[1] = 0x76.toByte(); bootRegion[2] = 0x90.toByte()
        "EXFAT   ".toByteArray(Charsets.US_ASCII).copyInto(bootRegion, 3)
        Le.put64(bootRegion, 64, 0L) // PartitionOffset
        Le.put64(bootRegion, 72, totalSectors) // VolumeLength
        Le.put32(bootRegion, 80, fatOffsetSectors)
        Le.put32(bootRegion, 84, fatLengthSectors)
        Le.put32(bootRegion, 88, heapOffsetSectors)
        Le.put32(bootRegion, 92, clusterCount)
        Le.put32(bootRegion, 96, rootCluster)
        Le.put32(bootRegion, 100, serial.toLong() and 0xFFFFFFFFL)
        Le.put16(bootRegion, 104, 0x0100) // FileSystemRevision 1.00
        Le.put16(bootRegion, 106, 0) // VolumeFlags
        bootRegion[108] = BPS_SHIFT.toByte()
        bootRegion[109] = spcShift.toByte()
        bootRegion[110] = 1 // NumberOfFats
        bootRegion[111] = 0x80.toByte() // DriveSelect
        bootRegion[112] = 0 // PercentInUse
        // Extended boot sectors (sectors 1..8) carry 0xAA550000 at bytes 508..511; sector 0 has 0x55 0xAA.
        bootRegion[510] = 0x55.toByte(); bootRegion[511] = 0xAA.toByte()
        for (s in 1..8) {
            val base = s * BYTES_PER_SECTOR
            bootRegion[base + 510] = 0x55.toByte()
            bootRegion[base + 511] = 0xAA.toByte()
        }
        // Sector 11 is the repeating 32-bit boot checksum over sectors 0..10.
        val bootSum = bootChecksum(bootRegion, 11 * BYTES_PER_SECTOR)
        for (i in 0 until BYTES_PER_SECTOR step 4) {
            Le.put32(bootRegion, 11 * BYTES_PER_SECTOR + i, bootSum)
        }
        dev.write(0, bootRegion, 0, bootRegion.size)
        dev.write(12L * BYTES_PER_SECTOR, bootRegion, 0, bootRegion.size)
        // Zero sectors between backup boot region (sector 24) and heap start if any padding precedes FAT.
        val zeroSector = ByteArray(BYTES_PER_SECTOR)
        for (s in (fatOffsetSectors + fatLengthSectors) until heapOffsetSectors) {
            dev.write(s * BYTES_PER_SECTOR, zeroSector, 0, BYTES_PER_SECTOR)
        }

        // 2. Write the FAT table explicitly.
        val fatByteLen = fatLengthSectors * BYTES_PER_SECTOR
        val fatStartBytes = fatOffsetSectors * BYTES_PER_SECTOR
        val firstFatBuf = ByteArray(minOf(fatByteLen, 65536L).toInt())
        Le.put32(firstFatBuf, 0, 0xFFFFFFF8L)
        Le.put32(firstFatBuf, 4, 0xFFFFFFFFL)
        fun writeChainInFirstBuf(start: Long, count: Int) {
            for (k in 0 until count) {
                val c = start + k
                val next = if (k + 1 < count) c + 1 else 0xFFFFFFFFL
                val o = (c * 4).toInt()
                if (o + 4 <= firstFatBuf.size) Le.put32(firstFatBuf, o, next)
            }
        }
        writeChainInFirstBuf(bitmapStartCluster, bitmapClusters)
        writeChainInFirstBuf(upcaseStartCluster, upcaseClusters)
        writeChainInFirstBuf(rootCluster, rootClusters)

        dev.write(fatStartBytes, firstFatBuf, 0, firstFatBuf.size)
        val zeroChunk = ByteArray(minOf(fatByteLen, 65536L).toInt())
        var off = firstFatBuf.size.toLong()
        while (off < fatByteLen) {
            val n = minOf(zeroChunk.size.toLong(), fatByteLen - off).toInt()
            dev.write(fatStartBytes + off, zeroChunk, 0, n)
            off += n
        }

        val heapOffsetBytes = heapOffsetSectors * BYTES_PER_SECTOR
        fun clusterOff(c: Long) = heapOffsetBytes + (c - 2) * clusterSize

        // 3. Write allocation bitmap (first totalMetaClusters bits set to 1, rest 0).
        val bmFull = ByteArray(bitmapClusters * clusterSize)
        for (bit in 0 until totalMetaClusters) {
            bmFull[bit ushr 3] = (bmFull[bit ushr 3].toInt() or (1 shl (bit and 7))).toByte()
        }
        dev.write(clusterOff(bitmapStartCluster), bmFull, 0, bmFull.size)

        // 4. Write compressed up-case table.
        val upFull = ByteArray(upcaseClusters * clusterSize)
        System.arraycopy(COMPRESSED_UPCASE, 0, upFull, 0, COMPRESSED_UPCASE.size)
        dev.write(clusterOff(upcaseStartCluster), upFull, 0, upFull.size)

        // 5. Write root directory cluster: Volume Label (0x83 or 0x03), Bitmap (0x81), Up-case (0x82).
        val rootBuf = ByteArray(clusterSize)
        // Slot 0: Volume Label entry
        if (cleanLabel.isNotEmpty()) {
            rootBuf[0] = 0x83.toByte()
            rootBuf[1] = cleanLabel.length.toByte()
            for (i in cleanLabel.indices) Le.put16(rootBuf, 2 + i * 2, cleanLabel[i].code)
        } else {
            rootBuf[0] = 0x03.toByte() // no label
        }
        // Slot 1: Allocation Bitmap (0x81)
        val bmOff = 32
        rootBuf[bmOff] = 0x81.toByte()
        rootBuf[bmOff + 1] = 0 // first bitmap, FAT chain present
        Le.put32(rootBuf, bmOff + 20, bitmapStartCluster)
        Le.put64(rootBuf, bmOff + 24, bitmapBytes)
        // Slot 2: Up-case Table (0x82)
        val upOff = 64
        rootBuf[upOff] = 0x82.toByte()
        Le.put32(rootBuf, upOff + 4, UPCASE_CHECKSUM)
        Le.put32(rootBuf, upOff + 20, upcaseStartCluster)
        Le.put64(rootBuf, upOff + 24, upcaseBytes)

        dev.write(clusterOff(rootCluster), rootBuf, 0, rootBuf.size)
        dev.sync()
    }

    private fun alignUp(v: Long, align: Long): Long = ((v + align - 1) / align) * align

    internal fun bootChecksum(b: ByteArray, len: Int): Long {
        var sum = 0L
        for (i in 0 until len) {
            if (i == 106 || i == 107 || i == 112) continue
            sum = (((sum and 1L) shl 31) or (sum ushr 1)) + (b[i].toLong() and 0xFF)
            sum = sum and 0xFFFFFFFFL
        }
        return sum
    }

    internal fun tableChecksum(b: ByteArray): Long {
        var sum = 0L
        for (x in b) {
            sum = (((sum and 1L) shl 31) or (sum ushr 1)) + (x.toLong() and 0xFF)
            sum = sum and 0xFFFFFFFFL
        }
        return sum
    }

    private fun buildCompressedUpcase(): ByteArray {
        val full = IntArray(65536) { cp ->
            val ch = cp.toChar()
            val up = ch.uppercaseChar()
            if (up != ch) up.code else cp
        }
        val out = ByteArrayOutputStream(8192)
        fun writeU16(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }
        var i = 0
        while (i < 65536) {
            var run = 0
            while (i + run < 65536 && full[i + run] == i + run) run++
            if (run >= 2) {
                writeU16(0xFFFF)
                writeU16(run)
                i += run
            } else {
                writeU16(full[i])
                i++
            }
        }
        return out.toByteArray()
    }
}

