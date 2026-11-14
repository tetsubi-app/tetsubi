package gg.dekiru.musicplayer.ui.search

import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.models.DiscGrouping
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Albums by title and by artist (as iOS `AlbumSearch`), since Jellyfin's
 * album search only matches titles ("Gruntruck" found nothing). Title
 * matches first, then albums by matching artists (up to 3 artists);
 * duplicates removed and multi-disc albums merged. Only the title search
 * failing is an error.
 */
object AlbumSearch {

    suspend fun run(api: JellyfinApi, term: String): List<JellyfinAlbum> = coroutineScope {
        val byTitle = async { api.searchAlbums(term, limit = 50) }
        val artists = async { orEmpty { api.searchArtists(term, limit = 3) } }
        val byArtist = artists.await()
            .map { artist -> async { orEmpty { api.albumsByArtist(artist.id) } } }
            .awaitAll()
            .flatten()
        combine(byTitle.await(), byArtist)
    }

    /** Title matches first, then artist matches; each album once; discs merged. */
    fun combine(titleMatches: List<JellyfinAlbum>, artistMatches: List<JellyfinAlbum>): List<JellyfinAlbum> =
        DiscGrouping.merge((titleMatches + artistMatches).distinctBy { it.id })

    private inline fun <T> orEmpty(block: () -> List<T>): List<T> =
        try {
            block()
        } catch (e: JellyfinError) {
            emptyList()
        }
}
