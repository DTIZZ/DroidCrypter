package app.droidcrypter.editor

import java.util.Locale
import kotlin.math.roundToLong

/** A closed-open microsecond interval `[startUs, endUs)` with `0 <= startUs < endUs`. */
data class UsRange(val startUs: Long, val endUs: Long) {
    init {
        require(startUs >= 0 && endUs > startUs) { "Invalid range [$startUs, $endUs)" }
    }
    val durationUs: Long get() = endUs - startUs
}

/**
 * Immutable Edit Decision List (section 8.1):
 * - Source duration in µs;
 * - A normalized, sorted, merged set of **removed ranges** clamped to `[0, sourceDurationUs]`;
 * - Derived retained ranges and output duration. Trims are just removals touching 0 or the end.
 */
data class EditDecisionList(
    val sourceDurationUs: Long,
    val removedRanges: List<UsRange> = emptyList(),
) {
    val retainedRanges: List<UsRange>
    val removedDurationUs: Long
    val outputDurationUs: Long
    val hasCuts: Boolean get() = removedRanges.isNotEmpty()

    init {
        require(sourceDurationUs >= 0) { "Negative source duration" }
        val normalized = normalizeRanges(removedRanges, sourceDurationUs)
        val retained = ArrayList<UsRange>()
        var cursor = 0L
        for (r in normalized) {
            if (r.startUs > cursor) {
                retained += UsRange(cursor, r.startUs)
            }
            cursor = r.endUs
        }
        if (cursor < sourceDurationUs) {
            retained += UsRange(cursor, sourceDurationUs)
        }
        retainedRanges = retained
        removedDurationUs = normalized.sumOf { it.durationUs }
        outputDurationUs = (sourceDurationUs - removedDurationUs).coerceAtLeast(0L)
    }

    /** Replaces cut at [index] with `[newStartUs, newEndUs)` and re-normalizes. */
    fun updateCut(index: Int, newStartUs: Long, newEndUs: Long): EditDecisionList {
        val s = minOf(newStartUs, newEndUs).coerceIn(0L, sourceDurationUs)
        val e = maxOf(newStartUs, newEndUs).coerceIn(0L, sourceDurationUs)
        require(index in removedRanges.indices) { "Cut index out of bounds" }
        require(e > s) { "End must be after start." }
        val updated = removedRanges.toMutableList().apply { this[index] = UsRange(s, e) }
        return EditDecisionList(sourceDurationUs, normalizeRanges(updated, sourceDurationUs))
    }

    fun updateCutAt(index: Int, newStartUs: Long, newEndUs: Long): EditDecisionList =
        updateCut(index, newStartUs, newEndUs)

    /** Adds a cut, swapping start/end if needed, and merges overlapping or adjacent cuts. */
    fun addCut(startUs: Long, endUs: Long, allowSwap: Boolean = true): EditDecisionList {
        val lo = if (allowSwap) minOf(startUs, endUs) else startUs
        val hi = if (allowSwap) maxOf(startUs, endUs) else endUs
        val s = lo.coerceIn(0L, sourceDurationUs)
        val e = hi.coerceIn(0L, sourceDurationUs)
        require(e > s) { "Selection is invalid: mark a start before the end." }
        return EditDecisionList(sourceDurationUs, normalizeRanges(removedRanges + UsRange(s, e), sourceDurationUs))
    }

    /** Removes cut at [index]. */
    fun removeCutAt(index: Int): EditDecisionList {
        require(index in removedRanges.indices) { "Cut index out of bounds" }
        val updated = removedRanges.toMutableList().apply { removeAt(index) }
        return EditDecisionList(sourceDurationUs, normalizeRanges(updated, sourceDurationUs))
    }

    fun clearAll(): EditDecisionList = EditDecisionList(sourceDurationUs, emptyList())

    /**
     * When "Preview edited" is ON, if [playheadUs] falls inside a removed range, returns the end of
     * that removed range (so playback skips over it); otherwise returns [playheadUs].
     */
    fun skipRemovedAt(playheadUs: Long): Long {
        for (r in removedRanges) {
            if (playheadUs >= r.startUs && playheadUs < r.endUs) {
                return r.endUs
            }
        }
        return playheadUs
    }

    companion object {
        fun normalizeRanges(ranges: List<UsRange>, maxDurationUs: Long): List<UsRange> {
            if (ranges.isEmpty() || maxDurationUs <= 0L) return emptyList()
            val clamped = ranges.mapNotNull { r ->
                val s = r.startUs.coerceIn(0L, maxDurationUs)
                val e = r.endUs.coerceIn(0L, maxDurationUs)
                if (e > s) UsRange(s, e) else null
            }.sortedWith(compareBy<UsRange> { it.startUs }.thenBy { it.endUs })
            if (clamped.isEmpty()) return emptyList()
            val merged = ArrayList<UsRange>(clamped.size)
            var cur = clamped[0]
            for (i in 1 until clamped.size) {
                val next = clamped[i]
                cur = if (next.startUs <= cur.endUs) {
                    UsRange(cur.startUs, maxOf(cur.endUs, next.endUs))
                } else {
                    merged += cur
                    next
                }
            }
            merged += cur
            return merged
        }
    }
}

