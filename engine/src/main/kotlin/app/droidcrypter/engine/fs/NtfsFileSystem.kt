package app.droidcrypter.engine.fs

import app.droidcrypter.engine.BlockDevice
import app.droidcrypter.engine.FsFileHandle
import java.io.IOException

/**
 * NTFS 3.x reader (read-only): MFT records with update-sequence fixups, attribute lists (fragmented
 * files), non-resident runs including sparse runs and initialized-size tails, resident data, `$I30`
 * indexes (root + allocation blocks, honouring the index bitmap), FILETIME → epoch. Compressed and
 * EFS files fail on open with a clear error; ADS and reparse points are ignored / shown as plain entries.
 */
class NtfsFileSystem(dev: BlockDevice) : TreeFileSystem<NtfsFileSystem.NtfsEntry>(dev) {

    class NtfsEntry internal constructor(
        override val name: String,
        override val isDirectory: Boolean,
        override val size: Long,
        override val lastModifiedMillis: Long,
        val record: Long,
    ) : Entry()

    private class Run(val vcn: Long, val lcn: Long, val len: Long) // lcn -1 = sparse

    private class Attr(
        val type: Int, val name: String, val nonResident: Boolean, val flags: Int,
        val value: ByteArray?, val startVcn: Long, val runs: List<Run>,
        val dataSize: Long, val initSize: Long, val compressionUnit: Int,
    )

    val bytesPerSector: Int
    val clusterSize: Int
    val totalClusters: Long
    private val recordSize: Int
    private val indexBlockSizeBoot: Int
    private var mftRuns: List<Run>
    private var mftSize: Long
    private val volumeLabel: String
    private val freeClusters: Long

    override val typeName get() = "NTFS"
    override val writeSupport: String get() = "Saving into NTFS volumes isn't supported yet"

    init {
        val bs = ByteArray(512)
        dev.readFully(0, bs, 0, 512)
        if (String(bs, 3, 8, Charsets.US_ASCII) != "NTFS    ") throw corrupt("not NTFS")
        bytesPerSector = Le.u16(bs, 11)
        if (bytesPerSector !in setOf(512, 1024, 2048, 4096)) throw corrupt("bad bytes per sector")
        val spcRaw = Le.u8(bs, 13)
        val spc = if (spcRaw <= 0x80) spcRaw else 1 shl (256 - spcRaw).coerceIn(0, 24)
        if (spc == 0 || spc and (spc - 1) != 0) throw corrupt("bad sectors per cluster")
        val cs = bytesPerSector.toLong() * spc
        if (cs > (2L shl 20)) throw corrupt("cluster size too large")
        clusterSize = cs.toInt()
        val totalSectors = Le.s64(bs, 40)
        if (totalSectors <= 0 || totalSectors > dev.size / bytesPerSector) throw corrupt("bad sector count")
        totalClusters = totalSectors / spc
        val mftLcn = Le.s64(bs, 48)
        recordSize = sizeField(bs[64].toInt())
        indexBlockSizeBoot = sizeField(bs[68].toInt())
        if (recordSize !in 256..65536 || recordSize and (recordSize - 1) != 0) throw corrupt("bad MFT record size")
        if (mftLcn <= 0 || mftLcn >= totalClusters) throw corrupt("bad MFT location")
        // Bootstrap: read $MFT's own record at its LCN, then use its $DATA runs.
        val rec0 = ByteArray(recordSize)
        dev.readFully(mftLcn * clusterSize, rec0, 0, recordSize)
        fixup(rec0, 0, recordSize, "FILE")
        val data0 = parseAttrs(rec0).filter { it.type == 0x80 && it.name.isEmpty() && it.nonResident }.minByOrNull { it.startVcn }
            ?: throw corrupt("\$MFT has no data")
        mftRuns = data0.runs
        mftSize = data0.dataSize
        // A fragmented $MFT keeps further extents in records referenced by its attribute list.
        val full = dataAttrs(0)
        if (full.isNotEmpty()) { mftRuns = full.flatMap { it.runs }; mftSize = full.first().dataSize }
        volumeLabel = try {
            attributes(3).firstOrNull { it.type == 0x60 }?.value?.let { Le.utf16(it, 0, it.size / 2) } ?: ""
        } catch (_: IOException) { "" }
        freeClusters = countFree()
    }

    private fun sizeField(v: Int): Int = if (v > 0) v * clusterSize else if (v in -31..-1) 1 shl (-v) else -1

