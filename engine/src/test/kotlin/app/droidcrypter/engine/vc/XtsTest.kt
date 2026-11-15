package app.droidcrypter.engine.vc

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class XtsTest {
    private val key = ByteArray(192) { (it * 13 + 1).toByte() }

    @ParameterizedTest
    @EnumSource(CipherSuite::class)
    fun `encrypt then decrypt round-trips and depends on the unit number`(suite: CipherSuite) {
        val c = suite.createCascade(key)
        val plain = ByteArray(4096) { (it % 251).toByte() }
        val buf = plain.copyOf()
        c.encrypt(buf, 0, buf.size, 1234)
        assertThat(buf).isNotEqualTo(plain)
        val plainOther = ByteArray(4096) { (it % 251).toByte() }
        val other = plainOther.copyOf()
        c.encrypt(other, 0, other.size, 1235)
        assertThat(other.copyOf(512)).isNotEqualTo(buf.copyOf(512))
        // Unit 1235 is the second unit of the first buffer (offsets 512..1023 with data unit index 1235).
        // Since plain has 512-byte repeating pattern (251 != 512), let's test unit 1235 on identical plaintext:
        val singleUnit1235 = plain.copyOfRange(512, 1024)
        c.encrypt(singleUnit1235, 0, 512, 1235)
        assertThat(singleUnit1235).isEqualTo(buf.copyOfRange(512, 1024))
        c.decrypt(buf, 0, buf.size, 1234)
        assertThat(buf).isEqualTo(plain)
    }

    /** Reference XTS-AES (one block at a time, textbook α multiplication) vs. the batched one. */
    @Test
    fun `batched XTS-AES equals a textbook implementation`() {
        val k1 = key.copyOfRange(0, 32)
        val k2 = key.copyOfRange(32, 64)
        val plain = ByteArray(1024) { (it * 31).toByte() }
        val unit = 0x0102030405L
        val expected = plain.copyOf()
        val e1 = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k1, "AES")) }
        val e2 = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k2, "AES")) }
        for (u in 0 until 2) {
            val t = ByteArray(16)
            var n = unit + u
            for (i in 0 until 8) { t[i] = n.toByte(); n = n ushr 8 }
            var tw = e2.doFinal(t)
            for (j in 0 until 32) {
                val o = u * 512 + j * 16
                val blk = ByteArray(16) { (expected[o + it].toInt() xor tw[it].toInt()).toByte() }
                val enc = e1.doFinal(blk)
                for (i in 0 until 16) expected[o + i] = (enc[i].toInt() xor tw[i].toInt()).toByte()
                // multiply tw by alpha (little-endian byte order)
                val carry = (tw[15].toInt() and 0x80) != 0
                val next = ByteArray(16)
                for (i in 15 downTo 1) next[i] = ((tw[i].toInt() shl 1) or ((tw[i - 1].toInt() and 0xFF) ushr 7)).toByte()
                next[0] = ((tw[0].toInt() shl 1) xor (if (carry) 0x87 else 0)).toByte()
                tw = next
            }
        }
        val c = CipherSuite.AES.createCascade(k1 + k2)
        val buf = plain.copyOf()
        c.encrypt(buf, 0, buf.size, unit)
        assertThat(buf).isEqualTo(expected)
    }

    @Test
    fun `cascade names list the outermost cipher first`() {
        assertThat(CipherSuite.AES_TWOFISH_SERPENT.layers)
            .containsExactly(CipherAlg.SERPENT, CipherAlg.TWOFISH, CipherAlg.AES).inOrder()
        assertThat(CipherSuite.trialOrder.map { it.layers.size }).isInOrder()
    }
}

