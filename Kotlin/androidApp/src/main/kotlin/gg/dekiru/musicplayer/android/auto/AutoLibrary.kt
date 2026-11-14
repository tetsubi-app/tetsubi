package gg.dekiru.musicplayer.android.auto

import gg.dekiru.musicplayer.AppSession
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.models.DiscGrouping
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.PlaylistEntry
import gg.dekiru.musicplayer.data.models.allDiscs
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.downloads.LocalSearch
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.ui.album.AlbumModel
import gg.dekiru.musicplayer.ui.search.AlbumSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * What Android Auto and voice requests reach the app through (as iOS
 * `AppSession`'s CarPlay and Siri helpers): the browse tree's pages,
 * search, and play requests, which go to the app's own [PlayerController]
 * with the same calls its screens make, so the queue, Up next, play
 * reporting and Maniac Mode stay right.
 *
 * Pages are loaded off the main thread ([Dispatchers.Default]); the server
 * calls don't block anyway. Without a connection, or when the server
 * doesn't answer, pages and searches fall back to what's downloaded.
 * Play requests run on the main thread, as the player wants.
 */
class AutoLibrary(private val session: AppSession) {

    /** Albums seen in lists, by their disc IDs, so a tapped album needn't be fetched again. */
    private val albums = ConcurrentHashMap<String, JellyfinAlbum>()

    /** Playlists seen in lists, by ID. */
    private val playlists = ConcurrentHashMap<String, JellyfinPlaylist>()

    private val api: JellyfinApi? get() = session.api.value
    private val downloads: DownloadManager? get() = session.downloads.value
    private val online: Boolean get() = session.environment.isOnline()

    /** Maniac Mode: no Shuffle rows, and tapped tracks play from the start. */
    val maniacMode: Boolean get() = session.player.value?.maniacMode ?: session.playbackSettings.maniacMode

    /**
     * Restores the saved login when Android Auto or Assistant started the
     * app without its screens (main thread). Returns whether signed in.
     */
    fun restore(): Boolean = session.restore()

    // Pages

    /** The rows of the page `id` opens. */
    suspend fun children(id: AutoId): List<AutoRow> = withContext(Dispatchers.Default) {
        when (id) {
            AutoId.Root -> AutoTree.tabs()
            is AutoId.Tab -> tab(id.tab)
            is AutoId.Artist -> artistAlbums(id.artistId)
            is AutoId.Album -> albumPage(id.discIds)
            is AutoId.Playlist -> playlistPage(id.playlistId)
            else -> emptyList()
        }
    }

    private suspend fun tab(tab: AutoTab): List<AutoRow> {
        val api = api ?: return AutoTree.signedOut()
        return when (tab) {
            AutoTab.HOME -> home(api)
            AutoTab.ARTISTS -> AutoTree.artists(if (online) attempt { api.albumArtists() } else null)
            AutoTab.PLAYLISTS -> playlistList().let { (list, fromServer) -> AutoTree.playlists(list, fromServer) }
            AutoTab.DOWNLOADS -> AutoTree.downloads(downloads?.downloaded?.value.orEmpty())
        }
    }

    /** Recently Played, Favourites and Recently Added; offline, a row that points to Downloads. */
    private suspend fun home(api: JellyfinApi): List<AutoRow> = coroutineScope {
        if (!online) return@coroutineScope listOf(AutoTree.offline())
        val played = async { attempt { DiscGrouping.merge(api.recentlyPlayedAlbums(AutoTree.HOME_ROW_LIMIT)) } }
        val favourites = async { attempt { DiscGrouping.merge(session.favorites.ordered(api.favoriteAlbums())).take(AutoTree.HOME_ROW_LIMIT) } }
        val added = async { attempt { DiscGrouping.merge(api.recentlyAddedAlbums(AutoTree.HOME_ROW_LIMIT)) } }
        val rows = listOf(played.await(), favourites.await(), added.await())
        rows.forEach { it?.forEach(::remember) }
        // As CarPlay: Recently Played failing is what says the server can't be reached.
        AutoTree.home(rows[0].orEmpty(), rows[1].orEmpty(), rows[2].orEmpty(), failed = rows[0] == null)
    }

    private suspend fun artistAlbums(artistId: String): List<AutoRow> {
        val api = api ?: return AutoTree.signedOut()
        val found = if (online) attempt { api.albumsByArtist(artistId) } else null
        found?.let { DiscGrouping.merge(it).forEach(::remember) }
        return AutoTree.artistAlbums(found)
    }

    private suspend fun albumPage(discIds: List<String>): List<AutoRow> {
        if (api == null) return AutoTree.signedOut()
        val album = album(discIds) ?: return listOf(AutoTree.note(AutoTree.CANT_LOAD_ALBUM))
        return AutoTree.albumPage(album, tracks(album), maniacMode)
    }

    private suspend fun playlistPage(playlistId: String): List<AutoRow> {
        if (api == null) return AutoTree.signedOut()
        return AutoTree.playlistPage(playlistId, playlistEntries(playlistId), maniacMode)
    }

    // Albums

    private fun key(discIds: List<String>) = discIds.joinToString(",")

    private fun remember(album: JellyfinAlbum) {
        albums[key(album.allDiscs.map { it.id })] = album
    }

    /**
     * The album with these discs: as listed, else from the server (a lone
     * disc of a set finds its other discs, as iOS `playAlbum(id:)`), else
     * from the downloads.
     */
    suspend fun album(discIds: List<String>): JellyfinAlbum? {
        albums[key(discIds)]?.let { return it }
        val api = api ?: return null
        val fetched = if (online) {
            coroutineScope { discIds.map { id -> async { attempt { api.album(id) } } }.awaitAll() }
                .takeIf { list -> list.all { it != null } }?.filterNotNull()
        } else {
            null
        }
        val album = when {
            fetched == null -> downloadedAlbum(discIds)
            fetched.size > 1 -> DiscGrouping.combined(fetched)
            else -> withSiblings(api, fetched.single())
        } ?: return null
        remember(album)
        return album
    }

    /** A disc of a set on its own ("Album (Disc 2)") becomes the whole set; anything else keeps its name without the disc. */
    private suspend fun withSiblings(api: JellyfinApi, album: JellyfinAlbum): JellyfinAlbum {
        if (album.discAlbums.isNotEmpty()) return album
        val artistId = album.artistId
        if (DiscGrouping.parse(album.displayName) != null && artistId != null && online) {
            val siblings = attempt { api.albumsByArtist(artistId) }?.let { DiscGrouping.siblings(album, it) }.orEmpty()
            if (siblings.size > 1) return DiscGrouping.combined(siblings)
        }
        return DiscGrouping.merge(listOf(album)).first()
    }

    /** Downloaded discs as one album; null unless every disc is downloaded. */
    private fun downloadedAlbum(discIds: List<String>): JellyfinAlbum? {
        val manager = downloads ?: return null
        val discs = discIds.map { manager.downloadedAlbum(it)?.toAlbum() ?: return null }
        return if (discs.size > 1) DiscGrouping.combined(discs) else DiscGrouping.merge(discs).first()
    }

    private fun DownloadedAlbum.toAlbum() =
        JellyfinAlbum(id = albumId, name = title, productionYear = year, albumArtists = listOf(JellyfinPerson(name = artist)))

    /** Each disc with its tracks: from the server, or the download's list offline or when the server fails. */
    private suspend fun tracks(album: JellyfinAlbum): List<Pair<JellyfinAlbum, List<JellyfinTrack>?>> = coroutineScope {
        val api = api
        album.allDiscs.map { disc ->
            async {
                val downloaded = downloads?.downloadedAlbum(disc.id)
                val tracks = if (online && api != null) attempt { api.tracks(disc.id) } else null
                disc to (tracks ?: downloaded?.let(AlbumModel::downloadedTracks))
            }
        }.awaitAll()
    }

    // Playlists

    /** The server's playlists, or without it those downloaded for offline (false). */
    private suspend fun playlistList(): Pair<List<JellyfinPlaylist>, Boolean> {
        val api = api
        if (online && api != null) {
            attempt { api.playlists() }?.let { listed ->
                listed.forEach { playlists[it.id] = it }
                return listed to true
            }
        }
        return offlinePlaylists() to false
    }

    private fun offlinePlaylists(): List<JellyfinPlaylist> =
        downloads?.playlists?.availableOffline.orEmpty().map { it.manifest.toPlaylist() }

    /** A playlist's songs: the server's, else the download's. Null when neither can be read. */
    private suspend fun playlistEntries(playlistId: String): List<PlaylistEntry>? {
        val api = api
        if (online && api != null) attempt { api.playlistTracks(playlistId) }?.let { return it }
        return downloads?.playlists?.saved(playlistId)?.takeIf { it.isAvailableOffline }?.manifest?.toEntries()
    }

    private suspend fun playlist(playlistId: String): JellyfinPlaylist {
        playlists[playlistId]?.let { return it }
        downloads?.playlists?.saved(playlistId)?.let { return it.manifest.toPlaylist() }
        val listed = if (online) api?.let { attempt { it.playlists() } } else null
        listed?.forEach { playlists[it.id] = it }
        return playlists[playlistId] ?: JellyfinPlaylist(id = playlistId)
    }

    // Search (Android Auto's search screen and voice)

    /** Albums whose title or artist matches; offline, or when the server fails, the downloaded ones. */
    private suspend fun searchAlbums(terms: String): List<JellyfinAlbum> {
        val api = api
        val found = if (online && api != null) attempt { AlbumSearch.run(api, terms) } else null
        val albums = found ?: DiscGrouping.merge(
            LocalSearch.matches(terms, downloads?.localAlbums().orEmpty()).mapNotNull { downloads?.downloadedAlbum(it.id)?.toAlbum() },
        )
        albums.forEach(::remember)
        return albums
    }

    /** Playlists whose name matches; offline, among those downloaded. */
    private suspend fun searchPlaylists(terms: String): List<JellyfinPlaylist> {
        val api = api
        val found = if (online && api != null) attempt { api.searchPlaylists(terms) } else null
        val list = found ?: LocalSearch.matchingPlaylists(terms, offlinePlaylists())
        list.forEach { playlists[it.id] = it }
        return list
    }

    /** Rows for Android Auto's search screen: albums, then playlists. */
    suspend fun search(query: String): List<AutoRow> = withContext(Dispatchers.Default) {
        if (api == null || query.isBlank()) return@withContext emptyList()
        coroutineScope {
            val albums = async { searchAlbums(query) }
            val playlists = async { searchPlaylists(query) }
            AutoTree.searchResults(albums.await(), playlists.await())
        }
    }

    // Playing

    /**
     * Plays what a tapped row stands for, through the app's player (main
     * thread). Returns why nothing played, in the app's words, or null.
     */
    suspend fun play(id: AutoId): String? {
        val player = session.player.value ?: return VoiceText.NOT_SIGNED_IN
        return when (id) {
            is AutoId.PlayAlbum -> playAlbum(player, withContext(Dispatchers.Default) { album(id.discIds) }, shuffled = id.shuffled)
            is AutoId.AlbumTrack -> {
                val album = withContext(Dispatchers.Default) { album(id.discIds) } ?: return AutoTree.CANT_LOAD_ALBUM
                // As the album page: shuffle stays as it is, and Maniac Mode starts at track 1.
                player.play(album, startTrackId = id.trackId, openNowPlaying = false)
                finished(player)
            }
            is AutoId.Downloaded -> {
                val album = downloadedAlbum(listOf(id.albumId)) ?: return AutoTree.CANT_LOAD_ALBUM
                // A downloaded album always plays in order from track 1.
                player.play(album, openNowPlaying = false)
                finished(player)
            }
            is AutoId.PlayPlaylist -> playPlaylist(player, id.playlistId, startingAt = null, shuffled = id.shuffled)
            is AutoId.PlaylistSong -> playPlaylist(player, id.playlistId, startingAt = id.position, shuffled = null)
            else -> AutoTree.CANT_LOAD_ALBUM
        }
    }

    /** As CarPlay's Play and Shuffle: shuffle set first (Maniac Mode refuses it), then the album from the start. */
    private suspend fun playAlbum(player: PlayerController, album: JellyfinAlbum?, shuffled: Boolean): String? {
        if (album == null) return AutoTree.CANT_LOAD_ALBUM
        player.changeShuffle(shuffled)
        player.play(album, openNowPlaying = false)
        return finished(player)
    }

    /**
     * As iOS `AppSession.play(_:rows:startingAt:shuffled:)`: `shuffled`
     * sets shuffle first (null leaves it), and a refused play (offline, not
     * downloaded) puts it back and says why.
     */
    private suspend fun playPlaylist(player: PlayerController, playlistId: String, startingAt: Int?, shuffled: Boolean?): String? {
        val entries = withContext(Dispatchers.Default) { playlistEntries(playlistId) } ?: return AutoTree.CANT_LOAD_PLAYLIST
        if (entries.isEmpty()) return AutoTree.PLAYLIST_EMPTY
        val playlist = withContext(Dispatchers.Default) { playlist(playlistId) }
        val before = player.shuffle
        if (shuffled == false) player.changeShuffle(false)
        val refusal = player.play(playlist, entries, startingAt = startingAt, openNowPlaying = false, shuffled = shuffled == true)
        if (refusal != null) {
            player.changeShuffle(before)
            return refusal
        }
        return finished(player)
    }

    /** Waits for a streamed album's tracks to load; returns the player's error, if it had one. */
    private suspend fun finished(player: PlayerController): String? {
        withTimeoutOrNull(LOAD_TIMEOUT_MILLIS) { while (player.isLoading) delay(100) }
        return player.lastError
    }

    // Voice

    /**
     * "Play <album> on Tetsubi", as iOS's Siri intents: the best album match
     * (a playlist named exactly that comes first), shuffled when asked
     * (ignored in Maniac Mode); a playlist when asked for one; a random
     * album for "play music". Offline, only what's downloaded. Returns
     * what to tell the user when nothing plays, in iOS's words, or null.
     */
    suspend fun playFromSearch(request: VoiceRequest): String? {
        if (api == null) return VoiceText.NOT_SIGNED_IN
        val player = session.player.value ?: return VoiceText.NOT_SIGNED_IN
        if (request.terms.isEmpty()) {
            val pick = withContext(Dispatchers.Default) { randomAlbum() } ?: return VoiceText.LIBRARY_EMPTY
            return playAlbum(player, pick, shuffled = request.shuffle)
        }
        val (albums, playlists) = withContext(Dispatchers.Default) {
            coroutineScope {
                val albums = async { if (request.focus == VoiceFocus.PLAYLIST) emptyList() else searchAlbums(request.terms) }
                val playlists = async { if (request.focus == VoiceFocus.PLAYLIST || request.focus == VoiceFocus.ANY) searchPlaylists(request.terms) else emptyList() }
                albums.await() to playlists.await()
            }
        }
        val playlist = when (request.focus) {
            VoiceFocus.PLAYLIST -> VoiceQuery.bestPlaylist(request.terms, playlists) ?: playlists.firstOrNull()
            VoiceFocus.ANY -> VoiceQuery.exactPlaylist(request.terms, playlists)
            else -> null
        }
        if (playlist != null) return playPlaylist(player, playlist.id, startingAt = null, shuffled = request.shuffle)
        if (request.focus == VoiceFocus.PLAYLIST) return VoiceText.PLAYLIST_NOT_FOUND
        VoiceQuery.bestAlbum(request.terms, albums)?.let { album ->
            val whole = withContext(Dispatchers.Default) { api?.let { withSiblings(it, album) } ?: album }
            return playAlbum(player, whole, shuffled = request.shuffle)
        }
        // "Play <playlist> on Tetsubi" with no album of that name.
        VoiceQuery.bestPlaylist(request.terms, playlists)?.let {
            return playPlaylist(player, it.id, startingAt = null, shuffled = request.shuffle)
        }
        return VoiceText.ALBUM_NOT_FOUND
    }

    /** A random album from the server; offline, or when it doesn't answer, a random downloaded one. */
    private suspend fun randomAlbum(): JellyfinAlbum? {
        val api = api
        val fromServer = if (online && api != null) attempt { api.randomAlbums(limit = 1) }?.firstOrNull() else null
        if (fromServer != null) return api?.let { withSiblings(it, fromServer) }
        val downloaded = downloads?.downloaded?.value.orEmpty().randomOrNull() ?: return null
        return downloadedAlbum(listOf(downloaded.albumId))
    }

    /** The server's answer, or null when it couldn't be had. */
    private suspend fun <T> attempt(fetch: suspend () -> T): T? =
        try {
            fetch()
        } catch (e: JellyfinError) {
            null
        }

    private companion object {
        /** How long a play request waits for an album's tracks before Android Auto is told it started. */
        const val LOAD_TIMEOUT_MILLIS = 30_000L
    }
}

/** What voice requests say when nothing plays: iOS's Siri wording. */
object VoiceText {
    const val NOT_SIGNED_IN = "Open Tetsubi and sign in first."
    const val ALBUM_NOT_FOUND = "I couldn't find that album on your Jellyfin server."
    const val LIBRARY_EMPTY = "I couldn't reach your Jellyfin server."
    const val PLAYLIST_NOT_FOUND = "I couldn't find that playlist on your Jellyfin server."
}
