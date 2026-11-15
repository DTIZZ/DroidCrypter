package app.droidcrypter.engine.fs

import app.droidcrypter.engine.BlockDevice
import app.droidcrypter.engine.FsFileHandle
import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.FsPaths
import app.droidcrypter.engine.Secrets
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.concurrent.write

/**
 * exFAT reader and writer per Microsoft's specification: FAT-chained and contiguous (NoFatChain)
 * allocations, ValidDataLength (bytes past it read as zeros), timestamps with 10 ms and UTC-offset
 * fields, entry set checksums, NameHash via the volume's up-case table, and free space counted
 * from the allocation bitmap.
 */
class ExFatFileSystem(dev: BlockDevice) : TreeFileSystem<ExFatFileSystem.ExEntry>(dev) {

    class ExEntry internal constructor(
        override val name: String,
        override val isDirectory: Boolean,
        override val size: Long,
        override val lastModifiedMillis: Long,
        val firstCluster: Long,
        val noFatChain: Boolean,
        val validDataLength: Long,
        /** Device offsets of every 32-byte entry of the set (0x85 first). */
        internal val slots: LongArray,
    ) : Entry()

    val bytesPerSector: Int
    val clusterSize: Int
    val clusterCount: Long
    private val heapOffset: Long
    private val rootCluster: Long
    internal val fat: FatTable
    internal val fatOffsetBytes: Long

    internal var bitmapCluster = 0L; private set
    internal var bitmapLength = 0L; private set
    internal var bitmapNoFatChain = false; private set
    internal var upcaseCluster = 0L; private set
    internal var upcaseLength = 0L; private set
    internal var upcaseNoFatChain = false; private set
    private var volumeLabel = ""

    @Volatile
    private var freeCache = -1L

    /** Cached 65536-entry up-case mapping decoded from the volume's Up-case Table. */
    private val upcaseTable: CharArray by lazy { loadUpcaseTable() }

    /** Cached allocation bitmap bytes (allocated on first bitmap read/write). */
    private var bitmapCache: ByteArray? = null

    override val typeName get() = "exFAT"

    init {
        val bs = ByteArray(512)
        dev.readFully(0, bs, 0, 512)
        if (String(bs, 3, 8, Charsets.US_ASCII) != "EXFAT   ") throw corrupt("not exFAT")
        val bpsShift = Le.u8(bs, 108)
        val spcShift = Le.u8(bs, 109)
        if (bpsShift !in 9..12 || spcShift > 25 - bpsShift) throw corrupt("bad exFAT geometry")
        bytesPerSector = 1 shl bpsShift
        clusterSize = 1 shl (bpsShift + spcShift)
        val fatOffset = Le.u32(bs, 80)
        val fatLength = Le.u32(bs, 84)
        heapOffset = Le.u32(bs, 88) * bytesPerSector
        clusterCount = Le.u32(bs, 92)
        rootCluster = Le.u32(bs, 96)
        if (clusterCount == 0L || clusterCount > 0xFFFFFFF5L) throw corrupt("bad cluster count")
        if (heapOffset + clusterCount * clusterSize > dev.size) throw corrupt("cluster heap beyond the volume")
        fatOffsetBytes = fatOffset * bytesPerSector
        if (fatOffsetBytes + fatLength * bytesPerSector > dev.size) throw corrupt("FAT beyond the volume")
        fat = FatTable(dev, fatOffsetBytes, fatLength * bytesPerSector, 1, 32, clusterCount, exfat = true)
        fat.ensureSane()
        if (rootCluster < 2 || rootCluster > clusterCount + 1) throw corrupt("bad root cluster")
        scanRootMetadata()
    }

    override val root = ExEntry("", true, 0, 0, rootCluster, false, 0, LongArray(0))

    override val label get() = volumeLabel
    override val totalBytes get() = clusterCount * clusterSize
    override val writeSupport: String? get() = if (dev.isWritable) null else "This volume is read-only"

    internal fun clusterOffset(c: Long) = heapOffset + (c - 2) * clusterSize

    private val maxDirClusters get() = maxOf(1L, (256L shl 20) / clusterSize)

