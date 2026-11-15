package app.droidcrypter.engine.fs

import app.droidcrypter.engine.BlockDevice
import java.io.IOException

/**
 * FAT table access through a page cache. FAT12/16 tables are small and held fully in memory; FAT32
 * uses 64 KiB pages (LRU). On [flush], every dirty page is written to **every** FAT mirror so the
 * copies never disagree. The free-cluster count is cached (-1 = needs recount) and invalidated by
 * any [set].
 */
internal class FatTable(
    private val dev: BlockDevice,
    private val fatOffset: Long,
    private val fatBytes: Long,
    private val numFats: Int,
    val bits: Int,
    val clusterCount: Long,
    /** exFAT: full 32-bit entries, EOC 0xFFFFFFFF. */
    private val exfat: Boolean = false,
) {
    private val pageSize: Int = if (fatBytes <= 1 shl 20) fatBytes.toInt() else 1 shl 16
    private val maxPages = 64
    private val pages = object : LinkedHashMap<Long, Page>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Page>): Boolean {
            if (size > maxPages) {
                if (eldest.value.dirty) writePage(eldest.key, eldest.value)
                return true
            }
            return false
        }
    }

    private class Page(val data: ByteArray, var dirty: Boolean = false)

    @Volatile
    var freeCountCache: Long = -1
        private set

    private val mask: Long = if (exfat) 0xFFFFFFFFL else 0x0FFFFFFFL
    val eocMin: Long = when { exfat -> 0xFFFFFFF8L; bits == 12 -> 0xFF8L; bits == 16 -> 0xFFF8L; else -> 0x0FFFFFF8L }
    val bad: Long = when { exfat -> 0xFFFFFFF7L; bits == 12 -> 0xFF7L; bits == 16 -> 0xFFF7L; else -> 0x0FFFFFF7L }
    val eocMark: Long = when { exfat -> 0xFFFFFFFFL; bits == 12 -> 0xFFFL; bits == 16 -> 0xFFFFL; else -> 0x0FFFFFFFL }

    private fun page(index: Long): Page {
        pages[index]?.let { return it }
        val start = index * pageSize
        val len = minOf(pageSize.toLong(), fatBytes - start).toInt()
        if (len <= 0) throw corrupt("FAT access out of range")
        val buf = ByteArray(pageSize)
        dev.readFully(fatOffset + start, buf, 0, len)
        val p = Page(buf)
        pages[index] = p
        return p
    }

    private fun writePage(index: Long, p: Page) {
        val start = index * pageSize
        val len = minOf(pageSize.toLong(), fatBytes - start).toInt()
        for (f in 0 until numFats) dev.write(fatOffset + f * fatBytes + start, p.data, 0, len)
        p.dirty = false
    }

    private fun byteAt(off: Long): Int {
        val p = page(off / pageSize)
        return p.data[(off % pageSize).toInt()].toInt() and 0xFF
    }

    private fun setByte(off: Long, v: Int) {
        val p = page(off / pageSize)
        p.data[(off % pageSize).toInt()] = v.toByte()
        p.dirty = true
    }

    @Synchronized
    fun get(n: Long): Long {
        if (n < 0 || n > clusterCount + 1) throw corrupt("cluster $n out of range")
        return when (bits) {
            12 -> {
                val o = n + n / 2
                val v = byteAt(o) or (byteAt(o + 1) shl 8)
                (if (n and 1L == 0L) v and 0xFFF else v ushr 4).toLong()
            }
            16 -> (byteAt(2 * n) or (byteAt(2 * n + 1) shl 8)).toLong()
            else -> {
                val o = 4 * n
                ((byteAt(o).toLong()) or (byteAt(o + 1).toLong() shl 8) or
                    (byteAt(o + 2).toLong() shl 16) or (byteAt(o + 3).toLong() shl 24)) and mask
            }
        }
    }

    @Synchronized
    fun set(n: Long, value: Long) {
        if (n < 2 || n > clusterCount + 1) throw corrupt("cluster $n out of range")
        freeCountCache = -1
        when (bits) {
            12 -> {
                val o = n + n / 2
                val v = value.toInt() and 0xFFF
                if (n and 1L == 0L) {
                    setByte(o, v and 0xFF)
                    setByte(o + 1, (byteAt(o + 1) and 0xF0) or (v ushr 8))
                } else {
                    setByte(o, (byteAt(o) and 0x0F) or ((v and 0x0F) shl 4))
                    setByte(o + 1, v ushr 4)
                }
            }
            16 -> { setByte(2 * n, value.toInt() and 0xFF); setByte(2 * n + 1, (value.toInt() ushr 8) and 0xFF) }
            else -> {
                val o = 4 * n
                val keepHigh = if (exfat) 0 else byteAt(o + 3) and 0xF0 // FAT32: top 4 bits reserved, preserved
                val top = if (exfat) 0xFF else 0x0F
                setByte(o, (value and 0xFF).toInt()); setByte(o + 1, ((value ushr 8) and 0xFF).toInt())
                setByte(o + 2, ((value ushr 16) and 0xFF).toInt()); setByte(o + 3, keepHigh or ((value ushr 24).toInt() and top))
            }
        }
    }

    @Synchronized
    fun flush() {
        for ((i, p) in pages) if (p.dirty) writePage(i, p)
    }

    fun isEoc(v: Long) = v >= eocMin

    /** Counts entries 2 … clusterCount+1 equal to 0 (never trusts FSInfo). Cached until the next [set]. */
    @Synchronized
    fun freeClusters(): Long {
        if (freeCountCache >= 0) return freeCountCache
        var free = 0L
        for (n in 2L..clusterCount + 1) if (get(n) == 0L) free++
        freeCountCache = free
        return free
    }

    /**
     * Follows the chain from [start]. Stops at end-of-chain, or after [limit] clusters if
     * [stopAtLimit]; otherwise a longer chain is corruption (also catches loops).
     */
    @Synchronized
    fun chain(start: Long, limit: Long, stopAtLimit: Boolean): LongArray {
        if (start < 2 || start > clusterCount + 1) throw corrupt("cluster $start out of range")
        var out = LongArray(minOf(limit, 1024L).toInt().coerceAtLeast(1))
        var n = 0
        var c = start
        while (true) {
            if (n.toLong() == limit) {
                if (stopAtLimit) break
                throw corrupt("cluster chain too long (loop?)")
            }
            if (c < 2 || c > clusterCount + 1) throw corrupt("cluster $c out of range")
            if (n == out.size) out = out.copyOf(minOf(limit, n * 2L).toInt())
            out[n++] = c
            val next = get(c)
            if (isEoc(next)) break
            if (next == bad) throw corrupt("bad cluster in chain")
            if (next == 0L) throw corrupt("free cluster inside a chain")
            c = next
        }
        return out.copyOf(n)
    }

    fun ensureSane() {
        val needed = (clusterCount + 2) * bits / 8
        if (needed > fatBytes) throw IOException("Corrupt filesystem: FAT too small for the cluster count")
    }
}
