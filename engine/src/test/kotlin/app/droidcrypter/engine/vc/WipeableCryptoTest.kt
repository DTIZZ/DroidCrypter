package app.droidcrypter.engine.vc

import app.droidcrypter.engine.vc.bc.SerpentEngine
import app.droidcrypter.engine.vc.bc.TwofishEngine
import com.google.common.truth.Truth.assertThat
import org.bouncycastle.crypto.BlockCipher
import org.bouncycastle.crypto.digests.EncodableDigest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.Random

/**
 * The vendored Serpent/Twofish engines and [Prf.newHmac] must produce exactly what upstream Bouncy
 * Castle produces, and their wipe/destroy must really zero the key material.
 */
class WipeableCryptoTest {
    private val rnd = Random(0x5eed)

    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    private fun assertSameCipher(ours: () -> BlockCipher, upstream: () -> BlockCipher, keyLen: Int) {
        repeat(50) {
            val key = bytes(keyLen)
            for (forEncryption in listOf(true, false)) {
                val a = ours().apply { init(forEncryption, KeyParameter(key)) }
                val b = upstream().apply { init(forEncryption, KeyParameter(key)) }
                repeat(20) {
                    val block = bytes(16)
                    val outA = ByteArray(16)
                    val outB = ByteArray(16)
                    a.processBlock(block, 0, outA, 0)
                    b.processBlock(block, 0, outB, 0)
                    assertThat(outA).isEqualTo(outB)
                }
            }
        }
    }

    @Test
    fun serpentMatchesUpstream() {
        for (len in listOf(16, 24, 32)) {
            assertSameCipher({ SerpentEngine() }, { org.bouncycastle.crypto.engines.SerpentEngine() }, len)
        }
    }

    @Test
    fun twofishMatchesUpstream() {
        for (len in listOf(16, 24, 32)) {
            assertSameCipher({ TwofishEngine() }, { org.bouncycastle.crypto.engines.TwofishEngine() }, len)
        }
    }

    @Test
    fun twofishResetAndReinitMatchUpstream() {
        val k1 = bytes(32)
        val k2 = bytes(32)
        val ours = TwofishEngine().apply { init(true, KeyParameter(k1)); reset(); init(true, KeyParameter(k2)) }
        val upstream = org.bouncycastle.crypto.engines.TwofishEngine().apply { init(true, KeyParameter(k2)) }
        val block = bytes(16)
        val a = ByteArray(16)
        val b = ByteArray(16)
        ours.processBlock(block, 0, a, 0)
        upstream.processBlock(block, 0, b, 0)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun serpentWipeZeroesScheduleInPlace() {
        val e = SerpentEngine().apply { init(true, KeyParameter(bytes(32))) }
        val field = Class.forName("app.droidcrypter.engine.vc.bc.SerpentEngineBase").getDeclaredField("wKey")
            .apply { isAccessible = true }
        val schedule = field.get(e) as IntArray
        assertThat(schedule.any { it != 0 }).isTrue()
        e.wipe()
        assertThat(schedule.all { it == 0 }).isTrue()
        assertThrows(IllegalStateException::class.java) { e.processBlock(ByteArray(16), 0, ByteArray(16), 0) }
    }

    @Test
    fun twofishWipeZeroesKeyAndTablesInPlace() {
        val key = bytes(32)
        val param = KeyParameter(key)
        val e = TwofishEngine().apply { init(false, param) }
        fun <T> field(name: String): T {
            @Suppress("UNCHECKED_CAST")
            return TwofishEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(e) as T
        }
        val working: ByteArray = field("workingKey")
        val subKeys: IntArray = field("gSubKeys")
        val sBox: IntArray = field("gSBox")
        assertThat(working).isNotSameInstanceAs(param.key) // engine owns its own copy
        assertThat(working).isEqualTo(key)
        e.wipe()
        assertThat(working.all { it == 0.toByte() }).isTrue()
        assertThat(subKeys.all { it == 0 }).isTrue()
        assertThat(sBox.all { it == 0 }).isTrue()
        assertThat(param.key).isEqualTo(key) // caller's parameter left for the caller to wipe
        assertThrows(IllegalStateException::class.java) { e.processBlock(ByteArray(16), 0, ByteArray(16), 0) }
    }

    @ParameterizedTest
    @EnumSource(Prf::class)
    fun hmacMatchesUpstream(prf: Prf) {
        for (keyLen in listOf(0, 1, 20, 63, 64, 65, 127, 128, 129, 300)) {
            val key = bytes(keyLen)
            val ours = prf.newHmac(key)
            val upstream = HMac(prf.newDigest()).apply { init(KeyParameter(key)) }
            assertThat(ours.macLen).isEqualTo(prf.hLen)
            repeat(3) { // the MAC must reset correctly between messages
                val msg = bytes(rnd.nextInt(400))
                val a = ByteArray(ours.macLen)
                val b = ByteArray(upstream.macSize)
                ours.update(msg, 0, msg.size)
                ours.doFinal(a, 0)
                upstream.update(msg, 0, msg.size)
                upstream.doFinal(b, 0)
                assertThat(a).isEqualTo(b)
            }
            ours.destroy()
        }
    }

    @ParameterizedTest
    @EnumSource(value = Prf::class, names = ["SHA512", "SHA256"])
    fun hmacDestroyResetsAllDigestStates(prf: Prf) {
        val mac = prf.newHmac("correct horse battery staple".toByteArray())
        val blank = (prf.newDigest() as EncodableDigest).encodedState
        fun state(name: String) =
            (mac.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(mac) as EncodableDigest).encodedState
        assertThat(state("inner")).isNotEqualTo(blank)
        assertThat(state("outer")).isNotEqualTo(blank)
        mac.update(ByteArray(10), 0, 10) // leave data buffered in the working digest
        mac.destroy()
        for (name in listOf("digest", "inner", "outer")) assertThat(state(name)).isEqualTo(blank)
    }
}
