package gg.dekiru.musicplayer.data.models

/**
 * Which artist the artist line on the album page and in Now Playing opens
 * (that album artist's albums), or that it isn't tappable. As iOS `ArtistLink`.
 */
sealed interface ArtistLink {
    /** Open this artist's page. */
    data class Artist(val artist: JellyfinArtist) : ArtistLink

    /** Only the name is known: look it up ([match]) and open the match. */
    data class Lookup(val name: String) : ArtistLink

    companion object {
        /**
         * The album artist named [name] with server ID [id] (the first of
         * `AlbumArtists`). Null, not tappable: no name, or "Various Artists".
         */
        fun resolve(name: String, id: String?): ArtistLink? {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || isVarious(trimmed)) return null
            return if (!id.isNullOrEmpty()) Artist(JellyfinArtist(id, name)) else Lookup(trimmed)
        }

        /** The artist in [candidates] (an album-artist search) with exactly this name, ignoring case and surrounding spaces. */
        fun match(name: String, candidates: List<JellyfinArtist>): JellyfinArtist? {
            val wanted = name.trim()
            return candidates.firstOrNull { it.id.isNotEmpty() && it.name.trim().equals(wanted, ignoreCase = true) }
        }

        /**
         * The artist to open, looking a name-only artist up with [search]
         * (the album artists matching a term) when [online]. Null: not tappable.
         */
        suspend fun find(name: String, id: String?, online: Boolean, search: suspend (String) -> List<JellyfinArtist>): JellyfinArtist? =
            when (val link = resolve(name, id)) {
                is Artist -> link.artist
                is Lookup -> if (online) match(link.name, search(link.name)) else null
                null -> null
            }

        /**
         * The album artist shown for a track (`PlaybackTrack.artist`) and its
         * ID: the track's first album artist, else the album's. A track artist
         * without an ID borrows the album's when the names are the same.
         */
        fun albumArtist(trackArtists: List<JellyfinPerson>?, album: JellyfinAlbum): Pair<String, String?> {
            val first = trackArtists?.firstOrNull() ?: return album.artistName to album.artistId
            return first.name to (first.id ?: album.artistId?.takeIf { first.name == album.artistName })
        }

        private fun isVarious(name: String) = name.equals("Various Artists", ignoreCase = true)
    }
}
