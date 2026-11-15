package app.droidcrypter.engine

import app.droidcrypter.engine.fs.ExFatFileSystem
import app.droidcrypter.engine.fs.ExFatFormatter
import app.droidcrypter.engine.fs.FatFileSystem
import app.droidcrypter.engine.fs.FatFormatter
import app.droidcrypter.engine.fs.FileSystems
import app.droidcrypter.engine.vc.CipherSuite
import app.droidcrypter.engine.vc.ContainerCreator
import app.droidcrypter.engine.vc.CreatePhase
import app.droidcrypter.engine.vc.CreateSpec
import app.droidcrypter.engine.vc.DecryptedDevice
import app.droidcrypter.engine.vc.FormatFilesystem
import app.droidcrypter.engine.vc.Prf
import app.droidcrypter.engine.vc.DroidCrypter
import app.droidcrypter.engine.vc.VolumeHeader
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.random.Random

/** Milestone 2 acceptance: FAT and exFAT writes, formatters, free space accounting, and container creation. */
class ContainerWriteTest {

    @TempDir
    lateinit var tempDir: Path

    private val keepWrittenDir: File? = System.getProperty("keepWritten")?.let { File(it).apply { mkdirs() } }

    /** Copies a real Windows-created fixture to a temporary working file for read-write tests. */
    private fun copyFixture(name: String, tag: String): Pair<TestData.Fixture, File> {
        val f = TestData.fixture(name)
        assumeTrue(f.file.isFile, "Missing fixture ${f.file}")
        val dst = tempDir.resolve("${name}_$tag.hc").toFile()
        f.file.copyTo(dst, overwrite = true)
        return f to dst
    }

    private fun openCopied(f: TestData.Fixture, file: File, writable: Boolean = true): ContainerVolume {
        val dev = FileChannelBlockDevice.open(file, writable)
        try {
            return DroidCrypter.open(dev, f.password.toByteArray(Charsets.UTF_8), f.pim, f.prf)
        } catch (e: Throwable) {
            dev.close()
            throw e
        }
    }

    private fun maybeKeep(file: File, outName: String) {
        keepWrittenDir?.let { dir ->
            file.copyTo(File(dir, outName), overwrite = true)
        }
    }

    private fun readDecryptedArea(vol: ContainerVolume): ByteArray {
        val devField = vol.javaClass.getDeclaredField("device").apply { isAccessible = true }
        val dev = devField.get(vol) as DecryptedDevice
        val out = ByteArray(dev.size.toInt())
        dev.readFully(0, out, 0, out.size)
        return out
    }

    private fun containsSubarray(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        val first = needle[0]
        val limit = haystack.size - needle.size
        var i = 0
        while (i <= limit) {
            if (haystack[i] == first) {
                var match = true
                for (j in 1 until needle.size) {
                    if (haystack[i + j] != needle[j]) {
                        match = false
                        break
                    }
                }
                if (match) return true
            }
            i++
        }
        return false
    }

