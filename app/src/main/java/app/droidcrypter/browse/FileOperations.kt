package app.droidcrypter.browse

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.FsPaths
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.engine.Secrets
import app.droidcrypter.provider.VaultDocumentsProvider
import java.io.IOException

data class IncomingFileMeta(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
)

/** A file found inside a picked folder; [relativeDir] is its folder below the picked one ("" = directly inside). */
data class IncomingTreeFile(
    val meta: IncomingFileMeta,
    val relativeDir: String,
)

/** A folder picked with `ACTION_OPEN_DOCUMENT_TREE`, scanned and ready to be added to a vault. */
data class IncomingFolder(
    val treeUri: Uri,
    val name: String,
    /** Every subfolder as a "/"-separated path below the picked folder, parents before children. */
    val subfolders: List<String>,
    val files: List<IncomingTreeFile>,
    /** Entries the scan had to leave out (unreadable folders, limits), as user-facing lines. */
    val skipped: List<String>,
) {
    val totalBytes: Long get() = files.sumOf { it.meta.sizeBytes.coerceAtLeast(0L) }
}

data class FileOpProgress(
    val title: String,
    val currentFile: String,
    val itemIndex: Int,
    val itemCount: Int,
    val bytesDone: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) {
            if (itemCount <= 0) 0f else (itemIndex - 1).coerceAtLeast(0).toFloat() / itemCount.toFloat()
        } else {
            (bytesDone.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

data class FileBatchResult(
    val succeeded: Int,
    val failures: List<String>,
    /** Folders created in the vault (folder imports only). */
    val foldersCreated: Int = 0,
)

/**
 * Streams files into and out of an unlocked [MountedVolume] without temporary files on disk (section 7.4).
 */
object FileOperations {

    fun queryIncomingFiles(contentResolver: ContentResolver, uris: List<Uri>): List<IncomingFileMeta> {
        return uris.mapIndexed { idx, uri ->
            var name = "file_${idx + 1}"
            var size = -1L
            try {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !c.isNull(nameIdx)) {
                            val n = c.getString(nameIdx)?.trim()
                            if (!n.isNullOrEmpty()) name = n
                        }
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                            size = c.getLong(sizeIdx)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            if (size < 0L) {
                try {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        if (afd.length >= 0L) size = afd.length
                    }
                } catch (_: Throwable) {
                }
            }
            if (size <= 0L) {
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        var counted = 0L
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = stream.read(buf)
                            if (r < 0) break
                            counted += r
                        }
                        if (counted > 0L) size = counted
                    }
                } catch (_: Throwable) {
                }
            }
            if (!name.contains('.')) {
                try {
                    val mime = contentResolver.getType(uri)
                    if (mime != null) {
                        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                        if (!ext.isNullOrEmpty()) {
                            name = "$name.$ext"
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            IncomingFileMeta(
                uri = uri,
                displayName = name,
                sizeBytes = size.coerceAtLeast(0L),
            )
        }
    }

    /**
     * Streams [files] into [targetDir] inside [volume].
     * If [deleteOriginals] is true, attempts to delete each source URI after its container write succeeds.
     */
    fun addFilesToContainer(
        context: Context,
        volume: MountedVolume,
        targetDir: String,
        files: List<IncomingFileMeta>,
        deleteOriginals: Boolean,
        onProgress: (FileOpProgress) -> Unit,
    ): FileBatchResult {
        val res = importFiles(context, volume, files.map { it to targetDir }, deleteOriginals, "Adding files to vault", onProgress)
        VaultDocumentsProvider.notifyChildDocumentsChanged(context, volume.id, targetDir)
        VaultDocumentsProvider.notifyRootsChanged(context)
        return res
    }

    /**
     * Lists everything inside the folder picked as [treeUri]. Nothing is read from the files yet.
     * Bounded to [MAX_TREE_DEPTH] levels and [MAX_TREE_ENTRIES] entries.
     */
    fun queryIncomingFolder(contentResolver: ContentResolver, treeUri: Uri): IncomingFolder {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        var rootName = ""
        try {
            contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) rootName = c.getString(0).orEmpty() }
        } catch (_: Throwable) {
        }
        if (rootName.isBlank()) rootName = rootId.substringAfterLast('/').substringAfterLast(':')

        val subfolders = ArrayList<String>()
        val files = ArrayList<IncomingTreeFile>()
        val skipped = ArrayList<String>()
        var entries = 0
        var truncated = false

        // Breadth-first, so parents are always listed before their children.
        val queue = ArrayDeque<Triple<String, String, Int>>() // document id, relative dir, depth
        queue.add(Triple(rootId, "", 0))
        while (queue.isNotEmpty() && !truncated) {
            val (docId, relDir, depth) = queue.removeFirst()
            val shown = relDir.ifEmpty { rootName }
            try {
                contentResolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId),
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        if (++entries > MAX_TREE_ENTRIES) {
                            truncated = true
                            break
                        }
                        val childId = c.getString(0) ?: continue
                        val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                        if (isDir) {
                            if (depth + 1 >= MAX_TREE_DEPTH) {
                                skipped.add("$shown/$name: folders nested this deeply are not added")
                                continue
                            }
                            val rel = if (relDir.isEmpty()) name else "$relDir/$name"
                            subfolders.add(rel)
                            queue.add(Triple(childId, rel, depth + 1))
                        } else {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            // Providers may leave the size out: fall back to measuring the stream.
                            val size = if (c.isNull(3)) {
                                queryIncomingFiles(contentResolver, listOf(uri)).first().sizeBytes
                            } else {
                                c.getLong(3)
                            }
                            files.add(IncomingTreeFile(IncomingFileMeta(uri, name, size.coerceAtLeast(0L)), relDir))
                        }
                    }
                } ?: skipped.add("$shown: could not be read")
            } catch (t: Throwable) {
                skipped.add("$shown: ${t.message ?: "could not be read"}")
            }
        }
        if (truncated) skipped.add("Only the first $MAX_TREE_ENTRIES items of this folder are added")
        return IncomingFolder(treeUri, rootName, subfolders, files, skipped)
    }

    /**
     * Recreates [folder] (subfolders included, empty ones too) inside [targetDir] and streams its
     * files in. The top folder becomes "name (2)" if the name is taken; names FAT/exFAT cannot
     * store are adjusted. With [deleteOriginals], each source file is deleted once it is safely in
     * the vault, and the source folder itself only if everything was added.
     */
    fun addFolderToContainer(
        context: Context,
        volume: MountedVolume,
        targetDir: String,
        folder: IncomingFolder,
        deleteOriginals: Boolean,
        onProgress: (FileOpProgress) -> Unit,
    ): FileBatchResult {
        val cr = context.contentResolver
        val title = "Adding folder to vault"
        val failures = ArrayList(folder.skipped)
        onProgress(FileOpProgress(title, folder.name, 0, folder.files.size, 0L, folder.totalBytes))

        // Source relative dir -> the vault path it became.
        val created = HashMap<String, String>()
        try {
            created[""] = volume.createDirectory(
                targetDir, FsPaths.sanitizeName(folder.name, "folder"), autoRename = true,
            ).path
        } catch (t: Throwable) {
            return FileBatchResult(0, listOf("${folder.name}: ${t.message ?: "Could not create the folder"}"))
        }
        var foldersMade = 1
        for (rel in folder.subfolders) {
            val parent = created[rel.substringBeforeLast('/', "")] ?: continue // its parent already failed
            try {
                created[rel] = volume.createDirectory(
                    parent, FsPaths.sanitizeName(rel.substringAfterLast('/'), "folder"), autoRename = true,
                ).path
                foldersMade++
            } catch (t: Throwable) {
                failures.add("$rel: ${t.message ?: "Could not create the folder"}")
            }
        }

        val items = ArrayList<Pair<IncomingFileMeta, String>>(folder.files.size)
        for (f in folder.files) {
            val dir = created[f.relativeDir]
            if (dir == null) {
                failures.add("${f.relativeDir}/${f.meta.displayName}: its folder could not be created")
            } else {
                items.add(f.meta.copy(displayName = FsPaths.sanitizeName(f.meta.displayName, "file")) to dir)
            }
        }
        val res = importFiles(context, volume, items, deleteOriginals, title, onProgress)
        failures.addAll(res.failures)

        if (deleteOriginals && failures.isEmpty()) {
            val deleted = try {
                DocumentsContract.deleteDocument(
                    cr, DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, DocumentsContract.getTreeDocumentId(folder.treeUri)),
                )
            } catch (_: Throwable) {
                false
            }
            if (!deleted) failures.add("${folder.name}: added, but the now-empty original folder could not be deleted")
        }

        VaultDocumentsProvider.notifyChildDocumentsChanged(context, volume.id, targetDir)
        VaultDocumentsProvider.notifyRootsChanged(context)
        return FileBatchResult(res.succeeded, failures, foldersMade)
    }

    /** Streams each file into its own target folder; shared by the file and folder imports. */
    private fun importFiles(
        context: Context,
        volume: MountedVolume,
        items: List<Pair<IncomingFileMeta, String>>,
        deleteOriginals: Boolean,
        title: String,
        onProgress: (FileOpProgress) -> Unit,
    ): FileBatchResult {
        val cr = context.contentResolver
        val totalBytes = items.sumOf { it.first.sizeBytes.coerceAtLeast(0L) }
        var cumulativeBytes = 0L
        var succeeded = 0
        val failures = mutableListOf<String>()

        for ((idx, entry) in items.withIndex()) {
            val (item, targetDir) = entry
            val baseDone = cumulativeBytes
            onProgress(
                FileOpProgress(
                    title = title,
                    currentFile = item.displayName,
                    itemIndex = idx + 1,
                    itemCount = items.size,
                    bytesDone = baseDone,
                    totalBytes = totalBytes,
                )
            )

            try {
                cr.openInputStream(item.uri)?.use { input ->
                    var streamOffset = 0L
                    volume.createFile(
                        dir = targetDir,
                        name = item.displayName,
                        size = item.sizeBytes,
                        source = { pos, buf, off, len ->
                            if (pos != streamOffset) {
                                throw IOException("Non-sequential read requested at $pos (expected $streamOffset)")
                            }
                            var got = 0
                            while (got < len) {
                                val r = input.read(buf, off + got, len - got)
                                if (r < 0) {
                                    throw IOException("Source file '${item.displayName}' shrunk during import ($streamOffset < ${item.sizeBytes})")
                                }
                                got += r
                                streamOffset += r
                            }
                        },
                        onProgress = { fileWritten ->
                            onProgress(
                                FileOpProgress(
                                    title = title,
                                    currentFile = item.displayName,
                                    itemIndex = idx + 1,
                                    itemCount = items.size,
                                    bytesDone = baseDone + fileWritten,
                                    totalBytes = totalBytes,
                                )
                            )
                        },
                    )
                } ?: throw IOException("Could not open source file '${item.displayName}'")

                cumulativeBytes += item.sizeBytes
                succeeded++

                if (deleteOriginals) {
                    val deleted = try {
                        DocumentsContract.deleteDocument(cr, item.uri) || cr.delete(item.uri, null, null) > 0
                    } catch (_: Throwable) {
                        false
                    }
                    if (!deleted) {
                        failures.add("${item.displayName}: imported, but original could not be deleted from source provider")
                    }
                }
            } catch (t: Throwable) {
                failures.add("${item.displayName}: ${t.message ?: "Import failed"}")
            }
        }
        return FileBatchResult(succeeded, failures)
    }

    /** [summary] plus the first few [failures]; a big import must not produce an endless message. */
    fun summarizeFailures(summary: String, failures: List<String>, shown: Int = 8): String = buildString {
        append(summary)
        append("\n${failures.size} problem(s):\n")
        append(failures.take(shown).joinToString("\n"))
        if (failures.size > shown) append("\n… and ${failures.size - shown} more")
    }

    private const val MAX_TREE_DEPTH = 48
    private const val MAX_TREE_ENTRIES = 50_000

    /**
     * Copies (and optionally moves) a single file out of [volume] to [destDocumentUri] (`ACTION_CREATE_DOCUMENT`).
     * Deletes any partial destination file on failure; for `moveOut`, deletes the container source only after
     * the destination file has been completely written and flushed.
     */
    fun exportSingleFile(
        context: Context,
        volume: MountedVolume,
        ratingsStore: RatingsStore,
        sourcePath: String,
        destDocumentUri: Uri,
        moveOut: Boolean,
        onProgress: (FileOpProgress) -> Unit,
    ): FileBatchResult {
        val cr = context.contentResolver
        val node = volume.stat(sourcePath)
            ?: return FileBatchResult(0, listOf("File not found: $sourcePath"))
        val total = node.size.coerceAtLeast(0L)
        val opTitle = if (moveOut) "Moving file out of vault" else "Copying file out of vault"

        var copyComplete = false
        try {
            volume.openFile(sourcePath).use { handle ->
                cr.openOutputStream(destDocumentUri, "wt")?.use { out ->
                    val buf = ByteArray(128 * 1024)
                    try {
                        var pos = 0L
                        while (pos < handle.size) {
                            val take = minOf(buf.size.toLong(), handle.size - pos).toInt()
                            val n = handle.read(pos, buf, 0, take)
                            if (n <= 0) throw IOException("Unexpected EOF reading ${node.name}")
                            out.write(buf, 0, n)
                            pos += n
                            onProgress(
                                FileOpProgress(
                                    title = opTitle,
                                    currentFile = node.name,
                                    itemIndex = 1,
                                    itemCount = 1,
                                    bytesDone = pos,
                                    totalBytes = total,
                                )
                            )
                        }
                        out.flush()
                    } finally {
                        Secrets.wipe(buf)
                    }
                } ?: throw IOException("Could not open destination file for writing")
            }
            copyComplete = true
        } catch (t: Throwable) {
            deletePartialDocument(cr, destDocumentUri)
            return FileBatchResult(0, listOf("${node.name}: ${t.message ?: "Export failed"}"))
        }

        if (moveOut && copyComplete) {
            return try {
                val parent = FsPaths.parent(sourcePath)
                volume.delete(sourcePath, overwrite = true) { overwritten ->
                    onProgress(
                        FileOpProgress(
                            title = "Overwriting & deleting vault copy",
                            currentFile = node.name,
                            itemIndex = 1,
                            itemCount = 1,
                            bytesDone = overwritten,
                            totalBytes = total,
                        )
                    )
                }
                ratingsStore.onPathDeleted(sourcePath)
                VaultDocumentsProvider.notifyChildDocumentsChanged(context, volume.id, parent)
                VaultDocumentsProvider.notifyRootsChanged(context)
                FileBatchResult(1, emptyList())
            } catch (t: Throwable) {
                FileBatchResult(1, listOf("${node.name}: copied out, but removing from vault failed (${t.message})"))
            }
        }

        return FileBatchResult(1, emptyList())
    }

    /**
     * Copies (and optionally moves) multiple files/folders out of [volume] into a destination document tree
     * (`ACTION_OPEN_DOCUMENT_TREE`).
     * For [moveOut], source items are deleted from [volume] ONLY after all items have been copied out cleanly.
     */
    fun exportToTree(
        context: Context,
        volume: MountedVolume,
        ratingsStore: RatingsStore,
        sourcePaths: List<String>,
        treeUri: Uri,
        moveOut: Boolean,
        onProgress: (FileOpProgress) -> Unit,
    ): FileBatchResult {
        val cr = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)

        // Collect all files to compute total bytes and item count
        data class ExportEntry(val srcNode: FsNode, val parentTreeDocUri: Uri)

        val entries = mutableListOf<ExportEntry>()
        val failures = mutableListOf<String>()

        fun collectRecursive(path: String, targetParentDocUri: Uri) {
            val node = volume.stat(path) ?: throw IOException("Not found: $path")
            if (RatingsCodec.isInternalMetaName(node.name)) return
            if (node.isDirectory) {
                val subDirUri = DocumentsContract.createDocument(
                    cr,
                    targetParentDocUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    node.name,
                ) ?: throw IOException("Could not create folder '${node.name}' in destination")
                val children = volume.list(node.path)
                for (child in children) {
                    if (!RatingsCodec.isInternalMetaName(child.name)) {
                        collectRecursive(child.path, subDirUri)
                    }
                }
            } else {
                entries.add(ExportEntry(node, targetParentDocUri))
            }
        }

        for (srcPath in sourcePaths) {
            try {
                collectRecursive(srcPath, rootDocUri)
            } catch (t: Throwable) {
                failures.add("$srcPath: ${t.message ?: "Failed to prepare destination"}")
            }
        }

        val totalBytes = entries.sumOf { it.srcNode.size.coerceAtLeast(0L) }
        var bytesDone = 0L
        var succeeded = 0
        val opTitle = if (moveOut) "Moving items out of vault" else "Copying items out of vault"

        for ((idx, entry) in entries.withIndex()) {
            val node = entry.srcNode
            val fileBaseDone = bytesDone
            var createdFileUri: Uri? = null
            try {
                val mime = FileClassification.mimeType(node)
                createdFileUri = DocumentsContract.createDocument(
                    cr,
                    entry.parentTreeDocUri,
                    mime,
                    node.name,
                ) ?: throw IOException("Could not create '${node.name}' in destination")

                volume.openFile(node.path).use { handle ->
                    cr.openOutputStream(createdFileUri, "wt")?.use { out ->
                        val buf = ByteArray(128 * 1024)
                        try {
                            var pos = 0L
                            while (pos < handle.size) {
                                val take = minOf(buf.size.toLong(), handle.size - pos).toInt()
                                val n = handle.read(pos, buf, 0, take)
                                if (n <= 0) throw IOException("Unexpected EOF reading ${node.name}")
                                out.write(buf, 0, n)
                                pos += n
                                onProgress(
                                    FileOpProgress(
                                        title = opTitle,
                                        currentFile = node.name,
                                        itemIndex = idx + 1,
                                        itemCount = entries.size,
                                        bytesDone = fileBaseDone + pos,
                                        totalBytes = totalBytes,
                                    )
                                )
                            }
                            out.flush()
                        } finally {
                            Secrets.wipe(buf)
                        }
                    } ?: throw IOException("Could not open destination stream for '${node.name}'")
                }

                bytesDone += node.size.coerceAtLeast(0L)
                succeeded++
            } catch (t: Throwable) {
                if (createdFileUri != null) {
                    deletePartialDocument(cr, createdFileUri)
                }
                failures.add("${node.name}: ${t.message ?: "Export failed"}")
            }
        }

        // Move out deletes each source item from the container ONLY after all items have been copied out cleanly.
        if (moveOut && failures.isEmpty()) {
            val touchedDirs = mutableSetOf<String>()
            for ((idx, srcPath) in sourcePaths.withIndex()) {
                try {
                    val parent = FsPaths.parent(srcPath)
                    touchedDirs.add(parent)
                    volume.delete(srcPath, overwrite = true) { overwritten ->
                        onProgress(
                            FileOpProgress(
                                title = "Overwriting & deleting vault copies",
                                currentFile = FsPaths.name(srcPath),
                                itemIndex = idx + 1,
                                itemCount = sourcePaths.size,
                                bytesDone = overwritten,
                                totalBytes = totalBytes,
                            )
                        )
                    }
                    ratingsStore.onPathDeleted(srcPath)
                } catch (t: Throwable) {
                    failures.add("$srcPath: copied out, but deleting from vault failed (${t.message})")
                }
            }
            for (dir in touchedDirs) {
                VaultDocumentsProvider.notifyChildDocumentsChanged(context, volume.id, dir)
            }
            VaultDocumentsProvider.notifyRootsChanged(context)
        }

        return FileBatchResult(succeeded = succeeded, failures = failures)
    }

    /**
     * Returns the base name range `[0, baseEnd)` to select when pre-filling a Rename field
     * (excludes the file extension for non-directories when a dot exists after index 0).
     */
    fun renameInitialSelectionEnd(name: String, isDirectory: Boolean): Int {
        if (isDirectory) return name.length
        val dot = name.lastIndexOf('.')
        return if (dot > 0) dot else name.length
    }

    private fun deletePartialDocument(cr: ContentResolver, uri: Uri) {
        try {
            if (!DocumentsContract.deleteDocument(cr, uri)) {
                cr.delete(uri, null, null)
            }
        } catch (_: Throwable) {
            try {
                cr.delete(uri, null, null)
            } catch (_: Throwable) {
            }
        }
    }
}

