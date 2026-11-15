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
import kotlin.concurrent.write

/**
 * FAT12/16/32 reader and writer with VFAT long file names (LFN checksum validated), DOS timestamps
 * (local time, 2 s resolution; 0 if unset/invalid) and the volume label from the root label entry.
 * Free space is counted from the FAT itself, never taken from FSInfo.
 */
class FatFileSystem(dev: BlockDevice) : TreeFileSystem<FatFileSystem.FatEntry>(dev) {

    class FatEntry internal constructor(
        override val name: String,
        override val isDirectory: Boolean,
        override val size: Long,
        override val lastModifiedMillis: Long,
        val firstCluster: Long,
        /** Device offsets of every 32-byte slot of this entry (LFN slots first, short entry last). */
        internal val slots: LongArray,
        internal val shortName: ByteArray,
    ) : Entry()

    val bytesPerSector: Int
    val sectorsPerCluster: Int
    val clusterSize: Int
    val fatType: Int
    internal val fat: FatTable
    private val reservedSectors: Int
    private val numFats: Int
    private val rootEntryCount: Int
    private val fatSectors: Long
    private val rootDirOffset: Long
    private val dataOffset: Long
    val clusterCount: Long
    private val rootCluster: Long
    internal val fsInfoSector: Int
    private val volumeLabel: String by lazy { findLabel() }

    override val typeName: String get() = "FAT$fatType"

    init {
        val bs = ByteArray(512)
        dev.readFully(0, bs, 0, 512)
        bytesPerSector = Le.u16(bs, 11)
        if (bytesPerSector !in setOf(512, 1024, 2048, 4096)) throw corrupt("bad bytes per sector")
        sectorsPerCluster = Le.u8(bs, 13)
        if (sectorsPerCluster == 0 || sectorsPerCluster and (sectorsPerCluster - 1) != 0) throw corrupt("bad sectors per cluster")
        clusterSize = bytesPerSector * sectorsPerCluster
        reservedSectors = Le.u16(bs, 14)
        numFats = Le.u8(bs, 16)
        rootEntryCount = Le.u16(bs, 17)
        val tot16 = Le.u16(bs, 19)
        val totalSectors = if (tot16 != 0) tot16.toLong() else Le.u32(bs, 32)
        val fat16Size = Le.u16(bs, 22)
        fatSectors = if (fat16Size != 0) fat16Size.toLong() else Le.u32(bs, 36)
        if (reservedSectors < 1 || numFats !in 1..4 || fatSectors == 0L || totalSectors == 0L) throw corrupt("bad boot sector")
        if (totalSectors * bytesPerSector > dev.size) throw corrupt("filesystem larger than the volume")
        val rootDirSectors = (rootEntryCount * 32L + bytesPerSector - 1) / bytesPerSector
        val firstDataSector = reservedSectors + numFats * fatSectors + rootDirSectors
        if (firstDataSector >= totalSectors) throw corrupt("no data area")
        clusterCount = (totalSectors - firstDataSector) / sectorsPerCluster
        fatType = when {
            clusterCount < 4085 -> 12
            clusterCount < 65525 -> 16
            else -> 32
        }
        rootCluster = if (fatType == 32) Le.u32(bs, 44) else 0
        fsInfoSector = if (fatType == 32) Le.u16(bs, 48) else 0
        rootDirOffset = (reservedSectors + numFats * fatSectors) * bytesPerSector.toLong()
        dataOffset = firstDataSector * bytesPerSector
        fat = FatTable(dev, reservedSectors.toLong() * bytesPerSector, fatSectors * bytesPerSector, numFats, fatType, clusterCount)
        fat.ensureSane()
        if (fatType == 32 && (rootCluster < 2 || rootCluster > clusterCount + 1)) throw corrupt("bad root cluster")
    }

    override val root = FatEntry("", true, 0, 0, rootCluster, LongArray(0), ByteArray(11))

    override val label: String get() = volumeLabel
    override val totalBytes: Long get() = clusterCount * clusterSize
    override val writeSupport: String? get() = if (dev.isWritable) null else "This volume is read-only"

    override fun freeBytes(): Long {
        ensureOpen()
        return fsLock.writeLock().let { l -> l.lock(); try { fat.freeClusters() * clusterSize } finally { l.unlock() } }
    }

