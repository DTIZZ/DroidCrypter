package app.droidcrypter.engine

import app.droidcrypter.engine.fs.FileSystemDriver
import app.droidcrypter.engine.vc.CipherSuite
import app.droidcrypter.engine.vc.Prf
import java.io.IOException

/**
 * An unlocked DroidCrypter container. Owns the host device, the decrypted device (and with it the
 * master keys) and the filesystem. [close] drops the keys and the metadata caches; afterwards every
 * operation throws `IOException("Volume is locked")`.
 */
class ContainerVolume internal constructor(
    private val host: BlockDevice,
    private val device: BlockDevice,
    private val fs: FileSystemDriver,
    val cipher: CipherSuite,
    val prf: Prf,
    val isHidden: Boolean,
) : MountedVolume {
    override val id: String = Secrets.randomHexId()

    @Volatile
    private var closed = false

    val filesystemName: String get() = fs.typeName

    override val label: String = fs.label.trim().ifEmpty { if (isHidden) "Hidden volume" else "DroidCrypter volume" }

    override val totalBytes: Long get() = fs.totalBytes

    override val freeBytes: Long get() = guard { fs.freeBytes() }

    override val writeSupport: String?
        get() = when {
            closed -> "Volume is locked"
            !device.isWritable -> "This volume is read-only"
            else -> fs.writeSupport
        }

    override fun stat(path: String): FsNode? = guard { fs.stat(path) }
    override fun list(dir: String): List<FsNode> = guard { fs.list(dir) }
    override fun openFile(path: String): FsFileHandle = guard { fs.openFile(path) }

    override fun createFile(dir: String, name: String, size: Long,
                            source: (pos: Long, buf: ByteArray, off: Int, len: Int) -> Unit,
                            onProgress: (Long) -> Unit): FsNode = writable { fs.createFile(dir, name, size, source, onProgress) }

    override fun createDirectory(dir: String, name: String, autoRename: Boolean): FsNode =
        writable { fs.createDirectory(dir, name, autoRename) }

    override fun delete(path: String, overwrite: Boolean, onProgress: (Long) -> Unit) =
        writable { fs.delete(path, overwrite, onProgress) }

    override fun rename(path: String, newName: String): String = writable { fs.rename(path, newName) }
    override fun move(path: String, targetDir: String): String = writable { fs.move(path, targetDir) }

    private inline fun <T> guard(block: () -> T): T {
        if (closed) throw IOException("Volume is locked")
        try {
            return block()
        } catch (e: IOException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message ?: "Invalid argument", e)
        } catch (e: RuntimeException) {
            // Safety net for hostile containers: never let a parser bug crash the caller's process.
            throw IOException("Corrupt filesystem: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private inline fun <T> writable(block: () -> T): T = guard {
        writeSupport?.let { throw IOException(it) }
        block()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { fs.close() } catch (_: Exception) {}
        try { device.close() } catch (_: Exception) {}
        try { host.close() } catch (_: Exception) {}
    }

    val isClosed: Boolean get() = closed
}