/** Linear undo/redo history over full [EditDecisionList] snapshots. */
class EdlHistory(initial: EditDecisionList) {
    private val snapshots = ArrayList<EditDecisionList>().apply { add(initial) }
    private var index = 0

    val current: EditDecisionList get() = snapshots[index]
    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < snapshots.lastIndex

    fun push(next: EditDecisionList): EditDecisionList {
        if (next == current) return current
        while (snapshots.lastIndex > index) {
            snapshots.removeAt(snapshots.lastIndex)
        }
        snapshots += next
        index = snapshots.lastIndex
        return current
    }

    fun undo(): EditDecisionList {
        if (canUndo) index--
        return current
    }

    fun redo(): EditDecisionList {
        if (canRedo) index++
        return current
    }

    fun reset(newInitial: EditDecisionList) {
        snapshots.clear()
        snapshots += newInitial
        index = 0
    }
}

/**
 * Pure, unit-testable timeline zoom/scroll and time-formatting math in µs (section 8.2):
 * - Zoom in/out multiplies/divides `pxPerSecond` by 1.5;
 * - Visible thumbnail slots are computed for the visible range plus one slot each side;
 * - Frames are bucketed into 100 ms (100,000 µs) timestamp buckets;
 * - Time format `HH:MM:SS.mmm`, parser accepts `HH:MM:SS.mmm`, `MM:SS.mmm`, or plain seconds.
 */
object TimelineMath {
    const val ZOOM_FACTOR = 1.5f
    const val DEFAULT_PX_PER_SEC = 80f
    const val MIN_PX_PER_SEC = 4f
    const val MAX_PX_PER_SEC = 600f
    const val BUCKET_US = 100_000L // 100 ms buckets

    fun zoomIn(pxPerSec: Float): Float = (pxPerSec * ZOOM_FACTOR).coerceIn(MIN_PX_PER_SEC, MAX_PX_PER_SEC)
    fun zoomOut(pxPerSec: Float): Float = (pxPerSec / ZOOM_FACTOR).coerceIn(MIN_PX_PER_SEC, MAX_PX_PER_SEC)

    fun usToPx(timeUs: Long, pxPerSec: Float): Float =
        (timeUs.toDouble() / 1_000_000.0 * pxPerSec).toFloat()

    fun timeUsToX(timeUs: Long, pxPerSec: Float): Float = usToPx(timeUs, pxPerSec)

    fun timelineWidthPx(durationUs: Long, pxPerSec: Float): Float =
        usToPx(durationUs, pxPerSec).coerceAtLeast(280f)

    fun pxToUs(px: Float, pxPerSec: Float, durationUs: Long): Long {
        if (pxPerSec <= 0f) return 0L
        val us = (px.toDouble() / pxPerSec * 1_000_000.0).roundToLong()
        return us.coerceIn(0L, durationUs.coerceAtLeast(0L))
    }

    fun xToTimeUs(px: Float, pxPerSec: Float): Long {
        if (pxPerSec <= 0f) return 0L
        return (px.toDouble() / pxPerSec * 1_000_000.0).roundToLong().coerceAtLeast(0L)
    }

