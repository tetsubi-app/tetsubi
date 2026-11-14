package gg.dekiru.musicplayer.ui.search

import gg.dekiru.musicplayer.data.api.TetsubiJson
import gg.dekiru.musicplayer.data.settings.Preferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Recent search terms, most recent first, at most [LIMIT] (as iOS
 * `SearchHistory`, same key). Stored as a JSON list of strings.
 */
class SearchHistory(private val preferences: Preferences) {

    private val serializer = ListSerializer(String.serializer())

    val terms: List<String>
        get() = preferences.getString(KEY)
            ?.let { runCatching { TetsubiJson.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    /**
     * Adds `term` to the top. Typing refines a search ("mel" → "mellon"),
     * so a newest entry that the new term extends is replaced, not kept.
     */
    fun record(term: String) {
        val trimmed = term.trim()
        if (trimmed.length < 2) return
        val list = terms.filterNot { it.equals(trimmed, ignoreCase = true) }.toMutableList()
        list.firstOrNull()?.let { newest ->
            val a = trimmed.lowercase()
            val b = newest.lowercase()
            if (a.startsWith(b) || b.startsWith(a)) list.removeAt(0)
        }
        list.add(0, trimmed)
        preferences.putString(KEY, TetsubiJson.encodeToString(serializer, list.take(LIMIT)))
    }

    fun clear() = preferences.putString(KEY, null)

    companion object {
        const val KEY = "search.history"
        const val LIMIT = 10
    }
}