    /** Cluster list of an allocation. */
    internal fun clusters(first: Long, length: Long, noFatChain: Boolean, isDir: Boolean): LongArray {
        if (length == 0L) return LongArray(0)
        if (first < 2 || first > clusterCount + 1) throw corrupt("cluster $first out of range")
        val needed = (length + clusterSize - 1) / clusterSize
        if (isDir && needed > maxDirClusters) throw corrupt("directory too large")
        if (noFatChain) {
            if (needed > clusterCount + 2 - first) throw corrupt("contiguous run beyond the heap")
            if (needed > Int.MAX_VALUE) throw corrupt("allocation too large")
            return LongArray(needed.toInt()) { first + it }
        }
        if (needed > Int.MAX_VALUE) throw corrupt("allocation too large")
        val chain = fat.chain(first, needed, true)
        if (chain.size < needed) throw corrupt("cluster chain shorter than the data length")
        return chain
    }

    internal fun readDirBytes(dir: ExEntry): Pair<ByteArray, LongArray> {
        val cl = if (dir === root || dir.firstCluster == rootCluster && dir.slots.isEmpty()) {
            fat.chain(rootCluster, maxDirClusters, false)
        } else {
            clusters(dir.firstCluster, dir.size, dir.noFatChain, true)
        }
        val b = ByteArray(cl.size * clusterSize)
        val per = clusterSize / 32
        val pos = LongArray(cl.size * per)
        for ((i, c) in cl.withIndex()) {
            val base = clusterOffset(c)
            dev.readFully(base, b, i * clusterSize, clusterSize)
            for (k in 0 until per) pos[i * per + k] = base + k * 32L
        }
        return b to pos
    }

    private fun scanRootMetadata() {
        val (b, _) = readDirBytes(ExEntry("", true, 0, 0, rootCluster, false, 0, LongArray(0)))
        var i = 0
        while (i + 32 <= b.size) {
            val t = Le.u8(b, i)
            if (t == 0) break
            when (t) {
                0x81 -> if (bitmapCluster == 0L && Le.u8(b, i + 1) and 1 == 0) {
                    bitmapNoFatChain = Le.u8(b, i + 1) and 0x02 != 0
                    bitmapCluster = Le.u32(b, i + 20)
                    bitmapLength = Le.s64(b, i + 24)
                }
                0x82 -> {
                    upcaseNoFatChain = Le.u8(b, i + 1) and 0x02 != 0
                    upcaseCluster = Le.u32(b, i + 20)
                    upcaseLength = Le.s64(b, i + 24)
                }
                0x83 -> { val n = minOf(Le.u8(b, i + 1), 11); volumeLabel = Le.utf16(b, i + 2, n) }
            }
            i += 32
        }
        if (bitmapCluster == 0L) throw corrupt("no allocation bitmap")
    }

    override fun readDirectory(dir: ExEntry): List<ExEntry> {
        val (b, pos) = readDirBytes(dir)
        val out = ArrayList<ExEntry>()
        val total = b.size / 32
        var s = 0
        while (s < total) {
            val o = s * 32
            val t = Le.u8(b, o)
            if (t == 0) break
            if (t != 0x85) { s++; continue }
            val sc = Le.u8(b, o + 1)
            if (sc < 2 || sc > 18 || s + sc >= total) { s++; continue }
            val so = o + 32
            if (Le.u8(b, so) != 0xC0) { s++; continue }
            if (setChecksum(b, o, sc + 1) != Le.u16(b, o + 2)) { s++; continue }
            val nameLen = Le.u8(b, so + 3)
            val nameEntries = (nameLen + 14) / 15
            if (nameLen == 0 || nameEntries > sc - 1) { s += sc + 1; continue }
            val sb = StringBuilder(nameLen)
            var ok = true
            for (k in 0 until nameEntries) {
                val no = o + (2 + k) * 32
                if (Le.u8(b, no) != 0xC1) { ok = false; break }
                val take = minOf(15, nameLen - k * 15)
                sb.append(Le.utf16(b, no + 2, take))
            }
            if (ok) {
                val attrs = Le.u16(b, o + 4)
                val flags = Le.u8(b, so + 1)
                val dataLength = Le.s64(b, so + 24)
                val valid = Le.s64(b, so + 8)
                if (dataLength < 0 || valid < 0 || valid > dataLength) throw corrupt("bad stream lengths")
                val mtime = exTime(Le.u32(b, o + 12), Le.u8(b, o + 21), Le.u8(b, o + 23))
                val slots = LongArray(sc + 1) { pos[s + it] }
                out += ExEntry(sb.toString(), attrs and 0x10 != 0, dataLength, mtime,
                    Le.u32(b, so + 20), flags and 0x02 != 0, valid, slots)
            }
            s += sc + 1
        }
        return out
    }

