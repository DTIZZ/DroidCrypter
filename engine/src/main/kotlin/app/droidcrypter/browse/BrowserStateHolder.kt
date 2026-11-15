package app.droidcrypter.browse

import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.FsPaths
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.engine.VolumeSpace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ViewerKind {
    IMAGE, MEDIA, EXTERNAL
}

/**Immutable row model for the browser list so only rows whose data changed recompose. */
data class BrowserRow(
    val node: FsNode,
    val displayedRating: Int,
    val isSelected: Boolean,
    val viewerKind: ViewerKind,
) {
    val displayedStars: Int get() = displayedRating
}

data class BrowserUiState(
    val currentDir: String = "/",
    val rows: List<BrowserRow> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val sortConfig: SortConfig = SortConfig.DEFAULT,
    val showStars: Boolean = true,
    val selectionMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val space: VolumeSpace? = null,
    val writeSupport: String? = null,
    /** The search field is open. While it is, Back / the up arrow close it instead of navigating. */
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    /** A search for [searchQuery] is pending or running; [rows] may still show the previous results. */
    val isSearching: Boolean = false,
    /** The last search stopped at a safety bound, so [rows] is not every match. */
    val searchTruncated: Boolean = false,
) {
    /** True when [rows] are whole-container search hits rather than the listing of [currentDir]. */
    val showingSearchResults: Boolean get() = searchActive && searchQuery.isNotBlank()
    val totalBytes: Long get() = space?.totalBytes ?: 0L
    val freeBytes: Long get() = space?.freeBytes ?: 0L
    val isWritable: Boolean get() = writeSupport == null
    val selectedCount: Int get() = selectedPaths.size
    val selectedNodes: List<FsNode> get() = rows.filter { it.isSelected }.map { it.node }
    val canRenameSelected: Boolean get() = isWritable && selectedPaths.size == 1
    val canMutateSelected: Boolean get() = isWritable && selectedPaths.isNotEmpty()
    val canCopyOutSelected: Boolean get() = selectedPaths.isNotEmpty()
}

