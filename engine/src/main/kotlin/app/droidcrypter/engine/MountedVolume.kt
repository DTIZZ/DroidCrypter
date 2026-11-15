package app.droidcrypter.engine

import java.io.Closeable

/** A file or folder. Paths are absolute, "/"-separated; the root is "/". */
data class FsNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModifiedMillis: Long,
)

interface FsFileHandle : Closeable {
    val size: Long

    /** Positional, thread-safe read. Returns the number of bytes read, or -1 at EOF. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

interface MountedVolume : Closeable {
    /** Random, URI-safe (16 hex chars), new per unlock. */
    val id: String
    /** FS label, or "DroidCrypter volume" / "Hidden volume" if blank. */
    val label: String
    /** Capacity of the filesystem's data area. */
    val totalBytes: Long
    /** Live free space. May scan the allocation table: never call on the main thread. */
    val freeBytes: Long
    /** null = writable; else a user-facing reason. */
    val writeSupport: String?

    fun stat(path: String): FsNode?
    fun list(dir: String): List<FsNode>
    fun openFile(path: String): FsFileHandle

    /** Creates [name] in [dir] streaming [size] bytes from [source]. The name may become "name (2).ext". */
    fun createFile(
        dir: String, name: String, size: Long,
        source: (pos: Long, buf: ByteArray, off: Int, len: Int) -> Unit,
        onProgress: (Long) -> Unit = {},
    ): FsNode

    /**
     * Creates the empty folder [name] in [dir]. If the name is taken this throws, unless
     * [autoRename] is set, in which case the folder becomes "name (2)".
     */
    fun createDirectory(dir: String, name: String, autoRename: Boolean = false): FsNode

    /** Deletes a file or (recursively) a folder; with [overwrite], zeroes data and entries first. */
    fun delete(path: String, overwrite: Boolean, onProgress: (Long) -> Unit = {})

    /** Renames within the same folder; returns the new path. */
    fun rename(path: String, newName: String): String

    /** Moves within the same volume; returns the new path. */
    fun move(path: String, targetDir: String): String
}

/** Path helpers shared by the engine. */
object FsPaths {
    /** Splits an absolute path into segments. Rejects relative paths and "."/".." segments. */
    fun segments(path: String): List<String> {
        require(path.startsWith("/")) { "Path must be absolute: $path" }
        val parts = path.split('/').filter { it.isNotEmpty() }
        require(parts.none { it == "." || it == ".." }) { "Invalid path: $path" }
        return parts
    }

    fun join(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"

    fun parent(path: String): String {
        val i = path.trimEnd('/').lastIndexOf('/')
        return if (i <= 0) "/" else path.substring(0, i)
    }

    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')

    fun normalize(path: String): String = "/" + segments(path).joinToString("/")

    /**
     * Makes a name from outside the vault (another app, Android storage) storable on FAT/exFAT:
     * characters those filesystems forbid become "_", trailing dots and spaces are dropped.
     */
    fun sanitizeName(name: String, fallback: String = "unnamed"): String {
        val sb = StringBuilder(name.length)
        for (c in name) sb.append(if (c.code < 0x20 || c in FORBIDDEN_NAME_CHARS) '_' else c)
        val cleaned = sb.toString().trim().trimEnd('.', ' ').take(255).trimEnd('.', ' ')
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") fallback else cleaned
    }

    private const val FORBIDDEN_NAME_CHARS = "\\/:*?\"<>|"
}

