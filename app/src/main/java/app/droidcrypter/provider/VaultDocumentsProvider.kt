package app.droidcrypter.provider

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import app.droidcrypter.R
import app.droidcrypter.browse.FileClassification
import app.droidcrypter.browse.RatingsCodec
import app.droidcrypter.browse.RatingsRegistry
import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.FsPaths
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.engine.VolumeRegistry
import java.io.FileNotFoundException

/**
 * Exposes unlocked DroidCrypter volumes to the Android system file picker and Samsung My Files
 * without writing any plaintext to disk (section 7.10).
 */
class VaultDocumentsProvider : DocumentsProvider() {

    private lateinit var proxyThread: HandlerThread
    private lateinit var proxyHandler: Handler

    override fun onCreate(): Boolean {
        proxyThread = HandlerThread("droidcrypter-doc-proxy").apply { start() }
        proxyHandler = Handler(proxyThread.looper)

        VolumeRegistry.addChangeListener {
            context?.let { notifyRootsChanged(it) }
        }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(cols)
        val volumes = VolumeRegistry.all()
        for (vol in volumes) {
            val row = cursor.newRow()
            row.add(Root.COLUMN_ROOT_ID, vol.id)
            row.add(Root.COLUMN_DOCUMENT_ID, VolumeUris.formatDocumentId(vol.id, "/"))
            row.add(Root.COLUMN_TITLE, vol.label)
            row.add(Root.COLUMN_SUMMARY, "DroidCrypter container")
            row.add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            row.add(Root.COLUMN_CAPACITY_BYTES, vol.totalBytes)
            val free = try {
                vol.freeBytes.coerceIn(0L, vol.totalBytes)
            } catch (_: Throwable) {
                0L
            }
            row.add(Root.COLUMN_AVAILABLE_BYTES, free)
        }
        return cursor
    }

    /** A parsed document ID together with its (currently unlocked) volume. */
    private data class Target(val path: String, val vol: MountedVolume)

    /** Parses [documentId] and looks up its volume; every failure becomes [FileNotFoundException]. */
    private fun resolve(documentId: String): Target {
        val parsed = try {
            VolumeUris.parseDocumentId(documentId)
        } catch (t: Throwable) {
            throw FileNotFoundException(t.message ?: "Invalid documentId")
        }
        val vol = VolumeRegistry.get(parsed.volumeId)
            ?: throw FileNotFoundException("Volume is locked or unavailable")
        return Target(parsed.path, vol)
    }

    /** Like [resolve] but for a mutation of a non-root document on a writable volume. */
    private fun resolveForWrite(documentId: String, action: String): Target {
        val t = resolve(documentId)
        if (t.path == "/") throw UnsupportedOperationException("Cannot $action root directory")
        t.vol.writeSupport?.let { throw UnsupportedOperationException(it) }
        return t
    }

    private fun MountedVolume.statOrThrow(path: String): FsNode =
        try {
            stat(path)
        } catch (t: Throwable) {
            throw FileNotFoundException(t.message ?: "Could not stat $path")
        } ?: throw FileNotFoundException("Not found: $path")

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val (path, vol) = resolve(documentId)
        val node = vol.statOrThrow(path)

        if (RatingsCodec.isInternalMetaName(node.name)) {
            throw FileNotFoundException("Hidden metadata file")
        }

        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(cols)
        includeNode(cursor, vol, node)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val (path, vol) = resolve(parentDocumentId)