    override fun openEntry(entry: ExEntry, path: String): FsFileHandle {
        val rb = RunBuilder()
        for (c in clusters(entry.firstCluster, entry.size, entry.noFatChain, false)) rb.add(clusterOffset(c), clusterSize.toLong())
        return rb.handle(dev, entry.size, entry.validDataLength) { !closed }
    }

    override fun freeBytes(): Long {
        ensureOpen()
        val l = fsLock.writeLock()
        l.lock()
        try {
            if (freeCache < 0) freeCache = countFreeClusters()
            return freeCache * clusterSize
        } finally {
            l.unlock()
        }
    }

    /** Invalidates the cached free-cluster count (any bitmap change). */
    internal fun invalidateFree() {
        freeCache = -1
    }

    private fun ensureBitmapLoaded(): ByteArray {
        bitmapCache?.let { return it }
        val needBytes = (clusterCount + 7) / 8
        if (bitmapLength < needBytes) throw corrupt("allocation bitmap too small")
        if (bitmapLength > needBytes + clusterSize || bitmapLength > MAX_BITMAP) {
            throw corrupt("allocation bitmap implausibly large")
        }
        val cl = clusters(bitmapCluster, needBytes, bitmapNoFatChain, false)
        val full = ByteArray(cl.size * clusterSize)
        for ((i, c) in cl.withIndex()) {
            dev.readFully(clusterOffset(c), full, i * clusterSize, clusterSize)
        }
        val trimmed = full.copyOf(needBytes.toInt())
        bitmapCache = trimmed
        return trimmed
    }

    private fun isClusterAllocated(bm: ByteArray, c: Long): Boolean {
        val idx = (c - 2).toInt()
        return (bm[idx ushr 3].toInt() and (1 shl (idx and 7))) != 0
    }

    private fun setClusterAllocated(bm: ByteArray, c: Long, allocated: Boolean) {
        val idx = (c - 2).toInt()
        val byteIdx = idx ushr 3
        val bit = 1 shl (idx and 7)
        val cur = bm[byteIdx].toInt() and 0xFF
        bm[byteIdx] = (if (allocated) cur or bit else cur and bit.inv()).toByte()
        invalidateFree()
    }

    private fun flushBitmap(bm: ByteArray) {
        val needBytes = (clusterCount + 7) / 8
        val cl = clusters(bitmapCluster, needBytes, bitmapNoFatChain, false)
        val buf = ByteArray(clusterSize)
        for ((i, c) in cl.withIndex()) {
            val start = i * clusterSize
            val copyLen = minOf(clusterSize, bm.size - start)
            buf.fill(0)
            if (copyLen > 0) System.arraycopy(bm, start, buf, 0, copyLen)
            dev.write(clusterOffset(c), buf, 0, clusterSize)
        }
    }

    private fun countFreeClusters(): Long {
        val needBytes = (clusterCount + 7) / 8
        if (bitmapLength < needBytes) throw corrupt("allocation bitmap too small")
        if (bitmapLength > needBytes + clusterSize || bitmapLength > MAX_BITMAP) return 0 // implausible: skip
        val bm = try {
            ensureBitmapLoaded()
        } catch (_: IOException) {
            return 0
        }
        var used = 0L
        var remaining = clusterCount
        for (b in bm) {
            if (remaining <= 0) break
            val v = b.toInt() and 0xFF
            if (remaining >= 8) {
                used += Integer.bitCount(v)
                remaining -= 8
            } else {
                used += Integer.bitCount(v and ((1 shl remaining.toInt()) - 1))
                remaining = 0
            }
        }
        return clusterCount - used
    }

    // ---- writers ----

