package app.droidcrypter.engine

import app.droidcrypter.engine.TestData.Fixture
import app.droidcrypter.engine.vc.CipherSuite
import app.droidcrypter.engine.vc.Prf
import app.droidcrypter.engine.vc.DroidCrypter
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.io.IOException
import kotlin.random.Random

/** Milestone 1 acceptance: Windows-created containers open and read byte-exact. */
class ContainerReadTest {

    companion object {
        @JvmStatic
        fun fixtures() = TestData.fixtures

        const val UNICODE_NAME = "Ünïcødé 日本語 файл.txt"
        const val LONG_NAME = "This is a very long file name that goes far beyond the old eight dot three limit of FAT.txt"
    }

    private fun payload(rel: String) = File(TestData.payload, rel).readBytes()

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    fun `opens with the right cipher, PRF and filesystem and reads byte-exact`(f: Fixture) {
        assumeTrue(f.file.isFile, "missing fixture ${f.file}")
        TestData.open(f).use { v ->
            assertThat(v.cipher).isEqualTo(f.cipher)
            assertThat(v.prf).isEqualTo(f.prf)
            assertThat(v.isHidden).isFalse()
            assertThat(v.filesystemName).startsWith(f.fs)
            assertThat(v.label).isEqualTo("DroidCrypter volume")
            assertThat(v.id).matches("[0-9a-f]{16}")

            val root = v.list("/").associateBy { it.name }
            assertThat(root.keys).containsAtLeast("small.txt", "empty.dat", "random_300k.bin", "random_1m.bin",
                UNICODE_NAME, LONG_NAME, "nested", "many")
            assertThat(root.getValue("nested").isDirectory).isTrue()
            assertThat(root.getValue("random_1m.bin").size).isEqualTo(1048583)
            assertThat(root.getValue("small.txt").lastModifiedMillis).isGreaterThan(1_600_000_000_000L)

            for (name in listOf("small.txt", "empty.dat", "random_300k.bin", "random_1m.bin", UNICODE_NAME, LONG_NAME)) {
                assertThat(TestData.readAll(v, "/$name")).isEqualTo(payload(name))
            }
            assertThat(TestData.readAll(v, "/nested/level1/level2/level3/deep.txt"))
                .isEqualTo(payload("nested/level1/level2/level3/deep.txt"))
            assertThat(v.list("/nested/Empty Folder")).isEmpty()
            val many = v.list("/many")
            assertThat(many).hasSize(600)
            assertThat(many.map { it.name }.toSet()).contains("file_0599.txt")
            assertThat(TestData.readAll(v, "/many/file_0321.txt")).isEqualTo(payload("many/file_0321.txt"))
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    fun `random seeks read the same bytes as the original`(f: Fixture) {
        assumeTrue(f.file.isFile)
        val expected = payload("random_1m.bin")
        TestData.open(f).use { v ->
            v.openFile("/random_1m.bin").use { h ->
                val rnd = Random(42)
                repeat(200) {
                    val pos = rnd.nextLong(0, expected.size.toLong())
                    val len = rnd.nextInt(1, 70_000)
                    val buf = ByteArray(len)
                    val n = h.read(pos, buf, 0, len)
                    val want = minOf(len.toLong(), expected.size - pos).toInt()
                    assertThat(n).isEqualTo(want)
                    assertThat(buf.copyOf(n)).isEqualTo(expected.copyOfRange(pos.toInt(), pos.toInt() + n))
                }
                assertThat(h.read(expected.size.toLong(), ByteArray(4), 0, 4)).isEqualTo(-1)
            }
        }
    }

    @Test
    fun `path lookup is case-insensitive and canonicalises names`() {
        val f = TestData.fixture("aes_sha512_exfat")
        assumeTrue(f.file.isFile)
        TestData.open(f).use { v ->
            val n = v.stat("/NESTED/level1/LEVEL2")!!
            assertThat(n.path).isEqualTo("/nested/level1/level2")
            assertThat(n.isDirectory).isTrue()
            assertThat(v.stat("/")!!).isEqualTo(FsNode("/", "", true, 0, 0))
            assertThat(v.stat("/nope")).isNull()
            assertThrows<IOException> { v.stat("/nested/../small.txt") }
        }
    }

    @Test
    fun `Auto hash selection finds a SHA-256 volume`() {
        val f = TestData.fixture("aes_sha256_fat")
        assumeTrue(f.file.isFile)
        TestData.open(f, prf = null).use { assertThat(it.prf).isEqualTo(Prf.SHA256) }
    }

    @Test
    fun `wrong password is rejected with the documented message`() {
        val f = TestData.fixture("aes_sha512_pim5_fat")
        assumeTrue(f.file.isFile)
        FileChannelBlockDevice.open(f.file, false).use { dev ->
            val e = assertThrows<IOException> { DroidCrypter.open(dev, "wrong-password-xxxxxxxx".toByteArray(), 5, Prf.SHA512) }
            assertThat(e.message).isEqualTo(DroidCrypter.UNSUPPORTED_MESSAGE)
            // Right password, wrong PIM.
            assertThrows<IOException> { DroidCrypter.open(dev, f.password.toByteArray(), 6, Prf.SHA512) }
        }
    }

    @Test
    fun `password bytes are zeroed after unlock, success or failure`() {
        val f = TestData.fixture("aes_sha512_pim5_fat")
        assumeTrue(f.file.isFile)
        val pw = f.password.toByteArray()
        FileChannelBlockDevice.open(f.file, false).let { dev -> DroidCrypter.open(dev, pw, f.pim, f.prf).close() }
        assertThat(pw.all { it == 0.toByte() }).isTrue()
        val bad = "bad-password-bad-password".toByteArray()
        FileChannelBlockDevice.open(f.file, false).use { dev ->
            assertThrows<IOException> { DroidCrypter.open(dev, bad, f.pim, f.prf) }
        }
        assertThat(bad.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `reads are refused after lock`() {
        val f = TestData.fixture("aes_sha512_fat")
        assumeTrue(f.file.isFile)
        val v = TestData.open(f)
        val h = v.openFile("/small.txt")
        v.close()
        assertThat(assertThrows<IOException> { v.list("/") }.message).isEqualTo("Volume is locked")
        assertThat(assertThrows<IOException> { h.read(0, ByteArray(4), 0, 4) }.message).isEqualTo("Volume is locked")
        assertThat(assertThrows<IOException> { v.freeBytes }.message).isEqualTo("Volume is locked")
        assertThat(v.writeSupport).isEqualTo("Volume is locked")
    }

    @Test
    fun `NTFS is read-only with the documented reason`() {
        val f = TestData.fixture("aes_sha512_ntfs")
        assumeTrue(f.file.isFile)
        TestData.open(f, writable = true).use { v ->
            assertThat(v.writeSupport).isEqualTo("Saving into NTFS volumes isn't supported yet")
            val e = assertThrows<IOException> { v.delete("/small.txt", false) }
            assertThat(e.message).isEqualTo("Saving into NTFS volumes isn't supported yet")
        }
        TestData.open(TestData.fixture("aes_sha512_fat"), writable = false).use { v ->
            assertThat(v.writeSupport).isEqualTo("This volume is read-only")
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    fun `total and free bytes match what Windows reports`(f: Fixture) {
        assumeTrue(f.file.isFile)
        val (size, free) = TestData.windowsSpace.getValue(f.name)
        TestData.open(f).use { v ->
            assertThat(v.totalBytes).isEqualTo(size)
            assertThat(v.freeBytes).isEqualTo(free)
        }
    }

    @Test
    fun `too-small files are rejected`() {
        val dev = MemoryBlockDevice(ByteArray(100_000), false)
        val e = assertThrows<IOException> { DroidCrypter.open(dev, "x".toByteArray(), 0, Prf.SHA512) }
        assertThat(e.message).isEqualTo("File is too small to be a DroidCrypter volume")
    }

    @Test
    fun `all eight suites are covered by the fixtures`() {
        assertThat(TestData.fixtures.map { it.cipher }.toSet()).containsExactlyElementsIn(CipherSuite.entries)
        assertThat(TestData.fixtures.map { it.prf }.toSet()).containsExactlyElementsIn(Prf.entries)
    }
}

