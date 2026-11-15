package app.droidcrypter.engine

import app.droidcrypter.engine.fs.FsWriteSupport
import java.io.IOException
import java.util.Locale

/**
 * In-memory [MountedVolume] fake used by unit and Compose UI tests.
 * Supports configurable `totalBytes`, `freeBytes`, `writeSupport`, and full file/folder CRUD.
 */
class InMemoryVolume(
    override val id: String = Secrets.randomHexId(),
    override val label: String = "Test Vault",
    override var totalBytes: Long = 1024L * 1024L * 1024L,
    var currentFreeBytes: Long = 512L * 1024L * 1024L,
    var readOnlyReason: String? = null,
    private val clusterSize: Int = 4096,
) : MountedVolume {

    private class MemEntry(
        var name: String,
        val isDirectory: Boolean,
        var data: ByteArray = ByteArray(0),
        var lastModifiedMillis: Long = System.currentTimeMillis(),
        val children: LinkedHashMap<String, MemEntry> = LinkedHashMap(),
    )

    private val root = MemEntry("", true)
    private val lock = Any()

    @Volatile
    private var closed = false

    override val freeBytes: Long
        get() = synchronized(lock) {
            ensureOpen()
            currentFreeBytes
        }

    override val writeSupport: String?
        get() = when {
            closed -> "Volume is locked"
            else -> readOnlyReason
        }

    fun addFolder(path: String, lastModifiedMillis: Long = System.currentTimeMillis()): FsNode = synchronized(lock) {
        ensureOpen()
        val segs = FsPaths.segments(path)
        var cur = root
        var curPath = "/"
        for (s in segs) {
            val k = s.lowercase(Locale.ROOT)
            val existing = cur.children[k]
            cur = if (existing != null) {
                if (!existing.isDirectory) throw IOException("Not a folder: $curPath")
                existing
            } else {
                val created = MemEntry(s, true, lastModifiedMillis = lastModifiedMillis)
                cur.children[k] = created
                created
            }
            curPath = FsPaths.join(curPath, cur.name)
        }
        FsNode(curPath, cur.name, true, 0L, cur.lastModifiedMillis)
    }

    fun putFile(path: String, bytes: ByteArray, lastModifiedMillis: Long = System.currentTimeMillis()): FsNode = synchronized(lock) {
        ensureOpen()
        val norm = FsPaths.normalize(path)
        val parentPath = FsPaths.parent(norm)
        if (parentPath != "/") addFolder(parentPath, lastModifiedMillis)
        val (_, parent) = resolveLocked(parentPath) ?: throw IOException("Folder not found: $parentPath")
        val name = FsPaths.name(norm)
        val k = name.lowercase(Locale.ROOT)
        val prev = parent.children[k]
        if (prev != null) {
            currentFreeBytes += roundClusters(prev.data.size.toLong())
        }
        val entry = MemEntry(name, false, bytes.copyOf(), lastModifiedMillis)
        parent.children[k] = entry
        currentFreeBytes = (currentFreeBytes - roundClusters(bytes.size.toLong())).coerceAtLeast(0L)
        val realParent = resolveLocked(parentPath)!!.first
        val realPath = FsPaths.join(realParent, name)
        FsNode(realPath, name, false, bytes.size.toLong(), lastModifiedMillis)
    }

    private fun roundClusters(size: Long): Long =
        if (size <= 0L) 0L else ((size + clusterSize - 1) / clusterSize) * clusterSize

    private fun resolveLocked(path: String): Pair<String, MemEntry>? {
        val segs = FsPaths.segments(path)
        var cur = root
        var real = "/"
        for (s in segs) {
            if (!cur.isDirectory) return null
            val next = cur.children[s.lowercase(Locale.ROOT)] ?: return null
            real = FsPaths.join(real, next.name)
            cur = next
        }
        return real to cur
    }

    override fun stat(path: String): FsNode? = synchronized(lock) {
        ensureOpen()
        val (real, e) = resolveLocked(path) ?: return null
        toNode(real, e)
    }

    override fun list(dir: String): List<FsNode> = synchronized(lock) {
        ensureOpen()
        val (real, e) = resolveLocked(dir) ?: throw IOException("Folder not found: $dir")
        if (!e.isDirectory) throw IOException("Not a folder: $dir")
        e.children.values.map { toNode(FsPaths.join(real, it.name), it) }
    }

    override fun openFile(path: String): FsFileHandle = synchronized(lock) {
        ensureOpen()
        val (_, e) = resolveLocked(path) ?: throw IOException("File not found: $path")
        if (e.isDirectory) throw IOException("Is a folder: $path")
        val snap = e.data.copyOf()
        object : FsFileHandle {
            override val size: Long = snap.size.toLong()
            @Volatile private var hClosed = false
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (hClosed || closed) throw IOException("Volume is locked")
                if (position >= size) return -1
                val n = minOf(length.toLong(), size - position).toInt()
                System.arraycopy(snap, position.toInt(), buffer, offset, n)
                return n
            }
            override fun close() {
                hClosed = true
            }
        }
    }

    override fun createFile(
        dir: String,
        name: String,
        size: Long,
        source: (pos: Long, buf: ByteArray, off: Int, len: Int) -> Unit,
        onProgress: (Long) -> Unit,
    ): FsNode = synchronized(lock) {
        ensureWritable()
        val (realDir, dirEntry) = resolveLocked(dir) ?: throw IOException("Folder not found: $dir")
        if (!dirEntry.isDirectory) throw IOException("Not a folder: $dir")
        val finalName = FsWriteSupport.uniqueName(name, dirEntry.children.keys)
        val neededBytes = roundClusters(size)
        if (neededBytes > currentFreeBytes) throw IOException("Not enough free space in the volume")
        val bytes = ByteArray(size.toInt())
        var off = 0
        while (off < bytes.size) {
            val chunk = minOf(65536, bytes.size - off)
            source(off.toLong(), bytes, off, chunk)
            off += chunk
            onProgress(off.toLong())
        }
        val now = System.currentTimeMillis()
        val entry = MemEntry(finalName, false, bytes, now)
        dirEntry.children[finalName.lowercase(Locale.ROOT)] = entry
        currentFreeBytes -= neededBytes
        toNode(FsPaths.join(realDir, finalName), entry)
    }

    override fun createDirectory(dir: String, name: String, autoRename: Boolean): FsNode = synchronized(lock) {
        ensureWritable()
        val (realDir, dirEntry) = resolveLocked(dir) ?: throw IOException("Folder not found: $dir")
        if (!dirEntry.isDirectory) throw IOException("Not a folder: $dir")
        val finalName = FsWriteSupport.newFolderName(name, dirEntry.children.keys, autoRename)
        val entry = MemEntry(finalName, true)
        dirEntry.children[finalName.lowercase(Locale.ROOT)] = entry
        toNode(FsPaths.join(realDir, finalName), entry)
    }

    override fun delete(path: String, overwrite: Boolean, onProgress: (Long) -> Unit) = synchronized(lock) {
        ensureWritable()
        val norm = FsPaths.normalize(path)
        if (norm == "/") throw IOException("Cannot delete the root folder")
        val (real, e) = resolveLocked(norm) ?: throw IOException("File or folder not found: $path")
        val (_, parent) = resolveLocked(FsPaths.parent(real))!!
        parent.children.remove(e.name.lowercase(Locale.ROOT))
        var freed = 0L
        fun freeRec(m: MemEntry) {
            if (m.isDirectory) {
                for (c in m.children.values) freeRec(c)
            } else {
                freed += roundClusters(m.data.size.toLong())
                if (overwrite) m.data.fill(0)
            }
        }
        freeRec(e)
        currentFreeBytes = (currentFreeBytes + freed).coerceAtMost(totalBytes)
        if (overwrite) onProgress(freed)
    }

    override fun rename(path: String, newName: String): String = synchronized(lock) {
        ensureWritable()
        FsWriteSupport.checkName(newName)
        val norm = FsPaths.normalize(path)
        if (norm == "/") throw IOException("Cannot rename the root folder")
        val (real, e) = resolveLocked(norm) ?: throw IOException("File or folder not found: $path")
        val parentPath = FsPaths.parent(real)
        val (_, parent) = resolveLocked(parentPath)!!
        val oldKey = e.name.lowercase(Locale.ROOT)
        val newKey = newName.lowercase(Locale.ROOT)
        if (newKey != oldKey && parent.children.containsKey(newKey)) {
            throw IOException("\"$newName\" already exists in this folder")
        }
        parent.children.remove(oldKey)
        e.name = newName
        parent.children[newKey] = e
        FsPaths.join(parentPath, newName)
    }

    override fun move(path: String, targetDir: String): String = synchronized(lock) {
        ensureWritable()
        val normSrc = FsPaths.normalize(path)
        val normDst = FsPaths.normalize(targetDir)
        if (normSrc == "/") throw IOException("Cannot move the root folder")
        val (realSrc, e) = resolveLocked(normSrc) ?: throw IOException("File or folder not found: $path")
        val (realDst, dst) = resolveLocked(normDst) ?: throw IOException("Target folder not found: $targetDir")
        if (!dst.isDirectory) throw IOException("Target is not a folder: $targetDir")
        FsWriteSupport.checkMoveTarget(realSrc, e.name, e.isDirectory, realDst)
        val srcParentPath = FsPaths.parent(realSrc)
        if (srcParentPath.equals(realDst, ignoreCase = true)) return realSrc
        val (_, srcParent) = resolveLocked(srcParentPath)!!
        val finalName = FsWriteSupport.uniqueName(e.name, dst.children.keys)
        srcParent.children.remove(e.name.lowercase(Locale.ROOT))
        e.name = finalName
        dst.children[finalName.lowercase(Locale.ROOT)] = e
        FsPaths.join(realDst, finalName)
    }

    private fun toNode(path: String, e: MemEntry): FsNode =
        if (path == "/") FsNode("/", "", true, 0L, 0L)
        else FsNode(path, e.name, e.isDirectory, if (e.isDirectory) 0L else e.data.size.toLong(), e.lastModifiedMillis)

    private fun ensureOpen() {
        if (closed) throw IOException("Volume is locked")
    }

    private fun ensureWritable() {
        ensureOpen()
        writeSupport?.let { throw IOException(it) }
    }

    override fun close() {
        closed = true
    }
}

