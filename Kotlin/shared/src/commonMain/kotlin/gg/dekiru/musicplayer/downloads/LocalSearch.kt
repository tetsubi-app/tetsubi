package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.data.api.stripDiacritics
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist

/** A downloaded album, as known without the server. */
data class LocalAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val trackTitles: List<String> = emptyList(),
)

/** Matches downloaded albums for searching offline (as iOS `LocalSearch`). */
object LocalSearch {
    /**
     * Every word of `query` must appear in the title, artist or a track title
     * (case- and accent-insensitive). Results keep the input order.
     */
    fun matches(query: String, albums: List<LocalAlbum>): List<LocalAlbum> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        return albums.filter { album ->
            val haystack = fold((listOf(album.title, album.artist) + album.trackTitles).joinToString(" "))
            words.all { it in haystack }
        }
    }

    /**
     * Downloaded playlists by name: every word of `query` must appear in it
     * (case- and accent-insensitive). Results keep the input order.
     */
    fun matchingPlaylists(query: String, playlists: List<JellyfinPlaylist>): List<JellyfinPlaylist> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        return playlists.filter { playlist ->
            val name = fold(playlist.name)
            words.all { it in name }
        }
    }

    private fun words(query: String): List<String> = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun fold(s: String): String = stripDiacritics(s).lowercase()
}
