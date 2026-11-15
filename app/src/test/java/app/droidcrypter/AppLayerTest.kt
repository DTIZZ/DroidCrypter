package app.droidcrypter

import android.net.Uri
import androidx.media3.common.C
import app.droidcrypter.browse.FileOperations
import app.droidcrypter.create.ContainerCreationService
import app.droidcrypter.engine.FsFileHandle
import app.droidcrypter.engine.FsNode
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.engine.vc.CreatePhase
import app.droidcrypter.provider.VolumeUris
import app.droidcrypter.security.BiometricKeyManager
import app.droidcrypter.settings.AppPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class AppLayerTest {

    @Test
    fun `containerIdForUri returns 24 lowercase hex chars of first 12 SHA-256 bytes`() {
        val id1 = AppPreferences.containerIdForUri("content://com.android.externalstorage.documents/document/primary%3Avault.hc")
        val id2 = AppPreferences.containerIdForUri("content://com.android.externalstorage.documents/document/primary%3Avault2.hc")
        assertThat(id1).hasLength(24)
        assertThat(id1).matches("[0-9a-f]{24}")
        assertThat(id1).isNotEqualTo(id2)
        // Deterministic
        assertThat(AppPreferences.containerIdForUri("content://com.android.externalstorage.documents/document/primary%3Avault.hc"))
            .isEqualTo(id1)
    }

    @Test
    fun `BiometricKeyManager secret packing, unpacking, wiping, and blob encoding round-trip`() {
        val pw = "CorrectHorseBatteryStaple-Ünïcødé!".toByteArray(Charsets.UTF_8)
        val packed = BiometricKeyManager.packSecret(pim = 485, passwordBytes = pw)
        assertThat(packed.size).isEqualTo(4 + pw.size)

        val creds = BiometricKeyManager.unpackSecret(packed)
        assertThat(creds.pim).isEqualTo(485)
        assertThat(creds.passwordBytes).isEqualTo(pw)

        // Wipe zeroes the unwrapped password bytes in RAM
        creds.wipe()
        assertThat(creds.passwordBytes).isEqualTo(ByteArray(pw.size))

        // Blob encode / decode with 12-byte GCM IV + ciphertext||16-byte tag
        val iv = ByteArray(12) { (it + 1).toByte() }
        val ctWithTag = ByteArray(36) { (it + 0x20).toByte() }
        val blob = BiometricKeyManager.encodeBlob(iv, ctWithTag)
        assertThat(blob[0].toInt() and 0xFF).isEqualTo(12)

        val (decodedIv, decodedCt) = BiometricKeyManager.decodeBlob(blob)
        assertThat(decodedIv).isEqualTo(iv)
        assertThat(decodedCt).isEqualTo(ctWithTag)

        // Reject truncated or invalid IV blobs
        assertThrows<IOException> { BiometricKeyManager.decodeBlob(ByteArray(10)) }
        val badIvBlob = blob.copyOf().also { it[0] = 8 }
        assertThrows<IOException> { BiometricKeyManager.decodeBlob(badIvBlob) }
        assertThrows<IOException> { BiometricKeyManager.unpackSecret(ByteArray(4)) }
    }

    @Test
    fun `FileOperations renameInitialSelectionEnd selects base name excluding extension for files`() {
        assertThat(FileOperations.renameInitialSelectionEnd("movie.mp4", isDirectory = false)).isEqualTo(5)
        assertThat(FileOperations.renameInitialSelectionEnd("archive.tar.gz", isDirectory = false)).isEqualTo(11)
        assertThat(FileOperations.renameInitialSelectionEnd(".hidden", isDirectory = false)).isEqualTo(7)
        assertThat(FileOperations.renameInitialSelectionEnd("README", isDirectory = false)).isEqualTo(6)
        assertThat(FileOperations.renameInitialSelectionEnd("Folder.With.Dots", isDirectory = true)).isEqualTo(16)
    }

    @Test
    fun `ContainerCreationService formatPhaseText reports all creation phases`() {
        assertThat(ContainerCreationService.formatPhaseText(CreatePhase.DERIVING_KEYS, 0.2f))
            .isEqualTo("Deriving header keys (1/2)...")
        assertThat(ContainerCreationService.formatPhaseText(CreatePhase.DERIVING_KEYS, 0.7f))
            .isEqualTo("Deriving header keys (2/2)...")
        assertThat(ContainerCreationService.formatPhaseText(CreatePhase.WRITING_HEADERS, 0.5f))
            .isEqualTo("Writing volume headers...")
        assertThat(ContainerCreationService.formatPhaseText(CreatePhase.ENCRYPTING_FREE_SPACE, 0.45f))
            .isEqualTo("Formatting container (45%)...")
        assertThat(ContainerCreationService.formatPhaseText(CreatePhase.FORMATTING, 0.5f))
            .isEqualTo("Writing filesystem...")
        assertThat(ContainerCreationService.formatPhaseText(CreatePhase.VERIFYING, 0.5f))
            .isEqualTo("Verifying container...")
    }
}