    override fun createFile(
        dir: String,
        name: String,
        size: Long,
        source: (Long, ByteArray, Int, Int) -> Unit,
        onProgress: (Long) -> Unit,
    ): FsNode = fsLock.write {
        ensureOpen()
        if (size < 0) throw IOException("Negative file size")
        val (dirPath, dirEntry) = resolve(dir) ?: throw IOException("Folder not found: $dir")
        if (!dirEntry.isDirectory) throw IOException("Not a folder: $dir")

        val existing = listing(dirEntry, dirPath).entries
        val finalName = FsWriteSupport.uniqueName(name, existing.map { key(it.name) }.toSet())
        val neededClusters = if (size == 0L) 0 else ((size + clusterSize - 1) / clusterSize).toInt()
        val bm = ensureBitmapLoaded()
        val (chosenClusters, isContiguous) = findFreeClusters(bm, neededClusters)

        // 1. Write file data into still-free clusters first (crash-safe ordering).
        val buf = ByteArray(minOf(maxOf(clusterSize, 65536), 262144))
        try {
            var written = 0L
            var cIdx = 0
            var inCluster = 0
            while (written < size) {
                val want = minOf((size - written).toInt(), buf.size)
                source(written, buf, 0, want)
                var off = 0
                while (off < want) {
                    val c = chosenClusters[cIdx]
                    val spaceInCluster = clusterSize - inCluster
                    val chunk = minOf(want - off, spaceInCluster)
                    dev.write(clusterOffset(c) + inCluster, buf, off, chunk)
                    off += chunk
                    inCluster += chunk
                    if (inCluster == clusterSize) {
                        cIdx++
                        inCluster = 0
                    }
                }
                if (inCluster > 0 && written + want == size) {
                    val pad = ByteArray(clusterSize - inCluster)
                    dev.write(clusterOffset(chosenClusters[cIdx]) + inCluster, pad, 0, pad.size)
                }
                written += want
                onProgress(written)
            }
        } finally {
            Secrets.wipe(buf)
        }

        val now = System.currentTimeMillis()
        val firstCluster = if (chosenClusters.isEmpty()) 0L else chosenClusters[0]
        val noFatChain = chosenClusters.isEmpty() || isContiguous
        val entryBytes = buildEntrySet(finalName, false, firstCluster, noFatChain, size, size, now)
        val slotCount = entryBytes.size / 32

        // Reserve directory slots (may grow the directory by 1 cluster, excluding chosenClusters).
        val slotOffsets = allocateDirSlots(bm, dirEntry, dirPath, slotCount, chosenClusters.toSet())

        // 2. Record cluster allocation in bitmap (and FAT if non-contiguous).
        for (c in chosenClusters) setClusterAllocated(bm, c, true)
        if (!noFatChain && chosenClusters.isNotEmpty()) {
            for (i in chosenClusters.indices) {
                val next = if (i + 1 < chosenClusters.size) chosenClusters[i + 1] else fat.eocMark
                fat.set(chosenClusters[i], next)
            }
        }
        fat.flush()
        flushBitmap(bm)

        // 3. Write directory entry set last: secondary entries (0xC0, 0xC1...) first, primary 0x85 entry last!
        writeEntrySetCrashSafe(slotOffsets, entryBytes)
        dev.sync()

        invalidate(dirPath)
        val fullPath = FsPaths.join(dirPath, finalName)
        return FsNode(fullPath, finalName, false, size, (now / 10) * 10)
    }

    override fun createDirectory(dir: String, name: String, autoRename: Boolean): FsNode = fsLock.write {
        ensureOpen()
        val (dirPath, dirEntry) = resolve(dir) ?: throw IOException("Folder not found: $dir")
        if (!dirEntry.isDirectory) throw IOException("Not a folder: $dir")
        val existing = listing(dirEntry, dirPath).entries
        val finalName = FsWriteSupport.newFolderName(name, existing.map { key(it.name) }.toSet(), autoRename)
        val bm = ensureBitmapLoaded()
        val cluster = findFreeClusters(bm, 1).first[0]
        val now = System.currentTimeMillis()

        // 1. Zero the still-free cluster: an empty exFAT directory is one cluster of end markers.
        dev.write(clusterOffset(cluster), ByteArray(clusterSize), 0, clusterSize)

        // A directory's ValidDataLength always equals its DataLength; one cluster is contiguous.
        val entryBytes = buildEntrySet(finalName, true, cluster, true, clusterSize.toLong(), clusterSize.toLong(), now)
        val slotOffsets = allocateDirSlots(bm, dirEntry, dirPath, entryBytes.size / 32, setOf(cluster))

        // 2. Record the allocation, 3. write the entry set last.
        setClusterAllocated(bm, cluster, true)
        fat.flush()
        flushBitmap(bm)
        writeEntrySetCrashSafe(slotOffsets, entryBytes)
        dev.sync()

        invalidate(dirPath)
        FsNode(FsPaths.join(dirPath, finalName), finalName, true, 0L, (now / 10) * 10)
    }

    override fun delete(path: String, overwrite: Boolean, onProgress: (Long) -> Unit) = fsLock.write {
        ensureOpen()
        val norm = FsPaths.normalize(path)
        if (norm == "/") throw IOException("Cannot delete the root folder")
        val (real, entry) = resolve(norm) ?: throw IOException("File or folder not found: $path")
        val parentPath = FsPaths.parent(real)
        val bm = ensureBitmapLoaded()
        var progress = 0L
        deleteEntryRecursive(bm, entry, real, overwrite, depth = 0) { delta ->
            progress += delta
            onProgress(progress)
        }
        fat.flush()
        flushBitmap(bm)
        dev.sync()
        invalidate(parentPath)
        invalidateSubtree(real)
    }

