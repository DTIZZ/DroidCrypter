package app.droidcrypter.engine

import java.security.SecureRandom

/**
 * Key-material hygiene helpers.
 *
 * [wipe] zeroes a byte array. On Android the app installs a native wiper (pins the array with
 * GetPrimitiveArrayCritical and zeroes through a volatile pointer behind a compiler barrier) via
 * [installWiper]; on the host JVM the fallback is a plain `fill(0)`.
 */
object Secrets {
    @Volatile
    private var wiper: (ByteArray) -> Unit = { it.fill(0) }

    val random: SecureRandom by lazy { SecureRandom() }

    fun installWiper(w: (ByteArray) -> Unit) {
        wiper = w
    }

    fun wipe(b: ByteArray?) {
        if (b == null || b.isEmpty()) return
        try {
            wiper(b)
        } catch (_: Throwable) {
            b.fill(0)
        }
    }

    fun wipe(c: CharArray?) {
        c?.fill('\u0000')
    }

    fun wipe(vararg arrays: ByteArray?) {
        for (a in arrays) wipe(a)
    }

    /** Max password length in UTF-8 bytes, as in DroidCrypter. */
    const val MAX_PASSWORD_BYTES = 128

    /**
     * Encodes [chars] to UTF-8 without ever creating a [String]. Unpaired surrogates become '?'.
     * Every intermediate buffer is wiped; the caller owns (and must wipe) the result.
     */
    fun utf8(chars: CharArray, offset: Int = 0, length: Int = chars.size - offset): ByteArray {
        val tmp = ByteArray(length * 4)
        var n = 0
        try {
            var i = offset
            val end = offset + length
            while (i < end) {
                val c = chars[i]
                var cp = c.code
                if (Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(chars[i + 1])) {
                    cp = Character.toCodePoint(c, chars[i + 1]); i++
                } else if (Character.isSurrogate(c)) {
                    cp = '?'.code
                }
                when {
                    cp < 0x80 -> tmp[n++] = cp.toByte()
                    cp < 0x800 -> {
                        tmp[n++] = (0xC0 or (cp shr 6)).toByte()
                        tmp[n++] = (0x80 or (cp and 0x3F)).toByte()
                    }
                    cp < 0x10000 -> {
                        tmp[n++] = (0xE0 or (cp shr 12)).toByte()
                        tmp[n++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                        tmp[n++] = (0x80 or (cp and 0x3F)).toByte()
                    }
                    else -> {
                        tmp[n++] = (0xF0 or (cp shr 18)).toByte()
                        tmp[n++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                        tmp[n++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                        tmp[n++] = (0x80 or (cp and 0x3F)).toByte()
                    }
                }
                cp = 0
                i++
            }
            return tmp.copyOf(n)
        } finally {
            wipe(tmp)
        }
    }

    /** Validates a password: non-empty and at most 128 UTF-8 bytes. Returns an error message or null. */
    fun passwordProblem(passwordUtf8: ByteArray): String? = when {
        passwordUtf8.isEmpty() -> "Enter a password"
        passwordUtf8.size > MAX_PASSWORD_BYTES -> "Password is longer than $MAX_PASSWORD_BYTES bytes"
        else -> null
    }

    fun utf8Bytes(chars: CharArray): ByteArray = utf8(chars)

    fun validatePasswordLength(passwordUtf8: ByteArray) {
        passwordProblem(passwordUtf8)?.let { throw IllegalArgumentException(it) }
    }

    /** Random, URI-safe 16-hex-char identifier. */
    fun randomHexId(): String {
        val b = ByteArray(8)
        random.nextBytes(b)
        return buildString(16) { for (x in b) append("%02x".format(x.toInt() and 0xFF)) }
    }
}