    override val root = NtfsEntry("", true, 0, 0, 5)
    override val label get() = volumeLabel
    override val totalBytes get() = totalClusters * clusterSize
    override fun freeBytes(): Long { ensureOpen(); return freeClusters * clusterSize }

    // ---- low level ----

    private fun fixup(b: ByteArray, off: Int, size: Int, magic: String) {
        for (i in 0 until 4) if (b[off + i] != magic[i].code.toByte()) throw corrupt("bad $magic record")
        val usaOff = Le.u16(b, off + 4)
        val usaCount = Le.u16(b, off + 6)
        val strides = size / 512
        if (usaCount != strides + 1 || usaOff < 8 || usaOff + usaCount * 2 > size) throw corrupt("bad update sequence")
        val usn = Le.u16(b, off + usaOff)
        for (i in 1..strides) {
            val p = off + i * 512 - 2
            if (Le.u16(b, p) != usn) throw corrupt("torn $magic record")
            b[p] = b[off + usaOff + 2 * i]; b[p + 1] = b[off + usaOff + 2 * i + 1]
        }
    }

    private fun decodeRuns(b: ByteArray, start: Int, end: Int, startVcn: Long): List<Run> {
        val runs = ArrayList<Run>()
        var p = start
        var vcn = startVcn
        var lcn = 0L
        while (p < end) {
            val h = Le.u8(b, p)
            if (h == 0) break
            val ls = h and 0x0F
            val os = h ushr 4
            if (ls == 0 || ls > 8 || os > 8 || p + 1 + ls + os > end) throw corrupt("bad run list")
            var len = 0L
            for (i in 0 until ls) len = len or ((b[p + 1 + i].toLong() and 0xFF) shl (8 * i))
            if (len <= 0 || len > totalClusters) throw corrupt("bad run length")
            if (os == 0) {
                runs += Run(vcn, -1, len)
            } else {
                var d = 0L
                for (i in 0 until os) d = d or ((b[p + 1 + ls + i].toLong() and 0xFF) shl (8 * i))
                if (os < 8 && (d ushr (8 * os - 1)) and 1L == 1L) d = d or (-1L shl (8 * os))
                lcn += d
                if (lcn < 0 || lcn > totalClusters - len) throw corrupt("run outside the volume")
                runs += Run(vcn, lcn, len)
            }
            vcn += len
            if (runs.size > 1_000_000) throw corrupt("run list too long")
            p += 1 + ls + os
        }
        return runs
    }

    private fun parseAttrs(rec: ByteArray): List<Attr> {
        val out = ArrayList<Attr>()
        var p = Le.u16(rec, 20)
        val used = minOf(Le.u32(rec, 24), rec.size.toLong()).toInt()
        while (p + 16 <= used) {
            val type = Le.u32(rec, p)
            if (type == 0xFFFFFFFFL) break
            val len = Le.u32(rec, p + 4)
            if (len < 16 || len % 8 != 0L || p + len > used) throw corrupt("bad attribute length")
            val l = len.toInt()
            val nonRes = rec[p + 8].toInt() != 0
            val nameLen = Le.u8(rec, p + 9)
            val nameOff = Le.u16(rec, p + 10)
            if (nameLen > 0 && nameOff + nameLen * 2 > l) throw corrupt("bad attribute name")
            val name = if (nameLen > 0) Le.utf16(rec, p + nameOff, nameLen) else ""
            val flags = Le.u16(rec, p + 12)
            if (!nonRes) {
                val vlen = Le.u32(rec, p + 16)
                val voff = Le.u16(rec, p + 20)
                if (voff + vlen > l) throw corrupt("bad resident value")
                val v = rec.copyOfRange(p + voff, p + voff + vlen.toInt())
                out += Attr(type.toInt(), name, false, flags, v, 0, emptyList(), vlen, vlen, 0)
            } else {
                if (l < 64) throw corrupt("bad non-resident attribute")
                val startVcn = Le.s64(rec, p + 16)
                val runOff = Le.u16(rec, p + 32)
                val cu = Le.u16(rec, p + 34)
                val dataSize = Le.s64(rec, p + 48)
                val initSize = Le.s64(rec, p + 56)
                if (runOff > l || startVcn < 0 || dataSize < 0 || initSize < 0) throw corrupt("bad non-resident attribute")
                val runs = decodeRuns(rec, p + runOff, p + l, startVcn)
                out += Attr(type.toInt(), name, true, flags, null, startVcn, runs, dataSize, minOf(initSize, dataSize), cu)
            }
            p += l
        }
        return out
    }

