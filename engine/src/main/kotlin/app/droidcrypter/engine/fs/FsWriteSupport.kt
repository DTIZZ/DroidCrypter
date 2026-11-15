package app.droidcrypter.engine.fs

import java.io.IOException
import java.util.Locale

internal object FsWriteSupport {
    private val FORBIDDEN = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    /** Validates a file or folder name per FAT / exFAT rules. Throws [IOException] if invalid. */
    fun checkName(name: String) {
        if (name.isBlank()) throw IOException("Name cannot be blank")
        if (name.length > 255) throw IOException("Name is too long (maximum 255 characters)")
        if (name.endsWith('.') || name.endsWith(' ')) {
            throw IOException("Name cannot end with a dot or a space")
        }
        for (c in name) {
            if (c.code < 0x20 || c in FORBIDDEN) {
                throw IOException("Name contains an invalid character: '$c'")
            }
        }
    }

    /**
     * Picks a unique name in a folder whose lowercase existing names are [existingLower]:
     * if [desired] is taken, tries `"base (2).ext"`, `"base (3).ext"` … up to 9999.
     */
    fun uniqueName(desired: String, existingLower: Set<String>): String {
        checkName(desired)
        if (desired.lowercase(Locale.ROOT) !in existingLower) return desired
        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        for (n in 2..9999) {
            val suffix = " ($n)$ext"
            val maxBase = (255 - suffix.length).coerceAtLeast(1)
            val trimmedBase = if (base.length > maxBase) base.substring(0, maxBase).trimEnd('.', ' ') else base
            val candidate = "$trimmedBase$suffix"
            if (candidate.lowercase(Locale.ROOT) !in existingLower) return candidate
        }
        throw IOException("Too many files with the same name in this folder")
    }

    /** The name a new folder gets: [desired] itself, or (with [autoRename]) the next free "name (2)". */
    fun newFolderName(desired: String, existingLower: Set<String>, autoRename: Boolean): String {
        checkName(desired)
        if (desired.lowercase(Locale.ROOT) !in existingLower) return desired
        if (!autoRename) throw IOException("\"$desired\" already exists in this folder")
        for (n in 2..9999) {
            val suffix = " ($n)"
            val candidate = desired.take(255 - suffix.length).trimEnd('.', ' ') + suffix
            if (candidate.lowercase(Locale.ROOT) !in existingLower) return candidate
        }
        throw IOException("Too many folders with the same name in this folder")
    }

    /** Checks that moving [srcPath] into [targetDirPath] is not moving root or a folder into itself/subtree. */
    fun checkMoveTarget(srcPath: String, srcName: String, isDir: Boolean, targetDirPath: String) {
        if (srcPath == "/") throw IOException("Cannot move the root folder")
        if (isDir) {
            val s = srcPath.lowercase(Locale.ROOT)
            val t = targetDirPath.lowercase(Locale.ROOT)
            if (t == s || t.startsWith("$s/")) {
                throw IOException("Can't move \"$srcName\" into itself")
            }
        }
    }
}