    internal fun clusterOffset(c: Long): Long = dataOffset + (c - 2) * clusterSize

    private val maxDirClusters: Long get() = maxOf(1L, ((2L shl 20) + clusterSize - 1) / clusterSize)

    /** Directory bytes and the device offset of each 32-byte slot. */
    internal fun readDirBytes(dir: FatEntry): Pair<ByteArray, LongArray> {
        if (dir === root && fatType != 32) {
            val len = rootEntryCount * 32
            val b = ByteArray(len)
            dev.readFully(rootDirOffset, b, 0, len)
            return b to LongArray(rootEntryCount) { rootDirOffset + it * 32L }
        }
        if (dir.firstCluster == 0L) return ByteArray(0) to LongArray(0)
        val chain = fat.chain(dir.firstCluster, maxDirClusters, false)
        val b = ByteArray(chain.size * clusterSize)
        val per = clusterSize / 32
        val pos = LongArray(chain.size * per)
        for ((i, c) in chain.withIndex()) {
            dev.readFully(clusterOffset(c), b, i * clusterSize, clusterSize)
            val base = clusterOffset(c)
            for (k in 0 until per) pos[i * per + k] = base + k * 32L
        }
        return b to pos
    }

    private fun findLabel(): String {
        val (b, _) = readDirBytes(root)
        var i = 0
        while (i + 32 <= b.size) {
            val b0 = Le.u8(b, i)
            if (b0 == 0) break
            val attr = Le.u8(b, i + 11)
            if (b0 != 0xE5 && attr != 0x0F && attr and 0x08 != 0) {
                return decodeOem(b, i, 11).trimEnd()
            }
            i += 32
        }
        return ""
    }

    override fun readDirectory(dir: FatEntry): List<FatEntry> {
        val (b, pos) = readDirBytes(dir)
        val out = ArrayList<FatEntry>()
        var lfn: CharArray? = null
        var lfnExpect = 0
        var lfnSum = 0
        var lfnStart = -1
        var i = 0
        while (i + 32 <= b.size) {
            val o = i
            val slot = i / 32
            i += 32
            val b0 = Le.u8(b, o)
            if (b0 == 0) break
            if (b0 == 0xE5) { lfn = null; continue }
            val attr = Le.u8(b, o + 11)
            if (attr == 0x0F) {
                val seq = b0 and 0x1F
                if (b0 and 0x40 != 0) {
                    if (seq == 0 || seq > 20) { lfn = null; continue }
                    lfn = CharArray(seq * 13) { '\uFFFF' }
                    lfnExpect = seq
                    lfnSum = Le.u8(b, o + 13)
                    lfnStart = slot
                } else if (lfn == null || seq != lfnExpect || Le.u8(b, o + 13) != lfnSum) {
                    lfn = null; continue
                }
                val base = (seq - 1) * 13
                var k = 0
                for (off in LFN_OFFSETS) { lfn[base + k] = Le.u16(b, o + off).toChar(); k++ }
                lfnExpect = seq - 1
                continue
            }
            if (attr and 0x08 != 0) { lfn = null; continue } // volume label
            val short = b.copyOfRange(o, o + 11)
            var name: String? = null
            var first = slot
            if (lfn != null && lfnExpect == 0 && shortChecksum(short) == lfnSum) {
                var end = lfn.indexOf('\u0000')
                if (end < 0) end = lfn.indexOfFirst { it == '\uFFFF' }.let { if (it < 0) lfn.size else it }
                name = String(lfn, 0, end)
                first = lfnStart
            }
            lfn = null
            if (name == null) name = shortToName(b, o)
            if (name == "." || name == "..") continue
            val isDir = attr and 0x10 != 0
            val hi = if (fatType == 32) Le.u16(b, o + 20).toLong() else 0L
            val cluster = (hi shl 16) or Le.u16(b, o + 26).toLong()
            val size = if (isDir) 0L else Le.u32(b, o + 28)
            val slots = LongArray(slot - first + 1) { pos[first + it] }
            out += FatEntry(name, isDir, size, dosTime(Le.u16(b, o + 24), Le.u16(b, o + 22)), cluster, slots, short)
        }
        return out
    }