        val children = try {
            vol.list(path)
        } catch (t: Throwable) {
            throw FileNotFoundException(t.message ?: "Could not list $path")
        }

        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(cols)
        context?.contentResolver?.let { cr ->
            cursor.setNotificationUri(
                cr,
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId),
            )
        }

        for (child in children) {
            if (RatingsCodec.isInternalMetaName(child.name)) continue
            includeNode(cursor, vol, child)
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            val parent = VolumeUris.parseDocumentId(parentDocumentId)
            val child = VolumeUris.parseDocumentId(documentId)
            if (parent.volumeId != child.volumeId) return false
            if (parent.path == "/") return true
            child.path == parent.path || child.path.startsWith("${parent.path}/")
        } catch (_: Throwable) {
            false
        }
    }

    override fun getDocumentType(documentId: String): String {
        val (path, vol) = resolve(documentId)
        return FileClassification.mimeType(vol.statOrThrow(path))
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (ParcelFileDescriptor.parseMode(mode) != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw UnsupportedOperationException("VaultDocumentsProvider openDocument is read-only")
        }
        val (path, vol) = resolve(documentId)
        val node = vol.statOrThrow(path)
        if (node.isDirectory || RatingsCodec.isInternalMetaName(node.name)) {
            throw FileNotFoundException("Cannot open directory or internal metadata file")
        }

        val handle = try {
            vol.openFile(path)
        } catch (t: Throwable) {
            throw FileNotFoundException(t.message ?: "Cannot open $path")
        }

        val storageManager = context?.getSystemService(StorageManager::class.java)
            ?: run {
                try { handle.close() } catch (_: Throwable) {}
                throw FileNotFoundException("StorageManager unavailable")
            }

        return try {
            storageManager.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = handle.size

                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        if (size <= 0) return 0
                        return try {
                            val n = handle.read(offset, data, 0, size.coerceAtMost(data.size))
                            if (n < 0) 0 else n
                        } catch (t: Throwable) {
                            throw ErrnoException("onRead", OsConstants.EIO, t)
                        }
                    }

                    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
                        throw ErrnoException("onWrite", OsConstants.EROFS)
                    }

                    override fun onFsync() {
                        // Read-only handle
                    }

                    override fun onRelease() {
                        try {
                            handle.close()
                        } catch (_: Throwable) {
                        }
                    }
                },
                proxyHandler,
            )
        } catch (t: Throwable) {
            try { handle.close() } catch (_: Throwable) {}
            throw FileNotFoundException(t.message ?: "Failed to open proxy file descriptor")
        }
    }

    override fun deleteDocument(documentId: String) {
        val (path, vol) = resolveForWrite(documentId, "delete")

        val parentPath = FsPaths.parent(path)
        try {
            // Plain delete (overwrite = false) per section 7.10.
            vol.delete(path, overwrite = false)
        } catch (t: Throwable) {
            throw FileNotFoundException(t.message ?: "Delete failed")
        }

        RatingsRegistry.get(vol.id)?.onPathDeleted(path)
        try {
            revokeDocumentPermission(documentId)
        } catch (_: Throwable) {
        }
        context?.let { ctx ->
            notifyChildDocumentsChanged(ctx, vol.id, parentPath)
            notifyRootsChanged(ctx)
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val (path, vol) = resolveForWrite(documentId, "rename")

        val parentPath = FsPaths.parent(path)
        val newPath = try {
            vol.rename(path, displayName)
        } catch (t: Throwable) {
            throw FileNotFoundException(t.message ?: "Rename failed")
        }

        RatingsRegistry.get(vol.id)?.onPathMovedOrRenamed(path, newPath)
        try {
            revokeDocumentPermission(documentId)
        } catch (_: Throwable) {
        }
        val newDocId = VolumeUris.formatDocumentId(vol.id, newPath)
        context?.let { ctx ->
            notifyChildDocumentsChanged(ctx, vol.id, parentPath)
        }
        return newDocId
    }

    private fun includeNode(cursor: MatrixCursor, vol: MountedVolume, node: FsNode) {
        val docId = VolumeUris.formatDocumentId(vol.id, node.path)
        val isRoot = node.path == "/"
        val displayName = if (isRoot) vol.label else node.name
        val mimeType = FileClassification.mimeType(node)

        // Thumbnails are intentionally NEVER advertised (no FLAG_SUPPORTS_THUMBNAIL) so the OS never caches previews.
        var flags = 0
        if (!isRoot && vol.writeSupport == null) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }

        val row = cursor.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, docId)
        row.add(Document.COLUMN_DISPLAY_NAME, displayName)
        row.add(Document.COLUMN_MIME_TYPE, mimeType)
        row.add(Document.COLUMN_FLAGS, flags)
        row.add(Document.COLUMN_SIZE, if (node.isDirectory) null else node.size)
        row.add(Document.COLUMN_LAST_MODIFIED, if (node.lastModifiedMillis > 0L) node.lastModifiedMillis else null)
    }

    companion object {
        const val AUTHORITY = "app.droidcrypter.documents"

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_CAPACITY_BYTES,
            Root.COLUMN_AVAILABLE_BYTES,
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        fun notifyRootsChanged(context: Context) {
            try {
                context.contentResolver.notifyChange(
                    DocumentsContract.buildRootsUri(AUTHORITY),
                    null,
                )
            } catch (_: Throwable) {
            }
        }

        fun notifyChildDocumentsChanged(context: Context, volumeId: String, dirPath: String) {
            try {
                val docId = VolumeUris.formatDocumentId(volumeId, FsPaths.normalize(dirPath))
                context.contentResolver.notifyChange(
                    DocumentsContract.buildChildDocumentsUri(AUTHORITY, docId),
                    null,
                )
            } catch (_: Throwable) {
            }
        }
    }
}

