package gg.dekiru.musicplayer.android.auto

import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import java.text.Normalizer

/** What a voice request asks for: Google Assistant and Android Auto's search say what kind of thing was named, when they know. */
enum class VoiceFocus { ANY, ALBUM, ARTIST, PLAYLIST }

/**
 * "Play <album> on Tetsubi", read: the words to search for, whether to
 * shuffle ("shuffle" anywhere in the request) and what kind of thing was
 * asked for. Empty `terms`: "play music on Tetsubi", which plays a random
 * album (iOS "Play Something").
 */
data class VoiceRequest(val terms: String, val shuffle: Boolean, val focus: VoiceFocus)

/**
 * Turns a voice request into a [VoiceRequest] and picks the best album or
 * playlist for it. Plain Kotlin, so it can be tested.
 */
object VoiceQuery {

    // Assistant's words for what was asked for (MediaStore.EXTRA_MEDIA_FOCUS values).
    const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
    const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"
    const val FOCUS_PLAYLIST = "vnd.android.cursor.item/playlist"

    /** Assistant's playlist name (MediaStore.EXTRA_MEDIA_PLAYLIST, whose constant is deprecated though Assistant still sends it). */
    const val EXTRA_PLAYLIST = "android.intent.extra.playlist"

    /** Words that mean "any music": "play music on Tetsubi" plays something. */
    private val anything = setOf("music", "something", "anything", "some music", "songs", "my music", "a random album", "an album")

    private val shuffleWords = Regex("""\b(?:shuffle|shuffled|shuffling)\b|\bon shuffle\b|\bin shuffle mode\b""", RegexOption.IGNORE_CASE)
    private val appName = Regex("""\s+(?:on|in|from|with|using)\s+tetsubi\s*$""", RegexOption.IGNORE_CASE)
    private val leadingPlay = Regex("""^\s*(?:play|listen to|put on|start)\s+""", RegexOption.IGNORE_CASE)
    private val leadingThe = Regex("""^\s*(?:the\s+)?(?:album|playlist)\s+""", RegexOption.IGNORE_CASE)

    /**
     * Reads the spoken `query` and Assistant's extras: `focus`
     * (EXTRA_MEDIA_FOCUS) and the album, artist and playlist names it
     * picked out (EXTRA_MEDIA_ALBUM, _ARTIST, _PLAYLIST), which are used
     * when given since they're cleaner than the whole sentence.
     */
    fun parse(query: String?, focus: String? = null, album: String? = null, artist: String? = null, playlist: String? = null): VoiceRequest {
        val spoken = query.orEmpty()
        val shuffle = shuffleWords.containsMatchIn(spoken)
        val kind = when {
            focus == FOCUS_PLAYLIST || (focus == null && !playlist.isNullOrBlank()) -> VoiceFocus.PLAYLIST
            focus == FOCUS_ALBUM -> VoiceFocus.ALBUM
            focus == FOCUS_ARTIST -> VoiceFocus.ARTIST
            else -> VoiceFocus.ANY
        }
        val named = when (kind) {
            VoiceFocus.PLAYLIST -> playlist
            VoiceFocus.ALBUM -> album
            VoiceFocus.ARTIST -> artist
            VoiceFocus.ANY -> null
        }?.takeIf { it.isNotBlank() }
        var terms = named ?: spoken
        terms = shuffleWords.replace(terms, " ")
        terms = appName.replace(terms, "")
        terms = leadingPlay.replace(terms, "")
        terms = leadingThe.replace(terms, "")
        terms = terms.replace(Regex("\\s+"), " ").trim()
        if (fold(terms) in anything) terms = ""
        return VoiceRequest(terms, shuffle, kind)
    }

    /**
     * The album to play for `terms` from search results (title matches
     * first): one whose name is the words exactly, else one whose name
     * holds every word, else one whose name and artist together do, else
     * the first result.
     */
    fun bestAlbum(terms: String, albums: List<JellyfinAlbum>): JellyfinAlbum? {
        if (albums.isEmpty()) return null
        val wanted = fold(terms)
        val words = words(terms)
        return albums.firstOrNull { fold(it.displayName) == wanted }
            ?: albums.firstOrNull { album -> fold(album.displayName).let { name -> words.all { it in name } } }
            ?: albums.firstOrNull { album -> fold("${album.displayName} ${album.artistName}").let { text -> words.all { it in text } } }
            ?: albums.first()
    }

    /** A playlist named exactly `terms`, or null. */
    fun exactPlaylist(terms: String, playlists: List<JellyfinPlaylist>): JellyfinPlaylist? =
        playlists.firstOrNull { fold(it.displayName) == fold(terms) }

    /** The playlist to play: named exactly `terms`, else one whose name holds every word. */
    fun bestPlaylist(terms: String, playlists: List<JellyfinPlaylist>): JellyfinPlaylist? {
        val words = words(terms)
        if (words.isEmpty()) return null
        return exactPlaylist(terms, playlists) ?: playlists.firstOrNull { p -> fold(p.displayName).let { name -> words.all { it in name } } }
    }

    private fun words(text: String): List<String> = fold(text).split(' ').filter { it.isNotEmpty() }

    /** Lower case, no accents, no punctuation, single spaces. */
    internal fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
