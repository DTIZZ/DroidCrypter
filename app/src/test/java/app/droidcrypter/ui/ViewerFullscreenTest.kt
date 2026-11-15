package app.droidcrypter.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.droidcrypter.engine.FsFileHandle
import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.MountedVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Drives the real [PhotoViewerScreen] on the host: full screen can be entered and left from the
 * viewer, it removes the top and bottom bars, and Previous / Next stay available while it is on —
 * in portrait and in landscape.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xxhdpi")
class ViewerFullscreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var closedWith: String? = null

    private fun show() {
        rule.setContent {
            DroidCrypterTheme {
                PhotoViewerScreen(
                    volume = NoFilesVolume,
                    images = IMAGES,
                    initialIndex = 0,
                    showStars = true,
                    onClose = { closedWith = it },
                )
            }
        }
        rule.waitUntilAtLeastOneExists(hasContentDescription("Enter full screen"), 10_000)
        rule.waitForIdle()
    }

    private fun checkFullscreenKeepsNavigation() {
        show()
        rule.onNodeWithContentDescription("Close photo viewer").assertIsDisplayed()
        rule.onNodeWithText("Next ▶").assertIsDisplayed()

        rule.onNodeWithContentDescription("Enter full screen").performClick()
        rule.waitForIdle()

        // The app bars are gone; only the overlay strip is left.
        rule.onNodeWithContentDescription("Close photo viewer").assertDoesNotExist()
        rule.onNodeWithText("Next ▶").assertDoesNotExist()
        rule.onNodeWithContentDescription("Enter full screen").assertDoesNotExist()
        rule.onNodeWithText("1 / 3").assertIsDisplayed()
        rule.onNodeWithContentDescription("Previous image").assertIsNotEnabled()

        // The strip spans the screen and its buttons are inside it.
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val exit = rule.onNodeWithContentDescription("Exit full screen").fetchSemanticsNode().boundsInRoot
        val next = rule.onNodeWithContentDescription("Next image").fetchSemanticsNode().boundsInRoot
        assertTrue("exit button on screen: $exit in $root", exit.left >= root.left && exit.top >= root.top)
        assertTrue("next button on screen: $next in $root", next.right <= root.right && next.bottom <= root.bottom)

        rule.onNodeWithContentDescription("Next image").performClick()
        rule.waitUntilAtLeastOneExists(hasText("2 / 3"), 10_000)
        rule.onNodeWithContentDescription("Next image").performClick()
        rule.waitUntilAtLeastOneExists(hasText("3 / 3"), 10_000)
        rule.onNodeWithContentDescription("Next image").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Previous image").performClick()
        rule.waitUntilAtLeastOneExists(hasText("2 / 3"), 10_000)

        // Back leaves full screen first, without closing the viewer, and keeps the image.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Exit full screen").assertDoesNotExist()
        rule.onNodeWithContentDescription("Close photo viewer").assertIsDisplayed()
        rule.onNodeWithText("2 / 3").assertIsDisplayed()
        assertEquals(null, closedWith)

        // The button in the overlay strip leaves it too.
        rule.onNodeWithContentDescription("Enter full screen").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Exit full screen").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Close photo viewer").assertIsDisplayed()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals("/b.png", closedWith)
    }

    @Test
    fun fullscreenKeepsPreviousAndNext_portrait() = checkFullscreenKeepsNavigation()

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun fullscreenKeepsPreviousAndNext_landscape() = checkFullscreenKeepsNavigation()

    private companion object {
        val IMAGES = listOf("a.png", "b.png", "c.png").map { FsNode("/$it", it, false, 1_000L, 1_700_000_000_000L) }
    }

    /** Every read fails, so each page shows its error text; the chrome around it is what is tested. */
    private object NoFilesVolume : MountedVolume {
        override val id = "00000000fullscr1"
        override val label = "Full screen test vault"
        override val totalBytes = 1L shl 30
        override val freeBytes = 1L shl 29
        override val writeSupport: String? = null

        override fun stat(path: String): FsNode? = null
        override fun list(dir: String): List<FsNode> = emptyList()
        override fun openFile(path: String): FsFileHandle = throw IOException("File not found: $path")
        override fun createFile(
            dir: String, name: String, size: Long,
            source: (pos: Long, buf: ByteArray, off: Int, len: Int) -> Unit,
            onProgress: (Long) -> Unit,
        ): FsNode = throw IOException("read-only test volume")

        override fun createDirectory(dir: String, name: String, autoRename: Boolean): FsNode =
            throw IOException("read-only test volume")

        override fun delete(path: String, overwrite: Boolean, onProgress: (Long) -> Unit) =
            throw IOException("read-only test volume")

        override fun rename(path: String, newName: String): String = throw IOException("read-only test volume")
        override fun move(path: String, targetDir: String): String = throw IOException("read-only test volume")
        override fun close() {}
    }
}