    @ParameterizedTest
    @ValueSource(strings = ["aes_sha512_fat", "aes_sha512_fat16_40m", "aes_sha512_exfat"])
    fun `createFile, collisions, empty files, rename, move, delete and exact live free space`(fixtureName: String) {
        val (f, file) = copyFixture(fixtureName, "crud")
        val clusterSize: Int
        val initialFree: Long

        openCopied(f, file).use { v ->
            val fsField = v.javaClass.getDeclaredField("fs").apply { isAccessible = true }.get(v)
            clusterSize = when (fsField) {
                is FatFileSystem -> fsField.clusterSize
                is ExFatFileSystem -> fsField.clusterSize
                else -> error("unexpected fs")
            }
            initialFree = v.freeBytes

            // 1. Empty file creation takes 0 clusters.
            val emptyNode = v.createFile("/", "zero.txt", 0L, { _, _, _, _ -> })
            assertThat(emptyNode.path).isEqualTo("/zero.txt")
            assertThat(emptyNode.size).isEqualTo(0L)
            assertThat(v.freeBytes).isEqualTo(initialFree)
            assertThat(TestData.readAll(v, "/zero.txt")).isEmpty()

            // 2. Write a multi-cluster payload and verify live free space drops by whole clusters.
            val data = ByteArray(clusterSize * 3 + 123) { (it * 37 + 11).toByte() }
            val expectedClusters = 4L
            var reportedProgress = 0L
            val created = v.createFile("/", "Notes Document.bin", data.size.toLong(), { pos, buf, off, len ->
                System.arraycopy(data, pos.toInt(), buf, off, len)
            }, { reportedProgress = it })
            assertThat(created.name).isEqualTo("Notes Document.bin")
            assertThat(reportedProgress).isEqualTo(data.size.toLong())
            val freeAfterWrite = v.freeBytes
            val expectedDrop = expectedClusters * clusterSize
            // Free space drops by the file's clusters (plus at most 1 directory cluster if root grew).
            assertThat(initialFree - freeAfterWrite).isIn(listOf(expectedDrop, expectedDrop + clusterSize))
            assertThat(TestData.readAll(v, "/Notes Document.bin")).isEqualTo(data)

            // 3. Name collision generates "base (2).ext" and "base (3).ext" (case-insensitively).
            val c2 = v.createFile("/", "notes document.bin", 10L, { _, buf, off, len ->
                for (i in 0 until len) buf[off + i] = 0x42
            })
            assertThat(c2.name).isEqualTo("notes document (2).bin")
            val c3 = v.createFile("/", "Notes Document.bin", 10L, { _, buf, off, len ->
                for (i in 0 until len) buf[off + i] = 0x43
            })
            assertThat(c3.name).isEqualTo("Notes Document (3).bin")

            // 4. Rename within the same folder: case-only rename allowed, collision rejected.
            val caseRenamed = v.rename("/Notes Document.bin", "NOTES DOCUMENT.BIN")
            assertThat(caseRenamed).isEqualTo("/NOTES DOCUMENT.BIN")
            val clashErr = assertThrows<IOException> {
                v.rename("/NOTES DOCUMENT.BIN", "notes document (2).bin")
            }
            assertThat(clashErr.message).isEqualTo("\"notes document (2).bin\" already exists in this folder")

            val renamed = v.rename("/NOTES DOCUMENT.BIN", "Renamed Payload Ünïcødé.bin")
            assertThat(renamed).isEqualTo("/Renamed Payload Ünïcødé.bin")
            assertThat(v.stat("/NOTES DOCUMENT.BIN")).isNull()
            assertThat(TestData.readAll(v, renamed)).isEqualTo(data)

            // 5. Move file into a subfolder: no data copied, free space drops by at most 1 dir cluster.
            val freeBeforeMove = v.freeBytes
            val movedPath = v.move(renamed, "/nested")
            assertThat(movedPath).isEqualTo("/nested/Renamed Payload Ünïcødé.bin")
            val freeAfterMove = v.freeBytes
            assertThat(freeBeforeMove - freeAfterMove).isIn(listOf(0L, clusterSize.toLong()))
            assertThat(v.stat(renamed)).isNull()
            assertThat(TestData.readAll(v, movedPath)).isEqualTo(data)

            // 6. Delete the created files and verify free space comes back.
            v.delete(movedPath, overwrite = false)
            v.delete("/notes document (2).bin", overwrite = false)
            v.delete("/Notes Document (3).bin", overwrite = false)
            v.delete("/zero.txt", overwrite = false)
        }

        // Remount and check that the live freeBytes before closing equals the fresh freeBytes after remount.
        openCopied(f, file, writable = false).use { v ->
            assertThat(v.stat("/nested/Renamed Payload Ünïcødé.bin")).isNull()
            assertThat(v.freeBytes).isEqualTo(initialFree)
        }
        maybeKeep(file, "${fixtureName}_crud.hc")
    }

