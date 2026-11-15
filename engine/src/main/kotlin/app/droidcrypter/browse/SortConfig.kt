package app.droidcrypter.browse

import app.droidcrypter.engine.FsNode

enum class SortCriterion {
    NAME, DATE, SIZE, STARS
}

enum class SortDirection {
    ASCENDING, DESCENDING;

    fun flip(): SortDirection = if (this == ASCENDING) DESCENDING else ASCENDING
}

data class SortConfig(
    val criterion: SortCriterion = SortCriterion.NAME,
    val direction: SortDirection = SortDirection.ASCENDING,
) {
    fun encode(): String = "${criterion.name}:${direction.name}"

    /** Selecting the active criterion flips direction; selecting another keeps the direction. */
    fun select(next: SortCriterion): SortConfig =
        if (next == criterion) copy(direction = direction.flip())
        else copy(criterion = next)

    /** While stars are hidden, a saved Stars sort behaves as Name (same direction), preserving saved config. */
    fun effective(showStars: Boolean): SortConfig =
        if (!showStars && criterion == SortCriterion.STARS) copy(criterion = SortCriterion.NAME)
        else this

    fun effectiveCriterion(showStars: Boolean): SortCriterion = effective(showStars).criterion

    companion object {
        val DEFAULT = SortConfig(SortCriterion.NAME, SortDirection.ASCENDING)

        /** Parses `"CRITERION:DIRECTION"`; anything unreadable falls back to Name ascending. */
        fun decode(raw: String?): SortConfig {
            if (raw.isNullOrBlank()) return DEFAULT
            val parts = raw.trim().split(':')
            if (parts.size != 2) return DEFAULT
            val c = SortCriterion.entries.firstOrNull { it.name == parts[0] } ?: return DEFAULT
            val d = SortDirection.entries.firstOrNull { it.name == parts[1] } ?: return DEFAULT
            return SortConfig(c, d)
        }
    }
}

/**
 * Browser sorting rules (section 7.2):
 * - Folders always first.
 * - Ties: name ascending (natural), then full path (stable between refreshes).
 * - Name: case-insensitive natural order (`IMG_2` < `IMG_10`), deterministic for names differing
 *   only in case or leading zeros.
 * - Date: unknown timestamps (`<= 0`) always last in both directions.
 * - Size: folders (no size on FAT) ordered by name in the chosen direction.
 * - Stars: unrated = 0; descending puts best-rated first; folders ordered by name.
 */
object BrowserSort {

    fun sort(
        nodes: List<FsNode>,
        config: SortConfig,
        showStars: Boolean = true,
        displayedRating: (String) -> Int = { 0 },
    ): List<FsNode> {
        val eff = config.effective(showStars)
        val sign = if (eff.direction == SortDirection.ASCENDING) 1 else -1
        return nodes.sortedWith { a, b ->
            // 1. Folders always first.
            if (a.isDirectory != b.isDirectory) {
                return@sortedWith if (a.isDirectory) -1 else 1
            }
            // 2. Primary criterion comparison.
            val primary = when (eff.criterion) {
                SortCriterion.NAME -> sign * compareNaturalName(a.name, b.name)
                SortCriterion.DATE -> {
                    val aUnk = a.lastModifiedMillis <= 0L
                    val bUnk = b.lastModifiedMillis <= 0L
                    when {
                        aUnk && bUnk -> 0
                        aUnk -> 1 // unknown always last
                        bUnk -> -1
                        else -> sign * a.lastModifiedMillis.compareTo(b.lastModifiedMillis)
                    }
                }
                SortCriterion.SIZE -> {
                    if (a.isDirectory) {
                        sign * compareNaturalName(a.name, b.name)
                    } else {
                        sign * a.size.compareTo(b.size)
                    }
                }
                SortCriterion.STARS -> {
                    if (a.isDirectory) {
                        compareNaturalName(a.name, b.name)
                    } else {
                        sign * displayedRating(a.path).compareTo(displayedRating(b.path))
                    }
                }
            }
            if (primary != 0) return@sortedWith primary

            // 3. Ties: name ascending, then full path.
            val byName = compareNaturalName(a.name, b.name)
            if (byName != 0) byName else a.path.compareTo(b.path)
        }
    }

    /**
     * Case-insensitive natural comparison where runs of ASCII digits compare by numeric value.
     * Ties in numeric value / case are broken deterministically:
     * 1. Fewer leading zeros first (or shorter digit run first),
     * 2. Case-sensitive lexicographic comparison.
     */
    fun compareNaturalName(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        var leadingZeroDiff = 0
        while (ia < a.length && ib < b.length) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca in '0'..'9' && cb in '0'..'9') {
                val startA = ia
                while (ia < a.length && a[ia] == '0') ia++
                val nzStartA = ia
                while (ia < a.length && a[ia] in '0'..'9') ia++

                val startB = ib
                while (ib < b.length && b[ib] == '0') ib++
                val nzStartB = ib
                while (ib < b.length && b[ib] in '0'..'9') ib++

                val lenA = ia - nzStartA
                val lenB = ib - nzStartB
                if (lenA != lenB) return lenA.compareTo(lenB)
                for (k in 0 until lenA) {
                    val d = a[nzStartA + k].compareTo(b[nzStartB + k])
                    if (d != 0) return d
                }
                if (leadingZeroDiff == 0) {
                    val rawLenA = ia - startA
                    val rawLenB = ib - startB
                    if (rawLenA != rawLenB) leadingZeroDiff = rawLenA.compareTo(rawLenB)
                }
            } else {
                val ua = ca.uppercaseChar().lowercaseChar()
                val ub = cb.uppercaseChar().lowercaseChar()
                if (ua != ub) return ua.compareTo(ub)
                ia++
                ib++
            }
        }
        if (ia < a.length || ib < b.length) {
            return (a.length - ia).compareTo(b.length - ib)
        }
        if (leadingZeroDiff != 0) return leadingZeroDiff
        return a.compareTo(b)
    }
}

