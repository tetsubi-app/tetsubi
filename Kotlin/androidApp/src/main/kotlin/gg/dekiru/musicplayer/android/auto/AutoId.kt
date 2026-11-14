package gg.dekiru.musicplayer.android.auto

/**
 * What each row in Android Auto's browse tree stands for, and the media ID
 * string it travels as. The car hands the string back when a row is opened
 * or tapped, possibly after the app was restarted, so everything needed to
 * act on it is in the string itself:
 *
 * - `root`, `tab:home`, `tab:artists`, `tab:playlists`, `tab:downloads`
 * - `artist:<id>`: the artist's albums
 * - `album:<disc ids>`: an album page (a multi-disc album lists every disc, comma-separated)
 * - `playlist:<id>`: a playlist page
 * - `play:album:<disc ids>`, `shuffle:album:<disc ids>`, `play:playlist:<id>`, `shuffle:playlist:<id>`
 * - `track:<disc ids>/<track id>`: plays the album from that track
 * - `song:<playlist id>/<position>`: plays the playlist from that row (a song can be there twice)
 * - `download:<album id>`: plays a downloaded album
 * - `note:<what>`: a message row ("Not signed in"), which does nothing
 *
 * The same album can be in two Home rows; `#<anything>` on the end keeps
 * the two IDs apart and is ignored when read back.
 */
sealed interface AutoId {
    data object Root : AutoId
    data class Tab(val tab: AutoTab) : AutoId
    data class Artist(val artistId: String) : AutoId
    data class Album(val discIds: List<String>) : AutoId
    data class Playlist(val playlistId: String) : AutoId
    data class PlayAlbum(val discIds: List<String>, val shuffled: Boolean) : AutoId
    data class PlayPlaylist(val playlistId: String, val shuffled: Boolean) : AutoId
    data class AlbumTrack(val discIds: List<String>, val trackId: String) : AutoId
    data class PlaylistSong(val playlistId: String, val position: Int) : AutoId
    data class Downloaded(val albumId: String) : AutoId
    data class Note(val what: String) : AutoId

    /** The media ID string; `tag` makes it unique within a list (see above). */
    fun format(tag: String? = null): String {
        val base = when (this) {
            Root -> ROOT
            is Tab -> "tab:${tab.key}"
            is Artist -> "artist:$artistId"
            is Album -> "album:${discIds.joinToString(",")}"
            is Playlist -> "playlist:$playlistId"
            is PlayAlbum -> "${verb(shuffled)}:album:${discIds.joinToString(",")}"
            is PlayPlaylist -> "${verb(shuffled)}:playlist:$playlistId"
            is AlbumTrack -> "track:${discIds.joinToString(",")}/$trackId"
            is PlaylistSong -> "song:$playlistId/$position"
            is Downloaded -> "download:$albumId"
            is Note -> "note:$what"
        }
        return if (tag.isNullOrEmpty()) base else "$base#$tag"
    }

    /** Whether tapping it starts music (rather than opening a page). */
    val plays: Boolean
        get() = this is PlayAlbum || this is PlayPlaylist || this is AlbumTrack || this is PlaylistSong || this is Downloaded

    companion object {
        const val ROOT = "root"

        private fun verb(shuffled: Boolean) = if (shuffled) "shuffle" else "play"

        /** Reads a media ID back; null for one this app didn't make. */
        fun parse(mediaId: String?): AutoId? {
            val id = mediaId?.substringBefore('#')?.takeIf { it.isNotEmpty() } ?: return null
            if (id == ROOT) return Root
            val kind = id.substringBefore(':', missingDelimiterValue = "")
            val rest = id.substringAfter(':', missingDelimiterValue = "")
            if (kind.isEmpty() || rest.isEmpty()) return null
            return when (kind) {
                "tab" -> AutoTab.entries.firstOrNull { it.key == rest }?.let(::Tab)
                "artist" -> Artist(rest)
                "album" -> ids(rest)?.let(::Album)
                "playlist" -> Playlist(rest)
                "play", "shuffle" -> {
                    val shuffled = kind == "shuffle"
                    val what = rest.substringBefore(':', missingDelimiterValue = "")
                    val target = rest.substringAfter(':', missingDelimiterValue = "")
                    when {
                        target.isEmpty() -> null
                        what == "album" -> ids(target)?.let { PlayAlbum(it, shuffled) }
                        what == "playlist" -> PlayPlaylist(target, shuffled)
                        else -> null
                    }
                }
                "track" -> {
                    val discs = ids(rest.substringBefore('/', missingDelimiterValue = "")) ?: return null
                    val track = rest.substringAfter('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() } ?: return null
                    AlbumTrack(discs, track)
                }
                "song" -> {
                    val playlist = rest.substringBefore('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() } ?: return null
                    val position = rest.substringAfter('/', missingDelimiterValue = "").toIntOrNull()?.takeIf { it >= 0 } ?: return null
                    PlaylistSong(playlist, position)
                }
                "download" -> Downloaded(rest)
                "note" -> Note(rest)
                else -> null
            }
        }

        private fun ids(text: String): List<String>? = text.split(',').filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
    }
}

/** The four tabs along the top of Android Auto, as CarPlay's. */
enum class AutoTab(val key: String, val title: String) {
    HOME("home", "Home"),
    ARTISTS("artists", "Artists"),
    PLAYLISTS("playlists", "Playlists"),
    DOWNLOADS("downloads", "Downloads"),
}
