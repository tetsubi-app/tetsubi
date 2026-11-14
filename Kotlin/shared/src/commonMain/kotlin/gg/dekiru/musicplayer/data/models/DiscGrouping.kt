package gg.dekiru.musicplayer.data.models

import gg.dekiru.musicplayer.data.api.stripDiacritics

/**
 * Jellyfin lists each disc of some albums as a separate album, named e.g.
 * "Album (Disc 1)", "Album [CD2]" or "Album - Disc 2". These helpers
 * recognise those names and merge the discs into one album for display
 * (as `DiscGrouping` on iOS).
 */
object DiscGrouping {

    data class Parsed(val base: String, val disc: Int, val subtitle: String?)

    private val bracketed = Regex(
        """^(.*?)\s*[(\[{]\s*(?:disc|disk|cd)\s*\.?\s*(\d+)(?:\s*(?:of|/)\s*\d+)?(?:[\s:,\-–—]+([^)\]}]*?))?\s*[)\]}]\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val separated = Regex(
        """^(.*?)[\s\-–—:,]+\b(?:disc|disk|cd)\s*\.?\s*(\d+)(?:\s*(?:of|/)\s*\d+)?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * "Album (Disc 2)" → ("Album", 2, null). Matches disc / disk / cd, either
     * in brackets — where a disc subtitle may follow, as in
     * "Dracula X (Disc 1 Chii no Rondo)" — or after a separator
     * ("Album - CD 2"); optionally "of N" / "/N". Null without a disc marker.
     */
    fun parse(name: String): Parsed? {
        for (regex in listOf(bracketed, separated)) {
            val match = regex.find(name) ?: continue
            val disc = match.groupValues[2].toIntOrNull() ?: continue
            val base = match.groupValues[1].trim()
            if (base.isEmpty()) return null
            val subtitle = match.groups.getOrNull(3)?.value?.trim()?.takeIf { it.isNotEmpty() }
            return Parsed(base, disc, subtitle)
        }
        return null
    }

    private fun MatchGroupCollection.getOrNull(index: Int) = if (index < size) get(index) else null

    /** The disc's subtitle from its name, if any ("Chii no Rondo"). */
    fun subtitle(album: JellyfinAlbum): String? = parse(album.displayName)?.subtitle

    /** The disc number in an album's own name, if it has one. */
    fun discNumber(album: JellyfinAlbum): Int? = parse(album.displayName)?.disc

    /** "Same album": artist + base name, ignoring case and accents. */
    internal fun key(artist: String, base: String): String {
        fun fold(text: String) = stripDiacritics(text).lowercase()
        return fold(artist) + "\u001F" + fold(base)
    }

    /**
     * Merges disc albums in `albums` into one entry, at the position of the
     * first disc seen. Other albums are untouched. The merged entry keeps the
     * lowest disc's ID (art, navigation), takes the base name, and lists the
     * discs in `discAlbums`. A lone disc still gets the base name; the album
     * page finds its other discs.
     */
    fun merge(albums: List<JellyfinAlbum>): List<JellyfinAlbum> {
        val groups = linkedMapOf<String, MutableList<JellyfinAlbum>>()
        val order = mutableListOf<Pair<String?, JellyfinAlbum>>()
        for (album in albums) {
            val parsed = parse(album.displayName)
            if (parsed == null) {
                order += null to album
                continue
            }
            val k = key(album.artistName, parsed.base)
            val group = groups.getOrPut(k) { order += k to album; mutableListOf() }
            if (group.none { it.id == album.id }) group += album
        }
        return order.map { (k, album) -> k?.let { groups[it] }?.let(::combined) ?: album }
    }

    /** One album standing for all of `discs` (each a single-disc album). */
    fun combined(discs: List<JellyfinAlbum>): JellyfinAlbum {
        val sorted = discs.sortedBy { discNumber(it) ?: 0 }
        val first = sorted.first()
        val base = parse(first.displayName)?.base ?: first.displayName
        return first.copy(name = base, discAlbums = sorted)
    }

    /** From an artist's albums, the discs that belong with `album`. Empty if it isn't a disc of a set. */
    fun siblings(album: JellyfinAlbum, candidates: List<JellyfinAlbum>): List<JellyfinAlbum> {
        val own = album.discAlbums.firstOrNull() ?: album
        val parsed = parse(own.displayName) ?: return emptyList()
        val k = key(own.artistName, parsed.base)
        return candidates.filter { candidate ->
            val p = parse(candidate.displayName) ?: return@filter false
            key(candidate.artistName, p.base) == k
        }
    }

    /**
     * One album's tracks split by their disc tag (`ParentIndexNumber`), for
     * albums stored whole with tracks tagged disc 1, disc 2 (The Fragile).
     * Null unless they span more than one disc. Untagged tracks count as disc 1.
     */
    fun byDiscNumber(tracks: List<JellyfinTrack>): List<Pair<Int, List<JellyfinTrack>>>? {
        val numbers = tracks.map { it.discNumber ?: 1 }.toSortedSet()
        if (numbers.size < 2) return null
        return numbers.map { n -> n to tracks.filter { (it.discNumber ?: 1) == n } }
    }
}

/** Whether this entry stands for more than one disc. */
val JellyfinAlbum.isMultiDisc: Boolean get() = discAlbums.size > 1

/** The real albums behind this entry: its discs, or just itself. */
val JellyfinAlbum.allDiscs: List<JellyfinAlbum> get() = discAlbums.ifEmpty { listOf(this) }
