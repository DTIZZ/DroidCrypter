package app.droidcrypter.browse

import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.FsPaths
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.engine.VolumeRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Hand-written tolerant JSON codec for `/.veradroid_meta.json`:
 * `{"version":1,"ratings":{"/Photos/a.jpg":3}}`.
 * - Unknown top-level keys are skipped;
 * - Out-of-range rating values (outside 1..5) or invalid paths are dropped;
 * - Incomplete / torn JSON documents are rejected (`null`);
 * - Max file size: 4 MiB.
 */
object RatingsCodec {
    const val META_FILE_NAME = ".veradroid_meta.json"
    const val META_NEW_FILE_NAME = ".veradroid_meta.json.new"
    const val META_PATH = "/$META_FILE_NAME"
    const val META_NEW_PATH = "/$META_NEW_FILE_NAME"
    const val MAX_META_BYTES = 4 * 1024 * 1024

    private val HIDDEN_NAMES = setOf(META_FILE_NAME.lowercase(), META_NEW_FILE_NAME.lowercase())

    fun isHiddenMetaFile(name: String): Boolean = name.lowercase() in HIDDEN_NAMES

    fun isInternalMetaName(name: String): Boolean = isHiddenMetaFile(name)

    /** Stored rating is 1..5; displayed rating clamps 4 or 5 to 3 (0 if unrated). */
    fun displayedStars(stored: Int?): Int = when {
        stored == null || stored !in 1..5 -> 0
        stored >= 3 -> 3
        else -> stored
    }

