package app.droidcrypter.engine

import java.util.Locale

/**
 * Space model (1.7.0) shared by the vault list and the browser.
 */
data class VolumeSpace(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Float
        get() = if (totalBytes <= 0) 0f else (usedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    val usedPercent: Int
        get() = (usedFraction * 100f).toInt().coerceIn(0, 100)
    val isLow: Boolean get() = totalBytes > 0 && freeBytes * 10 < totalBytes // under 10 % left

    fun vaultSummaryText(): String =
        "${formatBytes(freeBytes)} free of ${formatBytes(totalBytes)} · $usedPercent% used"

    fun browserSummaryText(): String =
        "${formatBytes(freeBytes)} free of ${formatBytes(totalBytes)}"

    fun talkBackSummary(): String =
        "${formatBytes(freeBytes)} free of ${formatBytes(totalBytes)}, $usedPercent% used"

    companion object {
        /** Returns a snapshot of [v]'s total and live free space, or null if locked/unreadable. */
        fun of(v: MountedVolume): VolumeSpace? = try {
            val total = v.totalBytes
            if (total < 0) null
            else {
                val free = v.freeBytes.coerceIn(0L, total)
                VolumeSpace(total, free)
            }
        } catch (_: Throwable) {
            null
        }

        /** Human-readable byte formatter shared across the app (e.g. "512.0 MB", "1.00 GB"). */
        fun formatBytes(bytes: Long): String {
            val b = bytes.coerceAtLeast(0L)
            val kb = 1024.0
            val mb = kb * 1024.0
            val gb = mb * 1024.0
            val tb = gb * 1024.0
            return when {
                b >= tb -> String.format(Locale.US, "%.2f TB", b / tb)
                b >= gb -> String.format(Locale.US, "%.2f GB", b / gb)
                b >= mb -> String.format(Locale.US, "%.1f MB", b / mb)
                b >= kb -> String.format(Locale.US, "%.1f KB", b / kb)
                else -> "$b B"
            }
        }
    }
}

