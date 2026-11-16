package app.droidcrypter.engine.vc

import app.droidcrypter.engine.Secrets
import app.droidcrypter.engine.vc.bc.SerpentEngine
import app.droidcrypter.engine.vc.bc.SerpentEngineBase
import app.droidcrypter.engine.vc.bc.TwofishEngine
import org.bouncycastle.crypto.BlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** A 128-bit block cipher used in ECB over whole buffers (lengths are multiples of 16). Not thread-safe. */
internal interface Block128 {
    fun encrypt(buf: ByteArray, off: Int, len: Int)
    fun decrypt(buf: ByteArray, off: Int, len: Int)
    fun destroy()
}

enum class CipherAlg(val displayName: String) {
    AES("AES"), SERPENT("Serpent"), TWOFISH("Twofish");

    internal fun create(key: ByteArray): Block128 {
        require(key.size == 32) { "256-bit key required" }
        return when (this) {
            AES -> JceAes(key)
            SERPENT -> BcBlock({ SerpentEngine() }, key)
            TWOFISH -> BcBlock({ TwofishEngine() }, key)
        }
    }
}

/** AES via the platform provider (hardware-accelerated on ARMv8 / AES-NI). */
private class JceAes(key: ByteArray) : Block128 {
    private var enc: Cipher? = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
    private var dec: Cipher? = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")) }

    override fun encrypt(buf: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        val c = enc ?: error("cipher destroyed")
        val n = c.update(buf, off, len, buf, off)
        check(n == len)
    }

    override fun decrypt(buf: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        val c = dec ?: error("cipher destroyed")
        val n = c.update(buf, off, len, buf, off)
        check(n == len)
    }

    override fun destroy() {
        // JCE offers no key-schedule wipe; dropping the references is the best we can do.
        enc = null; dec = null
    }
}

/**
 * Serpent / Twofish via copies of the Bouncy Castle engines (package `vc.bc`) that can zero their
 * key schedules. Upstream BC allocates a fresh schedule on every init and keeps it private, so
 * re-keying never overwrote the old one; [destroy] now wipes it in place.
 */
private class BcBlock(factory: () -> BlockCipher, key: ByteArray) : Block128 {
    private var enc: BlockCipher?
    private var dec: BlockCipher?

    init {
        val param = KeyParameter(key) // copies key; getKey() returns that copy, wiped below
        try {
            enc = factory().apply { init(true, param) }
            dec = factory().apply { init(false, param) }
        } finally {
            Secrets.wipe(param.key)
        }
    }

    override fun encrypt(buf: ByteArray, off: Int, len: Int) {
        val c = enc ?: error("cipher destroyed")
        var i = off
        val end = off + len
        while (i < end) { c.processBlock(buf, i, buf, i); i += 16 }
    }

    override fun decrypt(buf: ByteArray, off: Int, len: Int) {
        val c = dec ?: error("cipher destroyed")
        var i = off
        val end = off + len
        while (i < end) { c.processBlock(buf, i, buf, i); i += 16 }
    }

    override fun destroy() {
        wipe(enc); wipe(dec)
        enc = null; dec = null
    }

    private fun wipe(c: BlockCipher?) {
        when (c) {
            is SerpentEngineBase -> c.wipe()
            is TwofishEngine -> c.wipe()
            null -> {}
            else -> error("no wipe for ${c.algorithmName}")
        }
    }
}

/**
 * One XTS layer (IEEE 1619) with 512-byte data units. The tweak is the little-endian data unit
 * number. Processes a whole buffer per call: one ECB pass for every unit's tweak, mask expansion
 * by repeated multiplication by α in GF(2^128) (polynomial 0x87), one ECB pass over the data.
 */
internal class XtsLayer(dataKey: ByteArray, tweakKey: ByteArray, alg: CipherAlg) {
    private val data = alg.create(dataKey)
    private val tweak = alg.create(tweakKey)