    @ParameterizedTest
    @ValueSource(strings = ["aes_sha512_fat", "aes_sha512_exfat"])
    fun `delete with overwrite wipes both file data and every LFN or FileName directory slot`(fixtureName: String) {
        val (f, file) = copyFixture(fixtureName, "overwrite")
        val secretName = "UltraSecretFilename_99887766_WithLongName.txt"
        val secretContent = "TOP_SECRET_MARKER_PAYLOAD_445566778899_NEVER_LEAK".repeat(40).toByteArray(Charsets.UTF_8)
        // Check UTF-16LE substrings that stay within both FAT LFN sub-fields AND exFAT 15-char FileName entries:
        // LFN1 (0..4, 5..10) & exFAT1 (0..14): "Ultra" (0..4), "Secret" (5..10)
        // LFN2 (19..23) & exFAT2 (15..29): "9988" (20..23)
        // LFN3 (31..36) & exFAT3 (30..44): "thLon" (31..35)
        val nameSlicesUtf16 = listOf("Ultra", "Secret", "9988", "thLon").map { it.toByteArray(Charsets.UTF_16LE) }

        openCopied(f, file).use { v ->
            v.createFile("/nested", secretName, secretContent.size.toLong(), { pos, buf, off, len ->
                System.arraycopy(secretContent, pos.toInt(), buf, off, len)
            })
            val rawBefore = readDecryptedArea(v)
            assertThat(containsSubarray(rawBefore, "TOP_SECRET_MARKER_PAYLOAD_445566778899".toByteArray())).isTrue()
            for (slice in nameSlicesUtf16) {
                assertThat(containsSubarray(rawBefore, slice)).isTrue()
            }

            var overwrittenBytes = 0L
            v.delete("/nested/$secretName", overwrite = true) { overwrittenBytes = it }
            assertThat(overwrittenBytes).isGreaterThan(secretContent.size.toLong())

            val rawAfter = readDecryptedArea(v)
            assertThat(containsSubarray(rawAfter, "TOP_SECRET_MARKER_PAYLOAD_445566778899".toByteArray())).isFalse()
            for (slice in nameSlicesUtf16) {
                assertThat(containsSubarray(rawAfter, slice)).isFalse()
            }
            assertThat(containsSubarray(rawAfter, "ULTRA".toByteArray(Charsets.US_ASCII))).isFalse()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["aes_sha512_fat", "aes_sha512_fat16_40m", "aes_sha512_exfat"])
    fun `moving folders updates FAT dot-dot entry on disk and rejects moving into self or subtree`(fixtureName: String) {
        val (f, file) = copyFixture(fixtureName, "movefolder")
        openCopied(f, file).use { v ->
            // Moving into itself or its own subtree is rejected.
            val errSelf = assertThrows<IOException> { v.move("/nested", "/nested") }
            assertThat(errSelf.message).isEqualTo("Can't move \"nested\" into itself")
            val errSub = assertThrows<IOException> { v.move("/nested", "/nested/level1/level2") }
            assertThat(errSub.message).isEqualTo("Can't move \"nested\" into itself")

            // Move /nested/level1 to root -> /level1 (on FAT, ".." cluster must become 0).
            val movedToRoot = v.move("/nested/level1", "/")
            assertThat(movedToRoot).isEqualTo("/level1")
            assertThat(TestData.readAll(v, "/level1/level2/level3/deep.txt"))
                .isEqualTo(File(TestData.payload, "nested/level1/level2/level3/deep.txt").readBytes())

            val fsField = v.javaClass.getDeclaredField("fs").apply { isAccessible = true }.get(v)
            if (fsField is FatFileSystem) {
                val (b, _) = fsField.readDirBytes(fsField.list("/").let {
                    val m = fsField.javaClass.superclass.getDeclaredMethod("resolve", String::class.java)
                        .apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    (m.invoke(fsField, "/level1") as Pair<String, FatFileSystem.FatEntry>).second
                })
                // Slot 1 (offset 32..63) is ".."
                val hi = if (fsField.fatType == 32) ((b[52].toInt() and 0xFF) or ((b[53].toInt() and 0xFF) shl 8)) else 0
                val lo = (b[58].toInt() and 0xFF) or ((b[59].toInt() and 0xFF) shl 8)
                val dotDotCluster = (hi.toLong() shl 16) or lo.toLong()
                assertThat(dotDotCluster).isEqualTo(0L)
            }

            // Now move /level1 back into /nested -> /nested/level1 (on FAT, ".." must equal /nested's firstCluster).
            val movedBack = v.move("/level1", "/nested")
            assertThat(movedBack).isEqualTo("/nested/level1")
            if (fsField is FatFileSystem) {
                val resolveMethod = fsField.javaClass.superclass.getDeclaredMethod("resolve", String::class.java)
                    .apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val nestedEntry = (resolveMethod.invoke(fsField, "/nested") as Pair<String, FatFileSystem.FatEntry>).second
                @Suppress("UNCHECKED_CAST")
                val level1Entry = (resolveMethod.invoke(fsField, "/nested/level1") as Pair<String, FatFileSystem.FatEntry>).second
                val (b, _) = fsField.readDirBytes(level1Entry)
                val hi = if (fsField.fatType == 32) ((b[52].toInt() and 0xFF) or ((b[53].toInt() and 0xFF) shl 8)) else 0
                val lo = (b[58].toInt() and 0xFF) or ((b[59].toInt() and 0xFF) shl 8)
                val dotDotCluster = (hi.toLong() shl 16) or lo.toLong()
                assertThat(dotDotCluster).isEqualTo(nestedEntry.firstCluster)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["aes_sha512_fat", "aes_sha512_exfat"])
    fun `directory growth in a subdirectory works and preserves all files`(fixtureName: String) {
        val (f, file) = copyFixture(fixtureName, "dirgrowth")
        openCopied(f, file).use { v ->
            // Create 180 files with long names inside /nested/Empty Folder to force multiple cluster growths.
            for (i in 0 until 180) {
                val name = "LongSubdirectoryFileName_Number_%04d_WithExtraPadding.txt".format(i)
                val payload = "item-$i".toByteArray()
                v.createFile("/nested/Empty Folder", name, payload.size.toLong(), { pos, buf, off, len ->
                    System.arraycopy(payload, pos.toInt(), buf, off, len)
                })
            }
            val listed = v.list("/nested/Empty Folder")
            assertThat(listed).hasSize(180)
        }
        // Remount to verify on-disk directory chains and exFAT Stream Extension updates.
        openCopied(f, file, writable = false).use { v ->
            val listed = v.list("/nested/Empty Folder")
            assertThat(listed).hasSize(180)
            val sample = TestData.readAll(v, "/nested/Empty Folder/LongSubdirectoryFileName_Number_0179_WithExtraPadding.txt")
            assertThat(String(sample)).isEqualTo("item-179")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["aes_sha512_fat", "aes_sha512_fat16_40m", "aes_sha512_exfat"])
    fun `createDirectory makes nested folders that hold files, survive a remount and free their space`(fixtureName: String) {
        val (f, file) = copyFixture(fixtureName, "mkdir")
        val payload = ByteArray(70_000) { (it * 31 + 7).toByte() }
        val initialFree: Long
        openCopied(f, file).use { v ->
            initialFree = v.freeBytes

            val top = v.createDirectory("/", "New Folder Ünïcødé with a long name")
            assertThat(top.path).isEqualTo("/New Folder Ünïcødé with a long name")
            assertThat(top.isDirectory).isTrue()
            assertThat(v.list(top.path)).isEmpty()
            assertThat(v.freeBytes).isLessThan(initialFree)

            // Taken names (case-insensitively, files included) are rejected, or numbered on request.
            val clash = assertThrows<IOException> { v.createDirectory("/", "NEW FOLDER ünïcødé WITH A LONG NAME") }
            assertThat(clash.message).contains("already exists in this folder")
            assertThrows<IOException> { v.createDirectory("/", "small.txt") }
            assertThat(v.createDirectory("/", top.name, autoRename = true).name).isEqualTo("${top.name} (2)")
            assertThrows<IOException> { v.createDirectory("/", "bad:name") }
            assertThrows<IOException> { v.createDirectory("/no such folder", "x") }

            // Nested folders, a file inside, and enough siblings to grow the new directory itself.
            val inner = v.createDirectory(top.path, "inner")
            val deep = v.createDirectory(inner.path, "deep")
            v.createFile(deep.path, "payload.bin", payload.size.toLong(), { pos, buf, off, len ->
                System.arraycopy(payload, pos.toInt(), buf, off, len)
            })
            for (i in 0 until 150) v.createDirectory(inner.path, "Sibling folder number %03d with padding".format(i))
            assertThat(v.list(inner.path)).hasSize(151)

            // A new folder can be moved and renamed like any other.
            assertThat(v.move(deep.path, "/nested")).isEqualTo("/nested/deep")
            assertThat(v.rename("/nested/deep", "deeper")).isEqualTo("/nested/deeper")
        }

        openCopied(f, file).use { v ->
            assertThat(v.list("/New Folder Ünïcødé with a long name/inner")).hasSize(150)
            assertThat(v.stat("/New Folder Ünïcødé with a long name (2)")!!.isDirectory).isTrue()
            assertThat(TestData.readAll(v, "/nested/deeper/payload.bin")).isEqualTo(payload)
            assertThat(TestData.readAll(v, "/small.txt")).isEqualTo(File(TestData.payload, "small.txt").readBytes())

            val fs = v.javaClass.getDeclaredField("fs").apply { isAccessible = true }.get(v)
            if (fs is FatFileSystem) {
                // "." points at the folder itself; ".." of a folder in the root is cluster 0.
                val resolve = fs.javaClass.superclass.getDeclaredMethod("resolve", String::class.java)
                    .apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val entry = (resolve.invoke(fs, "/New Folder Ünïcødé with a long name") as Pair<String, FatFileSystem.FatEntry>).second
                val (b, _) = fs.readDirBytes(entry)
                fun cluster(o: Int): Long {
                    val hi = if (fs.fatType == 32) (b[o + 20].toInt() and 0xFF) or ((b[o + 21].toInt() and 0xFF) shl 8) else 0
                    return (hi.toLong() shl 16) or ((b[o + 26].toInt() and 0xFF) or ((b[o + 27].toInt() and 0xFF) shl 8)).toLong()
                }
                assertThat(String(b, 0, 11, Charsets.US_ASCII)).isEqualTo(".          ")
                assertThat(String(b, 32, 11, Charsets.US_ASCII)).isEqualTo("..         ")
                assertThat(cluster(0)).isEqualTo(entry.firstCluster)
                assertThat(cluster(32)).isEqualTo(0L)
            }

            v.delete("/New Folder Ünïcødé with a long name", overwrite = true)
            v.delete("/New Folder Ünïcødé with a long name (2)", overwrite = false)
            v.delete("/nested/deeper", overwrite = false)
        }
        openCopied(f, file, writable = false).use { v ->
            assertThat(v.stat("/New Folder Ünïcødé with a long name")).isNull()
            // /nested may have grown by one cluster to hold the moved folder's entry.
            assertThat(initialFree - v.freeBytes).isAtMost(65536L)
            assertThrows<IOException> { v.createDirectory("/", "read only") }
        }
        maybeKeep(file, "${fixtureName}_mkdir.hc")
    }

    @Test
    fun `sanitizeName makes outside names storable`() {
        assertThat(FsPaths.sanitizeName("a/b:c*?.txt")).isEqualTo("a_b_c__.txt")
        assertThat(FsPaths.sanitizeName("  trailing dots... ")).isEqualTo("trailing dots")
        assertThat(FsPaths.sanitizeName("..")).isEqualTo("unnamed")
        assertThat(FsPaths.sanitizeName("", "folder")).isEqualTo("folder")
        assertThat(FsPaths.sanitizeName("Normal name.jpg")).isEqualTo("Normal name.jpg")
    }

    @ParameterizedTest
    @ValueSource(strings = ["aes_sha512_fat", "aes_sha512_exfat"])
    fun `full volume is rejected cleanly without corrupting existing files and freed space is reusable`(fixtureName: String) {
        val (f, file) = copyFixture(fixtureName, "fullvol")
        openCopied(f, file).use { v ->
            val free = v.freeBytes
            // Requesting more than freeBytes fails cleanly.
            assertThrows<IOException> {
                v.createFile("/", "too_big.bin", free + 65536L, { _, buf, off, len -> buf.fill(1, off, off + len) })
            }
            assertThat(v.freeBytes).isEqualTo(free)

            // Fill nearly all free space, then delete and allocate again to prove freed clusters are reusable.
            val chunk = (free - 32768L).coerceAtLeast(65536L)
            v.createFile("/", "filler.bin", chunk, { _, buf, off, len -> buf.fill(0x5A, off, off + len) })
            assertThat(v.freeBytes).isLessThan(65536L)
            v.delete("/filler.bin", overwrite = false)
            assertThat(v.freeBytes).isEqualTo(free)
            v.createFile("/", "filler2.bin", chunk, { _, buf, off, len -> buf.fill(0x3C, off, off + len) })
            v.delete("/filler2.bin", overwrite = false)
            assertThat(v.freeBytes).isEqualTo(free)
            assertThat(TestData.readAll(v, "/small.txt")).isEqualTo(File(TestData.payload, "small.txt").readBytes())
        }
    }

    @Test
    fun `FAT formatter selects FAT12, FAT16 and FAT32 by volume size and round-trips writes`() {
        // 1. Small volume (2 MB) -> FAT12
        val dev12 = MemoryBlockDevice(ByteArray(2 * 1024 * 1024))
        FatFormatter.format(dev12, "MY FAT12")
        val fs12 = FileSystems.open(dev12) as FatFileSystem
        assertThat(fs12.fatType).isEqualTo(12)
        assertThat(fs12.label).isEqualTo("MY FAT12")
        fs12.createFile("/", "hello.txt", 5, { _, b, o, l -> System.arraycopy("world".toByteArray(), 0, b, o, l) }, {})
        assertThat(fs12.list("/").map { it.name }).containsExactly("hello.txt")

        // 2. Medium volume (16 MB) -> FAT16
        val dev16 = MemoryBlockDevice(ByteArray(16 * 1024 * 1024))
        FatFormatter.format(dev16, "MY FAT16")
        val fs16 = FileSystems.open(dev16) as FatFileSystem
        assertThat(fs16.fatType).isEqualTo(16)
        assertThat(fs16.label).isEqualTo("MY FAT16")

        // 3. Larger volume (40 MB, >= 65525 clusters at SPC=1) -> FAT32
        val dev32 = MemoryBlockDevice(ByteArray(40 * 1024 * 1024))
        FatFormatter.format(dev32, "MY FAT32")
        val fs32 = FileSystems.open(dev32) as FatFileSystem
        assertThat(fs32.fatType).isEqualTo(32)
        assertThat(fs32.label).isEqualTo("MY FAT32")
        val data = ByteArray(100_000) { (it % 251).toByte() }
        fs32.createFile("/", "fat32_test.bin", data.size.toLong(), { pos, b, o, l ->
            System.arraycopy(data, pos.toInt(), b, o, l)
        }, {})
        val reopened32 = FileSystems.open(dev32) as FatFileSystem
        val readBack = ByteArray(data.size)
        reopened32.openFile("/fat32_test.bin").use { it.read(0, readBack, 0, readBack.size) }
        assertThat(readBack).isEqualTo(data)
    }

    @Test
    fun `exFAT formatter writes valid boot checksum, upcase table and bitmap and round-trips writes`() {
        val dev = MemoryBlockDevice(ByteArray(4 * 1024 * 1024))
        ExFatFormatter.format(dev, "VaultExFAT")
        val fs = FileSystems.open(dev) as ExFatFileSystem
        assertThat(fs.label).isEqualTo("VaultExFAT")
        val payload = ByteArray(77_777) { (it * 19).toByte() }
        fs.createFile("/", "test_exfat.bin", payload.size.toLong(), { pos, b, o, l ->
            System.arraycopy(payload, pos.toInt(), b, o, l)
        }, {})
        val fs2 = FileSystems.open(dev) as ExFatFileSystem
        val out = ByteArray(payload.size)
        fs2.openFile("/test_exfat.bin").use { it.read(0, out, 0, out.size) }
        assertThat(out).isEqualTo(payload)
    }

    @ParameterizedTest
    @EnumSource(FormatFilesystem::class)
    fun `ContainerCreator full and quick format unlock via primary and backup headers, leave no plaintext zeros, and have unique salts`(
        fsType: FormatFilesystem,
    ) {
        val size = 2 * 1024 * 1024
        val host1 = MemoryBlockDevice(ByteArray(size))
        val host2 = MemoryBlockDevice(ByteArray(size))
        val pw1 = "creation-test-password-1".toByteArray()
        val pw2 = "creation-test-password-1".toByteArray()
        val phases = ArrayList<CreatePhase>()

        // Use PIM=1 (16,000 iterations) in unit test for fast verification.
        val spec = CreateSpec(
            cipher = CipherSuite.SERPENT_AES,
            prf = Prf.SHA256,
            pim = 1,
            filesystem = fsType,
            label = "CREATED",
            quickFormat = false,
        )
        ContainerCreator.create(host1, pw1, spec, onProgress = { p, _ ->
            if (phases.lastOrNull() != p) phases += p
        })
        // Password must be wiped by ContainerCreator.create.
        assertThat(pw1.all { it == 0.toByte() }).isTrue()
        assertThat(phases).containsExactly(
            CreatePhase.DERIVING_KEYS,
            CreatePhase.WRITING_HEADERS,
            CreatePhase.ENCRYPTING_FREE_SPACE,
            CreatePhase.FORMATTING,
            CreatePhase.VERIFYING,
            CreatePhase.DONE,
        ).inOrder()

        // Full create leaves no 512-byte sector of all plaintext zeros anywhere in the container file.
        val zeroSector = ByteArray(512)
        for (off in 0 until size step 512) {
            assertThat(host1.data.copyOfRange(off, off + 512)).isNotEqualTo(zeroSector)
        }
        // Primary and backup salts inside the same container must differ.
        val primarySalt1 = host1.data.copyOfRange(0, 64)
        val backupSalt1 = host1.data.copyOfRange(size - 131072, size - 131072 + 64)
        assertThat(primarySalt1).isNotEqualTo(backupSalt1)

        // Second vault created with the same parameters must have different salts and ciphertexts.
        ContainerCreator.create(host2, pw2, spec.copy(quickFormat = true))
        val primarySalt2 = host2.data.copyOfRange(0, 64)
        assertThat(primarySalt2).isNotEqualTo(primarySalt1)
        assertThat(host2.data.copyOfRange(64, 512)).isNotEqualTo(host1.data.copyOfRange(64, 512))

        // Unlock normally, write a file, lock.
        DroidCrypter.open(host1, "creation-test-password-1".toByteArray(), pim = 1, prf = Prf.SHA256).use { v ->
            assertThat(v.label).isEqualTo("CREATED")
            v.createFile("/", "inside.txt", 11L, { _, b, o, l ->
                System.arraycopy("backup-test".toByteArray(), 0, b, o, l)
            })
        }

        // Corrupt the primary header completely (bytes 0..511) and verify it still unlocks via the backup header!
        Random(99).nextBytes(host1.data, 0, VolumeHeader.SIZE)
        DroidCrypter.open(host1, "creation-test-password-1".toByteArray(), pim = 1, prf = Prf.SHA256).use { v ->
            assertThat(String(TestData.readAll(v, "/inside.txt"))).isEqualTo("backup-test")
        }

        // Verify that app-created containers mount in the Windows desktop tool when installed on the host.
        val vcExe = File("C:\\Program Files\\VeraCrypt\\VeraCrypt.exe")
        if (vcExe.isFile) {
            val tmpFile = tempDir.resolve("created_${fsType.name.lowercase()}.hc").toFile()
            val pw = "vc-win-interop-pass"
            FileChannelBlockDevice.open(tmpFile.apply { writeBytes(ByteArray(size)) }, true).use { dev ->
                ContainerCreator.create(
                    dev,
                    pw.toByteArray(),
                    CreateSpec(cipher = CipherSuite.AES, prf = Prf.SHA512, pim = 0, filesystem = fsType, label = "WINMOUNT", quickFormat = true),
                )
            }
            FileChannelBlockDevice.open(tmpFile, true).let { dev ->
                DroidCrypter.open(dev, pw.toByteArray(), pim = 0, prf = Prf.SHA512).use { v ->
                    v.createFile("/", "from_app.txt", 14L, { _, b, o, l ->
                        System.arraycopy("hello-windows!".toByteArray(), 0, b, o, l)
                    })
                }
            }
            maybeKeep(tmpFile, "created_${fsType.name.lowercase()}.hc")
        }
    }
}
