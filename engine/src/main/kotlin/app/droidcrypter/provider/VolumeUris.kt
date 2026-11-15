package app.droidcrypter.provider

import app.droidcrypter.engine.FsPaths
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pure-JVM parser and formatter for:
 * - DocumentsProvider Document IDs (`"<volumeId>:<absolute path>"`)
 * - Media3 DataSource URIs (`"droidcrypter://<volumeId>/<encoded path>"`)
 *
 * Strictly rejects relative paths, `.` or `..` segments, and blank volume IDs.
 */
object VolumeUris {
    const val SCHEME = "droidcrypter"

    data class VolumePath(val volumeId: String, val path: String)

    fun formatDocumentId(volumeId: String, path: String): String {
        require(volumeId.isNotBlank() && !volumeId.contains(':')) { "Invalid volumeId" }
        val norm = FsPaths.normalize(path)
        return "$volumeId:$norm"
    }

    fun parseDocumentId(documentId: String): VolumePath {
        val colon = documentId.indexOf(':')
        if (colon <= 0 || colon == documentId.length - 1) {
            throw IOException("Invalid document ID: $documentId")
        }
        val volumeId = documentId.substring(0, colon)
        if (!volumeId.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it in 'g'..'z' || it in 'G'..'Z' || it == '_' || it == '-' }) {
            throw IOException("Invalid volume ID in document ID: $documentId")
        }
        val rawPath = documentId.substring(colon + 1)
        if (!rawPath.startsWith("/") || rawPath.contains("//")) {
            throw IOException("Invalid document path: $rawPath")
        }
        val norm = try {
            FsPaths.normalize(rawPath)
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid document path: $rawPath", e)
        }
        return VolumePath(volumeId, norm)
    }

    fun formatMediaUri(volumeId: String, path: String): String {
        val segs = FsPaths.segments(path)
        val encodedPath = segs.joinToString("/") { seg ->
            URLEncoder.encode(seg, Charsets.UTF_8.name()).replace("+", "%20")
        }
        return "$SCHEME://$volumeId/$encodedPath"
    }

    fun parseMediaUri(uriString: String): VolumePath {
        val uri = try {
            URI(uriString)
        } catch (e: Exception) {
            throw IOException("Malformed media URI: $uriString", e)
        }
        if (uri.scheme?.lowercase() != SCHEME) {
            throw IOException("Unsupported URI scheme: ${uri.scheme}")
        }
        val volumeId = uri.host ?: uri.authority ?: throw IOException("Missing volume ID in URI: $uriString")
        if (volumeId.isBlank()) throw IOException("Blank volume ID in URI: $uriString")
        val rawPath = uri.rawPath ?: throw IOException("Missing path in URI: $uriString")
        if (!rawPath.startsWith("/")) throw IOException("URI path must be absolute: $rawPath")

        val decodedSegments = rawPath.split('/').filter { it.isNotEmpty() }.map { seg ->
            val dec = URLDecoder.decode(seg.replace("+", "%2B"), Charsets.UTF_8.name())
            if (dec == "." || dec == ".." || dec.contains('/') || dec.contains('\\')) {
                throw IOException("Invalid segment in media URI: $dec")
            }
            dec
        }
        val norm = "/" + decodedSegments.joinToString("/")
        return VolumePath(volumeId, norm)
    }
}

