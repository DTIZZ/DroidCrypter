package app.droidcrypter.engine.fs

import app.droidcrypter.engine.BlockDevice
import app.droidcrypter.engine.FsFileHandle
import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.FsPaths
import java.io.Closeable
import java.io.IOException
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read

internal fun corrupt(msg: String): IOException = IOException("Corrupt filesystem: $msg")

internal object Le {
    fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    fun u32(b: ByteArray, o: Int): Long = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
    fun s64(b: ByteArray, o: Int): Long = u32(b, o) or (u32(b, o + 4) shl 32)
    fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte() }
    fun put64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (8 * i)).toByte() }
    fun utf16(b: ByteArray, o: Int, chars: Int): String {
        val sb = StringBuilder(chars)
        for (i in 0 until chars) sb.append(u16(b, o + 2 * i).toChar())
        return sb.toString()
    }
}

/** A filesystem driver as seen by [app.droidcrypter.engine.ContainerVolume]. */
interface FileSystemDriver : Closeable {
    val typeName: String
    val label: String
    val totalBytes: Long
    /** Live free bytes. */
    fun freeBytes(): Long
    /** null if writable; else the user-facing reason why not. */
    val writeSupport: String?
    fun stat(path: String): FsNode?
    fun list(dir: String): List<FsNode>
    fun openFile(path: String): FsFileHandle
    fun createFile(dir: String, name: String, size: Long,
                   source: (Long, ByteArray, Int, Int) -> Unit, onProgress: (Long) -> Unit): FsNode =
        throw IOException(writeSupport ?: "Not supported")
    fun createDirectory(dir: String, name: String, autoRename: Boolean): FsNode =
        throw IOException(writeSupport ?: "Not supported")
    fun delete(path: String, overwrite: Boolean, onProgress: (Long) -> Unit): Unit =
        throw IOException(writeSupport ?: "Not supported")
    fun rename(path: String, newName: String): String = throw IOException(writeSupport ?: "Not supported")
    fun move(path: String, targetDir: String): String = throw IOException(writeSupport ?: "Not supported")
}

/**
 * Shared behaviour of tree filesystems: case-insensitive (Unicode lowercase) path lookup, a
 * per-directory listing cache keyed by lowercase path (invalidated by our own writes), skipping
 * "."/".."/empty names, and the root stat ("/", "", dir).
 */
abstract class TreeFileSystem<E : TreeFileSystem.Entry>(protected val dev: BlockDevice) : FileSystemDriver {
    abstract class Entry {
        abstract val name: String
        abstract val isDirectory: Boolean
        abstract val size: Long
        abstract val lastModifiedMillis: Long
    }

    protected abstract val root: E
    protected abstract fun readDirectory(dir: E): List<E>
    protected abstract fun openEntry(entry: E, path: String): FsFileHandle

    protected val fsLock = ReentrantReadWriteLock()
    private val cache = HashMap<String, DirListing<E>>()

    @Volatile
    protected var closed = false

    protected class DirListing<E>(val entries: List<E>, val byName: Map<String, E>)

    protected fun ensureOpen() {
        if (closed) throw IOException("Volume is locked")
    }

    protected fun key(name: String): String = name.lowercase(Locale.ROOT)

    /** Lists [dir] (cached). [dirPath] is the directory's path, used as cache key. */
    protected fun listing(dir: E, dirPath: String): DirListing<E> {
        val k = key(dirPath)
        synchronized(cache) { cache[k]?.let { return it } }
        val raw = readDirectory(dir)
        val list = ArrayList<E>(raw.size)
        val map = HashMap<String, E>(raw.size * 2)
        for (e in raw) {
            if (e.name.isEmpty() || e.name == "." || e.name == "..") continue
            val nk = key(e.name)
            if (map.containsKey(nk)) continue
            map[nk] = e
            list += e
        }
        val l = DirListing<E>(list, map)
        synchronized(cache) { cache[k] = l }
        return l
    }

    /** Resolves [path] to (real path, entry); null if missing. */
    protected fun resolve(path: String): Pair<String, E>? {
        val segs = FsPaths.segments(path)
        var cur = root
        var real = "/"
        for (s in segs) {
            if (!cur.isDirectory) return null
            val next = listing(cur, real).byName[key(s)] ?: return null
            real = FsPaths.join(real, next.name)
            cur = next
        }
        return real to cur
    }