    private fun deleteEntryRecursive(
        bm: ByteArray,
        entry: ExEntry,
        path: String,
        overwrite: Boolean,
        depth: Int,
        addProgress: (Long) -> Unit,
    ) {
        if (depth > 64) throw corrupt("directory nesting too deep (cycle?)")
        if (entry.isDirectory) {
            val children = readDirectory(entry)
            for (child in children) {
                deleteEntryRecursive(bm, child, FsPaths.join(path, child.name), overwrite, depth + 1, addProgress)
            }
        }
        val cl = if (entry.firstCluster >= 2 && entry.size > 0) {
            clusters(entry.firstCluster, entry.size, entry.noFatChain, entry.isDirectory)
        } else {
            LongArray(0)
        }

        // 1. Remove/retire directory entry set first (crash-safe ordering).
        retireEntrySet(entry.slots, overwrite)
        if (overwrite) {
            addProgress(entry.slots.size * 32L)
            val zeros = ByteArray(clusterSize)
            for (c in cl) {
                dev.write(clusterOffset(c), zeros, 0, clusterSize)
                addProgress(clusterSize.toLong())
            }
        }
        // 2. Free clusters in bitmap and FAT.
        for (c in cl) {
            setClusterAllocated(bm, c, false)
            if (!entry.noFatChain) fat.set(c, 0L)
        }
    }

    override fun rename(path: String, newName: String): String = fsLock.write {
        ensureOpen()
        FsWriteSupport.checkName(newName)
        val norm = FsPaths.normalize(path)
        if (norm == "/") throw IOException("Cannot rename the root folder")
        val (real, entry) = resolve(norm) ?: throw IOException("File or folder not found: $path")
        val parentPath = FsPaths.parent(real)
        val (_, parentEntry) = resolve(parentPath) ?: throw IOException("Parent folder not found")

        if (real.substringAfterLast('/') == newName) return real
        val siblings = listing(parentEntry, parentPath).entries
        val clash = siblings.firstOrNull { key(it.name) == key(newName) && key(it.name) != key(entry.name) }
        if (clash != null) throw IOException("\"$newName\" already exists in this folder")

        val bm = ensureBitmapLoaded()
        val entryBytes = buildEntrySet(
            newName, entry.isDirectory, entry.firstCluster, entry.noFatChain,
            entry.size, entry.validDataLength, entry.lastModifiedMillis
        )
        val slotCount = entryBytes.size / 32
        val newSlots = allocateDirSlots(bm, parentEntry, parentPath, slotCount, emptySet())
        fat.flush()
        flushBitmap(bm)

        // Write new entry set first, then retire the old one.
        writeEntrySetCrashSafe(newSlots, entryBytes)
        retireEntrySet(entry.slots, overwrite = false)
        dev.sync()

        val newPath = FsPaths.join(parentPath, newName)
        invalidate(parentPath)
        if (entry.isDirectory) invalidateSubtree(real)
        return newPath
    }

    override fun move(path: String, targetDir: String): String = fsLock.write {
        ensureOpen()
        val normSrc = FsPaths.normalize(path)
        val normDst = FsPaths.normalize(targetDir)
        if (normSrc == "/") throw IOException("Cannot move the root folder")
        val (realSrc, entry) = resolve(normSrc) ?: throw IOException("File or folder not found: $path")
        val (realDst, dstEntry) = resolve(normDst) ?: throw IOException("Target folder not found: $targetDir")
        if (!dstEntry.isDirectory) throw IOException("Target is not a folder: $targetDir")

        FsWriteSupport.checkMoveTarget(realSrc, entry.name, entry.isDirectory, realDst)
        val srcParentPath = FsPaths.parent(realSrc)
        if (key(srcParentPath) == key(realDst)) return realSrc

        val dstListing = listing(dstEntry, realDst).entries
        val finalName = FsWriteSupport.uniqueName(entry.name, dstListing.map { key(it.name) }.toSet())
        val bm = ensureBitmapLoaded()
        val entryBytes = buildEntrySet(
            finalName, entry.isDirectory, entry.firstCluster, entry.noFatChain,
            entry.size, entry.validDataLength, entry.lastModifiedMillis
        )
        val slotCount = entryBytes.size / 32
        val newSlots = allocateDirSlots(bm, dstEntry, realDst, slotCount, emptySet())
        fat.flush()
        flushBitmap(bm)

        // Write new entry set first, then retire the old one (exFAT has no ".." entry).
        writeEntrySetCrashSafe(newSlots, entryBytes)
        retireEntrySet(entry.slots, overwrite = false)
        dev.sync()

        invalidate(srcParentPath)
        invalidate(realDst)
        if (entry.isDirectory) invalidateSubtree(realSrc)
        return FsPaths.join(realDst, finalName)
    }

