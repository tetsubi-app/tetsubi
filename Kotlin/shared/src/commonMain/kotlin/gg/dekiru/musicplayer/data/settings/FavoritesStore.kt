package gg.dekiru.musicplayer.data.settings

import gg.dekiru.musicplayer.data.api.TetsubiJson
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Remembers when albums were favourited in this app, so the Home Favourites
 * row can be newest-first (as on iOS). Jellyfin has no "date favourited";
 * favourites made elsewhere keep server order, after the ones recorded here.
 */
class FavoritesStore(private val preferences: Preferences, private val now: () -> Long) {

    private val serializer = MapSerializer(String.serializer(), Long.serializer())

    private fun dates(): Map<String, Long> =
        preferences.getString(KEY)?.let { runCatching { TetsubiJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    fun record(albumId: String, isFavorite: Boolean) {
        val dates = dates().toMutableMap()
        if (isFavorite) dates[albumId] = now() else dates.remove(albumId)
        preferences.putString(KEY, TetsubiJson.encodeToString(serializer, dates))
    }

    /** Albums favourited here first (newest first), then the rest in their incoming order. */
    fun ordered(albums: List<JellyfinAlbum>): List<JellyfinAlbum> {
        val dates = dates()
        return albums.withIndex()
            .sortedWith(compareBy<IndexedValue<JellyfinAlbum>> { dates[it.value.id] == null }
                .thenByDescending { dates[it.value.id] ?: 0 }
                .thenBy { it.index })
            .map { it.value }
    }

    private companion object {
        const val KEY = "favorites.dates"
    }
}