    protected fun toNode(path: String, e: E): FsNode =
        if (path == "/") FsNode("/", "", true, 0, 0)
        else FsNode(path, e.name, e.isDirectory, if (e.isDirectory) 0 else e.size, e.lastModifiedMillis)

    override fun stat(path: String): FsNode? = fsLock.read {
        ensureOpen()
        val (real, e) = resolve(path) ?: return null
        toNode(real, e)
    }

    override fun list(dir: String): List<FsNode> = fsLock.read {
        ensureOpen()
        val (real, e) = resolve(dir) ?: throw IOException("Folder not found: $dir")
        if (!e.isDirectory) throw IOException("Not a folder: $dir")
        listing(e, real).entries.map { toNode(FsPaths.join(real, it.name), it) }
    }

    override fun openFile(path: String): FsFileHandle = fsLock.read {
        ensureOpen()
        val (real, e) = resolve(path) ?: throw IOException("File not found: $path")
        if (e.isDirectory) throw IOException("Is a folder: $path")
        openEntry(e, real)
    }

    protected fun invalidate(dirPath: String) {
        synchronized(cache) { cache.remove(key(dirPath)) }
    }

    protected fun invalidateSubtree(path: String) {
        val k = key(path)
        synchronized(cache) {
            cache.keys.removeAll { it == k || it.startsWith(if (k == "/") "/" else "$k/") }
        }
    }

    protected fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    override fun close() {
        closed = true
        clearCache()
    }
}

/**
 * File handle over a list of physical extents ([fileOffsets] ascending, each run [lengths] bytes at
 * device offset [devOffsets]; a device offset of -1 is a sparse run that reads as zeros). Physically
 * adjacent clusters are already coalesced into one run, so each run is one device read. Bytes past
 * [validLength] read as zeros.
 */
internal class ExtentFileHandle(
    private val dev: BlockDevice,
    override val size: Long,
    private val validLength: Long,
    private val fileOffsets: LongArray,
    private val devOffsets: LongArray,
    private val lengths: LongArray,
    private val isOpen: () -> Boolean,
) : FsFileHandle {
    @Volatile
    private var closed = false

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed || !isOpen()) throw IOException("Volume is locked")
        if (position < 0 || offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException()
        if (position >= size) return -1
        if (length == 0) return 0
        val n = minOf(length.toLong(), size - position).toInt()
        var done = 0
        var idx = findRun(position)
        while (done < n) {
            val p = position + done
            if (p >= validLength) {
                buffer.fill(0, offset + done, offset + n); done = n; break
            }
            while (idx < fileOffsets.size && fileOffsets[idx] + lengths[idx] <= p) idx++
            if (idx >= fileOffsets.size) throw corrupt("file data shorter than its size")
            val inRun = p - fileOffsets[idx]
            val take = minOf((n - done).toLong(), lengths[idx] - inRun, validLength - p).toInt()
            if (devOffsets[idx] < 0) buffer.fill(0, offset + done, offset + done + take)
            else dev.readFully(devOffsets[idx] + inRun, buffer, offset + done, take)
            done += take
        }
        return n
    }

    private fun findRun(pos: Long): Int {
        var lo = 0
        var hi = fileOffsets.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (fileOffsets[mid] <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    override fun close() {
        closed = true
    }
}

/** Accumulates runs, merging physically contiguous ones. */
internal class RunBuilder {
    private var fo = LongArray(8)
    private var dofs = LongArray(8)
    private var len = LongArray(8)
    var count = 0
        private set
    var total = 0L
        private set

    fun add(devOffset: Long, length: Long) {
        if (length <= 0) return
        if (count > 0) {
            val last = count - 1
            val contiguous = if (devOffset < 0) dofs[last] < 0 else dofs[last] >= 0 && dofs[last] + len[last] == devOffset
            if (contiguous) { len[last] += length; total += length; return }
        }
        if (count == fo.size) {
            fo = fo.copyOf(count * 2); dofs = dofs.copyOf(count * 2); len = len.copyOf(count * 2)
        }
        fo[count] = total; dofs[count] = devOffset; len[count] = length
        count++
        total += length
    }

    fun handle(dev: BlockDevice, size: Long, validLength: Long, isOpen: () -> Boolean): ExtentFileHandle {
        if (total < size) throw corrupt("allocation shorter than the file size")
        return ExtentFileHandle(dev, size, minOf(validLength, size), fo.copyOf(count), dofs.copyOf(count), len.copyOf(count), isOpen)
    }
}