    // ---- helpers ----

    /** Finds [count] free clusters in [bm], preferring a single contiguous run (so NoFatChain can be set). */
    private fun findFreeClusters(bm: ByteArray, count: Int, exclude: Set<Long> = emptySet()): Pair<LongArray, Boolean> {
        if (count == 0) return LongArray(0) to true
        var runStart = 2L
        var runLen = 0
        for (c in 2L..clusterCount + 1) {
            if (c !in exclude && !isClusterAllocated(bm, c)) {
                if (runLen == 0) runStart = c
                runLen++
                if (runLen == count) return LongArray(count) { runStart + it } to true
            } else {
                runLen = 0
            }
        }
        val out = LongArray(count)
        var n = 0
        for (c in 2L..clusterCount + 1) {
            if (c !in exclude && !isClusterAllocated(bm, c)) {
                out[n++] = c
                if (n == count) return out to false
            }
        }
        throw IOException("Not enough free space in the volume")
    }

    /**
     * Finds [slotCount] contiguous free 32-byte slots in [dir], growing the directory by 1 cluster
     * if full. When a contiguous (NoFatChain) subdirectory grows:
     * - if the immediately following cluster is free, it stays contiguous (NoFatChain = true);
     * - otherwise it is converted into a FAT chain and its Stream Extension (0xC0) + File (0x85)
     *   set checksum in its parent directory are updated.
     */
    private fun allocateDirSlots(
        bm: ByteArray,
        dir: ExEntry,
        dirPath: String,
        slotCount: Int,
        excludeClusters: Set<Long>,
    ): LongArray {
        val (b, pos) = readDirBytes(dir)
        val totalSlots = pos.size
        var endMarkerSlot = totalSlots
        for (s in 0 until totalSlots) {
            if (Le.u8(b, s * 32) == 0x00) {
                endMarkerSlot = s
                break
            }
        }
        // Look for a contiguous run of deleted (bit 7 == 0, non-zero) slots before endMarkerSlot.
        var runStart = 0
        var runLen = 0
        for (s in 0 until endMarkerSlot) {
            val t = Le.u8(b, s * 32)
            if (t and 0x80 == 0) {
                if (runLen == 0) runStart = s
                runLen++
                if (runLen == slotCount) {
                    return LongArray(slotCount) { pos[runStart + it] }
                }
            } else {
                runLen = 0
            }
        }
        val tailStart = if (runLen > 0) runStart else endMarkerSlot
        if (tailStart + slotCount <= totalSlots) {
            return LongArray(slotCount) { pos[tailStart + it] }
        }

        // Need to grow the directory by 1 cluster.
        val isRoot = dir === root || dir.firstCluster == rootCluster && dir.slots.isEmpty()
        val oldClusters = if (isRoot) {
            fat.chain(rootCluster, maxDirClusters, false)
        } else {
            clusters(dir.firstCluster, dir.size, dir.noFatChain, true)
        }
        if (oldClusters.size + 1 > maxDirClusters) throw IOException("Directory has reached the maximum 256 MB size")

        val nextContig = oldClusters.last() + 1
        val canStayContig = !isRoot && dir.noFatChain &&
            nextContig <= clusterCount + 1 &&
            nextContig !in excludeClusters &&
            !isClusterAllocated(bm, nextContig)

        val newCluster = if (canStayContig) {
            nextContig
        } else {
            findFreeClusters(bm, 1, excludeClusters).first[0]
        }

        val zeros = ByteArray(clusterSize)
        dev.write(clusterOffset(newCluster), zeros, 0, clusterSize)
        setClusterAllocated(bm, newCluster, true)

        if (isRoot) {
            fat.set(oldClusters.last(), newCluster)
            fat.set(newCluster, fat.eocMark)
        } else {
            val newSize = (oldClusters.size + 1L) * clusterSize
            val newNoFatChain = canStayContig
            if (!newNoFatChain) {
                if (dir.noFatChain) {
                    // Convert previously contiguous clusters into a FAT chain.
                    for (i in 0 until oldClusters.size - 1) {
                        fat.set(oldClusters[i], oldClusters[i + 1])
                    }
                }
                fat.set(oldClusters.last(), newCluster)
                fat.set(newCluster, fat.eocMark)
            }
            updateSubdirStreamEntry(dir, newSize, newNoFatChain)
            invalidate(FsPaths.parent(dirPath))
        }

        val per = clusterSize / 32
        val newPos = LongArray(totalSlots + per) { idx ->
            if (idx < totalSlots) pos[idx] else clusterOffset(newCluster) + (idx - totalSlots) * 32L
        }
        return LongArray(slotCount) { newPos[tailStart + it] }
    }

