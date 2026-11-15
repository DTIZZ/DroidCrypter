package app.droidcrypter.engine.vc

import app.droidcrypter.engine.BlockDevice
import app.droidcrypter.engine.ContainerVolume
import app.droidcrypter.engine.Secrets
import app.droidcrypter.engine.fs.FileSystems
import java.io.IOException

/** Unlock entry point: decrypts a DroidCrypter header and mounts the filesystem inside. */
object DroidCrypter {
    const val UNSUPPORTED_MESSAGE =
        "Wrong password or PIM, or an unsupported setting (supported: AES/Serpent/Twofish and their " +
            "cascades, with SHA-512, SHA-256, Whirlpool or Streebog; no keyfiles)"

    /**
     * Opens [host] with [password] (raw UTF-8 bytes). **Takes ownership of [password]: it is wiped
     * before this returns or throws.** Tries the standard header at offset 0, then the hidden-volume
     * header at 65536. [prf] null = Auto (tries all PRFs; slower). [cancel] is polled and may throw to
     * abort. On failure [host] is left open (the caller closes it); on success the volume owns it.
     */
    fun open(host: BlockDevice, password: ByteArray, pim: Int = 0, prf: Prf? = null,
             cancel: () -> Unit = {}): ContainerVolume {
        val raw = ByteArray(VolumeHeader.SIZE)
        try {
            Secrets.passwordProblem(password)?.let { throw IOException(it) }
            if (pim < 0) throw IOException("PIM must be a positive number")
            if (host.size < VolumeHeader.MIN_FILE_SIZE) throw IOException("File is too small to be a DroidCrypter volume")
            val prfs = if (prf == null) Prf.entries else listOf(prf)
            val offsets = listOf(
                0L to false,
                VolumeHeader.HIDDEN_HEADER_OFFSET to true,
                (host.size - VolumeHeader.HEADER_GROUP_SIZE) to false,
                (host.size - VolumeHeader.HIDDEN_HEADER_OFFSET) to true,
            )
            for ((offset, hidden) in offsets) {
                if (offset < 0 || offset + VolumeHeader.SIZE > host.size) continue
                host.readFully(offset, raw, 0, raw.size)
                val dh = HeaderCrypto.tryDecrypt(raw, password, pim, prfs, hidden, cancel) ?: continue
                try {
                    dh.header.checkBounds(host.size)
                    val dev = DecryptedDevice(host, dh.header.dataAreaOffset, dh.header.dataAreaSize, dh.cascade, host.isWritable)
                    val fs = try {
                        FileSystems.open(dev)
                    } catch (e: IOException) {
                        throw e
                    } catch (e: RuntimeException) {
                        throw IOException("Corrupt filesystem: ${e.message ?: e.javaClass.simpleName}", e)
                    }
                    return ContainerVolume(host, dev, fs, dh.suite, dh.prf, hidden)
                } catch (e: Throwable) {
                    dh.cascade.destroy()
                    throw e
                }
            }
        } finally {
            Secrets.wipe(raw)
            Secrets.wipe(password)
        }
        throw IOException(UNSUPPORTED_MESSAGE)
    }
}
