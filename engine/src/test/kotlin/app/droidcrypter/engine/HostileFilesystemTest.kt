package app.droidcrypter.engine

import app.droidcrypter.engine.fs.FileSystems
import app.droidcrypter.engine.vc.DecryptedDevice
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import kotlin.random.Random

/**
 * Hostile containers: corrupt the decrypted metadata of real filesystems at random and walk the
 * whole tree, reading every file. Only IOException is acceptable — nothing may crash or hang.
 */
class HostileFilesystemTest {

    private fun plaintext(name: String): ByteArray? {
        val f = TestData.fixture(name)
        if (!f.file.isFile) return null
        TestData.open(f).use { v ->
            val dev = v.javaClass.getDeclaredField("device").apply { isAccessible = true }.get(v) as DecryptedDevice
            val out = ByteArray(dev.size.toInt())
            dev.readFully(0, out, 0, out.size)
            return out
        }
    }

    private fun walk(fs: app.droidcrypter.engine.fs.FileSystemDriver, dir: String, depth: Int, budget: IntArray) {
        if (depth > 12 || budget[0]-- <= 0) return
        for (n in fs.list(dir)) {
            if (n.isDirectory) walk(fs, n.path, depth + 1, budget)
            else fs.openFile(n.path).use { h ->
                val buf = ByteArray(65536)
                var p = 0L
                var reads = 0
                while (reads++ < 64) { val r = h.read(p, buf, 0, buf.size); if (r <= 0) break; p += r }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["aes_sha512_fat", "aes_sha512_exfat", "aes_sha512_ntfs"])
    fun `random metadata corruption only ever yields IOException`(name: String) {
        val clean = plaintext(name)
        assumeTrue(clean != null)
        val rnd = Random(name.hashCode())
        // Metadata lives near the start (boot sector, FAT, bitmap, root dir, MFT).
        val hot = minOf(clean!!.size, 1 shl 20)
        repeat(300) { iteration ->
            val img = clean.copyOf()
            repeat(rnd.nextInt(1, 40)) {
                val pos = if (rnd.nextInt(4) == 0) rnd.nextInt(0, 512) else rnd.nextInt(0, hot)
                img[pos] = rnd.nextInt(256).toByte()
            }
            try {
                val fs = FileSystems.open(MemoryBlockDevice(img, false))
                fs.freeBytes()
                walk(fs, "/", 0, intArrayOf(2000))
            } catch (_: IOException) {
                // expected for corrupt structures
            } catch (t: Throwable) {
                assertWithMessage("iteration $iteration threw ${t.javaClass.name}: ${t.message}\n${t.stackTraceToString().take(1500)}")
                    .fail()
            }
        }
    }
}