    fun process(buf: ByteArray, off: Int, len: Int, firstUnit: Long, encrypt: Boolean) {
        if (len == 0) return
        val units = (len + UNIT - 1) / UNIT
        val tw = ByteArray(units * 16)
        val masks = ByteArray(units * UNIT)
        try {
            for (u in 0 until units) {
                var n = firstUnit + u
                val b = u * 16
                for (k in 0 until 8) { tw[b + k] = n.toByte(); n = n ushr 8 }
            }
            tweak.encrypt(tw, 0, tw.size)
            for (u in 0 until units) {
                var lo = leLong(tw, u * 16)
                var hi = leLong(tw, u * 16 + 8)
                var m = u * UNIT
                for (j in 0 until UNIT / 16) {
                    putLe(masks, m, lo); putLe(masks, m + 8, hi)
                    val carry = hi < 0
                    hi = (hi shl 1) or (lo ushr 63)
                    lo = (lo shl 1) xor (if (carry) 0x87L else 0L)
                    m += 16
                }
                lo = 0; hi = 0
            }
            // For partial units (e.g. 448 bytes in DroidCrypter header), processing is performed on full 16-byte blocks
            require(len % 16 == 0) { "XTS length must be a multiple of 16" }
            xor(buf, off, masks, len)
            if (encrypt) data.encrypt(buf, off, len) else data.decrypt(buf, off, len)
            xor(buf, off, masks, len)
        } finally {
            Secrets.wipe(tw, masks)
        }
    }

    fun destroy() {
        data.destroy(); tweak.destroy()
    }

    companion object {
        const val UNIT = 512

        private fun leLong(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            return v
        }

        private fun putLe(b: ByteArray, o: Int, v: Long) {
            var x = v
            for (i in 0 until 8) { b[o + i] = x.toByte(); x = x ushr 8 }
        }

        private fun xor(buf: ByteArray, off: Int, masks: ByteArray, len: Int) {
            for (i in 0 until len) buf[off + i] = (buf[off + i].toInt() xor masks[i].toInt()).toByte()
        }
    }
}

/**
 * DroidCrypter encryption algorithms. [layers] are listed in **encryption order** (first applied
 * first); the DroidCrypter name lists the outermost cipher first, i.e. the reverse.
 */
enum class CipherSuite(val displayName: String, val layers: List<CipherAlg>) {
    AES("AES", listOf(CipherAlg.AES)),
    SERPENT("Serpent", listOf(CipherAlg.SERPENT)),
    TWOFISH("Twofish", listOf(CipherAlg.TWOFISH)),
    AES_TWOFISH("AES-Twofish", listOf(CipherAlg.TWOFISH, CipherAlg.AES)),
    SERPENT_AES("Serpent-AES", listOf(CipherAlg.AES, CipherAlg.SERPENT)),
    TWOFISH_SERPENT("Twofish-Serpent", listOf(CipherAlg.SERPENT, CipherAlg.TWOFISH)),
    AES_TWOFISH_SERPENT("AES-Twofish-Serpent", listOf(CipherAlg.SERPENT, CipherAlg.TWOFISH, CipherAlg.AES)),
    SERPENT_TWOFISH_AES("Serpent-Twofish-AES", listOf(CipherAlg.AES, CipherAlg.TWOFISH, CipherAlg.SERPENT));

    /** Bytes of key material: 32-byte data key + 32-byte tweak key per layer. */
    val keyBytes: Int get() = layers.size * 64

    /**
     * Builds the cascade from key material laid out as DroidCrypter does: the data keys of all layers in
     * encryption order, then all tweak keys in the same order. Does not keep or wipe [key].
     */
    internal fun createCascade(key: ByteArray, keyOff: Int = 0): XtsCascade {
        require(key.size - keyOff >= keyBytes)
        val half = layers.size * 32
        val built = ArrayList<XtsLayer>(layers.size)
        val dk = ByteArray(32)
        val tk = ByteArray(32)
        try {
            for ((i, alg) in layers.withIndex()) {
                System.arraycopy(key, keyOff + i * 32, dk, 0, 32)
                System.arraycopy(key, keyOff + half + i * 32, tk, 0, 32)
                built += XtsLayer(dk, tk, alg)
            }
        } finally {
            Secrets.wipe(dk, tk)
        }
        return XtsCascade(this, built)
    }

    companion object {
        /** Trial order when unlocking: cheapest first. */
        val trialOrder: List<CipherSuite> = entries.sortedBy { it.layers.size }
    }
}

/** A stack of XTS layers. Thread-safe (calls are serialised). */
internal class XtsCascade(val suite: CipherSuite, private val layers: List<XtsLayer>) {
    @Volatile
    private var destroyed = false

    @Synchronized
    fun encrypt(buf: ByteArray, off: Int, len: Int, firstUnit: Long) {
        check(!destroyed) { "Volume is locked" }
        for (l in layers) l.process(buf, off, len, firstUnit, true)
    }

    @Synchronized
    fun decrypt(buf: ByteArray, off: Int, len: Int, firstUnit: Long) {
        check(!destroyed) { "Volume is locked" }
        for (i in layers.indices.reversed()) layers[i].process(buf, off, len, firstUnit, false)
    }

    @Synchronized
    fun destroy() {
        if (destroyed) return
        destroyed = true
        layers.forEach { it.destroy() }
    }
}