    override fun openEntry(entry: FatEntry, path: String): FsFileHandle {
        val rb = RunBuilder()
        if (entry.size > 0) {
            val needed = (entry.size + clusterSize - 1) / clusterSize
            val chain = fat.chain(entry.firstCluster, needed, true)
            if (chain.size < needed) throw corrupt("cluster chain shorter than the file size")
            for (c in chain) rb.add(clusterOffset(c), clusterSize.toLong())
        }
        return rb.handle(dev, entry.size, entry.size) { !closed }
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
        if (size > 0xFFFFFFFFL) throw IOException("File is larger than the 4 GB FAT limit")
        val (dirPath, dirEntry) = resolve(dir) ?: throw IOException("Folder not found: $dir")
        if (!dirEntry.isDirectory) throw IOException("Not a folder: $dir")

        val existing = listing(dirEntry, dirPath).entries
        val finalName = FsWriteSupport.uniqueName(name, existing.map { key(it.name) }.toSet())
        val neededClusters = if (size == 0L) 0 else ((size + clusterSize - 1) / clusterSize).toInt()
        val chosenClusters = findFreeClusters(neededClusters)

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
                    // Zero the remainder of the final cluster so no stale data leaks.
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

        // Reserve directory slots (may grow the directory by 1 cluster, excluding chosenClusters).
        val shortAlias = generateShortAlias(finalName, existing)
        val entryBytes = buildEntrySet(finalName, shortAlias, false, firstCluster, size, now)
        val slotCount = entryBytes.size / 32
        val (slotOffsets, isAtEnd) = allocateDirSlots(dirEntry, slotCount, chosenClusters.toSet())

        // 2. Record cluster allocation in the FAT and flush FAT mirrors.
        for (i in chosenClusters.indices) {
            val next = if (i + 1 < chosenClusters.size) chosenClusters[i + 1] else fat.eocMark
            fat.set(chosenClusters[i], next)
        }
        fat.flush()
        updateFsInfo(if (chosenClusters.isNotEmpty()) chosenClusters.last() + 1 else -1L)

        // 3. Write directory entry last (LFN slots first, short entry last).
        writeDirSlots(slotOffsets, entryBytes, isAtEnd)
        dev.sync()

        invalidate(dirPath)
        val fullPath = FsPaths.join(dirPath, finalName)
        return FsNode(fullPath, finalName, false, size, (now / 2000) * 2000)
    }

    override fun createDirectory(dir: String, name: String, autoRename: Boolean): FsNode = fsLock.write {
        ensureOpen()
        val (dirPath, dirEntry) = resolve(dir) ?: throw IOException("Folder not found: $dir")
        if (!dirEntry.isDirectory) throw IOException("Not a folder: $dir")
        val existing = listing(dirEntry, dirPath).entries
        val finalName = FsWriteSupport.newFolderName(name, existing.map { key(it.name) }.toSet(), autoRename)
        val cluster = findFreeClusters(1)[0]
        val now = System.currentTimeMillis()

        // 1. Fill the still-free cluster: "." and ".." (cluster 0 when the parent is the root), rest zero.
        val block = ByteArray(clusterSize)
        putDotEntry(block, 0, 1, cluster, now)
        putDotEntry(block, 32, 2, if (dirEntry === root) 0L else dirEntry.firstCluster, now)
        dev.write(clusterOffset(cluster), block, 0, clusterSize)

        val entryBytes = buildEntrySet(finalName, generateShortAlias(finalName, existing), true, cluster, 0L, now)
        val (slotOffsets, isAtEnd) = allocateDirSlots(dirEntry, entryBytes.size / 32, setOf(cluster))

        // 2. Record the allocation, 3. write the directory entry last.
        fat.set(cluster, fat.eocMark)
        fat.flush()
        updateFsInfo(cluster + 1)
        writeDirSlots(slotOffsets, entryBytes, isAtEnd)
        dev.sync()

        invalidate(dirPath)
        FsNode(FsPaths.join(dirPath, finalName), finalName, true, 0L, (now / 2000) * 2000)
    }

