package app.droidcrypter.engine

import app.droidcrypter.engine.vc.CipherSuite
import app.droidcrypter.engine.vc.Prf
import java.io.File

/** The Windows-created fixtures in /testdata (see testdata/README.md). */
object TestData {
    val dir: File = File(System.getProperty("testdata.dir") ?: "../testdata")
    val payload: File = File(dir, "payload")

    data class Fixture(
        val name: String,
        val password: String,
        val prf: Prf,
        val pim: Int,
        val cipher: CipherSuite,
        val fs: String,
    ) {
        val file: File get() = File(dir, "$name.hc")
        override fun toString() = name
    }

    val fixtures = listOf(
        Fixture("aes_sha512_fat", "test-aes-sha512", Prf.SHA512, 0, CipherSuite.AES, "FAT"),
        Fixture("aes_sha256_fat", "test-aes-sha256", Prf.SHA256, 0, CipherSuite.AES, "FAT"),
        Fixture("aes_sha512_fat16_40m", "test-aes-fat16", Prf.SHA512, 0, CipherSuite.AES, "FAT"),
        Fixture("aes_sha512_exfat", "test-aes-exfat", Prf.SHA512, 0, CipherSuite.AES, "exFAT"),
        Fixture("aes_sha512_ntfs", "test-aes-ntfs", Prf.SHA512, 0, CipherSuite.AES, "NTFS"),
        Fixture("aes_sha512_pim5_fat", "test-aes-pim5-long-password", Prf.SHA512, 5, CipherSuite.AES, "FAT"),
        Fixture("aes_whirlpool_fat", "test-aes-whirlpool", Prf.WHIRLPOOL, 0, CipherSuite.AES, "FAT"),
        Fixture("aes_streebog_fat", "test-aes-streebog", Prf.STREEBOG, 0, CipherSuite.AES, "FAT"),
        Fixture("serpent_sha512_fat", "test-serpent", Prf.SHA512, 0, CipherSuite.SERPENT, "FAT"),
        Fixture("twofish_sha512_fat", "test-twofish", Prf.SHA512, 0, CipherSuite.TWOFISH, "FAT"),
        Fixture("aes-twofish_sha512_fat", "test-aes-twofish", Prf.SHA512, 0, CipherSuite.AES_TWOFISH, "FAT"),
        Fixture("aes-twofish-serpent_sha512_fat", "test-aes-twofish-serpent", Prf.SHA512, 0, CipherSuite.AES_TWOFISH_SERPENT, "FAT"),
        Fixture("serpent-aes_sha512_fat", "test-serpent-aes", Prf.SHA512, 0, CipherSuite.SERPENT_AES, "FAT"),
        Fixture("serpent-twofish-aes_sha512_fat", "test-serpent-twofish-aes", Prf.SHA512, 0, CipherSuite.SERPENT_TWOFISH_AES, "FAT"),
        Fixture("twofish-serpent_sha512_fat", "test-twofish-serpent", Prf.SHA512, 0, CipherSuite.TWOFISH_SERPENT, "FAT"),
    )

    fun fixture(name: String) = fixtures.first { it.name == name }

    /** Windows' Win32_LogicalDisk figures recorded by make-testdata.ps1: name → (size, free). */
    val windowsSpace: Map<String, Pair<Long, Long>> by lazy {
        File(dir, "windows-space.csv").readLines().drop(1).filter { it.isNotBlank() }.associate { line ->
            val p = line.split(',')
            p[0] to (p[3].trim().toLong() to p[4].trim().toLong())
        }
    }

    fun open(f: Fixture, writable: Boolean = false, prf: Prf? = f.prf): ContainerVolume {
        val dev = FileChannelBlockDevice.open(f.file, writable)
        try {
            return app.droidcrypter.engine.vc.DroidCrypter.open(dev, f.password.toByteArray(Charsets.UTF_8), f.pim, prf)
        } catch (e: Throwable) {
            dev.close(); throw e
        }
    }

    fun readAll(v: MountedVolume, path: String): ByteArray = v.openFile(path).use { h ->
        val out = ByteArray(h.size.toInt())
        var p = 0
        while (p < out.size) {
            val n = h.read(p.toLong(), out, p, minOf(65536, out.size - p))
            check(n > 0)
            p += n
        }
        check(h.read(out.size.toLong(), ByteArray(1), 0, 1) == -1)
        out
    }
}

/** In-memory [BlockDevice] for tests. */
class MemoryBlockDevice(val data: ByteArray, override val isWritable: Boolean = true) : BlockDevice {
    override val size: Long get() = data.size.toLong()
    override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int) {
        checkRange(pos, len, size); System.arraycopy(data, pos.toInt(), buf, off, len)
    }
    override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) {
        if (!isWritable) throw java.io.IOException("read-only")
        checkRange(pos, len, size); System.arraycopy(buf, off, data, pos.toInt(), len)
    }
    override fun sync() {}
    override fun close() {}
}