    private fun readRuns(runs: List<Run>, pos: Long, buf: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            val p = pos + done
            val vcn = p / clusterSize
            val r = runs.firstOrNull { vcn >= it.vcn && vcn < it.vcn + it.len } ?: throw corrupt("read beyond allocation")
            val inRun = p - r.vcn * clusterSize
            val take = minOf((len - done).toLong(), r.len * clusterSize - inRun).toInt()
            if (r.lcn < 0) buf.fill(0, off + done, off + done + take)
            else dev.readFully(r.lcn * clusterSize + inRun, buf, off + done, take)
            done += take
        }
    }

    private fun readRecord(n: Long): ByteArray {
        if (n < 0 || n >= mftSize / recordSize) throw corrupt("MFT record $n out of range")
        val b = ByteArray(recordSize)
        readRuns(mftRuns, n * recordSize, b, 0, recordSize)
        fixup(b, 0, recordSize, "FILE")
        return b
    }

    private fun attrBytes(a: Attr, limit: Long): ByteArray {
        a.value?.let { return it }
        if (a.dataSize > limit) throw corrupt("attribute too large")
        val b = ByteArray(a.dataSize.toInt())
        val init = minOf(a.initSize, a.dataSize).toInt()
        readRuns(a.runs, 0, b, 0, init)
        return b
    }

    /** All attributes of a file, following its attribute list into extension records. */
    private fun attributes(recNo: Long): List<Attr> {
        val rec = readRecord(recNo)
        if (Le.u16(rec, 22) and 1 == 0) throw corrupt("MFT record $recNo not in use")
        val attrs = parseAttrs(rec)
        val al = attrs.firstOrNull { it.type == 0x20 } ?: return attrs
        val list = attrBytes(al, 4L shl 20)
        val others = LinkedHashSet<Long>()
        var p = 0
        while (p + 26 <= list.size) {
            val len = Le.u16(list, p + 4)
            if (len < 26 || p + len > list.size) throw corrupt("bad attribute list")
            val ref = Le.s64(list, p + 16) and 0xFFFFFFFFFFFFL
            if (ref != recNo) others += ref
            p += len
        }
        if (others.size > 4096) throw corrupt("attribute list too long")
        val out = ArrayList(attrs.filter { it.type != 0x20 })
        for (r in others) {
            val ext = readRecord(r)
            if (Le.s64(ext, 32) and 0xFFFFFFFFFFFFL != recNo) throw corrupt("extension record mismatch")
            out += parseAttrs(ext).filter { it.type != 0x20 }
        }
        return out
    }

    /** Unnamed $DATA extents in VCN order (first one carries sizes). */
    private fun dataAttrs(recNo: Long): List<Attr> =
        attributes(recNo).filter { it.type == 0x80 && it.name.isEmpty() }.sortedBy { it.startVcn }

    private fun countFree(): Long {
        val d = try { dataAttrs(6) } catch (_: IOException) { return 0 }
        val first = d.firstOrNull() ?: return 0
        val need = (totalClusters + 7) / 8
        if (first.dataSize < need || first.dataSize > need + (64L shl 20)) return 0 // implausible: skip
        val runs = d.flatMap { it.runs }
        val buf = ByteArray(1 shl 16)
        var used = 0L
        var pos = 0L
        var remaining = totalClusters
        while (remaining > 0) {
            val n = minOf(buf.size.toLong(), (remaining + 7) / 8).toInt()
            if (first.value != null) System.arraycopy(first.value, pos.toInt(), buf, 0, n) else readRuns(runs, pos, buf, 0, n)
            for (i in 0 until n) {
                val v = buf[i].toInt() and 0xFF
                if (remaining >= 8) { used += Integer.bitCount(v); remaining -= 8 } else {
                    used += Integer.bitCount(v and ((1 shl remaining.toInt()) - 1)); remaining = 0
                }
            }
            pos += n
        }
        return totalClusters - used
    }

    // ---- tree ----

    override fun readDirectory(dir: NtfsEntry): List<NtfsEntry> {
        val attrs = attributes(dir.record)
        val rootAttr = attrs.firstOrNull { it.type == 0x90 && it.name == "\$I30" } ?: throw corrupt("folder has no index")
        val v = rootAttr.value ?: throw corrupt("non-resident index root")
        if (v.size < 32) throw corrupt("bad index root")
        val blockSize = Le.u32(v, 8).toInt()
        val out = LinkedHashMap<String, NtfsEntry>()
        parseNode(v, 16, v.size, out)
        val alloc = attrs.filter { it.type == 0xA0 && it.name == "\$I30" }.sortedBy { it.startVcn }
        if (alloc.isNotEmpty()) {
            if (blockSize !in 512..65536 || blockSize and (blockSize - 1) != 0) throw corrupt("bad index block size")
            val runs = alloc.flatMap { it.runs }
            val bmAttr = attrs.firstOrNull { it.type == 0xB0 && it.name == "\$I30" }
            val bitmap = bmAttr?.let { attrBytes(it, 16L shl 20) }
            val blocks = alloc.first().dataSize / blockSize
            if (blocks > 1_000_000) throw corrupt("index too large")
            val blk = ByteArray(blockSize)
            for (i in 0 until blocks) {
                if (bitmap != null) {
                    val byte = (i / 8).toInt()
                    if (byte >= bitmap.size || (bitmap[byte].toInt() shr (i % 8).toInt()) and 1 == 0) continue
                }
                readRuns(runs, i * blockSize, blk, 0, blockSize)
                fixup(blk, 0, blockSize, "INDX")
                parseNode(blk, 0x18, blockSize, out)
                if (out.size > 2_000_000) throw corrupt("index too large")
            }
        }
        return out.values.toList()
    }

    /** Parses an index node whose header starts at [hdr]; entries are bounded by [limit]. */
    private fun parseNode(b: ByteArray, hdr: Int, limit: Int, out: MutableMap<String, NtfsEntry>) {
        if (hdr + 16 > limit) throw corrupt("bad index node")
        val start = hdr + Le.u32(b, hdr).toInt()
        val end = hdr + Le.u32(b, hdr + 4).toInt()
        if (start < hdr + 16 || end > limit || start > end) throw corrupt("bad index node bounds")
        var p = start
        while (p + 16 <= end) {
            val len = Le.u16(b, p + 8)
            val keyLen = Le.u16(b, p + 10)
            val flags = Le.u16(b, p + 12)
            if (flags and 2 != 0) break
            if (len < 16 || len % 8 != 0 || p + len > end || 16 + keyLen > len) throw corrupt("bad index entry")
            if (keyLen >= 66) {
                val k = p + 16
                val nameLen = Le.u8(b, k + 64)
                val ns = Le.u8(b, k + 65)
                val ref = Le.s64(b, p) and 0xFFFFFFFFFFFFL
                if (66 + nameLen * 2 > keyLen) throw corrupt("bad index key")
                if (ns != 2 && ref >= 24) {
                    val name = Le.utf16(b, k + 66, nameLen)
                    val isDir = Le.u32(b, k + 56) and 0x10000000L != 0L
                    val e = NtfsEntry(name, isDir, Le.s64(b, k + 48).coerceAtLeast(0), fileTime(Le.s64(b, k + 16)), ref)
                    out.putIfAbsent("$ref/${key(name)}", e)
                }
            }
            p += len
        }
    }

    override fun openEntry(entry: NtfsEntry, path: String): FsFileHandle {
        val d = dataAttrs(entry.record)
        val first = d.firstOrNull() ?: throw IOException("File has no data: $path")
        if (first.flags and 0x4000 != 0) throw IOException("This file is EFS-encrypted, which isn't supported")
        if (first.flags and 0x0001 != 0 || first.compressionUnit != 0) {
            throw IOException("This file is NTFS-compressed, which isn't supported")
        }
        if (first.value != null) {
            val bytes = first.value
            return object : FsFileHandle {
                override val size = bytes.size.toLong()
                @Volatile private var open = true
                override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    if (!open || closed) throw IOException("Volume is locked")
                    if (position >= size) return -1
                    val n = minOf(length.toLong(), size - position).toInt()
                    System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                    return n
                }
                override fun close() { open = false; bytes.fill(0) }
            }
        }
        val rb = RunBuilder()
        var expectVcn = 0L
        for (a in d) for (r in a.runs) {
            if (r.vcn != expectVcn) throw corrupt("run list gap")
            rb.add(if (r.lcn < 0) -1 else r.lcn * clusterSize, r.len * clusterSize)
            expectVcn += r.len
        }
        return rb.handle(dev, first.dataSize, first.initSize) { !closed }
    }

    companion object {
        /** FILETIME (100 ns since 1601) → epoch millis; 0 if unset. */
        internal fun fileTime(ft: Long): Long = if (ft <= 0) 0 else ft / 10_000 - 11_644_473_600_000L
    }
}