    /** Writes the "." ([dots] = 1) or ".." ([dots] = 2) entry of a new subdirectory. */
    private fun putDotEntry(block: ByteArray, o: Int, dots: Int, cluster: Long, mtimeMillis: Long) {
        for (i in 0 until 11) block[o + i] = (if (i < dots) '.' else ' ').code.toByte()
        block[o + 11] = 0x10
        val (dosDate, dosTime) = encodeDosTime(mtimeMillis)
        Le.put16(block, o + 14, dosTime)
        Le.put16(block, o + 16, dosDate)
        Le.put16(block, o + 18, dosDate)
        Le.put16(block, o + 20, if (fatType == 32) ((cluster ushr 16) and 0xFFFF).toInt() else 0)
        Le.put16(block, o + 22, dosTime)
        Le.put16(block, o + 24, dosDate)
        Le.put16(block, o + 26, (cluster and 0xFFFF).toInt())
    }

    override fun delete(path: String, overwrite: Boolean, onProgress: (Long) -> Unit) = fsLock.write {
        ensureOpen()
        val norm = FsPaths.normalize(path)
        if (norm == "/") throw IOException("Cannot delete the root folder")
        val (real, entry) = resolve(norm) ?: throw IOException("File or folder not found: $path")
        val parentPath = FsPaths.parent(real)
        var progress = 0L
        deleteEntryRecursive(entry, real, overwrite, depth = 0) { delta ->
            progress += delta
            onProgress(progress)
        }
        fat.flush()
        updateFsInfo(-1L)
        dev.sync()
        invalidate(parentPath)
        invalidateSubtree(real)
    }