    /** Encodes a map of path → stored rating (1..5) into canonical JSON bytes. */
    fun encode(ratings: Map<String, Int>): ByteArray {
        val sb = StringBuilder(64 + ratings.size * 32)
        sb.append("{\"version\":1,\"ratings\":{")
        var first = true
        for ((path, r) in ratings.entries.sortedBy { it.key }) {
            if (r !in 1..5 || !path.startsWith("/")) continue
            if (!first) sb.append(',')
            first = false
            appendJsonString(sb, path)
            sb.append(':').append(r)
        }
        sb.append("}}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Parses a JSON document; returns null if incomplete, torn, or > 4 MiB. */
    fun decode(bytes: ByteArray): Map<String, Int>? {
        if (bytes.isEmpty() || bytes.size > MAX_META_BYTES) return null
        val s = try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        val parser = Parser(s)
        return try {
            val map = parser.parseRoot()
            if (!parser.isAtEnd()) null else map
        } catch (_: ParseException) {
            null
        }
    }

    private fun appendJsonString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class ParseException : Exception()

    private class Parser(private val s: String) {
        private var p = 0

        fun isAtEnd(): Boolean {
            skipWs()
            return p == s.length
        }

        fun parseRoot(): Map<String, Int> {
            expect('{')
            val out = LinkedHashMap<String, Int>()
            skipWs()
            if (peek() == '}') {
                p++
                return out
            }
            while (true) {
                val key = parseString()
                expect(':')
                if (key == "ratings") {
                    parseRatingsObject(out)
                } else {
                    skipValue()
                }
                skipWs()
                when (val ch = next()) {
                    ',' -> continue
                    '}' -> break
                    else -> throw ParseException()
                }
            }
            return out
        }

        private fun parseRatingsObject(out: MutableMap<String, Int>) {
            expect('{')
            skipWs()
            if (peek() == '}') {
                p++
                return
            }
            while (true) {
                val path = parseString()
                expect(':')
                skipWs()
                val num = parseNumberOrOther()
                if (num != null && num in 1..5 && path.startsWith("/") && path != "/") {
                    val valid = try {
                        FsPaths.segments(path)
                        true
                    } catch (_: Exception) {
                        false
                    }
                    if (valid) out[path] = num
                }
                skipWs()
                when (next()) {
                    ',' -> continue
                    '}' -> break
                    else -> throw ParseException()
                }
            }
        }

        private fun parseNumberOrOther(): Int? {
            skipWs()
            val c = peek() ?: throw ParseException()
            return if (c == '-' || c in '0'..'9') {
                val start = p
                if (c == '-') p++
                if (p >= s.length || s[p] !in '0'..'9') throw ParseException()
                while (p < s.length && s[p] in '0'..'9') p++
                var isFloat = false
                if (p < s.length && s[p] == '.') {
                    isFloat = true
                    p++
                    if (p >= s.length || s[p] !in '0'..'9') throw ParseException()
                    while (p < s.length && s[p] in '0'..'9') p++
                }
                if (p < s.length && (s[p] == 'e' || s[p] == 'E')) {
                    isFloat = true
                    p++
                    if (p < s.length && (s[p] == '+' || s[p] == '-')) p++
                    if (p >= s.length || s[p] !in '0'..'9') throw ParseException()
                    while (p < s.length && s[p] in '0'..'9') p++
                }
                if (isFloat) null else s.substring(start, p).toIntOrNull()
            } else {
                skipValue()
                null
            }
        }

        private fun skipValue() {
            skipWs()
            when (val c = peek() ?: throw ParseException()) {
                '"' -> { parseString() }
                '{' -> {
                    p++
                    skipWs()
                    if (peek() == '}') { p++; return }
                    while (true) {
                        parseString()
                        expect(':')
                        skipValue()
                        skipWs()
                        when (next()) {
                            ',' -> continue
                            '}' -> break
                            else -> throw ParseException()
                        }
                    }
                }
                '[' -> {
                    p++
                    skipWs()
                    if (peek() == ']') { p++; return }
                    while (true) {
                        skipValue()
                        skipWs()
                        when (next()) {
                            ',' -> continue
                            ']' -> break
                            else -> throw ParseException()
                        }
                    }
                }
                't' -> expectLiteral("true")
                'f' -> expectLiteral("false")
                'n' -> expectLiteral("null")
                '-', in '0'..'9' -> { parseNumberOrOther() }
                else -> throw ParseException()
            }
        }

        private fun expectLiteral(lit: String) {
            if (!s.startsWith(lit, p)) throw ParseException()
            p += lit.length
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (p < s.length) {
                val c = s[p++]
                if (c == '"') return sb.toString()
                if (c.code < 0x20) throw ParseException()
                if (c == '\\') {
                    if (p >= s.length) throw ParseException()
                    when (val esc = s[p++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (p + 4 > s.length) throw ParseException()
                            val hex = s.substring(p, p + 4).toIntOrNull(16) ?: throw ParseException()
                            p += 4
                            sb.append(hex.toChar())
                        }
                        else -> throw ParseException()
                    }
                } else {
                    sb.append(c)
                }
            }
            throw ParseException()
        }

        private fun skipWs() {
            while (p < s.length && (s[p] == ' ' || s[p] == '\t' || s[p] == '\n' || s[p] == '\r')) p++
        }

        private fun peek(): Char? = if (p < s.length) s[p] else null

        private fun next(): Char {
            if (p >= s.length) throw ParseException()
            return s[p++]
        }

        private fun expect(expected: Char) {
            skipWs()
            if (next() != expected) throw ParseException()
        }
    }
}

/**
 * In-container star ratings store for one [MountedVolume] (section 7.3):
 * - Optimistic in-memory updates, replaying any mutations made before async load completes;
 * - Debounced (~400 ms) crash-safe save: write `.veradroid_meta.json.new`, delete old with overwrite,
 *   rename `.new` → `.veradroid_meta.json`;
 * - On load, prefers whichever of `.veradroid_meta.json` or `.veradroid_meta.json.new` parses completely;
 * - Synchronous flush in a [VolumeRegistry] before-close hook so locking never loses pending changes;
 * - Read-only volumes load and display ratings, but refuse modifications.
 */
class RatingsStore(
    val volume: MountedVolume,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMillis: Long = 400L,
    scope: CoroutineScope? = null,
) {
    private val storeScope = scope ?: CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Any()
    private val storedMap = LinkedHashMap<String, Int>()
    private val pendingReplays = ArrayList<(MutableMap<String, Int>) -> Unit>()
    private var loaded = false
    private var dirty = false
    private var debounceJob: Job? = null

    private val _ratingsFlow = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Map of path → stored rating (1..5). Use [displayedRating] for 0..3 UI stars. */
    val ratingsFlow: StateFlow<Map<String, Int>> = _ratingsFlow.asStateFlow()

    private val beforeCloseHook: (MountedVolume) -> Unit = { closingVol ->
        if (closingVol.id == volume.id) {
            flushSync()
        }
    }

    init {
        VolumeRegistry.addBeforeCloseHook(beforeCloseHook)
    }

    /** Loads stored ratings from the volume synchronously (safe to call from an IO dispatcher). */
    fun ensureLoadedSync() {
        synchronized(lock) {
            if (loaded) return
        }
        val diskMap = readFromDisk()
        synchronized(lock) {
            if (!loaded) {
                val merged = LinkedHashMap(diskMap)
                for (op in pendingReplays) op(merged)
                pendingReplays.clear()
                storedMap.clear()
                storedMap.putAll(merged)
                loaded = true
                _ratingsFlow.value = storedMap.toMap()
            }
        }
    }

    /** Returns the displayed 0..3 star rating for [path] (stored 4/5 displays as 3). */
    fun displayedRating(path: String): Int =
        RatingsCodec.displayedStars(_ratingsFlow.value[path])

    /** Returns the raw stored 1..5 rating for [path], or 0 if unrated. */
    fun storedRating(path: String): Int = _ratingsFlow.value[path] ?: 0

    /**
     * Taps star [star] (1..3) on [path]: if the displayed rating already equals [star], clears it;
     * otherwise sets the rating to [star]. Throws [IOException] if the volume is read-only.
     */
    fun toggleStar(path: String, star: Int) {
        require(star in 1..3) { "Star must be 1..3" }
        volume.writeSupport?.let { throw IOException(it) }
        val norm = FsPaths.normalize(path)
        mutate { map ->
            val currentDisp = RatingsCodec.displayedStars(map[norm])
            if (currentDisp == star) {
                map.remove(norm)
            } else {
                map[norm] = star
            }
        }
    }

    /** Updates ratings when a file or folder (and everything beneath it) is renamed or moved. */
    fun onPathMovedOrRenamed(oldPath: String, newPath: String) {
        if (volume.writeSupport != null) return
        val oldNorm = FsPaths.normalize(oldPath)
        val newNorm = FsPaths.normalize(newPath)
        if (oldNorm == newNorm) return
        val oldPrefix = "$oldNorm/"
        mutate { map ->
            val updates = ArrayList<Pair<String, Int>>()
            val toRemove = ArrayList<String>()
            for ((k, v) in map) {
                if (k == oldNorm) {
                    toRemove += k
                    updates += newNorm to v
                } else if (k.startsWith(oldPrefix)) {
                    toRemove += k
                    updates += (newNorm + k.substring(oldNorm.length)) to v
                }
            }
            for (k in toRemove) map.remove(k)
            for ((k, v) in updates) map[k] = v
        }
    }

    /** Drops ratings for a deleted file or folder (including any descendants). */
    fun onPathDeleted(deletedPath: String) {
        if (volume.writeSupport != null) return
        val norm = FsPaths.normalize(deletedPath)
        val prefix = "$norm/"
        mutate { map ->
            map.keys.removeAll { it == norm || it.startsWith(prefix) }
        }
    }

    /**
     * When [dirPath] is listed with [presentChildPaths], prunes ratings of direct children (or
     * subtrees of missing child folders) under [dirPath] that no longer exist on disk.
     */
    fun pruneMissingChildren(dirPath: String, presentNodes: List<FsNode>) {
        if (volume.writeSupport != null) return
        val normDir = FsPaths.normalize(dirPath)
        val dirPrefix = if (normDir == "/") "/" else "$normDir/"
        val presentMap = presentNodes.associateBy { it.path }
        mutateIfChanged { map ->
            var changed = false
            val it = map.keys.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (!p.startsWith(dirPrefix) || p == normDir) continue
                val rel = p.substring(dirPrefix.length)
                val firstSeg = rel.substringBefore('/')
                val directChildPath = if (normDir == "/") "/$firstSeg" else "$normDir/$firstSeg"
                val node = presentMap[directChildPath]
                if (node == null || (!node.isDirectory && p != directChildPath)) {
                    it.remove()
                    changed = true
                }
            }
            changed
        }
    }

    private fun mutate(op: (MutableMap<String, Int>) -> Unit) {
        mutateIfChanged { map ->
            val before = HashMap(map)
            op(map)
            before != map
        }
    }

    private fun mutateIfChanged(op: (MutableMap<String, Int>) -> Boolean) {
        var shouldSchedule = false
        synchronized(lock) {
            if (!loaded) {
                pendingReplays.add { m -> op(m) }
                val changed = op(storedMap)
                if (changed) {
                    dirty = true
                    _ratingsFlow.value = storedMap.toMap()
                    shouldSchedule = true
                }
            } else {
                val changed = op(storedMap)
                if (changed) {
                    dirty = true
                    _ratingsFlow.value = storedMap.toMap()
                    shouldSchedule = true
                }
            }
        }
        if (shouldSchedule) scheduleSave()
    }

    private fun scheduleSave() {
        synchronized(lock) {
            debounceJob?.cancel()
            debounceJob = storeScope.launch {
                if (debounceMillis > 0) delay(debounceMillis)
                flushSync()
            }
        }
    }

    /** Flushes pending changes to the volume immediately and synchronously. */
    fun flushSync() {
        ensureLoadedSync()
        val snapshot: Map<String, Int>
        synchronized(lock) {
            debounceJob?.cancel()
            debounceJob = null
            if (!dirty) return
            snapshot = LinkedHashMap(storedMap)
            dirty = false
        }
        try {
            writeToDiskCrashSafe(snapshot)
        } catch (_: Throwable) {
            // If save fails, mark dirty again so it is retried on the next change.
            synchronized(lock) {
                dirty = true
            }
        }
    }

    fun close() {
        VolumeRegistry.removeBeforeCloseHook(beforeCloseHook)
        flushSync()
    }

    private fun readFromDisk(): Map<String, Int> {
        // Prefer whichever of .veradroid_meta.json or .veradroid_meta.json.new parses completely.
        val primary = readCandidate(RatingsCodec.META_PATH)
        if (primary != null) return primary
        val backup = readCandidate(RatingsCodec.META_NEW_PATH)
        if (backup != null) return backup
        return emptyMap()
    }

    private fun readCandidate(path: String): Map<String, Int>? = try {
        val node = volume.stat(path) ?: return null
        if (node.isDirectory || node.size <= 0 || node.size > RatingsCodec.MAX_META_BYTES) return null
        volume.openFile(path).use { h ->
            val buf = ByteArray(node.size.toInt())
            var off = 0
            while (off < buf.size) {
                val n = h.read(off.toLong(), buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            if (off != buf.size) null else RatingsCodec.decode(buf)
        }
    } catch (_: Throwable) {
        null
    }

    private fun writeToDiskCrashSafe(ratings: Map<String, Int>) {
        if (volume.writeSupport != null) return
        val bytes = RatingsCodec.encode(ratings)
        // 1. Delete any stale .veradroid_meta.json.new first.
        try {
            if (volume.stat(RatingsCodec.META_NEW_PATH) != null) {
                volume.delete(RatingsCodec.META_NEW_PATH, overwrite = true)
            }
        } catch (_: Throwable) {}

        // 2. Write .veradroid_meta.json.new.
        volume.createFile("/", RatingsCodec.META_NEW_FILE_NAME, bytes.size.toLong(), { pos, buf, off, len ->
            System.arraycopy(bytes, pos.toInt(), buf, off, len)
        })

        // 3. Delete old .veradroid_meta.json with overwrite.
        try {
            if (volume.stat(RatingsCodec.META_PATH) != null) {
                volume.delete(RatingsCodec.META_PATH, overwrite = true)
            }
        } catch (_: Throwable) {}

        // 4. Rename .veradroid_meta.json.new -> .veradroid_meta.json.
        volume.rename(RatingsCodec.META_NEW_PATH, RatingsCodec.META_FILE_NAME)
    }
}

