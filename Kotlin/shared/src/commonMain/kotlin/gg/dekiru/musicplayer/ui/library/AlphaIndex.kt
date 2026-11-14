package gg.dekiru.musicplayer.ui.library

import gg.dekiru.musicplayer.data.api.stripDiacritics

/**
 * Buckets titles into the A–Z index (as `AlphaIndex` on iOS): `#` (digits
 * and symbols) first, then A–Z, then `あ` for every other script
 * (Japanese, Cyrillic, Greek…).
 */
object AlphaIndex {
    const val OTHER = "あ"
    val labels: List<String> = listOf("#") + ('A'..'Z').map { it.toString() } + OTHER

    /** Lower-cased and accent-stripped, so "Émile" files under E. */
    fun folded(s: String): String = stripDiacritics(s.trim()).lowercase()

    fun bucket(title: String): String {
        val first = folded(title).firstOrNull() ?: return "#"
        if (first in 'a'..'z') return first.uppercase()
        return if (first.isLetter()) OTHER else "#"
    }

    /** Position of the title's bucket in [labels]: keeps the sort order in line with the strip. */
    fun rank(title: String): Int = labels.indexOf(bucket(title)).coerceAtLeast(0)

    /** Bucket first (# … A–Z … other scripts), then alphabetically within it. */
    fun <T> comparator(key: (T) -> String): Comparator<T> =
        compareBy<T> { rank(key(it)) }.thenBy { folded(key(it)) }

    /** The first item's ID in each bucket, for the strip (letters missing here are dimmed). */
    fun <T> firstIds(items: List<T>, key: (T) -> String, id: (T) -> String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (item in items) result.getOrPut(bucket(key(item))) { id(item) }
        return result
    }

    /** Where a tap on [label] goes: that letter, or the next one with items, else the last that has any. */
    fun target(label: String, available: Map<String, String>): String? {
        val start = labels.indexOf(label).coerceAtLeast(0)
        val bucket = labels.drop(start).firstOrNull { it in available } ?: labels.lastOrNull { it in available }
        return bucket?.let(available::get)
    }
}
