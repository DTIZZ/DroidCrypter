package app.droidcrypter.security

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.droidcrypter.engine.Secrets
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class BiometricKeyInvalidatedException(
    message: String = "Biometrics changed on this device. Please unlock with your password and re-enable fingerprint unlock.",
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Unwrapped credentials from a Keystore-protected blob.
 * Always call [wipe] in a `finally` block as soon as the volume unlock attempt finishes.
 */
class UnwrappedCredentials(
    val pim: Int,
    val passwordBytes: ByteArray,
) {
    fun wipe() {
        Secrets.wipe(passwordBytes)
    }
}

/**
 * Per-container biometric / device-credential key manager (section 4.3 & Milestone 7).
 *
 * - Keystore key: AES-256-GCM, encrypt + decrypt, randomized IV required,
 *   `setUserAuthenticationRequired(true)`, `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`,
 *   `setInvalidatedByBiometricEnrollment(true)`, `setUnlockedDeviceRequired(true)`.
 * - Prefers StrongBox when available, falling back to TEE.
 * - Blob stored in `noBackupFilesDir/<containerId>.blob`: `1-byte IV length` + `IV` + `ciphertext||tag`.
 */
class BiometricKeyManager(private val context: Context) {

    private val appContext = context.applicationContext

    fun canAuthenticate(): Boolean {
        val bm = BiometricManager.from(appContext)
        return bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun isStrongBoxSupported(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    fun isEnrolled(containerId: String): Boolean {
        val blob = blobFile(containerId)
        if (!blob.isFile || blob.length() <= 2) return false
        return try {
            val ks = androidKeyStore()
            ks.containsAlias(keyAlias(containerId))
        } catch (_: Throwable) {
            false
        }
    }

    fun forget(containerId: String) {
        try {
            val ks = androidKeyStore()
            val alias = keyAlias(containerId)
            if (ks.containsAlias(alias)) {
                ks.deleteEntry(alias)
            }
        } catch (_: Throwable) {
        }
        try {
            val file = blobFile(containerId)
            if (file.exists()) {
                // Overwrite small ciphertext blob before deleting.
                val len = file.length().toInt().coerceIn(0, 4096)
                if (len > 0) file.writeBytes(ByteArray(len))
                file.delete()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Prompts the user with [BiometricPrompt] to wrap and save [passwordBytes] + [pim] for [containerId].
     * Caller retains ownership of [passwordBytes] and must wipe it after enrollment/unlock.
     */
    fun enroll(
        activity: FragmentActivity,
        containerId: String,
        passwordBytes: ByteArray,
        pim: Int,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val secretCopy = packSecret(pim, passwordBytes)
        val cipher = try {
            val key = createFreshKey(containerId)
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
        } catch (t: Throwable) {
            Secrets.wipe(secretCopy)
            forget(containerId)
            onError(t.message ?: "Could not initialize hardware keystore key.")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        val activeCipher = result.cryptoObject?.cipher ?: cipher
                        val ciphertext = activeCipher.doFinal(secretCopy)
                        val iv = activeCipher.iv
                        val encoded = encodeBlob(iv, ciphertext)
                        blobFile(containerId).writeBytes(encoded)
                        onSuccess()
                    } catch (e: KeyPermanentlyInvalidatedException) {
                        forget(containerId)
                        onError("Biometrics changed on this device. Please re-enroll with your password.")
                    } catch (t: Throwable) {
                        forget(containerId)
                        onError(t.message ?: "Failed to save encrypted fingerprint blob.")
                    } finally {
                        Secrets.wipe(secretCopy)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Secrets.wipe(secretCopy)
                    forget(containerId)
                    onError(errString.toString())
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Enable biometric unlock")
            .setSubtitle("Confirm your fingerprint or device credential to wrap this vault key")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    /**
     * Prompts the user with [BiometricPrompt] to unwrap the stored credentials for [containerId].
     * [onSuccess] receives [UnwrappedCredentials]; the callback implementation MUST call `creds.wipe()`
     * in a `finally` block once `DroidCrypter.open` completes.
     */
    fun unlock(
        activity: FragmentActivity,
        containerId: String,
        onSuccess: (UnwrappedCredentials) -> Unit,
        onInvalidated: (BiometricKeyInvalidatedException) -> Unit,
        onError: (String) -> Unit,
    ) {
        val rawBlob = try {
            val file = blobFile(containerId)
            if (!file.isFile) {
                onError("No fingerprint enrollment found for this vault.")
                return
            }
            file.readBytes()
        } catch (t: Throwable) {
            onError("Could not read fingerprint enrollment blob.")
            return
        }

        val (iv, ciphertext) = try {
            decodeBlob(rawBlob)
        } catch (t: Throwable) {
            forget(containerId)
            onError("Stored biometric data was corrupted and has been cleared. Please unlock with your password.")
            return
        }

        val cipher = try {
            val ks = androidKeyStore()
            val key = ks.getKey(keyAlias(containerId), null) as? SecretKey
                ?: throw KeyPermanentlyInvalidatedException("Key missing from AndroidKeyStore")
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            forget(containerId)
            onInvalidated(BiometricKeyInvalidatedException(cause = e))
            return
        } catch (t: Throwable) {
            // Check if the underlying cause is KeyPermanentlyInvalidatedException
            if (t.cause is KeyPermanentlyInvalidatedException) {
                forget(containerId)
                onInvalidated(BiometricKeyInvalidatedException(cause = t))
                return
            }
            onError(t.message ?: "Failed to initialize biometric key.")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    var plain: ByteArray? = null
                    try {
                        val activeCipher = result.cryptoObject?.cipher ?: cipher
                        plain = activeCipher.doFinal(ciphertext)
                        val creds = unpackSecret(plain)
                        onSuccess(creds)
                    } catch (e: KeyPermanentlyInvalidatedException) {
                        forget(containerId)
                        onInvalidated(BiometricKeyInvalidatedException(cause = e))
                    } catch (t: Throwable) {
                        onError(t.message ?: "Failed to decrypt vault credentials.")
                    } finally {
                        if (plain != null) Secrets.wipe(plain)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock DroidCrypter vault")
            .setSubtitle("Authenticate to decrypt the vault password")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    private fun createFreshKey(containerId: String): SecretKey {
        val alias = keyAlias(containerId)
        if (isStrongBoxSupported()) {
            try {
                return generateKey(alias, useStrongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                // Fall back to TEE below
            } catch (_: ProviderException) {
                // Fall back to TEE below
            }
        }
        return generateKey(alias, useStrongBox = false)
    }

    private fun generateKey(alias: String, useStrongBox: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(useStrongBox)
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun keyAlias(containerId: String): String = "droidcrypter.bio.$containerId"

    private fun blobFile(containerId: String): File {
        // Defence in depth: IDs are hex SHA-256 prefixes, so anything else must never reach the filesystem.
        require(containerId.isNotEmpty() && containerId.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid container id"
        }
        return File(appContext.noBackupFilesDir, "$containerId.blob")
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        /**
         * Packs 4-byte big-endian [pim] + [passwordBytes].
         */
        fun packSecret(pim: Int, passwordBytes: ByteArray): ByteArray {
            val out = ByteArray(4 + passwordBytes.size)
            ByteBuffer.wrap(out).putInt(pim.coerceAtLeast(0))
            System.arraycopy(passwordBytes, 0, out, 4, passwordBytes.size)
            return out
        }

        /**
         * Unpacks 4-byte big-endian [pim] + UTF-8 password bytes into a freshly allocated [UnwrappedCredentials].
         */
        fun unpackSecret(packed: ByteArray): UnwrappedCredentials {
            if (packed.size < 5) throw IOException("Wrapped credential payload is truncated")
            val pim = ByteBuffer.wrap(packed, 0, 4).int
            val pw = packed.copyOfRange(4, packed.size)
            return UnwrappedCredentials(pim = pim.coerceAtLeast(0), passwordBytes = pw)
        }

        /**
         * Blob format: `1-byte IV length` + `IV` + `ciphertext||16-byte GCM tag`.
         */
        fun encodeBlob(iv: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
            require(iv.size in 1..255) { "Invalid IV length: ${iv.size}" }
            require(ciphertextWithTag.size >= 16) { "Ciphertext shorter than GCM tag" }
            val out = ByteArray(1 + iv.size + ciphertextWithTag.size)
            out[0] = iv.size.toByte()
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(ciphertextWithTag, 0, out, 1 + iv.size, ciphertextWithTag.size)
            return out
        }

        fun decodeBlob(blob: ByteArray): Pair<ByteArray, ByteArray> {
            if (blob.size < 1 + 12 + 16) throw IOException("Biometric blob is too short")
            val ivLen = blob[0].toInt() and 0xFF
            if (ivLen !in 12..16 || blob.size < 1 + ivLen + 16) {
                throw IOException("Biometric blob has invalid IV length ($ivLen)")
            }
            val iv = blob.copyOfRange(1, 1 + ivLen)
            val ct = blob.copyOfRange(1 + ivLen, blob.size)
            return iv to ct
        }
    }
}

