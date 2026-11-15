package app.droidcrypter.engine

import app.droidcrypter.browse.BrowserSort
import app.droidcrypter.browse.BrowserStateHolder
import app.droidcrypter.browse.RatingsCodec
import app.droidcrypter.browse.RatingsStore
import app.droidcrypter.browse.SortConfig
import app.droidcrypter.browse.SortCriterion
import app.droidcrypter.browse.SortDirection
import app.droidcrypter.editor.EditDecisionList
import app.droidcrypter.editor.EdlHistory
import app.droidcrypter.editor.EncryptedScratch
import app.droidcrypter.editor.TimelineMath
import app.droidcrypter.editor.UsRange
import app.droidcrypter.engine.vc.DroidCrypter
import app.droidcrypter.provider.VolumeUris
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class DomainLogicTest {

    @TempDir
    lateinit var tempDir: Path

    // ---- 1. Sorting tests (section 7.2) ----

    @Test
    fun `SortConfig encoding, decoding and fallback to Name ascending`() {
        val cfg = SortConfig(SortCriterion.DATE, SortDirection.DESCENDING)
        assertThat(cfg.encode()).isEqualTo("DATE:DESCENDING")
        assertThat(SortConfig.decode("DATE:DESCENDING")).isEqualTo(cfg)
        assertThat(SortConfig.decode(null)).isEqualTo(SortConfig.DEFAULT)
        assertThat(SortConfig.decode("")).isEqualTo(SortConfig.DEFAULT)
        assertThat(SortConfig.decode("INVALID:VALUE")).isEqualTo(SortConfig.DEFAULT)
        assertThat(SortConfig.decode("NAME:UNKNOWN")).isEqualTo(SortConfig.DEFAULT)

        // Selecting active criterion flips direction; selecting another keeps direction.
        val flipped = cfg.select(SortCriterion.DATE)
        assertThat(flipped).isEqualTo(SortConfig(SortCriterion.DATE, SortDirection.ASCENDING))
        val switched = flipped.select(SortCriterion.SIZE)
        assertThat(switched).isEqualTo(SortConfig(SortCriterion.SIZE, SortDirection.ASCENDING))
    }

    @Test
    fun `every sort criterion x direction puts folders first, handles natural order, unknown dates, zero-byte files, and hidden stars`() {
        val nodes = listOf(
            FsNode("/IMG_10.jpg", "IMG_10.jpg", false, 500L, 2_000L),
            FsNode("/IMG_2.jpg", "IMG_2.jpg", false, 0L, 0L), // unknown date (0), zero-byte
            FsNode("/img_1.jpg", "img_1.jpg", false, 1_200L, 3_000L),
            FsNode("/ IMG_02.jpg", "IMG_02.jpg", false, 100L, -5L), // unknown date (<0)
            FsNode("/FolderB", "FolderB", true, 0L, 1_000L),
            FsNode("/FolderA", "FolderA", true, 0L, 5_000L),
        )
        val ratings = mapOf(
            "/IMG_10.jpg" to 1,
            "/img_1.jpg" to 3,
        )

        // Name ascending: folders first (FolderA, FolderB), then natural order (img_1, IMG_2, IMG_02, IMG_10)
        val nameAsc = BrowserSort.sort(nodes, SortConfig(SortCriterion.NAME, SortDirection.ASCENDING))
        assertThat(nameAsc.map { it.name })
            .containsExactly("FolderA", "FolderB", "img_1.jpg", "IMG_2.jpg", "IMG_02.jpg", "IMG_10.jpg")
            .inOrder()

        // Name descending: folders still first (FolderB, FolderA), then files reversed
        val nameDesc = BrowserSort.sort(nodes, SortConfig(SortCriterion.NAME, SortDirection.DESCENDING))
        assertThat(nameDesc.map { it.name })
            .containsExactly("FolderB", "FolderA", "IMG_10.jpg", "IMG_02.jpg", "IMG_2.jpg", "img_1.jpg")
            .inOrder()

        // Date ascending: unknown dates (<= 0) ALWAYS last in both directions!
        val dateAsc = BrowserSort.sort(nodes, SortConfig(SortCriterion.DATE, SortDirection.ASCENDING))
        assertThat(dateAsc.map { it.name })
            .containsExactly("FolderB", "FolderA", "IMG_10.jpg", "img_1.jpg", "IMG_2.jpg", "IMG_02.jpg")
            .inOrder()

        // Date descending: unknown dates (<= 0) STILL last!
        val dateDesc = BrowserSort.sort(nodes, SortConfig(SortCriterion.DATE, SortDirection.DESCENDING))
        assertThat(dateDesc.map { it.name })
            .containsExactly("FolderA", "FolderB", "img_1.jpg", "IMG_10.jpg", "IMG_2.jpg", "IMG_02.jpg")
            .inOrder()

        // Size ascending & descending (folders ordered by name in chosen direction, zero-byte file first in asc)
        val sizeAsc = BrowserSort.sort(nodes, SortConfig(SortCriterion.SIZE, SortDirection.ASCENDING))
        assertThat(sizeAsc.map { it.name })
            .containsExactly("FolderA", "FolderB", "IMG_2.jpg", "IMG_02.jpg", "IMG_10.jpg", "img_1.jpg")
            .inOrder()
        val sizeDesc = BrowserSort.sort(nodes, SortConfig(SortCriterion.SIZE, SortDirection.DESCENDING))
        assertThat(sizeDesc.map { it.name })
            .containsExactly("FolderB", "FolderA", "img_1.jpg", "IMG_10.jpg", "IMG_02.jpg", "IMG_2.jpg")
            .inOrder()

        // Stars descending: folders by name, then best-rated first (3 -> 1 -> 0 tied by natural name)
        val starsDesc = BrowserSort.sort(
            nodes,
            SortConfig(SortCriterion.STARS, SortDirection.DESCENDING),
            showStars = true,
            displayedRating = { ratings[it] ?: 0 },
        )
        assertThat(starsDesc.map { it.name })
            .containsExactly("FolderA", "FolderB", "img_1.jpg", "IMG_10.jpg", "IMG_2.jpg", "IMG_02.jpg")
            .inOrder()

        // When showStars = false, saved STARS sort behaves as NAME (same direction) while preserving saved config
        val starsHiddenDesc = BrowserSort.sort(
            nodes,
            SortConfig(SortCriterion.STARS, SortDirection.DESCENDING),
            showStars = false,
            displayedRating = { ratings[it] ?: 0 },
        )
        assertThat(starsHiddenDesc.map { it.name }).isEqualTo(nameDesc.map { it.name })
    }

    // ---- 2. Ratings JSON codec & RatingsStore tests (section 7.3) ----

    @Test
    fun `RatingsCodec skips unknown keys, drops out-of-range values, rejects torn JSON and enforces 4 MiB limit`() {
        val json = """
            {
              "version": 1,
              "extra_field": {"nested": [1, 2, true, false, null, "skip"]},
              "ratings": {
                "/Photos/a.jpg": 3,
                "/Photos/legacy5.jpg": 5,
                "/Photos/legacy4.jpg": 4,
                "/Photos/zero.jpg": 0,
                "/Photos/too_high.jpg": 6,
                "/Photos/negative.jpg": -1,
                "relative/path.jpg": 2,
                "/Photos/../traversal.jpg": 2
              }
            }
        """.trimIndent().toByteArray()

        val decoded = RatingsCodec.decode(json)!!
        assertThat(decoded).containsExactly(
            "/Photos/a.jpg", 3,
            "/Photos/legacy5.jpg", 5,
            "/Photos/legacy4.jpg", 4,
        )
        assertThat(RatingsCodec.displayedStars(decoded["/Photos/legacy5.jpg"])).isEqualTo(3)
        assertThat(RatingsCodec.displayedStars(decoded["/Photos/legacy4.jpg"])).isEqualTo(3)
        assertThat(RatingsCodec.displayedStars(decoded["/Photos/a.jpg"])).isEqualTo(3)
        assertThat(RatingsCodec.displayedStars(null)).isEqualTo(0)

        // Round-trip encode -> decode
        val reEncoded = RatingsCodec.encode(decoded)
        assertThat(RatingsCodec.decode(reEncoded)).isEqualTo(decoded)

        // Torn / incomplete JSON documents are rejected (null)
        assertThat(RatingsCodec.decode("{\"version\":1,\"ratings\":{\"/a.jpg\":3}".toByteArray())).isNull()
        assertThat(RatingsCodec.decode(ByteArray(0))).isNull()
        assertThat(RatingsCodec.decode(ByteArray(RatingsCodec.MAX_META_BYTES + 1))).isNull()
    }

    @Test
    fun `RatingsStore toggle, 3-star display vs stored 4-5, debounce, rename, delete, prune, torn-save recovery, and read-only refusal`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vol = InMemoryVolume()
        vol.putFile("/Photos/pic1.jpg", ByteArray(10))
        vol.putFile("/Photos/legacy5.jpg", ByteArray(10))
        vol.putFile("/Photos/keep.jpg", ByteArray(10))

        // Simulate a torn primary .veradroid_meta.json and a complete .veradroid_meta.json.new:
        vol.putFile(RatingsCodec.META_PATH, "{\"version\":1,\"ratings\":{\"/Photos/pic1.jpg\":".toByteArray())
        vol.putFile(
            RatingsCodec.META_NEW_PATH,
            RatingsCodec.encode(mapOf("/Photos/legacy5.jpg" to 5, "/Photos/ghost.jpg" to 2)),
        )

        val store = RatingsStore(vol, ioDispatcher = dispatcher, debounceMillis = 400L, scope = this)
        // Replay test: mutate BEFORE ensureLoadedSync runs
        store.toggleStar("/Photos/pic1.jpg", 2)
        store.ensureLoadedSync()

        // Loaded from valid .new backup AND replayed the pre-load mutation!
        assertThat(store.storedRating("/Photos/legacy5.jpg")).isEqualTo(5)
        assertThat(store.displayedRating("/Photos/legacy5.jpg")).isEqualTo(3)
        assertThat(store.displayedRating("/Photos/pic1.jpg")).isEqualTo(2)

        // Tapping star 3 on a stored 5 clears it (toggles on displayed value 3); tapping star 2 on stored 5 sets 2.
        store.toggleStar("/Photos/legacy5.jpg", 3)
        assertThat(store.storedRating("/Photos/legacy5.jpg")).isEqualTo(0)
        assertThat(store.displayedRating("/Photos/legacy5.jpg")).isEqualTo(0)

        // Rapid taps within 400 ms debounce window coalesce into one disk write after 400 ms.
        store.toggleStar("/Photos/pic1.jpg", 1)
        store.toggleStar("/Photos/pic1.jpg", 3)
        advanceTimeBy(200)
        // Before 400 ms, primary file still has the old torn content.
        advanceTimeBy(300)
        advanceUntilIdle()

        // Now primary .veradroid_meta.json is valid and .new is gone.
        assertThat(vol.stat(RatingsCodec.META_NEW_PATH)).isNull()
        val savedMap = RatingsCodec.decode(TestData.readAll(vol, RatingsCodec.META_PATH))!!
        assertThat(savedMap["/Photos/pic1.jpg"]).isEqualTo(3)

        // Rename / move updates paths (including folder subtrees).
        store.onPathMovedOrRenamed("/Photos", "/Gallery")
        assertThat(store.displayedRating("/Photos/pic1.jpg")).isEqualTo(0)
        assertThat(store.displayedRating("/Gallery/pic1.jpg")).isEqualTo(3)

        // Prune drops ratings of missing children when a folder is listed.
        assertThat(store.storedRating("/Gallery/ghost.jpg")).isEqualTo(2)
        val presentInGallery = listOf(FsNode("/Gallery/pic1.jpg", "pic1.jpg", false, 10L, 100L))
        store.pruneMissingChildren("/Gallery", presentInGallery)
        assertThat(store.storedRating("/Gallery/ghost.jpg")).isEqualTo(0)
        assertThat(store.displayedRating("/Gallery/pic1.jpg")).isEqualTo(3)

        // Delete drops ratings.
        store.onPathDeleted("/Gallery")
        assertThat(store.displayedRating("/Gallery/pic1.jpg")).isEqualTo(0)

        // Read-only refusal.
        vol.readOnlyReason = "This volume is read-only"
        assertThrows<IOException> { store.toggleStar("/Gallery/pic1.jpg", 2) }
        store.close()
    }

    @ParameterizedTest
    @ValueSource(strings = ["aes_sha512_fat", "aes_sha512_exfat"])
    fun `ratings survive lock and unlock of real FAT and exFAT containers via VolumeRegistry before-close hook`(
        fixtureName: String,
    ) {
        val f = TestData.fixture(fixtureName)
        assumeTrue(f.file.isFile)
        val dst = tempDir.resolve("${fixtureName}_ratings.hc").toFile()
        f.file.copyTo(dst, overwrite = true)

        // 1. Unlock container, register in VolumeRegistry, rate two files, and immediately unmount WITHOUT waiting for debounce!
        val dev1 = FileChannelBlockDevice.open(dst, true)
        val vol1 = DroidCrypter.open(dev1, f.password.toByteArray(), f.pim, f.prf)
        VolumeRegistry.mount(vol1)
        val store1 = RatingsStore(vol1, debounceMillis = 60_000L) // huge debounce so only before-close hook saves
        store1.ensureLoadedSync()
        store1.toggleStar("/small.txt", 3)
        store1.toggleStar("/nested/top.txt", 2)

        // Unmounting triggers the VolumeRegistry before-close hook while vol1 is still open and writable.
        assertThat(VolumeRegistry.unmount(vol1.id)).isTrue()
        assertThat(vol1.isClosed).isTrue()

        // 2. Re-unlock the real container and verify ratings persisted inside /.veradroid_meta.json.
        val dev2 = FileChannelBlockDevice.open(dst, false)
        DroidCrypter.open(dev2, f.password.toByteArray(), f.pim, f.prf).use { vol2 ->
            val store2 = RatingsStore(vol2)
            store2.ensureLoadedSync()
            assertThat(store2.displayedRating("/small.txt")).isEqualTo(3)
            assertThat(store2.displayedRating("/nested/top.txt")).isEqualTo(2)
            assertThat(store2.displayedRating("/empty.dat")).isEqualTo(0)
            store2.close()
        }
    }

    // ---- 3. VolumeSpace & BrowserStateHolder tests (sections 7.1, 7.5, 7.13) ----

    @Test
    fun `VolumeSpace maths usedBytes, usedFraction, 10 percent low-space threshold, total 0, and free greater than total`() {
        val normal = VolumeSpace(totalBytes = 1024L * 1024 * 1024, freeBytes = 512L * 1024 * 1024)
        assertThat(normal.usedBytes).isEqualTo(512L * 1024 * 1024)
        assertThat(normal.usedFraction).isWithin(0.001f).of(0.5f)
        assertThat(normal.isLow).isFalse()
        assertThat(normal.browserSummaryText()).isEqualTo("512.0 MB free of 1.00 GB")
        assertThat(normal.vaultSummaryText()).isEqualTo("512.0 MB free of 1.00 GB · 50% used")

        // Exactly 10% free is NOT low (free * 10 < total is false); under 10% IS low.
        assertThat(VolumeSpace(1000L, 100L).isLow).isFalse()
        assertThat(VolumeSpace(1000L, 99L).isLow).isTrue()

        // Total = 0 -> not low, fraction = 0
        val zeroTotal = VolumeSpace(0L, 0L)
        assertThat(zeroTotal.usedFraction).isEqualTo(0f)
        assertThat(zeroTotal.isLow).isFalse()

        // Free > total clamped by VolumeSpace.of(v)
        val fake = InMemoryVolume(totalBytes = 1000L, currentFreeBytes = 2000L)
        val clamped = VolumeSpace.of(fake)!!
        assertThat(clamped.freeBytes).isEqualTo(1000L)
        assertThat(clamped.usedBytes).isEqualTo(0L)
        assertThat(clamped.usedFraction).isEqualTo(0f)

        fake.close()
        assertThat(VolumeSpace.of(fake)).isNull()
    }

    @Test
    fun `BrowserStateHolder createFolder adds the folder to the current listing and explains refusals`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vol = InMemoryVolume()
        vol.addFolder("/Docs")
        vol.putFile("/Docs/notes.txt", ByteArray(10))
        val store = RatingsStore(vol, ioDispatcher = dispatcher, debounceMillis = 0L, scope = backgroundScope)
        val holder = BrowserStateHolder(
            volume = vol,
            ratingsStore = store,
            ioDispatcher = dispatcher,
            sortDispatcher = dispatcher,
            scope = backgroundScope,
        )
        holder.refreshSync("/Docs")

        assertThat(holder.createFolder("Holiday 2026")).isEqualTo("/Docs/Holiday 2026")
        assertThat(holder.state.value.rows.map { it.node.name }).containsExactly("Holiday 2026", "notes.txt").inOrder()
        assertThat(vol.stat("/Docs/Holiday 2026")!!.isDirectory).isTrue()
        assertThat(vol.list("/Docs/Holiday 2026")).isEmpty()

        // Taken names (folder or file, any case), invalid names and reserved names are refused with a reason.
        assertThat(assertThrows<IOException> { holder.createFolder("holiday 2026") }.message)
            .isEqualTo("\"holiday 2026\" already exists in this folder")
        assertThrows<IOException> { holder.createFolder("NOTES.TXT") }
        assertThrows<IOException> { holder.createFolder("a/b") }
        assertThrows<IOException> { holder.createFolder("   ") }
        assertThrows<IOException> { holder.createFolder(RatingsCodec.META_PATH.removePrefix("/")) }
        assertThat(vol.list("/Docs")).hasSize(2)

        // Numbering on request, as the folder import uses it.
        assertThat(vol.createDirectory("/Docs", "Holiday 2026", autoRename = true).name).isEqualTo("Holiday 2026 (2)")

        vol.readOnlyReason = "This volume is read-only"
        assertThat(assertThrows<IOException> { holder.createFolder("Nope") }.message).isEqualTo("This volume is read-only")
    }

    @Test
    fun `BrowserStateHolder exposes space, updates on refresh, hides meta files, manages selection, and moves ratings`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vol = InMemoryVolume(
            totalBytes = 1024L * 1024 * 1024,
            currentFreeBytes = 512L * 1024 * 1024,
        )
        vol.addFolder("/Docs")
        vol.addFolder("/Archive")
        vol.putFile("/photo_10.jpg", ByteArray(4096))
        vol.putFile("/photo_2.jpg", ByteArray(4096))
        vol.putFile("/clip.mkv", ByteArray(4096))
        vol.putFile(RatingsCodec.META_PATH, "{\"version\":1,\"ratings\":{\"/photo_2.jpg\":3}}".toByteArray())

        val store = RatingsStore(vol, ioDispatcher = dispatcher, debounceMillis = 0L, scope = backgroundScope)
        val holder = BrowserStateHolder(
            volume = vol,
            ratingsStore = store,
            ioDispatcher = dispatcher,
            sortDispatcher = dispatcher,
            scope = backgroundScope,
        )

        holder.refreshSync("/")
        val s1 = holder.state.value
        // .veradroid_meta.json is hidden; folders first, natural sort order!
        assertThat(s1.rows.map { it.node.name })
            .containsExactly("Archive", "Docs", "clip.mkv", "photo_2.jpg", "photo_10.jpg")
            .inOrder()
        assertThat(s1.totalBytes).isEqualTo(1024L * 1024 * 1024)
        assertThat(s1.freeBytes).isEqualTo(vol.freeBytes)

        // Photo pager and media playlist follow effective browser order.
        assertThat(holder.imagePlaylist().map { it.name }).containsExactly("photo_2.jpg", "photo_10.jpg").inOrder()
        assertThat(holder.mediaPlaylist().map { it.name }).containsExactly("clip.mkv")

        // Long-press enters selection mode; tap adds/removes; stars cannot be changed while selecting.
        holder.onLongPress("/photo_2.jpg")
        assertThat(holder.state.value.selectionMode).isTrue()
        assertThat(holder.state.value.selectedPaths).containsExactly("/photo_2.jpg")
        assertThat(holder.state.value.canRenameSelected).isTrue()

        // Try toggling star while in selection mode -> ignored
        holder.toggleStar("/photo_2.jpg", 3)
        assertThat(store.displayedRating("/photo_2.jpg")).isEqualTo(3)

        // Tap another item in selection mode -> 2 selected, Rename disabled
        assertThat(holder.onTapInSelectionMode("/Docs")).isTrue()
        assertThat(holder.state.value.selectedPaths).containsExactly("/photo_2.jpg", "/Docs")
        assertThat(holder.state.value.canRenameSelected).isFalse()

        // Selected folder (/Docs) is NOT offered as a move destination!
        val moveTargets = holder.listMoveDestinations("/")
        assertThat(moveTargets.map { it.name }).containsExactly("Archive")

        // Select all & Back exits selection first without leaving the folder.
        holder.selectAll()
        assertThat(holder.state.value.selectedPaths).hasSize(5)
        assertThat(holder.onBackPressed()).isTrue()
        assertThat(holder.state.value.selectionMode).isFalse()
        assertThat(holder.state.value.selectedPaths).isEmpty()

        // If a selected item disappears on disk, refresh drops it from selection.
        holder.onLongPress("/photo_10.jpg")
        holder.onTapInSelectionMode("/photo_2.jpg")
        vol.delete("/photo_10.jpg", overwrite = false)
        holder.refreshSync("/")
        assertThat(holder.state.value.selectedPaths).containsExactly("/photo_2.jpg")

        // Moving items via BrowserStateHolder moves their star ratings too and refreshes space.
        val moveRes = holder.moveItems(listOf("/photo_2.jpg"), "/Archive")
        assertThat(moveRes.succeeded).isEqualTo(1)
        assertThat(moveRes.failures).isEmpty()
        assertThat(store.displayedRating("/photo_2.jpg")).isEqualTo(0)
        assertThat(store.displayedRating("/Archive/photo_2.jpg")).isEqualTo(3)

        store.close()
    }

    // ---- 4. Document-ID & Media-URI parsing tests (sections 7.10, 7.12) ----

    @Test
    fun `VolumeUris parses valid document IDs and media URIs and rejects path traversal`() {
        val docId = VolumeUris.formatDocumentId("0123456789abcdef", "/Photos/Summer 2026/IMG_01.jpg")
        assertThat(docId).isEqualTo("0123456789abcdef:/Photos/Summer 2026/IMG_01.jpg")
        val parsedDoc = VolumeUris.parseDocumentId(docId)
        assertThat(parsedDoc.volumeId).isEqualTo("0123456789abcdef")
        assertThat(parsedDoc.path).isEqualTo("/Photos/Summer 2026/IMG_01.jpg")

        // Reject traversal, relative paths, double slashes, and invalid volume IDs
        assertThrows<IOException> { VolumeUris.parseDocumentId("0123456789abcdef:relative/path.jpg") }
        assertThrows<IOException> { VolumeUris.parseDocumentId("0123456789abcdef:/Photos/../secret.jpg") }
        assertThrows<IOException> { VolumeUris.parseDocumentId("0123456789abcdef:/Photos/./a.jpg") }
        assertThrows<IOException> { VolumeUris.parseDocumentId("0123456789abcdef://Photos/a.jpg") }
        assertThrows<IOException> { VolumeUris.parseDocumentId(":/Photos/a.jpg") }

        // Media3 URI round-trip with spaces, Unicode, plus signs, and special characters
        val trickyPath = "/Videos/Ünïcødé + 100% Fun #1/clip (2).mkv"
        val mediaUri = VolumeUris.formatMediaUri("fedcba9876543210", trickyPath)
        val parsedMedia = VolumeUris.parseMediaUri(mediaUri)
        assertThat(parsedMedia.volumeId).isEqualTo("fedcba9876543210")
        assertThat(parsedMedia.path).isEqualTo(trickyPath)

        // Reject traversal in media URIs (both plain and URL-encoded)
        assertThrows<IOException> { VolumeUris.parseMediaUri("droidcrypter://fedcba9876543210/a/../b.mp4") }
        assertThrows<IOException> { VolumeUris.parseMediaUri("droidcrypter://fedcba9876543210/a/%2e%2e/b.mp4") }
        assertThrows<IOException> { VolumeUris.parseMediaUri("http://fedcba9876543210/a.mp4") }
    }

    // ---- 5. EncryptedScratch tests (section 8.3) ----

    @Test
    fun `EncryptedScratch supports random writes, zero-filled gaps, truncation and SeekableByteChannel without plaintext on disk`() {
        EncryptedScratch(tempDir.toFile()).use { scratch ->
            val part1 = "FIRST_SECRET_CHUNK_DATA_11111111".toByteArray()
            val part2 = "SECOND_SECRET_CHUNK_DATA_2222222".toByteArray()

            // Write at offset 100, then jump past a multi-unit gap to offset 2200.
            scratch.writeAt(100L, part1, 0, part1.size)
            scratch.writeAt(2200L, part2, 0, part2.size)
            assertThat(scratch.logicalSize).isEqualTo(2200L + part2.size)

            // Read across the whole range: 0..99 is zeros, 100..131 is part1, 132..2199 is zeros, 2200..2231 is part2.
            val readAll = ByteArray(scratch.logicalSize.toInt())
            assertThat(scratch.readAt(0L, readAll, 0, readAll.size)).isEqualTo(readAll.size)
            assertThat(readAll.copyOfRange(0, 100)).isEqualTo(ByteArray(100))
            assertThat(readAll.copyOfRange(100, 100 + part1.size)).isEqualTo(part1)
            assertThat(readAll.copyOfRange(100 + part1.size, 2200)).isEqualTo(ByteArray(2200 - (100 + part1.size)))
            assertThat(readAll.copyOfRange(2200, 2200 + part2.size)).isEqualTo(part2)

            // Truncate down to 600 bytes, then grow to 1200 bytes: bytes >= 600 must now read as zeros!
            scratch.truncate(600L)
            assertThat(scratch.logicalSize).isEqualTo(600L)
            scratch.truncate(1200L)
            val tail = ByteArray(600)
            assertThat(scratch.readAt(600L, tail, 0, 600)).isEqualTo(600)
            assertThat(tail).isEqualTo(ByteArray(600))

            // SeekableByteChannel interface works for Mp4Muxer random seeks.
            scratch.asChannel().use { ch ->
                ch.position(100L)
                val bb = ByteBuffer.allocate(part1.size)
                assertThat(ch.read(bb)).isEqualTo(part1.size)
                assertThat(bb.array()).isEqualTo(part1)
            }
        }
    }

    // ---- 6. EditDecisionList, EdlHistory & TimelineMath tests (section 8.1, 8.2) ----

    @Test
    fun `EditDecisionList normalizes, merges overlapping cuts, computes retained ranges and supports undo-redo history`() {
        val base = EditDecisionList(sourceDurationUs = 10_000_000L) // 10 s
        val history = EdlHistory(base)
        assertThat(base.retainedRanges).containsExactly(UsRange(0L, 10_000_000L))
        assertThat(base.outputDurationUs).isEqualTo(10_000_000L)

        // Add cut [2s, 4s) and [3s, 6s) -> merged to [2s, 6s)
        val edl1 = history.push(base.addCut(2_000_000L, 4_000_000L))
        val edl2 = history.push(edl1.addCut(3_000_000L, 6_000_000L))
        assertThat(edl2.removedRanges).containsExactly(UsRange(2_000_000L, 6_000_000L))
        assertThat(edl2.retainedRanges).containsExactly(
            UsRange(0L, 2_000_000L),
            UsRange(6_000_000L, 10_000_000L),
        ).inOrder()
        assertThat(edl2.removedDurationUs).isEqualTo(4_000_000L)
        assertThat(edl2.outputDurationUs).isEqualTo(6_000_000L)
        assertThat(edl2.skipRemovedAt(2_500_000L)).isEqualTo(6_000_000L)

        // Undo & Redo
        assertThat(history.undo()).isEqualTo(edl1)
        assertThat(history.redo()).isEqualTo(edl2)

        // Time formatting and parsing (HH:MM:SS.mmm, MM:SS.mmm, plain seconds)
        assertThat(TimelineMath.formatTimeUs(3_723_456_000L)).isEqualTo("01:02:03.456")
        assertThat(TimelineMath.parseTimeUs("01:02:03.456")).isEqualTo(3_723_456_000L)
        assertThat(TimelineMath.parseTimeUs("02:03.5")).isEqualTo(123_500_000L)
        assertThat(TimelineMath.parseTimeUs("12.25")).isEqualTo(12_250_000L)
        assertThat(TimelineMath.parseTimeUs("invalid")).isNull()

        // Zoom and visible thumbnail slots (visible range + 1 slot each side, 100 ms buckets)
        assertThat(TimelineMath.zoomIn(100f)).isWithin(0.01f).of(150f)
        assertThat(TimelineMath.zoomOut(150f)).isWithin(0.01f).of(100f)
        val slots = TimelineMath.visibleSlotTimestampsUs(
            durationUs = 10_000_000L,
            pxPerSec = 100f,
            slotWidthPx = 50f,
            scrollPx = 100f, // slots 2..6 visible for [100..300] viewport -> requests slots 1..7 (7 slots)
            viewportWidthPx = 200f,
        )
        assertThat(slots).hasSize(7)
        assertThat(slots.all { it % 100_000L == 0L }).isTrue()
    }
}