    /** Snaps a timestamp in µs to its 100 ms bucket (`0, 100_000, 200_000, ...`). */
    fun bucket100ms(timeUs: Long): Long = (timeUs.coerceAtLeast(0L) / BUCKET_US) * BUCKET_US

    fun quantize100Ms(timeUs: Long): Long = bucket100ms(timeUs)

    /**
     * Computes the list of 100ms-bucketed timestamps in µs for thumbnail slots covering the visible
     * viewport `[scrollPx, scrollPx + viewportWidthPx]` **plus one slot on each side**.
     */
    fun visibleSlotTimestampsUs(
        durationUs: Long,
        pxPerSec: Float,
        slotWidthPx: Float,
        scrollPx: Float,
        viewportWidthPx: Float,
    ): List<Long> {
        if (durationUs <= 0L || pxPerSec <= 0f || slotWidthPx <= 0f || viewportWidthPx <= 0f) return emptyList()
        val totalWidthPx = usToPx(durationUs, pxPerSec).coerceAtLeast(slotWidthPx)
        val totalSlots = kotlin.math.ceil(totalWidthPx / slotWidthPx).toInt().coerceAtLeast(1)
        val firstVisible = kotlin.math.floor(scrollPx / slotWidthPx).toInt()
        val lastVisible = kotlin.math.floor((scrollPx + viewportWidthPx) / slotWidthPx).toInt()
        val startSlot = (firstVisible - 1).coerceIn(0, totalSlots - 1)
        val endSlot = (lastVisible + 1).coerceIn(0, totalSlots - 1)
        if (startSlot > endSlot) return emptyList()
        return (startSlot..endSlot).map { slot ->
            val centerPx = (slot + 0.5f) * slotWidthPx
            bucket100ms(pxToUs(centerPx, pxPerSec, durationUs))
        }
    }

    /** Formats microseconds as `HH:MM:SS.mmm`. */
    fun formatTimeUs(timeUs: Long): String {
        val msTotal = (timeUs.coerceAtLeast(0L) + 500L) / 1000L
        val ms = msTotal % 1000L
        val secTotal = msTotal / 1000L
        val sec = secTotal % 60L
        val minTotal = secTotal / 60L
        val min = minTotal % 60L
        val hours = minTotal / 60L
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, min, sec, ms)
    }

    /**
     * Parses `HH:MM:SS.mmm`, `MM:SS.mmm`, or plain seconds (e.g. `12.5`) into microseconds.
     * Returns `null` if invalid or negative.
     */
    fun parseTimeUs(input: String): Long? {
        val s = input.trim()
        if (s.isEmpty()) return null
        val parts = s.split(':')
        return try {
            when (parts.size) {
                1 -> {
                    val sec = parts[0].toDouble()
                    if (sec < 0 || sec.isNaN() || sec.isInfinite()) null else (sec * 1_000_000.0).roundToLong()
                }
                2 -> {
                    val min = parts[0].toLong()
                    val sec = parts[1].toDouble()
                    if (min < 0 || sec < 0 || sec >= 60.0 || sec.isNaN()) null
                    else min * 60_000_000L + (sec * 1_000_000.0).roundToLong()
                }
                3 -> {
                    val hr = parts[0].toLong()
                    val min = parts[1].toLong()
                    val sec = parts[2].toDouble()
                    if (hr < 0 || min !in 0..59 || sec < 0 || sec >= 60.0 || sec.isNaN()) null
                    else hr * 3_600_000_000L + min * 60_000_000L + (sec * 1_000_000.0).roundToLong()
                }
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * Export duration tolerance check (section 8.3): max track duration must be within
     * `max(0.5 s, 5 %)` of the expected output duration.
     */
    fun isExportDurationValid(actualDurationUs: Long, expectedDurationUs: Long): Boolean {
        if (actualDurationUs <= 0L || expectedDurationUs <= 0L) return false
        val toleranceUs = maxOf(500_000L, (expectedDurationUs * 0.05).roundToLong())
        return kotlin.math.abs(actualDurationUs - expectedDurationUs) <= toleranceUs
    }

    fun isDurationWithinTolerance(expectedDurationUs: Long, actualDurationUs: Long): Boolean =
        isExportDurationValid(actualDurationUs, expectedDurationUs)
}

