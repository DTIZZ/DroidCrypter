package app.droidcrypter.engine

import app.droidcrypter.browse.BrowserStateHolder
import app.droidcrypter.browse.RatingsCodec
import app.droidcrypter.browse.RatingsStore
import app.droidcrypter.browse.SortConfig
import app.droidcrypter.browse.SortCriterion
import app.droidcrypter.browse.SortDirection
import app.droidcrypter.browse.VaultSearch
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class VaultSearchTest {

    private fun sampleVolume(): InMemoryVolume {
        val vol = InMemoryVolume()
        vol.addFolder("/Photos/2024 Beach")
        vol.addFolder("/Docs/Taxes")
        vol.addFolder("/Empty")
        vol.putFile("/notes.txt", ByteArray(10))
        vol.putFile("/Beach plan.txt", ByteArray(20))
        vol.putFile("/Photos/IMG_2.jpg", ByteArray(30))
        vol.putFile("/Photos/IMG_10.jpg", ByteArray(40))
        vol.putFile("/Photos/2024 Beach/beach_sunset.JPG", ByteArray(50))
        vol.putFile("/Photos/2024 Beach/clip.mp4", ByteArray(60))
        vol.putFile("/Docs/Taxes/2024 return.pdf", ByteArray(70))
        vol.putFile("/Docs/Ünïcødé 日本語.txt", ByteArray(80))
        vol.putFile(RatingsCodec.META_PATH, "{\"version\":1,\"ratings\":{\"/Photos/IMG_10.jpg\":3}}".toByteArray())
        return vol
    }

    @Test
    fun `terms are lower-cased and split on whitespace, and a name must contain every term`() {
        assertThat(VaultSearch.terms("  Beach   2024 ")).containsExactly("beach", "2024").inOrder()
        assertThat(VaultSearch.terms(" \t ")).isEmpty()

        assertThat(VaultSearch.matches("2024 Beach trip.JPG", listOf("beach", "2024"))).isTrue()
        assertThat(VaultSearch.matches("2024 Beach trip.JPG", listOf("beach", "2023"))).isFalse()
        assertThat(VaultSearch.matches("anything", emptyList())).isFalse()
    }

    @Test
    fun `search walks the whole tree, matches files and folders case-insensitively, and never returns the ratings file`() {
        val vol = sampleVolume()

        // Shallow hits first (breadth-first), files and folders alike.
        assertThat(VaultSearch.search(vol, "BEACH").nodes.map { it.path }).containsExactly(
            "/Beach plan.txt",
            "/Photos/2024 Beach",
            "/Photos/2024 Beach/beach_sunset.JPG",
        ).inOrder()

        // Every term must match the same name.
        assertThat(VaultSearch.search(vol, "2024 pdf").nodes.map { it.path })
            .containsExactly("/Docs/Taxes/2024 return.pdf")

        // Extension and non-ASCII names work; a blank query finds nothing.
        assertThat(VaultSearch.search(vol, ".jpg").nodes.map { it.name })
            .containsExactly("IMG_2.jpg", "IMG_10.jpg", "beach_sunset.JPG")
        assertThat(VaultSearch.search(vol, "日本語").nodes.map { it.name }).containsExactly("Ünïcødé 日本語.txt")
        assertThat(VaultSearch.search(vol, "   ").nodes).isEmpty()
        assertThat(VaultSearch.search(vol, "no-such-file").nodes).isEmpty()

        // The in-container ratings file is invisible to search, as it is to the browser.
        assertThat(VaultSearch.search(vol, "veradroid").nodes).isEmpty()
        assertThat(VaultSearch.search(vol, "json").nodes).isEmpty()

        // Searching can be limited to a subtree.
        assertThat(VaultSearch.search(vol, "beach", startDir = "/Photos").nodes.map { it.path }).containsExactly(
            "/Photos/2024 Beach",
            "/Photos/2024 Beach/beach_sunset.JPG",
        )
    }

    @Test
    fun `search is bounded, skips unreadable folders, survives directory cycles and can be cancelled`() {
        val vol = InMemoryVolume()
        for (i in 1..30) vol.putFile("/dir$i/match_$i.bin", ByteArray(1))

        val capped = VaultSearch.search(vol, "match", maxResults = 10)
        assertThat(capped.nodes).hasSize(10)
        assertThat(capped.truncated).isTrue()

        val all = VaultSearch.search(vol, "match")
        assertThat(all.nodes).hasSize(30)
        assertThat(all.truncated).isFalse()

        // A folder that cannot be listed is skipped; one that loops back to itself is cut off at MAX_DEPTH.
        val hostile = object : MountedVolume by vol {
            override fun list(dir: String): List<FsNode> = when {
                dir == "/dir3" -> throw IOException("corrupt directory")
                dir.startsWith("/loop") -> listOf(FsNode("$dir/loop", "loop", true, 0L, 0L))
                dir == "/" -> vol.list(dir) + FsNode("/loop", "loop", true, 0L, 0L)
                else -> vol.list(dir)
            }
        }
        val res = VaultSearch.search(hostile, "match")
        assertThat(res.nodes.map { it.name }).doesNotContain("match_3.bin")
        assertThat(res.nodes).hasSize(29)
        assertThat(res.truncated).isTrue()

        // The root itself failing is an error, not an empty result.
        val locked = object : MountedVolume by vol {
            override fun list(dir: String): List<FsNode> = throw IOException("Volume is locked")
        }
        assertThrows<IOException> { VaultSearch.search(locked, "match") }

        // Cancellation is checked before every folder.
        var listed = 0
        assertThrows<CancellationException> {
            VaultSearch.search(vol, "match") {
                if (++listed > 5) throw CancellationException("stop")
            }
        }
    }

    private fun TestScope.holderFor(
        vol: InMemoryVolume,
        initialSearchQuery: String? = null,
    ): Pair<BrowserStateHolder, RatingsStore> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = RatingsStore(vol, ioDispatcher = dispatcher, debounceMillis = 0L, scope = backgroundScope)
        val holder = BrowserStateHolder(
            volume = vol,
            ratingsStore = store,
            ioDispatcher = dispatcher,
            sortDispatcher = dispatcher,
            scope = backgroundScope,
            initialSearchQuery = initialSearchQuery,
            searchDebounceMillis = 250L,
        )
        return holder to store
    }

    /** advanceUntilIdle skips backgroundScope work, so step the clock past the debounce instead. */
    private fun TestScope.settle() {
        advanceTimeBy(300L)
        runCurrent()
    }

    @Test
    fun `BrowserStateHolder search shows container-wide hits, debounces typing, and Back returns to the folder`() = runTest {
        val vol = sampleVolume()
        val (holder, store) = holderFor(vol)
        holder.refreshSync("/Photos")
        assertThat(holder.state.value.rows.map { it.node.name })
            .containsExactly("2024 Beach", "IMG_2.jpg", "IMG_10.jpg").inOrder()

        // Opening search keeps the folder listing until something is typed.
        holder.openSearch()
        assertThat(holder.state.value.searchActive).isTrue()
        assertThat(holder.state.value.showingSearchResults).isFalse()
        assertThat(holder.state.value.rows).hasSize(3)

        // Typing is debounced: only the final query is searched.
        holder.setSearchQuery("b")
        holder.setSearchQuery("be")
        holder.setSearchQuery("beach")
        assertThat(holder.state.value.isSearching).isTrue()
        advanceTimeBy(100L)
        runCurrent()
        assertThat(holder.state.value.rows).hasSize(3) // still the folder: debounce not elapsed
        settle()

        val hits = holder.state.value
        assertThat(hits.isSearching).isFalse()
        assertThat(hits.showingSearchResults).isTrue()
        assertThat(hits.searchTruncated).isFalse()
        assertThat(hits.currentDir).isEqualTo("/Photos") // searching does not navigate
        // Whole container, folders first, then natural name order.
        assertThat(hits.rows.map { it.node.path }).containsExactly(
            "/Photos/2024 Beach",
            "/Beach plan.txt",
            "/Photos/2024 Beach/beach_sunset.JPG",
        ).inOrder()

        // Sorting applies to hits too, and viewers page through the hits.
        holder.setSortConfig(SortConfig(SortCriterion.SIZE, SortDirection.DESCENDING))
        settle()
        assertThat(holder.state.value.rows.map { it.node.name })
            .containsExactly("2024 Beach", "beach_sunset.JPG", "Beach plan.txt").inOrder()
        assertThat(holder.imagePlaylist().map { it.name }).containsExactly("beach_sunset.JPG")

        holder.setSearchQuery("jpg")
        settle()
        assertThat(holder.imagePlaylist().map { it.path }).containsExactly(
            "/Photos/2024 Beach/beach_sunset.JPG",
            "/Photos/IMG_10.jpg",
            "/Photos/IMG_2.jpg",
        ).inOrder()
        // Ratings follow the file wherever it lives.
        assertThat(holder.state.value.rows.first { it.node.name == "IMG_10.jpg" }.displayedRating).isEqualTo(3)

        // No match -> empty rows; clearing the box shows the folder again but keeps search open.
        holder.setSearchQuery("zzz")
        settle()
        assertThat(holder.state.value.rows).isEmpty()
        holder.setSearchQuery("")
        settle()
        assertThat(holder.state.value.searchActive).isTrue()
        assertThat(holder.state.value.rows.map { it.node.name })
            .containsExactly("2024 Beach", "IMG_10.jpg", "IMG_2.jpg").inOrder()

        // Back: selection first, then search, then the parent folder.
        holder.setSearchQuery("clip")
        settle()
        holder.onLongPress("/Photos/2024 Beach/clip.mp4")
        assertThat(holder.onBackPressed()).isTrue()
        assertThat(holder.state.value.selectionMode).isFalse()
        assertThat(holder.state.value.searchActive).isTrue()
        assertThat(holder.onBackPressed()).isTrue()
        settle()
        assertThat(holder.state.value.searchActive).isFalse()
        assertThat(holder.state.value.searchQuery).isEmpty()
        assertThat(holder.state.value.currentDir).isEqualTo("/Photos")
        assertThat(holder.state.value.rows).hasSize(3)
        assertThat(holder.onBackPressed()).isTrue()
        settle()
        assertThat(holder.state.value.currentDir).isEqualTo("/")

        store.close()
    }

    @Test
    fun `search hits can be selected and changed, a refresh re-runs the search, and a folder hit opens that folder`() = runTest {
        val vol = sampleVolume()
        val (holder, store) = holderFor(vol)
        holder.refreshSync("/")
        holder.openSearch()
        holder.setSearchQuery("img")
        settle()
        assertThat(holder.state.value.rows.map { it.node.name }).containsExactly("IMG_2.jpg", "IMG_10.jpg").inOrder()

        // Select all selects the hits (in another folder) and they can be deleted from here.
        holder.selectAll()
        assertThat(holder.state.value.selectedPaths).containsExactly("/Photos/IMG_2.jpg", "/Photos/IMG_10.jpg")
        assertThat(holder.state.value.canMutateSelected).isTrue()
        val del = holder.deleteItems(listOf("/Photos/IMG_2.jpg"), overwrite = false)
        assertThat(del.succeeded).isEqualTo(1)
        val afterDelete = holder.state.value
        assertThat(afterDelete.showingSearchResults).isTrue()
        assertThat(afterDelete.rows.map { it.node.name }).containsExactly("IMG_10.jpg")
        assertThat(afterDelete.selectionMode).isFalse()

        // Renaming a hit so it no longer matches removes it; its rating moves with it.
        holder.renameItem("/Photos/IMG_10.jpg", "sunrise.jpg")
        assertThat(holder.state.value.rows).isEmpty()
        assertThat(store.displayedRating("/Photos/sunrise.jpg")).isEqualTo(3)

        // A change made elsewhere shows up on the next refresh.
        vol.putFile("/Docs/img_scan.png", ByteArray(5))
        holder.refreshSync()
        assertThat(holder.state.value.rows.map { it.node.path }).containsExactly("/Docs/img_scan.png")

        // Tapping a folder hit leaves search and lists that folder.
        holder.setSearchQuery("taxes")
        settle()
        assertThat(holder.state.value.rows.single().node.path).isEqualTo("/Docs/Taxes")
        holder.openFolderFromSearch("/Docs/Taxes")
        settle()
        val opened = holder.state.value
        assertThat(opened.searchActive).isFalse()
        assertThat(opened.currentDir).isEqualTo("/Docs/Taxes")
        assertThat(opened.rows.map { it.node.name }).containsExactly("2024 return.pdf")

        store.close()
    }

    @Test
    fun `an initial search query is restored on the first load, and a locked volume reports an error`() = runTest {
        val vol = sampleVolume()
        val (holder, store) = holderFor(vol, initialSearchQuery = "return")
        assertThat(holder.state.value.searchActive).isTrue()
        holder.refreshSync("/Photos")
        val restored = holder.state.value
        assertThat(restored.currentDir).isEqualTo("/Photos")
        assertThat(restored.isSearching).isFalse()
        assertThat(restored.rows.map { it.node.path }).containsExactly("/Docs/Taxes/2024 return.pdf")

        // Closing search falls back to the folder that was loaded underneath.
        holder.closeSearch()
        settle()
        assertThat(holder.state.value.rows.map { it.node.name })
            .containsExactly("2024 Beach", "IMG_2.jpg", "IMG_10.jpg").inOrder()

        // Searching a volume that has just been locked surfaces an error instead of hanging.
        holder.openSearch()
        vol.close()
        holder.setSearchQuery("beach")
        settle()
        assertThat(holder.state.value.isSearching).isFalse()
        assertThat(holder.state.value.error).isNotNull()

        store.close()
    }
}