    private fun deleteEntryRecursive(
        entry: FatEntry,
        path: String,
        overwrite: Boolean,
        depth: Int,
        addProgress: (Long) -> Unit,
    ) {
        if (depth > 64) throw corrupt("directory nesting too deep (cycle?)")
        if (entry.isDirectory) {
            val children = readDirectory(entry)
            for (child in children) {
                deleteEntryRecursive(child, FsPaths.join(path, child.name), overwrite, depth + 1, addProgress)
            }
        }
        val clusters = if (entry.firstCluster >= 2) {
            val limit = if (entry.isDirectory) maxDirClusters else maxOf(1L, (entry.size + clusterSize - 1) / clusterSize)
            fat.chain(entry.firstCluster, limit, !entry.isDirectory)
        } else {
            LongArray(0)
        }

        // Crash-safe ordering: remove/retire directory entry first, then overwrite/free clusters.
        retireDirSlots(entry.slots, overwrite)
        if (overwrite) {
            addProgress(entry.slots.size * 32L)
            val zeros = ByteArray(clusterSize)
            for (c in clusters) {
                dev.write(clusterOffset(c), zeros, 0, clusterSize)
                addProgress(clusterSize.toLong())
            }
        }
        for (c in clusters) {
            fat.set(c, 0L)
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

        val otherSiblings = siblings.filter { it !== entry }
        val alias = generateShortAlias(newName, otherSiblings)
        val entryBytes = buildEntrySet(
            newName, alias, entry.isDirectory, entry.firstCluster, entry.size, entry.lastModifiedMillis
        )
        val slotCount = entryBytes.size / 32
        // Write new entry BEFORE retiring the old one (crash-safe: worst case duplicate, never lost).
        val (newSlots, isAtEnd) = allocateDirSlots(parentEntry, slotCount, emptySet())
        fat.flush()
        writeDirSlots(newSlots, entryBytes, isAtEnd)
        retireDirSlots(entry.slots, overwrite = false)
        updateFsInfo(-1L)
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
        val alias = generateShortAlias(finalName, dstListing)
        val entryBytes = buildEntrySet(
            finalName, alias, entry.isDirectory, entry.firstCluster, entry.size, entry.lastModifiedMillis
        )
        val slotCount = entryBytes.size / 32
        // 1. Write new entry in target directory first.
        val (newSlots, isAtEnd) = allocateDirSlots(dstEntry, slotCount, emptySet())
        fat.flush()
        writeDirSlots(newSlots, entryBytes, isAtEnd)

        // 2. If moving a folder on FAT, update its ".." entry to point at the new parent (cluster 0 for root).
        if (entry.isDirectory && entry.firstCluster >= 2) {
            val parentClusterForDotDot = if (dstEntry === root) 0L else dstEntry.firstCluster
            updateDotDotCluster(entry.firstCluster, parentClusterForDotDot)
        }

        // 3. Retire old directory entry.
        retireDirSlots(entry.slots, overwrite = false)
        updateFsInfo(-1L)
        dev.sync()

        invalidate(srcParentPath)
        invalidate(realDst)
        if (entry.isDirectory) invalidateSubtree(realSrc)
        return FsPaths.join(realDst, finalName)
    }

    // ---- helpers ----

    private fun updateDotDotCluster(dirFirstCluster: Long, newParentCluster: Long) {
        val base = clusterOffset(dirFirstCluster)
        val buf = ByteArray(64)
        dev.readFully(base, buf, 0, 64)
        // Second 32-byte slot in a FAT subdirectory is "..          "
        if (buf[32] == '.'.code.toByte() && buf[33] == '.'.code.toByte()) {
            val hi = if (fatType == 32) ((newParentCluster ushr 16) and 0xFFFF).toInt() else 0
            val lo = (newParentCluster and 0xFFFF).toInt()
            Le.put16(buf, 32 + 20, hi)
            Le.put16(buf, 32 + 26, lo)
            dev.write(base + 32, buf, 32, 32)
        }
    }

    /** Finds [count] free clusters, preferring a single contiguous run. */
    private fun findFreeClusters(count: Int, exclude: Set<Long> = emptySet()): LongArray {
        if (count == 0) return LongArray(0)
        // First pass: look for a single contiguous run of [count] free clusters.
        var runStart = 2L
        var runLen = 0
        for (c in 2L..clusterCount + 1) {
            if (c !in exclude && fat.get(c) == 0L) {
                if (runLen == 0) runStart = c
                runLen++
                if (runLen == count) return LongArray(count) { runStart + it }
            } else {
                runLen = 0
            }
        }
        // Fallback: collect any [count] free clusters.
        val out = LongArray(count)
        var n = 0
        for (c in 2L..clusterCount + 1) {
            if (c !in exclude && fat.get(c) == 0L) {
                out[n++] = c
                if (n == count) return out
            }
        }
        throw IOException("Not enough free space in the volume")
    }

    /**
     * Finds [slotCount] contiguous free 32-byte slots in [dir], growing the directory by 1 cluster
     * if necessary. Returns `(slotOffsets, isAtEnd)` where `isAtEnd` is true if the slots sit right
     * before the end-of-directory `0x00` marker.
     */
    private fun allocateDirSlots(
        dir: FatEntry,
        slotCount: Int,
        excludeClusters: Set<Long>,
    ): Pair<LongArray, Boolean> {
        val (b, pos) = readDirBytes(dir)
        val totalSlots = pos.size
        var endMarkerSlot = totalSlots
        for (s in 0 until totalSlots) {
            if (Le.u8(b, s * 32) == 0x00) {
                endMarkerSlot = s
                break
            }
        }
        // Look for a contiguous run of 0xE5 slots before endMarkerSlot.
        var runStart = 0
        var runLen = 0
        for (s in 0 until endMarkerSlot) {
            if (Le.u8(b, s * 32) == 0xE5) {
                if (runLen == 0) runStart = s
                runLen++
                if (runLen == slotCount) {
                    return LongArray(slotCount) { pos[runStart + it] } to false
                }
            } else {
                runLen = 0
            }
        }
        // Check if there is room from the trailing 0xE5 run + free slots after endMarkerSlot.
        val tailStart = if (runLen > 0) runStart else endMarkerSlot
        if (tailStart + slotCount <= totalSlots) {
            return LongArray(slotCount) { pos[tailStart + it] } to true
        }
        // Need to grow the directory by 1 cluster (fixed FAT12/16 root cannot grow).
        if (dir === root && fatType != 32) {
            throw IOException("Root folder is full (FAT12/16 root directory limit reached)")
        }
        val newCluster = findFreeClusters(1, excludeClusters)[0]
        val zeros = ByteArray(clusterSize)
        dev.write(clusterOffset(newCluster), zeros, 0, clusterSize)
        val chain = fat.chain(dir.firstCluster, maxDirClusters, false)
        fat.set(chain.last(), newCluster)
        fat.set(newCluster, fat.eocMark)

        val per = clusterSize / 32
        val newPos = LongArray(totalSlots + per) { idx ->
            if (idx < totalSlots) pos[idx] else clusterOffset(newCluster) + (idx - totalSlots) * 32L
        }
        if (tailStart + slotCount > newPos.size) throw IOException("Directory entry too large")
        return LongArray(slotCount) { newPos[tailStart + it] } to true
    }

    private fun writeDirSlots(slots: LongArray, entryBytes: ByteArray, isAtEnd: Boolean) {
        // Write LFN slots first, then the short entry last.
        for (i in slots.indices) {
            dev.write(slots[i], entryBytes, i * 32, 32)
        }
        if (isAtEnd) {
            // Note: newly allocated clusters are already zeroed, and existing slots after endMarkerSlot are 0x00.
        }
    }

    private fun retireDirSlots(slots: LongArray, overwrite: Boolean) {
        val marker = ByteArray(32)
        if (!overwrite) marker[0] = 0xE5.toByte()
        for (off in slots) {
            if (overwrite) {
                // Zero the entire 32-byte slot (wiping LFN name fragments and short name), then mark 0xE5.
                val z = ByteArray(32)
                z[0] = 0xE5.toByte()
                dev.write(off, z, 0, 32)
            } else {
                dev.write(off, byteArrayOf(0xE5.toByte()), 0, 1)
            }
        }
    }

    private fun updateFsInfo(nextFreeHint: Long) {
        if (fatType != 32 || fsInfoSector <= 0) return
        val off = fsInfoSector.toLong() * bytesPerSector
        val buf = ByteArray(512)
        dev.readFully(off, buf, 0, 512)
        if (Le.u32(buf, 0) != 0x41615252L || Le.u32(buf, 484) != 0x61417272L) return
        Le.put32(buf, 488, fat.freeClusters())
        if (nextFreeHint >= 2) Le.put32(buf, 492, nextFreeHint)
        dev.write(off, buf, 0, 512)
    }

    private fun generateShortAlias(name: String, existing: List<FatEntry>): ByteArray {
        val taken = existing.map { String(it.shortName, Charsets.ISO_8859_1) }.toHashSet()
        val dot = name.lastIndexOf('.')
        val rawBase = (if (dot > 0) name.substring(0, dot) else name).uppercase()
        val rawExt = (if (dot > 0 && dot < name.length - 1) name.substring(dot + 1) else "").uppercase()

        fun clean(s: String, max: Int): String {
            val sb = StringBuilder(max)
            for (c in s) {
                if (sb.length >= max) break
                if (c in 'A'..'Z' || c in '0'..'9' || c in "!#$%&'()-@^_`{}~") sb.append(c)
                else if (c != ' ' && c != '.') sb.append('_')
            }
            return sb.toString().ifEmpty { "FILE" }
        }

        val cleanBase = clean(rawBase, 8)
        val cleanExt = if (rawExt.isEmpty()) "" else clean(rawExt, 3)

        // Check if name fits strictly in clean 8.3 without loss and isn't taken.
        val exactFits = name.uppercase() == (if (cleanExt.isEmpty()) cleanBase else "$cleanBase.$cleanExt") &&
            name.none { it.code >= 0x80 || it == ' ' }
        if (exactFits) {
            val cand = formatShort(cleanBase, cleanExt)
            if (String(cand, Charsets.ISO_8859_1) !in taken) return cand
        }

        for (n in 1..999999) {
            val tail = "~$n"
            val prefixLen = (8 - tail.length).coerceAtLeast(1)
            val b = cleanBase.take(prefixLen) + tail
            val cand = formatShort(b, cleanExt)
            if (String(cand, Charsets.ISO_8859_1) !in taken) return cand
        }
        throw IOException("Could not generate an 8.3 short alias for $name")
    }

    private fun formatShort(base: String, ext: String): ByteArray {
        val out = ByteArray(11) { ' '.code.toByte() }
        for (i in 0 until minOf(8, base.length)) out[i] = base[i].code.toByte()
        for (i in 0 until minOf(3, ext.length)) out[8 + i] = ext[i].code.toByte()
        return out
    }

    private fun buildEntrySet(
        name: String,
        shortAlias: ByteArray,
        isDir: Boolean,
        firstCluster: Long,
        size: Long,
        mtimeMillis: Long,
    ): ByteArray {
        val sum = shortChecksum(shortAlias)
        val lfnCount = (name.length + 12) / 13
        val out = ByteArray((lfnCount + 1) * 32)
        // LFN entries: highest sequence number first (with 0x40 last-LFN flag), down to 1.
        for (idx in 0 until lfnCount) {
            val seq = lfnCount - idx
            val o = idx * 32
            out[o] = (seq or (if (idx == 0) 0x40 else 0)).toByte()
            out[o + 11] = 0x0F.toByte()
            out[o + 12] = 0
            out[o + 13] = sum.toByte()
            Le.put16(out, o + 26, 0)
            val charBase = (seq - 1) * 13
            for (k in 0 until 13) {
                val cPos = charBase + k
                val ch = when {
                    cPos < name.length -> name[cPos].code
                    cPos == name.length -> 0x0000
                    else -> 0xFFFF
                }
                Le.put16(out, o + LFN_OFFSETS[k], ch)
            }
        }
        // Short entry at the end.
        val so = lfnCount * 32
        System.arraycopy(shortAlias, 0, out, so, 11)
        out[so + 11] = (if (isDir) 0x10 else 0x20).toByte()
        val (dosDate, dosTime) = encodeDosTime(mtimeMillis)
        Le.put16(out, so + 14, dosTime)
        Le.put16(out, so + 16, dosDate)
        Le.put16(out, so + 18, dosDate)
        Le.put16(out, so + 20, if (fatType == 32) ((firstCluster ushr 16) and 0xFFFF).toInt() else 0)
        Le.put16(out, so + 22, dosTime)
        Le.put16(out, so + 24, dosDate)
        Le.put16(out, so + 26, (firstCluster and 0xFFFF).toInt())
        Le.put32(out, so + 28, if (isDir) 0L else size)
        return out
    }

    companion object {
        private val LFN_OFFSETS = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)

        internal fun shortChecksum(n: ByteArray): Int {
            var s = 0
            for (i in 0 until 11) s = (((s and 1) shl 7) + (s ushr 1) + (n[i].toInt() and 0xFF)) and 0xFF
            return s
        }

        private const val CP437_HIGH =
            "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■\u00A0"

        internal fun decodeOem(b: ByteArray, o: Int, len: Int): String {
            val sb = StringBuilder(len)
            for (i in 0 until len) {
                val c = b[o + i].toInt() and 0xFF
                sb.append(if (c < 0x80) c.toChar() else if (CP437_HIGH.length == 128) CP437_HIGH[c - 0x80] else c.toChar())
            }
            return sb.toString()
        }

        private fun shortToName(b: ByteArray, o: Int): String {
            val nb = b.copyOfRange(o, o + 11)
            if (nb[0] == 0x05.toByte()) nb[0] = 0xE5.toByte()
            val flags = Le.u8(b, o + 12)
            var base = decodeOem(nb, 0, 8).trimEnd()
            var ext = decodeOem(nb, 8, 3).trimEnd()
            if (flags and 0x08 != 0) base = base.lowercase()
            if (flags and 0x10 != 0) ext = ext.lowercase()
            return if (ext.isEmpty()) base else "$base.$ext"
        }

        /** DOS date/time (local time) → epoch millis; 0 if unset or invalid. */
        internal fun dosTime(date: Int, time: Int): Long {
            if (date == 0) return 0
            val year = 1980 + (date ushr 9)
            val month = (date ushr 5) and 0x0F
            val day = date and 0x1F
            val hour = time ushr 11
            val minute = (time ushr 5) and 0x3F
            val second = (time and 0x1F) * 2
            return try {
                LocalDateTime.of(year, month, day, hour, minute, second)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
                0
            }
        }

        internal fun encodeDosTime(millis: Long): Pair<Int, Int> {
            if (millis <= 0L) return 0x0021 to 0 // 1980-01-01 00:00:00
            val ldt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()
            val year = (ldt.year - 1980).coerceIn(0, 127)
            val date = (year shl 9) or (ldt.monthValue shl 5) or ldt.dayOfMonth
            val time = (ldt.hour shl 11) or (ldt.minute shl 5) or (ldt.second / 2)
            return date to time
        }
    }
}