    private fun updateSubdirStreamEntry(dir: ExEntry, newSize: Long, newNoFatChain: Boolean) {
        val slots = dir.slots
        if (slots.size < 3) return
        val raw = ByteArray(slots.size * 32)
        for (i in slots.indices) {
            dev.readFully(slots[i], raw, i * 32, 32)
        }
        val so = 32
        val flags = (Le.u8(raw, so + 1) and 0xFD) or (if (newNoFatChain) 0x02 else 0x00)
        raw[so + 1] = flags.toByte()
        Le.put64(raw, so + 8, newSize)
        Le.put64(raw, so + 24, newSize)
        val chk = setChecksum(raw, 0, slots.size)
        Le.put16(raw, 2, chk)
        // Secondary entries first, primary 0x85 entry last.
        writeEntrySetCrashSafe(slots, raw)
    }

    /** Writes secondary entries (1..N-1) first, and the primary 0x85 entry (0) last. */
    private fun writeEntrySetCrashSafe(slots: LongArray, entryBytes: ByteArray) {
        for (i in 1 until slots.size) {
            dev.write(slots[i], entryBytes, i * 32, 32)
        }
        dev.write(slots[0], entryBytes, 0, 32)
    }

    private fun retireEntrySet(slots: LongArray, overwrite: Boolean) {
        for (off in slots) {
            if (overwrite) {
                // Zero all 32 bytes and set type byte to 0x05 (deleted entry, not 0x00 which is end-of-dir).
                val z = ByteArray(32)
                z[0] = 0x05
                dev.write(off, z, 0, 32)
            } else {
                val b = ByteArray(1)
                dev.readFully(off, b, 0, 1)
                b[0] = (b[0].toInt() and 0x7F).toByte()
                dev.write(off, b, 0, 1)
            }
        }
    }

    private fun buildEntrySet(
        name: String,
        isDir: Boolean,
        firstCluster: Long,
        noFatChain: Boolean,
        dataLength: Long,
        validLength: Long,
        mtimeMillis: Long,
    ): ByteArray {
        val nameEntries = (name.length + 14) / 15
        val totalEntries = 2 + nameEntries
        val secondaryCount = totalEntries - 1
        val out = ByteArray(totalEntries * 32)

        // Primary 0x85 File Directory Entry.
        out[0] = 0x85.toByte()
        out[1] = secondaryCount.toByte()
        Le.put16(out, 4, if (isDir) 0x0010 else 0x0020)
        val (ts, tenMs, utcOff) = encodeExTime(mtimeMillis)
        Le.put32(out, 8, ts)
        Le.put32(out, 12, ts)
        Le.put32(out, 16, ts)
        out[20] = tenMs.toByte()
        out[21] = tenMs.toByte()
        out[22] = utcOff.toByte()
        out[23] = utcOff.toByte()
        out[24] = utcOff.toByte()

        // Secondary 0xC0 Stream Extension Directory Entry.
        val so = 32
        out[so] = 0xC0.toByte()
        val hasAlloc = firstCluster >= 2 && dataLength > 0
        out[so + 1] = ((if (hasAlloc) 0x01 else 0x00) or (if (noFatChain && hasAlloc) 0x02 else 0x00)).toByte()
        out[so + 3] = name.length.toByte()
        Le.put16(out, so + 4, nameHash(name))
        Le.put64(out, so + 8, if (hasAlloc) validLength else 0L)
        Le.put32(out, so + 20, if (hasAlloc) firstCluster else 0L)
        Le.put64(out, so + 24, if (hasAlloc) dataLength else 0L)

        // Secondary 0xC1 File Name Directory Entries.
        for (k in 0 until nameEntries) {
            val no = (2 + k) * 32
            out[no] = 0xC1.toByte()
            out[no + 1] = 0
            val base = k * 15
            val take = minOf(15, name.length - base)
            for (c in 0 until take) {
                Le.put16(out, no + 2 + c * 2, name[base + c].code)
            }
        }

        val chk = setChecksum(out, 0, totalEntries)
        Le.put16(out, 2, chk)
        return out
    }

