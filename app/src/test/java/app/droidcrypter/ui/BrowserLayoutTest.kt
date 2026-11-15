package app.droidcrypter.ui

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.droidcrypter.engine.FsFileHandle
import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.FsPaths
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.settings.AppPreferences
import app.droidcrypter.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Lays out the real [BrowserScreen] on the host (Robolectric) and feeds it the window insets a
 * phone reports — 3-button navigation bar, gesture pill, a side navigation bar in landscape, and
 * the on-screen keyboard — then checks, in pixels, that the buttons at the bottom of the screen
 * never sit on top of a list row and never sit under a system bar.
 *
 * The list is edge-to-edge, so rows may pass *behind* a bar while scrolling; what must hold is that
 * every row can be scrolled completely clear of it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = PORTRAIT)
class BrowserLayoutTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var hostView: View

    private fun show(
        volume: MountedVolume = FakeVolume(),
        initialSearchQuery: String? = null,
        lastViewedPath: String? = null,
        waitFor: String = FIRST_ROOT_ROW,
    ) {
        rule.setContent {
            hostView = LocalView.current
            DroidCrypterTheme {
                BrowserScreen(
                    volume = volume,
                    prefs = AppPreferences(rule.activity),
                    settings = AppSettings(),
                    initialSearchQuery = initialSearchQuery,
                    lastViewedPath = lastViewedPath,
                    onOpenPhotoViewer = { _, _ -> },
                    onOpenMediaPlayer = { _, _ -> },
                    onOpenVideoEditor = {},
                    onLockVolume = {},
                    onCloseBrowser = {},
                )
            }
        }
        rule.waitUntilAtLeastOneExists(hasText(waitFor), 10_000)
        rule.waitForIdle()
    }

    private fun Dp.px(): Int = with(rule.density) { roundToPx() }

    /** Dispatches the insets a device would report for its system bars and keyboard. */
    private fun setInsets(status: Dp = 24.dp, navBottom: Dp = 0.dp, navRight: Dp = 0.dp, ime: Dp = 0.dp) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, status.px(), 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, navRight.px(), navBottom.px()))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime.px()))
            .setVisible(WindowInsetsCompat.Type.ime(), ime > 0.dp)
            .build()
        rule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(hostView, insets) }
        rule.waitForIdle()
    }

    private fun root(): Rect = rule.onRoot().fetchSemanticsNode().boundsInRoot
    private fun row(name: String): Rect = rule.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
    private fun actionBar(): Rect = rule.onNodeWithTag(BrowserTestTags.ACTION_BAR).fetchSemanticsNode().boundsInRoot
    private fun button(text: String): Rect = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun scrollToEnd(lastIndex: Int) {
        rule.onNodeWithTag(BrowserTestTags.FILE_LIST).performScrollToIndex(lastIndex)
        rule.onNodeWithTag(BrowserTestTags.FILE_LIST).performTouchInput { swipeUp() }
        rule.waitForIdle()
    }

    private fun enterSelectionMode(rowName: String = FIRST_ROOT_ROW) {
        rule.onNodeWithText(rowName).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag(BrowserTestTags.ACTION_BAR).assertExists()
    }

    private fun assertAtMost(what: String, actual: Float, limit: Float) {
        assertTrue("$what: $actual must be <= $limit", actual <= limit + 0.5f)
    }

    private fun checkBottom(navBottom: Dp) {
        show()
        setInsets(navBottom = navBottom)
        val safeBottom = root().bottom - navBottom.px()

        // Plain browsing: the last row scrolls fully above the system navigation bar.
        scrollToEnd(ROOT_ROWS - 1)
        assertAtMost("last row bottom vs navigation bar", row(LAST_ROOT_ROW).bottom, safeBottom)

        // Multi-select: the action bar appears while already at the end of the list.
        enterSelectionMode(LAST_ROOT_ROW)
        for (label in ACTIONS) {
            assertAtMost("'$label' bottom vs navigation bar", button(label).bottom, safeBottom)
        }
        assertEquals("action bar reaches the screen bottom", root().bottom, actionBar().bottom, 0.5f)

        // ...and the last row can still be brought completely above the action buttons.
        scrollToEnd(ROOT_ROWS - 1)
        assertAtMost("last row bottom vs action bar top", row(LAST_ROOT_ROW).bottom, actionBar().top)
        assertTrue("action bar is below the list's first rows", actionBar().top > root().bottom / 2)
    }

    @Test
    fun threeButtonNavigation_lastRowClearsNavigationBarAndActionBar() = checkBottom(navBottom = 48.dp)

    @Test
    fun gestureNavigation_lastRowClearsGesturePillAndActionBar() = checkBottom(navBottom = 24.dp)

    @Test
    fun noNavigationBarInset_lastRowClearsActionBar() = checkBottom(navBottom = 0.dp)

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun landscapeSideNavigationBar_rowsAndButtonsStayOutFromUnderIt() {
        show()
        val navRight = 48.dp
        setInsets(navRight = navRight)
        val safeRight = root().right - navRight.px()

        assertAtMost("row right edge vs side navigation bar", row(FIRST_ROOT_ROW).right, safeRight)
        assertAtMost("'Lock' right edge vs side navigation bar", button("Lock").right, safeRight)

        enterSelectionMode()
        for (label in ACTIONS) {
            assertAtMost("'$label' right edge vs side navigation bar", button(label).right, safeRight)
        }
        assertAtMost("'Select none' right edge vs side navigation bar", button("Select none").right, safeRight)
        scrollToEnd(ROOT_ROWS - 1)
        assertAtMost("last row bottom vs action bar top", row(LAST_ROOT_ROW).bottom, actionBar().top)
    }

    @Test
    fun search_findsFilesInOtherFolders_andResultsStayAboveKeyboardAndActionBar() {
        show()
        val navBottom = 48.dp
        setInsets(navBottom = navBottom)

        rule.onNodeWithTag(BrowserTestTags.SEARCH_BUTTON).performClick()
        rule.onNodeWithTag(BrowserTestTags.SEARCH_FIELD).performTextInput("REPORT")
        val lastHit = "report_%02d.pdf".format(REPORTS)
        rule.waitUntilAtLeastOneExists(hasText("$SEARCH_HITS matches in this vault"), 10_000)
        // Hits come from a folder that is not the one being browsed, and say where they live.
        assertTrue(
            "hits show their folder",
            rule.onAllNodesWithText("in /Docs/Reports").fetchSemanticsNodes().isNotEmpty(),
        )
        rule.onNodeWithText("report_01.pdf").assertExists()
        rule.onNodeWithText(FIRST_ROOT_ROW).assertDoesNotExist()

        // Keyboard up: the last hit scrolls clear of it.
        val ime = 300.dp
        setInsets(navBottom = navBottom, ime = ime)
        scrollToEnd(SEARCH_HITS - 1)
        assertAtMost("last hit bottom vs keyboard top", row(lastHit).bottom, root().bottom - ime.px())

        // Keyboard down, hits selected: the action bar does not cover the last hit either.
        setInsets(navBottom = navBottom)
        rule.onNodeWithText(lastHit).performTouchInput { longClick() }
        rule.waitForIdle()
        scrollToEnd(SEARCH_HITS - 1)
        assertAtMost("last hit bottom vs action bar top", row(lastHit).bottom, actionBar().top)
        assertAtMost("'Delete' bottom vs navigation bar", button("Delete").bottom, root().bottom - navBottom.px())

        // Back leaves selection, then search, and the folder listing returns.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(BrowserTestTags.ACTION_BAR).assertDoesNotExist()
        rule.onNodeWithTag(BrowserTestTags.SEARCH_FIELD).assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntilAtLeastOneExists(hasText(FIRST_ROOT_ROW), 10_000)
        rule.onNodeWithTag(BrowserTestTags.SEARCH_FIELD).assertDoesNotExist()
    }

    @Test
    fun search_tappingAFolderHitOpensThatFolder_andNoMatchIsExplained() {
        show()
        setInsets(navBottom = 48.dp)
        rule.onNodeWithContentDescription("Search this vault").performClick()
        rule.onNodeWithTag(BrowserTestTags.SEARCH_FIELD).performTextInput("zzz-nothing")
        rule.waitUntilAtLeastOneExists(hasText("No files or folders match."), 10_000)

        rule.onNodeWithContentDescription("Clear search text").performClick()
        rule.onNodeWithTag(BrowserTestTags.SEARCH_FIELD).performTextInput("reports")
        rule.waitUntilAtLeastOneExists(hasText("1 match in this vault"), 10_000)
        rule.onNodeWithText("Reports").performClick()
        rule.waitUntilAtLeastOneExists(hasText("report_01.pdf"), 10_000)
        rule.onNodeWithTag(BrowserTestTags.SEARCH_FIELD).assertDoesNotExist()
        rule.onNodeWithText("/Docs/Reports").assertExists()
    }

    /**
     * The dialogs themselves are not driven here: an AlertDialog holding a focused text field
     * (Rename as much as New folder) never goes idle under Robolectric. Creating the folder is
     * covered by DomainLogicTest and ContainerWriteTest.
     */
    @Test
    fun addMenu_offersFilesFolderAndNewFolder() {
        show()
        setInsets(navBottom = 48.dp)
        rule.onNodeWithText("Add files").assertDoesNotExist()
        rule.onNodeWithTag(BrowserTestTags.ADD_BUTTON).performClick()
        rule.onNodeWithText("Add files").assertIsDisplayed()
        rule.onNodeWithText("Add a folder").assertIsDisplayed()
        rule.onNodeWithText("New folder").assertIsDisplayed()
    }

    /** The row is marked, fully visible between the top bar and the navigation bar, with context above it. */
    private fun assertReturnedTo(name: String, previousName: String, navBottom: Dp) {
        val marked = rule.onAllNodes(hasStateDescription(LAST_VIEWED_STATE)).fetchSemanticsNodes()
        assertEquals("exactly one row is marked as last viewed", 1, marked.size)
        rule.onNodeWithText(name).assert(hasStateDescription(LAST_VIEWED_STATE)).assertIsDisplayed()

        val list = rule.onNodeWithTag(BrowserTestTags.FILE_LIST).fetchSemanticsNode().boundsInRoot
        val target = row(name)
        assertTrue("row top ${target.top} is below the top bar ${list.top}", target.top >= list.top)
        assertAtMost("row bottom vs navigation bar", target.bottom, root().bottom - navBottom.px())
        // Not jammed against the top edge: the file before it is on screen as well.
        assertTrue("previous row is visible above it", row(previousName).top >= list.top)
    }

    @Test
    fun returningFromAViewer_scrollsToAndMarksTheFileThatWasOpen() {
        val navBottom = 48.dp
        show(lastViewedPath = "/file_035.txt", waitFor = "file_035.txt")
        setInsets(navBottom = navBottom)
        assertReturnedTo("file_035.txt", previousName = "file_034.txt", navBottom = navBottom)
        // It was far down the list: the top of the folder is no longer on screen.
        rule.onNodeWithText(FIRST_ROOT_ROW).assertDoesNotExist()
    }

    @Test
    fun returningFromAViewer_toSearchResults_restoresTheHitsAndMarksTheFile() {
        val navBottom = 48.dp
        show(
            initialSearchQuery = "report",
            lastViewedPath = "/Docs/Reports/report_25.pdf",
            waitFor = "report_25.pdf",
        )
        setInsets(navBottom = navBottom)
        rule.onNodeWithTag(BrowserTestTags.SEARCH_FIELD).assertExists()
        assertReturnedTo("report_25.pdf", previousName = "report_24.pdf", navBottom = navBottom)
    }

    @Test
    fun aLastViewedFileNearTheTop_isMarkedWithoutScrollingPastTheStart() {
        show(lastViewedPath = "/file_001.txt")
        rule.onNodeWithText("file_001.txt").assert(hasStateDescription(LAST_VIEWED_STATE)).assertIsDisplayed()
        rule.onNodeWithText(FIRST_ROOT_ROW).assertIsDisplayed()
    }

    @Test
    fun aLastViewedFileThatWasDeleted_marksNothing() {
        show(lastViewedPath = "/gone.jpg")
        rule.onNodeWithText(FIRST_ROOT_ROW).assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasStateDescription(LAST_VIEWED_STATE)).fetchSemanticsNodes().isEmpty())
    }

    private companion object {
        const val ROOT_FILES = 40
        const val REPORTS = 30
        const val ROOT_ROWS = ROOT_FILES + 1 // + the Docs folder
        const val SEARCH_HITS = REPORTS + 1 // "report" also matches the Reports folder
        const val FIRST_ROOT_ROW = "Docs"
        val LAST_ROOT_ROW = "file_%03d.txt".format(ROOT_FILES)
        val ACTIONS = listOf("Move", "Copy out", "Move out", "Rename", "Delete")
    }

    /** `/Docs/Reports/report_NN.pdf` and `/file_NNN.txt`; the only write it supports is new folders. */
    private class FakeVolume : MountedVolume {
        private val tree: MutableMap<String, List<FsNode>>

        init {
            val t = HashMap<String, List<FsNode>>()
            t["/"] = listOf(FsNode("/Docs", "Docs", true, 0L, STAMP)) +
                (1..ROOT_FILES).map { file("/", "file_%03d.txt".format(it)) }
            t["/Docs"] = listOf(FsNode("/Docs/Reports", "Reports", true, 0L, STAMP), file("/Docs", "notes.txt"))
            t["/Docs/Reports"] = (1..REPORTS).map { file("/Docs/Reports", "report_%02d.pdf".format(it)) }
            tree = t
        }

        private fun file(dir: String, name: String) = FsNode(FsPaths.join(dir, name), name, false, 123_456L, STAMP)

        override val id = "00000000layout01"
        override val label = "Layout test vault"
        override val totalBytes = 1L shl 30
        override val freeBytes = 1L shl 29
        override val writeSupport: String? = null

        override fun stat(path: String): FsNode? =
            tree[FsPaths.parent(path)]?.firstOrNull { it.path == path }

        override fun list(dir: String): List<FsNode> = tree[dir] ?: throw IOException("Folder not found: $dir")
        override fun openFile(path: String): FsFileHandle = throw IOException("File not found: $path")
        override fun createFile(
            dir: String, name: String, size: Long,
            source: (pos: Long, buf: ByteArray, off: Int, len: Int) -> Unit,
            onProgress: (Long) -> Unit,
        ): FsNode = throw IOException("read-only test volume")

        @Synchronized
        override fun createDirectory(dir: String, name: String, autoRename: Boolean): FsNode {
            val siblings = tree[dir] ?: throw IOException("Folder not found: $dir")
            if (siblings.any { it.name.equals(name, ignoreCase = true) }) {
                throw IOException("\"$name\" already exists in this folder")
            }
            val node = FsNode(FsPaths.join(dir, name), name, true, 0L, STAMP)
            tree[dir] = siblings + node
            tree[node.path] = emptyList()
            return node
        }

        override fun delete(path: String, overwrite: Boolean, onProgress: (Long) -> Unit) =
            throw IOException("read-only test volume")

        override fun rename(path: String, newName: String): String = throw IOException("read-only test volume")
        override fun move(path: String, targetDir: String): String = throw IOException("read-only test volume")
        override fun close() {}

        private companion object {
            const val STAMP = 1_700_000_000_000L
        }
    }
}

private const val PORTRAIT = "w411dp-h891dp-port-xxhdpi"
private const val LANDSCAPE = "w891dp-h411dp-land-xxhdpi"
