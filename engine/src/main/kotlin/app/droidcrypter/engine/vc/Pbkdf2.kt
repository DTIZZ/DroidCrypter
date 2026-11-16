package app.droidcrypter.engine.vc

import app.droidcrypter.engine.Secrets
import org.bouncycastle.crypto.ExtendedDigest
import org.bouncycastle.crypto.digests.GOST3411_2012_512Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.digests.WhirlpoolDigest
import org.bouncycastle.util.Memoable

/** PBKDF2 pseudo-random functions supported by DroidCrypter (in unlock trial order). */
enum class Prf(val displayName: String, val hLen: Int) {
    SHA512("SHA-512", 64),
    SHA256("SHA-256", 32),
    WHIRLPOOL("Whirlpool", 64),
    STREEBOG("Streebog", 64);

    internal fun newDigest(): ExtendedDigest = when (this) {
        SHA512 -> SHA512Digest()
        SHA256 -> SHA256Digest()
        WHIRLPOOL -> WhirlpoolDigest()
        STREEBOG -> GOST3411_2012_512Digest()
    }

    internal fun newHmac(key: ByteArray): Hmac = WipeableHmac(::newDigest, key)

    companion object {
        /** Iteration count for a non-system volume: PIM 0 → 500,000, else 15,000 + PIM × 1,000. */
        fun iterations(pim: Int): Int {
            require(pim >= 0) { "PIM must not be negative" }
            return if (pim == 0) 500_000 else 15_000 + pim * 1_000
        }
    }
}

internal interface Hmac {
    val macLen: Int
    fun update(b: ByteArray, off: Int, len: Int)
    /** Writes the MAC into [out] at [off] and resets for the next message with the same key. */
    fun doFinal(out: ByteArray, off: Int)
    /** Zeroes every key-dependent buffer and digest state; the instance is unusable afterwards. */
    fun destroy()
}

/**
 * HMAC (RFC 2104) built directly on a Bouncy Castle digest so that every copy of the key lives in
 * state this class can wipe. The JCE `Mac` (and `SecretKeySpec`) keep key copies nothing can zero,
 * and BC's own `HMac` keeps its padded-key buffers private, so neither can be wiped on lock.
 *
 * The inner and outer digests are snapshotted after absorbing `key ^ ipad` / `key ^ opad`, exactly
 * as BC's `HMac` does; [destroy] resets all three digests and then overwrites them from a fresh
 * instance, which clears both the visible state and any internal block buffers.
 */
private class WipeableHmac(private val newDigest: () -> ExtendedDigest, key: ByteArray) : Hmac {
    private val digest = newDigest()
    private val inner: Memoable
    private val outer: Memoable
    private val innerHash: ByteArray
    override val macLen: Int = digest.digestSize

    init {
        val blockLen = digest.byteLength
        val pad = ByteArray(blockLen)
        try {
            if (key.size > blockLen) {
                digest.update(key, 0, key.size)
                digest.doFinal(pad, 0) // also resets the digest
            } else {
                System.arraycopy(key, 0, pad, 0, key.size)
            }
            for (i in pad.indices) pad[i] = (pad[i].toInt() xor IPAD).toByte()
            digest.update(pad, 0, blockLen)
            inner = (digest as Memoable).copy()
            digest.reset()
            for (i in pad.indices) pad[i] = (pad[i].toInt() xor (IPAD xor OPAD)).toByte()
            digest.update(pad, 0, blockLen)
            outer = (digest as Memoable).copy()
            (digest as Memoable).reset(inner)
        } finally {
            Secrets.wipe(pad)
        }
        innerHash = ByteArray(macLen)
    }

    override fun update(b: ByteArray, off: Int, len: Int) = digest.update(b, off, len)

    override fun doFinal(out: ByteArray, off: Int) {
        digest.doFinal(innerHash, 0)
        (digest as Memoable).reset(outer)
        digest.update(innerHash, 0, macLen)
        digest.doFinal(out, off)
        (digest as Memoable).reset(inner)
    }

    override fun destroy() {
        Secrets.wipe(innerHash)
        val blank = newDigest() as Memoable
        for (m in listOf(digest as Memoable, inner, outer)) {
            (m as ExtendedDigest).reset()
            m.reset(blank)
        }
    }

    private companion object {
        const val IPAD = 0x36
        const val OPAD = 0x5C
    }
}

/**
 * PBKDF2-HMAC over raw password bytes, producing output lazily one hLen block at a time.
 * PBKDF2 blocks are independent, so [bytes] only computes the blocks it needs and caches them.
 * Call [close] to wipe the cached output, the salt copy and the password copy.
 */
class Pbkdf2(val prf: Prf, password: ByteArray, salt: ByteArray, val iterations: Int) : AutoCloseable {
    private val password = password.copyOf()
    private val salt = salt.copyOf()
    private var derived = ByteArray(0)
    private var blocks = 0

    /** Returns a fresh copy of the first [n] derived bytes (caller wipes it). */
    @Synchronized
    fun bytes(n: Int): ByteArray {
        val needBlocks = (n + prf.hLen - 1) / prf.hLen
        if (needBlocks > blocks) {
            val grown = derived.copyOf(needBlocks * prf.hLen)
            Secrets.wipe(derived)
            for (b in blocks + 1..needBlocks) computeBlock(b, grown, (b - 1) * prf.hLen)
            derived = grown
            blocks = needBlocks
        }
        return derived.copyOf(n)
    }

    private fun computeBlock(index: Int, out: ByteArray, outOff: Int) {
        val mac = prf.newHmac(password)
        val h = mac.macLen
        val u = ByteArray(h)
        val t = ByteArray(h)
        val ib = byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())
        try {
            mac.update(salt, 0, salt.size)
            mac.update(ib, 0, 4)
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, t, 0, h)
            for (i in 1 until iterations) {
                mac.update(u, 0, h)
                mac.doFinal(u, 0)
                for (j in 0 until h) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, outOff, h)
        } finally {
            Secrets.wipe(u, t)
            mac.destroy()
        }
    }

    @Synchronized
    override fun close() {
        Secrets.wipe(derived, password, salt)
        derived = ByteArray(0)
        blocks = 0
    }
}

