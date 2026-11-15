package app.droidcrypter.browse

import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.MountedVolume
import java.util.Locale

/**
 * Recursive, RAM-only file-name search over an unlocked [MountedVolume].
 *
 * - A query is split on whitespace into terms; a name matches when it contains **every** term,
 *   case-insensitively (so `"beach 2024"` finds `2024-07 Beach trip.jpg`).
 * - Both files and folders are matched; `.veradroid_meta.json` (and its `.new`) never are.
 * - Nothing is indexed or cached: every search walks the directory tree on demand, so no file
 *   name ever leaves the container.
 * - Bounded against hostile or corrupted filesystems: at most [MAX_RESULTS] hits,
 *   [MAX_DIRECTORIES] folders visited and [MAX_DEPTH] levels deep (a directory cycle cannot spin
 *   forever). Hitting any bound sets [Result.truncated]. Unreadable folders are skipped.
 */
object VaultSearch {
    const val MAX_RESULTS = 500
    const val MAX_DIRECTORIES = 20_000
    const val MAX_DEPTH = 64

    data class Result(val nodes: List<FsNode>, val truncated: Boolean)

    /** Lower-cased, whitespace-separated search terms; empty when the query is blank. */
    fun terms(query: String): List<String> =
        query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun matches(name: String, terms: List<String>): Boolean {
        if (terms.isEmpty()) return false
        val lower = name.lowercase(Locale.ROOT)
        return terms.all { lower.contains(it) }
    }

    /**
     * Walks [startDir] breadth-first (shallow hits first) and returns every matching node.
     * [checkCancelled] is called before each folder is listed and should throw to abort.
     * A failure to list [startDir] itself propagates; failures below it are skipped.
     */
    fun search(
        volume: MountedVolume,
        query: String,
        startDir: String = "/",
        maxResults: Int = MAX_RESULTS,
        checkCancelled: () -> Unit = {},
    ): Result {
        val terms = terms(query)
        if (terms.isEmpty()) return Result(emptyList(), false)

        val hits = ArrayList<FsNode>()
        var truncated = false
        var visited = 0
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.addLast(startDir to 0)

        while (queue.isNotEmpty()) {
            checkCancelled()
            val (dir, depth) = queue.removeFirst()
            if (visited >= MAX_DIRECTORIES) {
                truncated = true
                break
            }
            visited++
            val children = try {
                volume.list(dir)
            } catch (e: Exception) {
                if (depth == 0) throw e
                continue
            }
            for (child in children) {
                if (RatingsCodec.isHiddenMetaFile(child.name)) continue
                if (matches(child.name, terms)) {
                    if (hits.size >= maxResults) {
                        return Result(hits, true)
                    }
                    hits += child
                }
                if (child.isDirectory) {
                    if (depth + 1 >= MAX_DEPTH) truncated = true
                    else queue.addLast(child.path to depth + 1)
                }
            }
        }
        return Result(hits, truncated)
    }
}