object FileClassification {
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    private val VIDEO_EXTS = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "avi", "ts", "mts", "m2ts")
    private val AUDIO_EXTS = setOf("mp3", "flac", "opus", "ogg", "m4a", "aac", "wav", "amr")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isEditableVideo(name: String): Boolean = extension(name) in VIDEO_EXTS

    fun viewerKind(node: FsNode): ViewerKind {
        if (node.isDirectory) return ViewerKind.EXTERNAL
        return when (extension(node.name)) {
            in IMAGE_EXTS -> ViewerKind.IMAGE
            in VIDEO_EXTS, in AUDIO_EXTS -> ViewerKind.MEDIA
            else -> ViewerKind.EXTERNAL
        }
    }

    fun mimeType(node: FsNode): String {
        if (node.isDirectory) return "vnd.android.document/directory"
        return when (extension(node.name)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "avif" -> "image/avif"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "ts", "mts", "m2ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "opus" -> "audio/opus"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "txt", "log", "md" -> "text/plain"
            "json" -> "application/json"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}

/**
 * UI-free, host-testable state holder for browsing an unlocked [MountedVolume]:
 * - Lists on [ioDispatcher], sorts on [sortDispatcher], joins ratings from [ratingsStore];
 * - Refreshes [VolumeSpace] every time the folder is (re)listed;
 * - Hides `.veradroid_meta.json` and `.veradroid_meta.json.new` everywhere;
 * - Manages multi-select (long-press starts selection, taps add/remove, Select all / Select none,
 *   Back exits selection first, picks that disappear after refresh are dropped);
 * - Exposes photo-pager and media-playlist items in the exact effective browser order;
 * - Searches the whole container by file name ([VaultSearch]): while a non-blank query is set the
 *   rows are the hits (same sort, selection, ratings and playlists as a folder listing).
 */
class BrowserStateHolder(
    val volume: MountedVolume,
    val ratingsStore: RatingsStore,
    initialSort: SortConfig = SortConfig.DEFAULT,
    initialShowStars: Boolean = true,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sortDispatcher: CoroutineDispatcher = Dispatchers.Default,
    scope: CoroutineScope? = null,
    /** Re-opens the search field with this query (used when returning from a viewer). */
    initialSearchQuery: String? = null,
    private val searchDebounceMillis: Long = 250L,
) {
    private val holderScope = scope ?: CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Unsorted listing of the current folder. */
    private var folderNodes: List<FsNode> = emptyList()

    /** Unsorted nodes behind the visible rows: [folderNodes], or the search hits. */
    private var rawNodes: List<FsNode> = emptyList()

    private var searchJob: Job? = null

    private val _state = MutableStateFlow(
        BrowserUiState(
            sortConfig = initialSort,
            showStars = initialShowStars,
            writeSupport = volume.writeSupport,
            searchActive = initialSearchQuery != null,
            searchQuery = initialSearchQuery.orEmpty(),
            isSearching = !initialSearchQuery.isNullOrBlank(),
        )
    )
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    init {
        holderScope.launch {
            ratingsStore.ratingsFlow.collect {
                // Re-project rows when ratings change; only re-sort if effective sort is STARS.
                recomputeRows()
            }
        }
    }

    /** Loads or refreshes [dir] asynchronously on [ioDispatcher]. */
    fun loadDirectory(dir: String = _state.value.currentDir) {
        holderScope.launch {
            refreshSync(dir)
        }
    }

    /** Synchronous refresh of [dir] (used by tests and after file operations). */
    suspend fun refreshSync(dir: String = _state.value.currentDir) {
        val normDir = FsPaths.normalize(dir)
        _state.value = _state.value.copy(currentDir = normDir, isLoading = true, error = null)
        try {
            val query = _state.value.let { if (it.showingSearchResults) it.searchQuery else null }
            val (listing, spaceSnap, writeSup) = withContext(ioDispatcher) {
                ratingsStore.ensureLoadedSync()
                val all = volume.list(normDir)
                val visible = all.filterNot { RatingsCodec.isHiddenMetaFile(it.name) }
                ratingsStore.pruneMissingChildren(normDir, visible)
                val sp = VolumeSpace.of(volume)
                val ctx = coroutineContext
                val found = query?.let { q -> VaultSearch.search(volume, q) { ctx.ensureActive() } }
                Triple(visible to found, sp, volume.writeSupport)
            }
            val (folderListing, found) = listing
            folderNodes = folderListing
            // If the query changed while we were listing, its own search job publishes the rows.
            val after = _state.value
            val searchIsCurrent = found != null && after.searchQuery == query
            val stale = after.showingSearchResults && !searchIsCurrent
            val visibleNodes = when {
                stale -> rawNodes
                after.showingSearchResults && found != null -> found.nodes
                else -> folderListing
            }
            rawNodes = visibleNodes
            // Drop any selected paths that disappeared after the refresh.
            val existingPaths = visibleNodes.map { it.path }.toSet()
            val keptSelection = _state.value.selectedPaths.intersect(existingPaths)
            val stillSelecting = _state.value.selectionMode && keptSelection.isNotEmpty()
            val sorted = sortForCurrentSettings(visibleNodes)
            val rows = buildRows(sorted, keptSelection)
            _state.value = _state.value.copy(
                currentDir = normDir,
                rows = rows,
                isLoading = false,
                error = null,
                selectionMode = stillSelecting,
                selectedPaths = keptSelection,
                space = spaceSnap,
                writeSupport = writeSup,
                isSearching = if (stale) _state.value.isSearching else false,
                searchTruncated = if (stale) _state.value.searchTruncated else found?.truncated ?: false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            folderNodes = emptyList()
            rawNodes = emptyList()
            _state.value = _state.value.copy(
                currentDir = normDir,
                rows = emptyList(),
                isLoading = false,
                isSearching = false,
                selectionMode = false,
                selectedPaths = emptySet(),
                error = e.message ?: "Could not list folder",
                writeSupport = volume.writeSupport,
            )
        }
    }

    private suspend fun recomputeRows() {
        val cur = _state.value
        if (cur.sortConfig.effective(cur.showStars).criterion == SortCriterion.STARS) {
            resortRows()
        } else {
            // Same order, fresh star counts; applied to the latest state, never a stale copy.
            _state.update { it.copy(rows = buildRows(it.rows.map { r -> r.node }, it.selectedPaths)) }
        }
    }

    private fun buildRows(nodes: List<FsNode>, selected: Set<String>): List<BrowserRow> =
        nodes.map { n ->
            BrowserRow(
                node = n,
                displayedRating = if (n.isDirectory) 0 else ratingsStore.displayedRating(n.path),
                isSelected = n.path in selected,
                viewerKind = FileClassification.viewerKind(n),
            )
        }

    fun setSortCriterion(criterion: SortCriterion) {
        val next = _state.value.sortConfig.select(criterion)
        setSortConfig(next)
    }

    fun setSortConfig(config: SortConfig) {
        _state.update { it.copy(sortConfig = config) }
        holderScope.launch { resortRows() }
    }

    fun setShowStars(show: Boolean) {
        _state.update { it.copy(showStars = show) }
        holderScope.launch { resortRows() }
    }

    /**
     * Sorts [nodes] with the sort settings in force when the sort *finishes*: if the user (or the
     * startup preference sync) changes them while a sort is running, it is simply run again.
     */
    private suspend fun sortForCurrentSettings(nodes: List<FsNode>): List<FsNode> {
        while (true) {
            val before = _state.value
            val sorted = withContext(sortDispatcher) {
                BrowserSort.sort(nodes, before.sortConfig, before.showStars, ratingsStore::displayedRating)
            }
            val after = _state.value
            if (after.sortConfig == before.sortConfig && after.showStars == before.showStars) return sorted
        }
    }

    /**
     * Re-sorts whatever is on screen after a sort-setting change. It only ever replaces `rows`, on
     * top of the latest state, so it can never undo a folder load or search that finished meanwhile
     * (the old code copied a stale snapshot back and could leave the list empty and "loading").
     */
    private suspend fun resortRows() {
        while (true) {
            val nodes = rawNodes
            val sorted = sortForCurrentSettings(nodes)
            if (rawNodes !== nodes) continue
            _state.update { it.copy(rows = buildRows(sorted, it.selectedPaths)) }
            return
        }
    }

    /** Toggles star [star] (1..3) on [path]. Ignored while in multi-select mode or on folders. */
    fun toggleStar(path: String, star: Int) {
        val cur = _state.value
        if (cur.selectionMode) return
        val row = cur.rows.firstOrNull { it.node.path == path } ?: return
        if (row.node.isDirectory) return
        ratingsStore.toggleStar(path, star)
    }

    // ---- multi-select ----

    fun onLongPress(path: String) {
        val cur = _state.value
        if (cur.rows.none { it.node.path == path }) return
        val nextSel = cur.selectedPaths + path
        _state.value = cur.copy(
            selectionMode = true,
            selectedPaths = nextSel,
            rows = cur.rows.map { it.copy(isSelected = it.node.path in nextSel) },
        )
    }

    /** In selection mode, tap adds/removes the item and never opens it. Returns true if consumed by selection. */
    fun onTapInSelectionMode(path: String): Boolean {
        val cur = _state.value
        if (!cur.selectionMode) return false
        val nextSel = if (path in cur.selectedPaths) cur.selectedPaths - path else cur.selectedPaths + path
        val stillSelecting = nextSel.isNotEmpty()
        _state.value = cur.copy(
            selectionMode = stillSelecting,
            selectedPaths = nextSel,
            rows = cur.rows.map { it.copy(isSelected = it.node.path in nextSel) },
        )
        return true
    }

    fun selectAll() {
        val cur = _state.value
        val allPaths = cur.rows.map { it.node.path }.toSet()
        if (allPaths.isEmpty()) return
        _state.value = cur.copy(
            selectionMode = true,
            selectedPaths = allPaths,
            rows = cur.rows.map { it.copy(isSelected = true) },
        )
    }

    fun clearSelection() {
        val cur = _state.value
        if (!cur.selectionMode && cur.selectedPaths.isEmpty()) return
        _state.value = cur.copy(
            selectionMode = false,
            selectedPaths = emptySet(),
            rows = cur.rows.map { it.copy(isSelected = false) },
        )
    }

    fun selectNone() = clearSelection()

    // ---- search ----

    /** Opens the (empty) search field; the folder listing stays visible until a query is typed. */
    fun openSearch() {
        val cur = _state.value
        if (cur.searchActive) return
        _state.value = cur.copy(searchActive = true, searchQuery = "", isSearching = false, searchTruncated = false)
    }

    /**
     * Sets the query and (after a short debounce) replaces the rows with every file or folder in
     * the container whose name contains all of its words. A blank query shows the folder again.
     */
    fun setSearchQuery(query: String) {
        val cur = _state.value
        if (cur.searchActive && cur.searchQuery == query) return
        val hasTerms = VaultSearch.terms(query).isNotEmpty()
        searchJob?.cancel()
        _state.value = cur.copy(
            searchActive = true,
            searchQuery = query,
            isSearching = hasTerms,
            searchTruncated = false,
        )
        searchJob = holderScope.launch {
            if (hasTerms) delay(searchDebounceMillis)
            publishSearch(query)
        }
    }

    /** Closes search and returns to the listing of the current folder. */
    fun closeSearch() {
        if (!_state.value.searchActive) return
        leaveSearchState()
        searchJob = holderScope.launch { publishSearch("") }
    }

    /** Opens a folder picked from the search results: leaves search and lists [dir]. */
    fun openFolderFromSearch(dir: String) {
        leaveSearchState()
        loadDirectory(dir)
    }

    private fun leaveSearchState() {
        searchJob?.cancel()
        val cur = _state.value
        _state.value = cur.copy(
            searchActive = false,
            searchQuery = "",
            isSearching = false,
            searchTruncated = false,
            selectionMode = false,
            selectedPaths = emptySet(),
            rows = cur.rows.map { if (it.isSelected) it.copy(isSelected = false) else it },
        )
    }

    /** Runs the search for [query] (or restores the folder listing when blank) and publishes rows. */
    private suspend fun publishSearch(query: String) {
        val hasTerms = VaultSearch.terms(query).isNotEmpty()
        val result = try {
            if (hasTerms) {
                withContext(ioDispatcher) {
                    val ctx = coroutineContext
                    VaultSearch.search(volume, query) { ctx.ensureActive() }
                }
            } else {
                VaultSearch.Result(folderNodes, false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val cur = _state.value
            if (cur.searchQuery == query) {
                _state.value = cur.copy(isSearching = false, error = e.message ?: "Search failed")
            }
            return
        }
        val sorted = sortForCurrentSettings(result.nodes)
        val cur = _state.value
        // A newer query (or closing search) superseded this one while it ran.
        if (hasTerms && !(cur.searchActive && cur.searchQuery == query)) return
        if (!hasTerms && cur.showingSearchResults) return
        rawNodes = result.nodes
        val present = result.nodes.mapTo(HashSet()) { it.path }
        val keptSelection = cur.selectedPaths.intersect(present)
        _state.value = cur.copy(
            rows = buildRows(sorted, keptSelection),
            isSearching = false,
            searchTruncated = result.truncated,
            selectionMode = cur.selectionMode && keptSelection.isNotEmpty(),
            selectedPaths = keptSelection,
        )
    }

    fun navigateUp() {
        loadDirectory(FsPaths.parent(_state.value.currentDir))
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Handles Back navigation:
     * 1. If in selection mode, exits selection mode and returns `true` (stays in current folder).
     * 2. If search is open, closes it and returns `true` (back to the current folder's listing).
     * 3. If in a subfolder, navigates up to parent folder and returns `true`.
     * 4. If at root `/`, returns `false` (caller returns to the vault list).
     */
    fun onBackPressed(): Boolean {
        val cur = _state.value
        if (cur.selectionMode) {
            clearSelection()
            return true
        }
        if (cur.searchActive) {
            closeSearch()
            return true
        }
        if (cur.currentDir != "/") {
            loadDirectory(FsPaths.parent(cur.currentDir))
            return true
        }
        return false
    }

    /** Photo pager list: every image in the visible rows (folder or search hits) in browser order. */
    fun imagePlaylist(): List<FsNode> =
        _state.value.rows.filter { it.viewerKind == ViewerKind.IMAGE }.map { it.node }

    /** Media playlist: every video and audio file in the visible rows (folder or search hits) in browser order. */
    fun mediaPlaylist(): List<FsNode> =
        _state.value.rows.filter { it.viewerKind == ViewerKind.MEDIA }.map { it.node }

    /**
     * Lists valid destination subfolders inside [folderPath] for the Move dialog, excluding any
     * folders currently selected for moving (so a folder cannot be moved into itself).
     */
    fun listMoveDestinations(folderPath: String): List<FsNode> {
        val selected = _state.value.selectedPaths
        return volume.list(folderPath)
            .filter { it.isDirectory && !RatingsCodec.isHiddenMetaFile(it.name) && it.path !in selected }
            .let { BrowserSort.sort(it, SortConfig.DEFAULT) }
    }

    // ---- mutations with rating tracking & refresh ----

    /** Creates the empty folder [name] in the current folder and returns its path. */
    suspend fun createFolder(name: String): String {
        val dir = _state.value.currentDir
        val created = withContext(ioDispatcher) {
            if (RatingsCodec.isHiddenMetaFile(name)) throw java.io.IOException("\"$name\" is a name DroidCrypter reserves")
            volume.createDirectory(dir, name).path
        }
        refreshSync()
        return created
    }

    suspend fun renameItem(path: String, newName: String): String {
        val newPath = withContext(ioDispatcher) {
            val res = volume.rename(path, newName)
            ratingsStore.onPathMovedOrRenamed(path, res)
            res
        }
        clearSelection()
        refreshSync()
        return newPath
    }

    data class BulkResult(val succeeded: Int, val failures: List<String>)

    suspend fun moveItems(paths: Collection<String>, targetDir: String): BulkResult {
        var ok = 0
        val fails = ArrayList<String>()
        withContext(ioDispatcher) {
            for (p in paths) {
                try {
                    val newPath = volume.move(p, targetDir)
                    ratingsStore.onPathMovedOrRenamed(p, newPath)
                    ok++
                } catch (e: Throwable) {
                    fails += "${FsPaths.name(p)}: ${e.message ?: "Move failed"}"
                }
            }
        }
        clearSelection()
        refreshSync()
        return BulkResult(ok, fails)
    }

    suspend fun deleteItems(
        paths: Collection<String>,
        overwrite: Boolean,
        onProgress: (Long) -> Unit = {},
    ): BulkResult {
        var ok = 0
        val fails = ArrayList<String>()
        var totalOverwritten = 0L
        withContext(ioDispatcher) {
            for (p in paths) {
                try {
                    var itemPrev = 0L
                    volume.delete(p, overwrite) { itemBytes ->
                        totalOverwritten += (itemBytes - itemPrev)
                        itemPrev = itemBytes
                        onProgress(totalOverwritten)
                    }
                    ratingsStore.onPathDeleted(p)
                    ok++
                } catch (e: Throwable) {
                    fails += "${FsPaths.name(p)}: ${e.message ?: "Delete failed"}"
                }
            }
        }
        clearSelection()
        refreshSync()
        return BulkResult(ok, fails)
    }
}