    internal fun nameHash(name: String): Int {
        val up = upcaseTable
        var hash = 0
        for (c in name) {
            val u = up[c.code].code
            val b0 = u and 0xFF
            val b1 = (u ushr 8) and 0xFF
            hash = (((hash and 1) shl 15) or (hash ushr 1)) + b0
            hash = hash and 0xFFFF
            hash = (((hash and 1) shl 15) or (hash ushr 1)) + b1
            hash = hash and 0xFFFF
        }
        return hash
    }

    private fun loadUpcaseTable(): CharArray {
        val table = CharArray(65536) { it.toChar().uppercaseChar() }
        if (upcaseCluster < 2 || upcaseLength <= 0 || upcaseLength > 262144) return table
        return try {
            val cl = clusters(upcaseCluster, upcaseLength, upcaseNoFatChain, false)
            val full = ByteArray(cl.size * clusterSize)
            for ((i, c) in cl.withIndex()) {
                dev.readFully(clusterOffset(c), full, i * clusterSize, clusterSize)
            }
            val out = CharArray(65536) { it.toChar() }
            var p = 0
            var idx = 0
            val end = minOf(full.size, upcaseLength.toInt())
            while (p + 2 <= end && idx < 65536) {
                val w = Le.u16(full, p)
                p += 2
                if (w == 0xFFFF && p + 2 <= end) {
                    val skip = Le.u16(full, p)
                    p += 2
                    idx = (idx + skip).coerceAtMost(65536)
                } else {
                    out[idx++] = w.toChar()
                }
            }
            out
        } catch (_: Exception) {
            table
        }
    }

    companion object {
        private const val MAX_BITMAP = 512L shl 20

        internal fun setChecksum(b: ByteArray, o: Int, entries: Int): Int {
            var chk = 0
            for (i in 0 until entries * 32) {
                if (i == 2 || i == 3) continue
                chk = (((chk and 1) shl 15) or (chk ushr 1)) + (b[o + i].toInt() and 0xFF)
                chk = chk and 0xFFFF
            }
            return chk
        }

        /** exFAT timestamp + 10 ms increment + UTC offset → epoch millis; 0 if unset/invalid. */
        internal fun exTime(ts: Long, tenMs: Int, utc: Int): Long {
            if (ts == 0L) return 0
            val sec = ((ts and 0x1F) * 2).toInt()
            val min = ((ts ushr 5) and 0x3F).toInt()
            val hour = ((ts ushr 11) and 0x1F).toInt()
            val day = ((ts ushr 16) and 0x1F).toInt()
            val month = ((ts ushr 21) and 0x0F).toInt()
            val year = 1980 + ((ts ushr 25) and 0x7F).toInt()
            return try {
                val ldt = LocalDateTime.of(year, month, day, hour, min, sec)
                val base = if (utc and 0x80 != 0) {
                    var q = utc and 0x7F
                    if (q >= 0x40) q -= 0x80
                    ldt.toInstant(ZoneOffset.ofTotalSeconds(q * 15 * 60)).toEpochMilli()
                } else {
                    ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
                base + (if (tenMs in 0..199) tenMs * 10L else 0L)
            } catch (_: Exception) {
                0
            }
        }

        internal fun encodeExTime(millis: Long): Triple<Long, Int, Int> {
            val m = if (millis <= 0L) System.currentTimeMillis() else millis
            val zdt = Instant.ofEpochMilli(m).atZone(ZoneId.systemDefault())
            val year = (zdt.year - 1980).coerceIn(0, 127).toLong()
            val ts = (year shl 25) or
                (zdt.monthValue.toLong() shl 21) or
                (zdt.dayOfMonth.toLong() shl 16) or
                (zdt.hour.toLong() shl 11) or
                (zdt.minute.toLong() shl 5) or
                (zdt.second / 2).toLong()
            val tenMs = ((zdt.second % 2) * 100 + (m % 1000).toInt() / 10).coerceIn(0, 199)
            val q = (zdt.offset.totalSeconds / (15 * 60)).coerceIn(-64, 63)
            val utc = 0x80 or (q and 0x7F)
            return Triple(ts, tenMs, utc)
        }
    }
}
