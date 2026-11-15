package app.droidcrypter.engine.vc

import app.droidcrypter.engine.Secrets
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class Pbkdf2Test {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `RFC 7914 style vectors for SHA-256`() {
        Pbkdf2(Prf.SHA256, "password".toByteArray(), "salt".toByteArray(), 1).use {
            assertThat(hex(it.bytes(32))).isEqualTo("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b")
        }
        Pbkdf2(Prf.SHA256, "password".toByteArray(), "salt".toByteArray(), 2).use {
            assertThat(hex(it.bytes(32))).isEqualTo("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43")
        }
    }

    /** Cross-checks against the JCE implementation (ASCII passwords encode identically). */
    @ParameterizedTest
    @CsvSource("SHA512,PBKDF2WithHmacSHA512,1000,192", "SHA256,PBKDF2WithHmacSHA256,1000,192", "SHA512,PBKDF2WithHmacSHA512,3,64")
    fun `matches JCE PBKDF2 including multi-block output`(prf: Prf, jce: String, iterations: Int, len: Int) {
        val salt = ByteArray(64) { (it * 7 + 3).toByte() }
        val expected = SecretKeyFactory.getInstance(jce)
            .generateSecret(PBEKeySpec("correct horse".toCharArray(), salt, iterations, len * 8)).encoded
        Pbkdf2(prf, "correct horse".toByteArray(), salt, iterations).use {
            // Lazy: ask for a prefix first, then grow; both must be prefixes of the full output.
            assertThat(it.bytes(16)).isEqualTo(expected.copyOf(16))
            assertThat(it.bytes(len)).isEqualTo(expected)
        }
    }

    @Test
    fun `iteration counts follow PIM rules`() {
        assertThat(Prf.iterations(0)).isEqualTo(500_000)
        assertThat(Prf.iterations(5)).isEqualTo(20_000)
        assertThat(Prf.iterations(485)).isEqualTo(500_000)
    }

    @Test
    fun `utf8 encoding without String matches the platform encoder`() {
        val s = "pässwörd 日本 \uD83D\uDE00 x"
        val bytes = Secrets.utf8(s.toCharArray())
        assertThat(bytes).isEqualTo(s.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `password validation`() {
        assertThat(Secrets.passwordProblem(ByteArray(0))).isNotNull()
        assertThat(Secrets.passwordProblem(ByteArray(128))).isNull()
        assertThat(Secrets.passwordProblem(ByteArray(129))).isNotNull()
    }
}

