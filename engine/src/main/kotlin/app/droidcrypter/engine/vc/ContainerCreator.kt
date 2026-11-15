package app.droidcrypter.engine.vc

import app.droidcrypter.engine.BlockDevice
import app.droidcrypter.engine.Secrets
import app.droidcrypter.engine.fs.ExFatFormatter
import app.droidcrypter.engine.fs.FatFormatter
import java.io.IOException

enum class FormatFilesystem(val displayName: String) {
    EXFAT("exFAT"),
    FAT("FAT"),
}

enum class CreatePhase(val label: String) {
    DERIVING_KEYS("Deriving keys"),
    WRITING_HEADERS("Writing headers"),
    ENCRYPTING_FREE_SPACE("Encrypting free space"),
    FORMATTING("Formatting"),
    VERIFYING("Verifying"),
    DONE("Done"),
}

data class CreateSpec(
    val cipher: CipherSuite = CipherSuite.AES,
    val prf: Prf = Prf.SHA512,
    val pim: Int = 0,
    val filesystem: FormatFilesystem = FormatFilesystem.EXFAT,
    val label: String = "",
    val quickFormat: Boolean = false,
)

/**
 * Creates a standard (non-hidden) DroidCrypter container on a writable [BlockDevice] of at least 2 MB:
 * ```
 * [0, 512)              primary header
 * [512, 131072)         random (the hidden-volume header slot at 65536 is random too)
 * [131072, end-131072)  data area
 * [end-131072, +512)    backup header (own random salt, same master keys)
 * [.., end)             random
 * ```
 * Takes ownership of [password] and wipes it in a `finally` block before returning or throwing.
 */
object ContainerCreator {
    const val MIN_VOLUME_BYTES = 2L * 1024 * 1024

    fun create(
        host: BlockDevice,
        password: ByteArray,
        spec: CreateSpec = CreateSpec(),
        onProgress: (CreatePhase, Float) -> Unit = { _, _ -> },
        cancel: () -> Unit = {},
    ) {
        val masterKeys = ByteArray(VolumeHeader.MASTER_KEY_SIZE)
        val primarySalt = ByteArray(VolumeHeader.SALT_SIZE)
        val backupSalt = ByteArray(VolumeHeader.SALT_SIZE)
        val verifyPw = password.copyOf()
        try {
            Secrets.passwordProblem(password)?.let { throw IOException(it) }
            if (spec.pim < 0) throw IOException("PIM must not be negative")
            if (!host.isWritable) throw IOException("Target container is not writable")
            val totalSize = (host.size / 512L) * 512L
            if (totalSize < MIN_VOLUME_BYTES) {
                throw IOException("Container must be at least 2 MB")
            }
            val dataOffset = VolumeHeader.HEADER_GROUP_SIZE
            val dataSize = totalSize - 2 * VolumeHeader.HEADER_GROUP_SIZE

            cancel()
            onProgress(CreatePhase.DERIVING_KEYS, 0f)
            Secrets.random.nextBytes(masterKeys)
            Secrets.random.nextBytes(primarySalt)
            do {
                Secrets.random.nextBytes(backupSalt)
            } while (backupSalt.contentEquals(primarySalt))

            val primaryHeader = HeaderCrypto.buildHeader(
                primarySalt, password, spec.pim, spec.prf, spec.cipher, masterKeys, totalSize, dataOffset, dataSize
            )
            cancel()
            onProgress(CreatePhase.DERIVING_KEYS, 0.5f)
            val backupHeader = HeaderCrypto.buildHeader(
                backupSalt, password, spec.pim, spec.prf, spec.cipher, masterKeys, totalSize, dataOffset, dataSize
            )
            onProgress(CreatePhase.DERIVING_KEYS, 1f)

            // Phase 2: Write primary & backup headers and random filler in both header groups.
            cancel()
            onProgress(CreatePhase.WRITING_HEADERS, 0f)
            val tailFillerLen = (VolumeHeader.HEADER_GROUP_SIZE - VolumeHeader.SIZE).toInt()
            val randomBuf = ByteArray(tailFillerLen)
            try {
                host.write(0L, primaryHeader, 0, VolumeHeader.SIZE)
                Secrets.random.nextBytes(randomBuf)
                host.write(VolumeHeader.SIZE.toLong(), randomBuf, 0, tailFillerLen)
                onProgress(CreatePhase.WRITING_HEADERS, 0.5f)

                val backupOffset = totalSize - VolumeHeader.HEADER_GROUP_SIZE
                host.write(backupOffset, backupHeader, 0, VolumeHeader.SIZE)
                Secrets.random.nextBytes(randomBuf)
                host.write(backupOffset + VolumeHeader.SIZE, randomBuf, 0, tailFillerLen)
                onProgress(CreatePhase.WRITING_HEADERS, 1f)
            } finally {
                Secrets.wipe(primaryHeader, backupHeader, randomBuf)
            }

            // Phase 3 & 4: Encrypt free space (if full format) and format filesystem through encryption.
            val cascade = spec.cipher.createCascade(masterKeys)
            val dev = DecryptedDevice(host, dataOffset, dataSize, cascade, true)
            try {
                if (!spec.quickFormat) {
                    onProgress(CreatePhase.ENCRYPTING_FREE_SPACE, 0f)
                    val zeroChunk = ByteArray(minOf(dataSize, 262_144L).toInt())
                    var written = 0L
                    while (written < dataSize) {
                        cancel()
                        val n = minOf(zeroChunk.size.toLong(), dataSize - written).toInt()
                        dev.write(written, zeroChunk, 0, n)
                        written += n
                        onProgress(CreatePhase.ENCRYPTING_FREE_SPACE, (written.toDouble() / dataSize).toFloat().coerceIn(0f, 1f))
                    }
                }
                cancel()
                onProgress(CreatePhase.FORMATTING, 0f)
                when (spec.filesystem) {
                    FormatFilesystem.EXFAT -> ExFatFormatter.format(dev, spec.label)
                    FormatFilesystem.FAT -> FatFormatter.format(dev, spec.label)
                }
                dev.sync()
                onProgress(CreatePhase.FORMATTING, 1f)
            } finally {
                cascade.destroy()
            }

            // Phase 5: Re-unlock the new container through the normal unlock path to verify it opens.
            cancel()
            onProgress(CreatePhase.VERIFYING, 0f)
            val nonClosingHost = object : BlockDevice by host {
                override fun close() {} // Don't close caller's host when closing the verification mount.
            }
            DroidCrypter.open(nonClosingHost, verifyPw.copyOf(), spec.pim, spec.prf, cancel).use { vol ->
                vol.list("/")
                vol.freeBytes
            }
            onProgress(CreatePhase.VERIFYING, 1f)
            onProgress(CreatePhase.DONE, 1f)
        } finally {
            Secrets.wipe(password, verifyPw, masterKeys, primarySalt, backupSalt)
        }
    }
}

