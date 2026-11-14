package gg.dekiru.musicplayer.data.models

import kotlinx.datetime.LocalDate

/**
 * Release date from `PremiereDate` (just its leading "yyyy-MM-dd": Jellyfin
 * sends "1994-10-31T00:00:00.0000000Z"), else Jan 1 of `ProductionYear`.
 * Jellyfin's "no date" (year 1) counts as none. (iOS also drops dates
 * before 1970, so a 1965 album falls back to Jan 1 there; not here.)
 */
val JellyfinAlbum.releaseDate: LocalDate?
    get() {
        premiereDate?.take(10)?.let { day ->
            runCatching { LocalDate.parse(day) }.getOrNull()?.takeIf { it.year > 1 }?.let { return it }
        }
        return productionYear?.let { LocalDate(it, 1, 1) }
    }

/** An album released on today's month and day in a past year. */
data class OnThisDayEntry(val album: JellyfinAlbum, val yearsAgo: Int) {
    val label: String get() = OnThisDay.anniversaryLabel(yearsAgo)
}

object OnThisDay {

    /** At most this many albums in Home's row. */
    const val LIMIT = 12

    /** Albums released on `today`'s month/day in an earlier year, most recent first. Discs shown once. */
    fun entries(albums: List<JellyfinAlbum>, today: LocalDate): List<OnThisDayEntry> = matches(DiscGrouping.merge(albums), today)

    /** As [entries], without merging discs (for the release-dates-only list, which has no artists). */
    fun matches(albums: List<JellyfinAlbum>, today: LocalDate): List<OnThisDayEntry> =
        albums
            .mapNotNull { album ->
                val date = album.releaseDate ?: return@mapNotNull null
                if (date.month != today.month || date.day != today.day) return@mapNotNull null
                val yearsAgo = today.year - date.year
                if (yearsAgo > 0) OnThisDayEntry(album, yearsAgo) else null
            }
            .sortedBy { it.yearsAgo }

    /** "1 year ago", "30 years ago"; empty for 0. */
    fun anniversaryLabel(yearsAgo: Int): String = when {
        yearsAgo <= 0 -> ""
        yearsAgo == 1 -> "1 year ago"
        else -> "$yearsAgo years ago"
    }
}
