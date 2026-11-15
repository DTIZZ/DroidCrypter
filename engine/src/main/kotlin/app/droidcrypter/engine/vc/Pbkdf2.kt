package app.droidcrypter.engine.vc

import app.droidcrypter.engine.Secrets
import org.bouncycastle.crypto.digests.GOST3411_2012_512Digest
import org.bouncycastle.crypto.digests.WhirlpoolDigest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** PBKDF2 pseudo-random functions supported by DroidCrypter (in unlock trial order). */
enum class Prf(val displayName: String, val hLen: Int) {
    SHA512("SHA-512", 64),
    SHA256("SHA-256", 32),
    WHIRLPOOL("Whirlpool", 64),
    STREEBOG("Streebog", 64);

    internal fun newHmac(key: ByteArray): Hmac = when (this) {
        SHA512 -> JceHmac("HmacSHA512", key)
        SHA256 -> JceHmac("HmacSHA256", key)
        WHIRLPOOL -> BcHmac(HMac(WhirlpoolDigest()), key)
        STREEBOG -> BcHmac(HMac(GOST3411_2012_512Digest()), key)
    }

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
}

private class JceHmac(alg: String, key: ByteArray) : Hmac {
    // SecretKeySpec copies the key; the JVM gives no way to wipe that copy (documented limit).
    private val mac: Mac = Mac.getInstance(alg).apply { init(SecretKeySpec(key, alg)) }
    override val macLen = mac.macLength
    override fun update(b: ByteArray, off: Int, len: Int) = mac.update(b, off, len)
    override fun doFinal(out: ByteArray, off: Int) {
        mac.doFinal(out, off)
    }
}

private class BcHmac(private val mac: HMac, key: ByteArray) : Hmac {
    init {
        mac.init(KeyParameter(key))
    }

    override val macLen = mac.macSize
    override fun update(b: ByteArray, off: Int, len: Int) = mac.update(b, off, len)
    override fun doFinal(out: ByteArray, off: Int) {
        mac.doFinal(out, off)
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
        }
    }

    @Synchronized
    override fun close() {
        Secrets.wipe(derived, password, salt)
        derived = ByteArray(0)
        blocks = 0
    }
}

