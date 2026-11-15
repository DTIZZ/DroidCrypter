package app.droidcrypter.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.droidcrypter.browse.BrowserRow
import app.droidcrypter.browse.BrowserStateHolder
import app.droidcrypter.browse.FileClassification
import app.droidcrypter.browse.FileOpProgress
import app.droidcrypter.browse.FileOperations
import app.droidcrypter.browse.IncomingFileMeta
import app.droidcrypter.browse.IncomingFolder
import app.droidcrypter.browse.RatingsRegistry
import app.droidcrypter.browse.SortCriterion
import app.droidcrypter.browse.SortDirection
import app.droidcrypter.browse.ViewerKind
import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.FsPaths
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.engine.VolumeSpace
import app.droidcrypter.provider.VaultDocumentsProvider
import app.droidcrypter.provider.VolumeUris
import app.droidcrypter.settings.AppPreferences
import app.droidcrypter.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    volume: MountedVolume,
    prefs: AppPreferences,
    settings: AppSettings,
    initialDir: String = "/",
    initialSearchQuery: String? = null,
    /** The photo/video the user just came back from: the list scrolls to it and marks it. */
    lastViewedPath: String? = null,
    onDirChanged: (String) -> Unit = {},
    onSearchChanged: (String?) -> Unit = {},
    onOpenPhotoViewer: (List<FsNode>, Int) -> Unit,
    onOpenMediaPlayer: (List<FsNode>, Int) -> Unit,
    onOpenVideoEditor: (String) -> Unit,
    onLockVolume: () -> Unit,
    onCloseBrowser: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ratingsStore = remember(volume.id) { RatingsRegistry.getOrCreate(volume) }

    val holder = remember(volume.id) {
        BrowserStateHolder(
            volume = volume,
            ratingsStore = ratingsStore,
            initialSort = settings.sortConfig,
            initialShowStars = settings.showStars,
            initialSearchQuery = initialSearchQuery,
        )
    }

    val state by holder.state.collectAsState()

    // Keep holder synced with global preferences
    LaunchedEffect(settings.showStars) {
        holder.setShowStars(settings.showStars)
    }
    LaunchedEffect(settings.sortConfig) {
        holder.setSortConfig(settings.sortConfig)
    }

    // Load initial directory
    LaunchedEffect(volume.id, initialDir) {
        holder.refreshSync(initialDir)
    }
    LaunchedEffect(state.currentDir) {
        onDirChanged(state.currentDir)
    }
    // Remembered by the host so the hits are still there after coming back from a viewer.
    LaunchedEffect(state.searchActive, state.searchQuery) {
        onSearchChanged(if (state.searchActive) state.searchQuery else null)
    }

    // Edge-to-edge: keep bars and rows clear of a side navigation bar / display cutout (landscape
    // 3-button navigation), and keep the list and action bar above the on-screen keyboard.
    val sideInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal)
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()

    // System Back: exit selection first; else close search; else parent directory; else vault list
    BackHandler {
        if (holder.onBackPressed()) {
            // Selection cleared
        } else if (state.currentDir != "/") {
            holder.navigateUp()
        } else {
            onCloseBrowser()
        }
    }

    // Dialog states
    var pendingAddFiles by remember { mutableStateOf<List<IncomingFileMeta>?>(null) }
    var pendingAddFolder by remember { mutableStateOf<IncomingFolder?>(null) }
    var deleteOriginalsAfterAdd by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var overwriteOnDelete by remember { mutableStateOf(true) } // Default ON per section 1 & 7.4

    var showRenameDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }

    var pendingExportMoveOut by remember { mutableStateOf<Boolean?>(null) }
    var activeExportPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeExportMoveOut by remember { mutableStateOf(false) }

    var activeProgress by remember { mutableStateOf<FileOpProgress?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // File pickers for Add files, Copy out / Move out (single file vs. folder tree)
    val addFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val metas = withContext(Dispatchers.IO) {
                    FileOperations.queryIncomingFiles(context.contentResolver, uris)
                }
                deleteOriginalsAfterAdd = false
                pendingAddFiles = metas
            }
        }
    }

    val addFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            scope.launch {
                activeProgress = FileOpProgress("Reading folder", "Listing its contents", 0, 0, 0L, 0L)
                val folder = try {
                    withContext(Dispatchers.IO) {
                        FileOperations.queryIncomingFolder(context.contentResolver, treeUri)
                    }
                } catch (t: Throwable) {
                    statusMessage = "Could not read that folder: ${t.message ?: "unknown error"}"
                    null
                }
                activeProgress = null
                if (folder != null) {
                    deleteOriginalsAfterAdd = false
                    pendingAddFolder = folder
                }
            }
        }
    }

    val singleExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { destUri: Uri? ->
        val paths = activeExportPaths
        val moveOut = activeExportMoveOut
        if (destUri != null && paths.size == 1) {
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    FileOperations.exportSingleFile(
                        context = context,
                        volume = volume,
                        ratingsStore = ratingsStore,
                        sourcePath = paths.first(),
                        destDocumentUri = destUri,
                        moveOut = moveOut,
                        onProgress = { activeProgress = it },
                    )
                }
                activeProgress = null
                holder.clearSelection()
                holder.refreshSync()
                statusMessage = if (res.failures.isEmpty()) {
                    if (moveOut) "Moved 1 file out of vault." else "Copied 1 file out of vault."
                } else {
                    res.failures.joinToString("\n")
                }
            }
        }
    }

    val treeExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri: Uri? ->
        val paths = activeExportPaths
        val moveOut = activeExportMoveOut
        if (treeUri != null && paths.isNotEmpty()) {
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    FileOperations.exportToTree(
                        context = context,
                        volume = volume,
                        ratingsStore = ratingsStore,
                        sourcePaths = paths,
                        treeUri = treeUri,
                        moveOut = moveOut,
                        onProgress = { activeProgress = it },
                    )
                }
                activeProgress = null
                holder.clearSelection()
                holder.refreshSync()
                statusMessage = if (res.failures.isEmpty()) {
                    val verb = if (moveOut) "Moved" else "Copied"
                    "$verb ${res.succeeded} file(s) out of vault."
                } else {
                    res.failures.joinToString("\n")
                }
            }
        }
    }

    Scaffold(
        containerColor = VaultColors.Background,
        contentColor = VaultColors.TextPrimary,
        topBar = {
            Surface(
                color = VaultColors.Surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .windowInsetsPadding(sideInsets)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                if (state.selectionMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { holder.clearSelection() },
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                    .semantics { contentDescription = "Exit selection mode" },
                            ) {
                                Text("✕", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                text = "${state.selectedCount} selected",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { holder.selectAll() },
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            ) {
                                Text("Select all", color = VaultColors.Primary)
                            }
                            TextButton(
                                onClick = { holder.selectNone() },
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            ) {
                                Text("Select none", color = VaultColors.TextSecondary)
                            }
                        }
                    }
                } else if (state.searchActive) {
                    SearchBarRow(
                        query = state.searchQuery,
                        onQueryChange = { holder.setSearchQuery(it) },
                        onClose = { holder.closeSearch() },
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    if (state.currentDir != "/") {
                                        holder.navigateUp()
                                    } else {
                                        onCloseBrowser()
                                    }
                                },
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                    .semantics {
                                        contentDescription = if (state.currentDir != "/") {
                                            "Navigate to parent folder"
                                        } else {
                                            "Back to vault list"
                                        }
                                    },
                            ) {
                                Text("←", style = MaterialTheme.typography.titleLarge, color = VaultColors.Primary)
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                val title = if (state.currentDir == "/") {
                                    volume.label
                                } else {
                                    FsPaths.name(state.currentDir)
                                }
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = state.currentDir,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VaultColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { holder.openSearch() },
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                    .testTag(BrowserTestTags.SEARCH_BUTTON)
                                    .semantics { contentDescription = "Search this vault" },
                            ) {
                                Text("🔍", style = MaterialTheme.typography.titleMedium)
                            }

                            if (state.isWritable) {
                                Box {
                                    Button(
                                        onClick = { showAddMenu = true },
                                        modifier = Modifier
                                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                            .testTag(BrowserTestTags.ADD_BUTTON)
                                            .semantics { contentDescription = "Add files or folders" },
                                        colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Primary),
                                    ) {
                                        Text("+ Add", fontWeight = FontWeight.SemiBold)
                                    }
                                    DropdownMenu(
                                        expanded = showAddMenu,
                                        onDismissRequest = { showAddMenu = false },
                                        containerColor = VaultColors.SurfaceElevated,
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Add files", color = VaultColors.TextPrimary) },
                                            onClick = {
                                                showAddMenu = false
                                                addFilesLauncher.launch(arrayOf("*/*"))
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Add a folder", color = VaultColors.TextPrimary) },
                                            onClick = {
                                                showAddMenu = false
                                                addFolderLauncher.launch(null)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("New folder", color = VaultColors.TextPrimary) },
                                            onClick = {
                                                showAddMenu = false
                                                showNewFolderDialog = true
                                            },
                                        )
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = onLockVolume,
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                    .semantics { contentDescription = "Lock this vault" },
                            ) {
                                Text("Lock", color = VaultColors.Danger, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // ---- Space Summary directly under the folder title (section 7.5) ----
                val space = state.space
                if (space != null) {
                    val spaceText = space.browserSummaryText()
                    val barColor = if (space.isLow) VaultColors.Danger else VaultColors.Primary
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                            .clearAndSetSemantics {
                                contentDescription = if (space.isLow) {
                                    "Low disk space warning: $spaceText"
                                } else {
                                    spaceText
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = spaceText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (space.isLow) VaultColors.Danger else VaultColors.TextSecondary,
                                fontWeight = if (space.isLow) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (state.writeSupport != null) {
                                Text(
                                    text = state.writeSupport!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VaultColors.StarAmber,
                                )
                            }
                        }
                        LinearProgressIndicator(
                            progress = { space.usedFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = barColor,
                            trackColor = VaultColors.Outline,
                        )
                    }
                }

                // ---- Search status: what the rows below are ----
                if (state.searchActive && !state.selectionMode) {
                    val status = when {
                        state.searchQuery.isBlank() -> "Type a name to search every folder in this vault."
                        state.isSearching -> "Searching…"
                        state.rows.isEmpty() -> "No files or folders match."
                        state.searchTruncated -> "Showing the first ${state.rows.size} matches. Type more to narrow it down."
                        state.rows.size == 1 -> "1 match in this vault"
                        else -> "${state.rows.size} matches in this vault"
                    }
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.searchTruncated) VaultColors.StarAmber else VaultColors.TextSecondary,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .testTag(BrowserTestTags.SEARCH_STATUS),
                    )
                }

                // ---- Sort Bar (section 7.2) ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val criteria = remember(state.showStars) {
                        if (state.showStars) {
                            SortCriterion.entries
                        } else {
                            SortCriterion.entries.filter { it != SortCriterion.STARS }
                        }
                    }
                    val effectiveCrit = state.sortConfig.effectiveCriterion(state.showStars)
                    for (crit in criteria) {
                        val isActive = effectiveCrit == crit
                        val arrow = if (isActive) {
                            if (state.sortConfig.direction == SortDirection.ASCENDING) " ↑" else " ↓"
                        } else {
                            ""
                        }
                        val dirName = if (state.sortConfig.direction == SortDirection.ASCENDING) {
                            "Ascending"
                        } else {
                            "Descending"
                        }
                        val chipLabel = when (crit) {
                            SortCriterion.NAME -> "Name"
                            SortCriterion.DATE -> "Date"
                            SortCriterion.SIZE -> "Size"
                            SortCriterion.STARS -> "Stars"
                        }
                        FilterChip(
                            selected = isActive,
                            onClick = {
                                val nextCfg = state.sortConfig.select(crit)
                                prefs.setSortConfig(nextCfg)
                                holder.setSortConfig(nextCfg)
                            },
                            label = { Text("$chipLabel$arrow") },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = VaultColors.SurfaceElevated,
                                labelColor = VaultColors.TextPrimary,
                                selectedContainerColor = VaultColors.Primary,
                                selectedLabelColor = Color(0xFF0F141A),
                            ),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .semantics {
                                    contentDescription = "Sort by $chipLabel"
                                    if (isActive) {
                                        stateDescription = dirName
                                    }
                                },
                        )
                    }
                }
            }
        }
    },
    bottomBar = {
            if (state.selectionMode) {
                Surface(
                    color = VaultColors.Surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(BrowserTestTags.ACTION_BAR),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding(),
                    ) {
                        HorizontalDivider(color = VaultColors.Outline)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { showMoveDialog = true },
                                enabled = state.canMutateSelected,
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            ) {
                                Text("Move")
                            }

                            TextButton(
                                onClick = { pendingExportMoveOut = false },
                                enabled = state.canCopyOutSelected,
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            ) {
                                Text("Copy out")
                            }

                            TextButton(
                                onClick = { pendingExportMoveOut = true },
                                enabled = state.canMutateSelected,
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            ) {
                                Text("Move out")
                            }

                            TextButton(
                                onClick = { showRenameDialog = true },
                                enabled = state.canRenameSelected,
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            ) {
                                Text("Rename")
                            }

                            TextButton(
                                onClick = {
                                    overwriteOnDelete = true
                                    showDeleteConfirm = true
                                },
                                enabled = state.canMutateSelected,
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            ) {
                                Text("Delete", color = if (state.canMutateSelected) VaultColors.Danger else VaultColors.TextSecondary)
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            HorizontalDivider(color = VaultColors.Outline)

            if (statusMessage != null || state.error != null) {
                val msg = statusMessage ?: state.error!!
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(VaultColors.SurfaceElevated)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.StarAmber,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            statusMessage = null
                            holder.clearError()
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Text("Dismiss")
                    }
                }
                HorizontalDivider(color = VaultColors.Outline)
            }

            // ---- File List ----
            // The list draws edge-to-edge behind the bottom bar / navigation bar; this padding is what
            // lets the last row scroll fully clear of whichever is tallest: the multi-select action
            // bar, the system navigation bar, or the keyboard.
            val listBottomPadding = maxOf(innerPadding.calculateBottomPadding(), imeBottom) + 16.dp
            // A different folder or a different set of search hits starts from the top.
            val listState = rememberLazyListState()
            // Coming back from a viewer: wait for the rows, then put the last viewed file about a
            // third of the way down the list so the files around it are visible too.
            var pendingReturnPath by remember(volume.id) { mutableStateOf(lastViewedPath) }
            LaunchedEffect(state.currentDir, state.showingSearchResults, state.searchQuery) {
                if (pendingReturnPath == null) listState.scrollToItem(0)
            }
            LaunchedEffect(state.rows, state.isLoading, state.isSearching) {
                val target = pendingReturnPath ?: return@LaunchedEffect
                if (state.isLoading || state.isSearching) return@LaunchedEffect
                pendingReturnPath = null
                val index = state.rows.indexOfFirst { it.node.path == target }
                if (index < 0) return@LaunchedEffect
                snapshotFlow { listState.layoutInfo }
                    .first { it.totalItemsCount > index && it.viewportSize.height > 0 }
                listState.scrollToItem(index, -(listState.layoutInfo.viewportSize.height / 3))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .windowInsetsPadding(sideInsets)
                    .testTag(BrowserTestTags.FILE_LIST),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = listBottomPadding,
                ),
            ) {
                if (!state.isLoading && state.rows.isEmpty() && !state.isSearching) {
                    item(key = "empty_folder") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (state.showingSearchResults) {
                                    "Nothing in this vault matches “${state.searchQuery.trim()}”."
                                } else {
                                    "This folder is empty."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = VaultColors.TextSecondary,
                            )
                        }
                    }
                }

                items(
                    items = state.rows,
                    key = { it.node.path },
                ) { row ->
                    BrowserItemRow(
                        row = row,
                        showStars = state.showStars,
                        selectionMode = state.selectionMode,
                        isWritable = state.isWritable,
                        isLastViewed = row.node.path == lastViewedPath,
                        locationLabel = if (state.showingSearchResults) {
                            "in ${FsPaths.parent(row.node.path)}"
                        } else {
                            null
                        },
                        modifier = Modifier.animateItem(),
                        onClick = {
                            if (state.selectionMode) {
                                holder.onTapInSelectionMode(row.node.path)
                            } else if (row.node.isDirectory) {
                                if (state.searchActive) {
                                    holder.openFolderFromSearch(row.node.path)
                                } else {
                                    holder.loadDirectory(row.node.path)
                                }
                            } else {
                                when (row.viewerKind) {
                                    ViewerKind.IMAGE -> {
                                        val images = holder.imagePlaylist()
                                        val idx = images.indexOfFirst { it.path == row.node.path }.coerceAtLeast(0)
                                        onOpenPhotoViewer(images, idx)
                                    }
                                    ViewerKind.MEDIA -> {
                                        val media = holder.mediaPlaylist()
                                        val idx = media.indexOfFirst { it.path == row.node.path }.coerceAtLeast(0)
                                        onOpenMediaPlayer(media, idx)
                                    }
                                    ViewerKind.EXTERNAL -> {
                                        openExternalReadOnly(context, volume.id, row.node) { err ->
                                            statusMessage = err
                                        }
                                    }
                                }
                            }
                        },
                        onLongClick = {
                            holder.onLongPress(row.node.path)
                        },
                        onToggleStar = { starIndex ->
                            holder.toggleStar(row.node.path, starIndex)
                        },
                        onEditVideo = if (!row.node.isDirectory && FileClassification.isEditableVideo(row.node.name)) {
                            { onOpenVideoEditor(row.node.path) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    // ---- 1. Add Files Confirmation Dialog ----
    val incomingFolder = pendingAddFolder
    val incoming = pendingAddFiles ?: incomingFolder?.files?.map { it.meta }
    if (incoming != null) {
        val totalBytes = incoming.sumOf { it.sizeBytes }
        val dismissAdd = {
            pendingAddFiles = null
            pendingAddFolder = null
        }
        AlertDialog(
            onDismissRequest = dismissAdd,
            title = {
                Text(
                    if (incomingFolder != null) "Add folder \"${incomingFolder.name}\" to vault"
                    else "Add ${incoming.size} file(s) to vault"
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (incomingFolder != null) {
                        Text(
                            text = "${incoming.size} file(s) in ${incomingFolder.subfolders.size + 1} folder(s). " +
                                "The folder structure is kept.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (incomingFolder.skipped.isNotEmpty()) {
                            Text(
                                text = "${incomingFolder.skipped.size} item(s) could not be read and will be left out.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColors.StarAmber,
                            )
                        }
                    }
                    Text(
                        text = "Total size: ${VolumeSpace.formatBytes(totalBytes)}\nDestination folder: ${state.currentDir}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val freeNow = state.space?.freeBytes
                    if (freeNow != null && totalBytes > freeNow) {
                        Text(
                            text = "This is more than the ${VolumeSpace.formatBytes(freeNow)} free in this vault; not everything will fit.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.Danger,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(role = Role.Checkbox) {
                                deleteOriginalsAfterAdd = !deleteOriginalsAfterAdd
                            }
                            .semantics {
                                stateDescription = if (deleteOriginalsAfterAdd) "Checked" else "Unchecked"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = deleteOriginalsAfterAdd,
                            onCheckedChange = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete the originals afterwards")
                    }
                    if (deleteOriginalsAfterAdd) {
                        Text(
                            text = "Note: Flash storage wear-levelling means deleting the original unencrypted file from Android storage cannot guarantee physical erasure of its old flash blocks, and some providers do not permit deletion.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.StarAmber,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val listToImport = incoming
                        val folderToImport = incomingFolder
                        val delOrig = deleteOriginalsAfterAdd
                        val targetDir = state.currentDir
                        dismissAdd()
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                if (folderToImport != null) {
                                    FileOperations.addFolderToContainer(
                                        context = context,
                                        volume = volume,
                                        targetDir = targetDir,
                                        folder = folderToImport,
                                        deleteOriginals = delOrig,
                                        onProgress = { activeProgress = it },
                                    )
                                } else {
                                    FileOperations.addFilesToContainer(
                                        context = context,
                                        volume = volume,
                                        targetDir = targetDir,
                                        files = listToImport,
                                        deleteOriginals = delOrig,
                                        onProgress = { activeProgress = it },
                                    )
                                }
                            }
                            activeProgress = null
                            holder.refreshSync()
                            val summary = if (folderToImport != null) {
                                "Added ${res.foldersCreated} folder(s) and ${res.succeeded} file(s) to $targetDir."
                            } else {
                                "Added ${res.succeeded} file(s) to $targetDir."
                            }
                            statusMessage = if (res.failures.isEmpty()) {
                                summary
                            } else {
                                FileOperations.summarizeFailures(summary, res.failures)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Primary),
                ) {
                    Text(if (incomingFolder != null) "Add folder" else "Add files")
                }
            },
            dismissButton = {
                TextButton(onClick = dismissAdd) {
                    Text("Cancel")
                }
            },
            containerColor = VaultColors.SurfaceElevated,
            titleContentColor = VaultColors.TextPrimary,
            textContentColor = VaultColors.TextPrimary,
        )
    }

    // ---- 2. Copy Out / Move Out Plaintext Warning Dialog ----
    val exportMoveFlag = pendingExportMoveOut
    if (exportMoveFlag != null) {
        val selectedNodes = state.selectedNodes
        val isSingleFile = selectedNodes.size == 1 && !selectedNodes.first().isDirectory
        AlertDialog(
            onDismissRequest = { pendingExportMoveOut = null },
            title = {
                Text(if (exportMoveFlag) "Move out of vault (unencrypted)" else "Copy out of vault (unencrypted)")
            },
            text = {
                Text(
                    text = "Warning: Files exported outside the DroidCrypter container will be written unencrypted (plaintext) to the destination storage you select." +
                        if (exportMoveFlag) "\n\nThe copies inside this vault will be overwritten and deleted only after all files have been copied out cleanly." else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.StarAmber,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingExportMoveOut = null
                        activeExportPaths = selectedNodes.map { it.path }
                        activeExportMoveOut = exportMoveFlag
                        if (isSingleFile) {
                            singleExportLauncher.launch(selectedNodes.first().name)
                        } else {
                            treeExportLauncher.launch(null)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Primary),
                ) {
                    Text("Choose destination")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingExportMoveOut = null }) {
                    Text("Cancel")
                }
            },
            containerColor = VaultColors.SurfaceElevated,
            titleContentColor = VaultColors.TextPrimary,
            textContentColor = VaultColors.TextPrimary,
        )
    }

    // ---- 3. Delete Confirmation Dialog ----
    if (showDeleteConfirm) {
        val pathsToDelete = state.selectedPaths.toList()
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${pathsToDelete.size} item(s)?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "This permanently removes the selected item(s) from the container.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(role = Role.Checkbox) {
                                overwriteOnDelete = !overwriteOnDelete
                            }
                            .semantics {
                                stateDescription = if (overwriteOnDelete) "Checked" else "Unchecked"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = overwriteOnDelete,
                            onCheckedChange = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Overwrite data with zeros before deleting (recommended)")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        val doOverwrite = overwriteOnDelete
                        val totalSize = state.selectedNodes.sumOf { it.size.coerceAtLeast(0L) }
                        scope.launch {
                            val res = holder.deleteItems(
                                paths = pathsToDelete,
                                overwrite = doOverwrite,
                                onProgress = { bytes ->
                                    activeProgress = FileOpProgress(
                                        title = if (doOverwrite) "Overwriting & deleting" else "Deleting",
                                        currentFile = "${pathsToDelete.size} item(s)",
                                        itemIndex = 1,
                                        itemCount = pathsToDelete.size,
                                        bytesDone = bytes,
                                        totalBytes = totalSize,
                                    )
                                },
                            )
                            activeProgress = null
                            for (dir in pathsToDelete.map { FsPaths.parent(it) }.toSet() + state.currentDir) {
                                VaultDocumentsProvider.notifyChildDocumentsChanged(context, volume.id, dir)
                            }
                            VaultDocumentsProvider.notifyRootsChanged(context)
                            if (res.failures.isNotEmpty()) {
                                statusMessage = res.failures.joinToString("\n")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Danger),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
            containerColor = VaultColors.SurfaceElevated,
            titleContentColor = VaultColors.TextPrimary,
            textContentColor = VaultColors.TextPrimary,
        )
    }

    // ---- 4. Rename Dialog (pre-selects base name excluding extension) ----
    if (showRenameDialog && state.selectedNodes.size == 1) {
        val targetNode = state.selectedNodes.first()
        val selEnd = FileOperations.renameInitialSelectionEnd(targetNode.name, targetNode.isDirectory)
        var tfv by remember(targetNode.path) {
            mutableStateOf(
                TextFieldValue(
                    text = targetNode.name,
                    selection = TextRange(0, selEnd),
                )
            )
        }
        var renameError by remember { mutableStateOf<String?>(null) }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            try { focusRequester.requestFocus() } catch (_: Throwable) {}
        }

        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tfv,
                        onValueChange = {
                            tfv = it
                            renameError = null
                        },
                        singleLine = true,
                        label = { Text("New name") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                    if (renameError != null) {
                        Text(
                            text = renameError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.Danger,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val desired = tfv.text.trim()
                        scope.launch {
                            try {
                                holder.renameItem(targetNode.path, desired)
                                showRenameDialog = false
                                VaultDocumentsProvider.notifyChildDocumentsChanged(
                                    context, volume.id, FsPaths.parent(targetNode.path),
                                )
                            } catch (err: Throwable) {
                                renameError = err.message ?: "Rename failed"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Primary),
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = VaultColors.SurfaceElevated,
            titleContentColor = VaultColors.TextPrimary,
            textContentColor = VaultColors.TextPrimary,
        )
    }

    // ---- 4b. New Folder Dialog ----
    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf(TextFieldValue("New folder", TextRange(0, 10))) }
        var folderError by remember { mutableStateOf<String?>(null) }
        var creating by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            try { focusRequester.requestFocus() } catch (_: Throwable) {}
        }
        val create = {
            val desired = folderName.text.trim()
            if (!creating) {
                creating = true
                scope.launch {
                    try {
                        val dir = state.currentDir
                        holder.createFolder(desired)
                        showNewFolderDialog = false
                        VaultDocumentsProvider.notifyChildDocumentsChanged(context, volume.id, dir)
                        VaultDocumentsProvider.notifyRootsChanged(context)
                    } catch (err: Throwable) {
                        folderError = err.message ?: "Could not create the folder"
                    } finally {
                        creating = false
                    }
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("New folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Created in ${state.currentDir}",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.TextSecondary,
                    )
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = {
                            folderName = it
                            folderError = null
                        },
                        singleLine = true,
                        label = { Text("Folder name") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { create() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag(BrowserTestTags.NEW_FOLDER_FIELD),
                    )
                    if (folderError != null) {
                        Text(
                            text = folderError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.Danger,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { create() },
                    enabled = !creating && folderName.text.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Primary),
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = VaultColors.SurfaceElevated,
            titleContentColor = VaultColors.TextPrimary,
            textContentColor = VaultColors.TextPrimary,
        )
    }

    // ---- 5. Move Folder-Picker Dialog (excludes selected folders) ----
    if (showMoveDialog) {
        var browseMoveDir by remember { mutableStateOf(state.currentDir) }
        var subfolders by remember { mutableStateOf<List<FsNode>>(emptyList()) }
        LaunchedEffect(browseMoveDir, state.selectedPaths) {
            subfolders = holder.listMoveDestinations(browseMoveDir)
        }

        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text("Move ${state.selectedCount} item(s) to...") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Target folder: $browseMoveDir",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = VaultColors.Primary,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { browseMoveDir = "/" },
                            enabled = browseMoveDir != "/",
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text("Root (/)")
                        }
                        if (browseMoveDir != "/") {
                            OutlinedButton(
                                onClick = { browseMoveDir = FsPaths.parent(browseMoveDir) },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text(".. (Parent)")
                            }
                        }
                    }

                    HorizontalDivider(color = VaultColors.Outline)

                    if (subfolders.isEmpty()) {
                        Text(
                            text = "No subfolders in $browseMoveDir",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.TextSecondary,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.height(200.dp)) {
                            items(subfolders, key = { it.path }) { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                        .clickable { browseMoveDir = folder.path }
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("📁 ${folder.name}", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val dest = browseMoveDir
                        val paths = state.selectedPaths.toList()
                        showMoveDialog = false
                        scope.launch {
                            val res = holder.moveItems(paths, dest)
                            for (dir in paths.map { FsPaths.parent(it) }.toSet() + state.currentDir + dest) {
                                VaultDocumentsProvider.notifyChildDocumentsChanged(context, volume.id, dir)
                            }
                            if (res.failures.isNotEmpty()) {
                                statusMessage = res.failures.joinToString("\n")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Primary),
                ) {
                    Text("Move here")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = VaultColors.SurfaceElevated,
            titleContentColor = VaultColors.TextPrimary,
            textContentColor = VaultColors.TextPrimary,
        )
    }

    // ---- 6. Progress Dialog ----
    val prog = activeProgress
    if (prog != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(prog.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (prog.itemCount > 0) "${prog.currentFile} (${prog.itemIndex} of ${prog.itemCount})" else prog.currentFile,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = { prog.fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = VaultColors.Primary,
                    )
                    Text(
                        text = "${VolumeSpace.formatBytes(prog.bytesDone)} / ${VolumeSpace.formatBytes(prog.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.TextSecondary,
                    )
                }
            },
            confirmButton = {},
            containerColor = VaultColors.SurfaceElevated,
            titleContentColor = VaultColors.TextPrimary,
            textContentColor = VaultColors.TextPrimary,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserItemRow(
    row: BrowserRow,
    showStars: Boolean,
    selectionMode: Boolean,
    isWritable: Boolean,
    isLastViewed: Boolean = false,
    locationLabel: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleStar: (Int) -> Unit,
    onEditVideo: (() -> Unit)?,
) {
    val node = row.node
    val bg = when {
        row.isSelected -> VaultColors.SurfaceElevated
        isLastViewed -> VaultColors.Primary.copy(alpha = 0.16f)
        else -> VaultColors.Background
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .background(bg)
            .drawBehind {
                // Accent bar down the left edge of the file the user last had open.
                if (isLastViewed) {
                    drawRect(VaultColors.Primary, size = Size(4.dp.toPx(), size.height))
                }
            }
            .semantics { if (isLastViewed) stateDescription = LAST_VIEWED_STATE }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = row.isSelected,
                    onCheckedChange = null,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }

            val iconGlyph = when {
                node.isDirectory -> "📁"
                row.viewerKind == ViewerKind.IMAGE -> "🖼️"
                row.viewerKind == ViewerKind.MEDIA -> "🎬"
                else -> "📄"
            }
            Text(
                text = iconGlyph,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 12.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = VaultColors.TextPrimary,
                    fontWeight = if (node.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (locationLabel != null) {
                    Text(
                        text = locationLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val datePart = if (node.lastModifiedMillis > 0L) {
                    formatShortDate(node.lastModifiedMillis)
                } else {
                    "Unknown date"
                }
                val metaText = if (node.isDirectory) {
                    "Folder · $datePart"
                } else {
                    "${VolumeSpace.formatBytes(node.size)} · $datePart"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.TextSecondary,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // 3-star rating control shifted to line 2 & moved closer together so file name can expand!
                    if (showStars && !node.isDirectory) {
                        val starsEnabled = isWritable && !selectionMode
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            for (k in 1..3) {
                                val isLit = k <= row.displayedStars
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable(
                                            enabled = starsEnabled,
                                            role = Role.Button,
                                            onClick = { onToggleStar(k) },
                                        )
                                        .semantics {
                                            contentDescription = "Rate ${node.name} $k of 3 stars"
                                            stateDescription = if (isLit) "Selected" else "Not selected"
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = if (isLit) "★" else "☆",
                                        color = if (isLit) VaultColors.StarAmber else VaultColors.TextSecondary,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (onEditVideo != null && !selectionMode && isWritable) {
            TextButton(
                onClick = onEditVideo,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Edit video ${node.name}" },
            ) {
                Text("✂ Edit", color = VaultColors.Primary)
            }
        }
    }
}

/** Spoken by screen readers (and matched by tests) on the row the user just came back from. */
const val LAST_VIEWED_STATE = "Last viewed"

/** Stable tags for the layout tests in `androidTest` (bottom bars must never cover list rows). */
object BrowserTestTags {
    const val FILE_LIST = "browser_file_list"
    const val ACTION_BAR = "browser_action_bar"
    const val SEARCH_BUTTON = "browser_search_button"
    const val ADD_BUTTON = "browser_add_button"
    const val NEW_FOLDER_FIELD = "browser_new_folder_field"
    const val SEARCH_FIELD = "browser_search_field"
    const val SEARCH_STATUS = "browser_search_status"
}

/**
 * Search field that replaces the title row. File names are sensitive, so the keyboard is asked not
 * to autocorrect or suggest (which also keeps most keyboards from learning the typed words).
 */
@Composable
private fun SearchBarRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // Only pop the keyboard for a fresh search, not when returning to existing results.
        if (query.isEmpty()) {
            try { focusRequester.requestFocus() } catch (_: Throwable) {}
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onClose,
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = "Close search" },
        ) {
            Text("←", style = MaterialTheme.typography.titleLarge, color = VaultColors.Primary)
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Search this vault", color = VaultColors.TextSecondary) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = VaultColors.TextPrimary,
                unfocusedTextColor = VaultColors.TextPrimary,
                cursorColor = VaultColors.Primary,
                focusedBorderColor = VaultColors.Primary,
                unfocusedBorderColor = VaultColors.Outline,
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .testTag(BrowserTestTags.SEARCH_FIELD),
        )
        if (query.isNotEmpty()) {
            TextButton(
                onClick = { onQueryChange("") },
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Clear search text" },
            ) {
                Text("✕", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun formatShortDate(millis: Long): String {
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
    } catch (_: Throwable) {
        "Unknown date"
    }
}

private fun openExternalReadOnly(
    context: android.content.Context,
    volumeId: String,
    node: FsNode,
    onError: (String) -> Unit,
) {
    try {
        val docId = VolumeUris.formatDocumentId(volumeId, node.path)
        val uri = DocumentsContract.buildDocumentUri(VaultDocumentsProvider.AUTHORITY, docId)
        val mime = FileClassification.mimeType(node)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, node.name))
    } catch (t: Throwable) {
        onError("No installed app could open ${node.name}.")
    }
}

