package gg.dekiru.musicplayer.ui.album

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.artist.ArtistInfo
import gg.dekiru.musicplayer.data.artist.ArtistInfoClient
import gg.dekiru.musicplayer.data.models.DiscGrouping
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.allDiscs
import gg.dekiru.musicplayer.data.models.isMultiDisc
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * One album page (as `AlbumDetailViewModel` on iOS). A page opened from a
 * single disc (only disc 2 was in a Home row) first finds the other discs
 * among the artist's albums, then loads tracks for all of them. Offline,
 * or when the server fails, a downloaded disc's tracks come from its
 * manifest, so a downloaded album opens and plays; More by and About the
 * artist stay hidden then.
 */
class AlbumModel(
    album: JellyfinAlbum,
    private val api: JellyfinApi,
    private val favorites: FavoritesStore,
    private val artistInfoClient: ArtistInfoClient,
    private val scope: CoroutineScope,
    private val downloads: () -> DownloadManager? = { null },
    private val isOnline: () -> Boolean = { true },
) {
    /** The signed-in server's downloads (the Download button), if any. */
    val downloadManager: DownloadManager? get() = downloads()

    var album by mutableStateOf(album)
        private set

    /** The real albums behind this page: one, or each disc of a set. */
    var discs by mutableStateOf(album.allDiscs)
        private set

    /** Tracks grouped by disc album, in disc order (one group for a normal album). */
    var tracksByDisc by mutableStateOf<List<Pair<JellyfinAlbum, List<JellyfinTrack>>>>(emptyList())
        private set

    /** One album whose tracks are tagged disc 1, disc 2…: tracks by that tag. Null otherwise. */
    var tracksByDiscNumber by mutableStateOf<List<Pair<Int, List<JellyfinTrack>>>?>(null)
        private set

    var isLoadingTracks by mutableStateOf(true)
        private set
    var isFavorite by mutableStateOf(album.userData?.isFavorite ?: false)
        private set

    /** Other albums by the artist (at most 12), newest first. */
    var moreByArtist by mutableStateOf<List<JellyfinAlbum>>(emptyList())
        private set
    var artistInfo by mutableStateOf<ArtistInfo?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    val tracks: List<JellyfinTrack> get() = tracksByDisc.flatMap { it.second }
    val isMultiDisc: Boolean get() = discs.size > 1

    private var loaded = false

    /** "Disc 2" heading number (from its name, else its position). */
    fun discNumber(disc: JellyfinAlbum): Int =
        DiscGrouping.discNumber(disc) ?: (discs.indexOfFirst { it.id == disc.id } + 1)

    /** Whether every track is this album's own (then the album name isn't repeated under each). */
    val allTracksFromThisAlbum: Boolean
        get() {
            val ids = (discs.map { it.id } + album.id).map { it.lowercase() }.toSet()
            return tracks.isNotEmpty() && tracks.all { t -> t.albumId?.let { it.lowercase() in ids } ?: (t.album == album.album) }
        }

    fun load() {
        if (loaded) return
        loaded = true
        if (!isOnline()) {
            loadTracks()
            return
        }
        scope.launch { runCatching { isFavorite = api.isFavorite(album.id) } }
        scope.launch { loadArtistInfo() }

        val own = album.discAlbums.firstOrNull() ?: album
        val looksLikeDisc = DiscGrouping.parse(own.displayName) != null
        val artistId = album.artistId
        if (artistId == null) {
            loadTracks()
            return
        }
        if (!looksLikeDisc || album.isMultiDisc) loadTracks()

        scope.launch {
            val found = try {
                api.albumsByArtist(artistId)
            } catch (e: JellyfinError) {
                emptyList()
            }
            if (looksLikeDisc) {
                val siblings = DiscGrouping.siblings(own, found)
                if (siblings.size > 1 && siblings.map { it.id }.toSet() != discs.map { it.id }.toSet()) {
                    val combined = DiscGrouping.combined(siblings)
                    album = combined
                    discs = combined.allDiscs
                    loadTracks()
                } else if (!isMultiDisc) {
                    loadTracks()
                }
            }
            val shown = discs.map { it.id }.toSet()
            moreByArtist = DiscGrouping.merge(found.filter { it.id !in shown }).take(12)
        }
    }

    /** Tracks for every disc, in disc order: from the server, or a download's manifest offline or when the server fails. */
    private fun loadTracks() {
        isLoadingTracks = true
        errorMessage = null
        val discs = discs
        val offline = !isOnline()
        scope.launch {
            var firstError: String? = null
            val results = discs.map { disc ->
                val downloaded = downloads()?.downloadedAlbum(disc.id)
                async {
                    if (offline && downloaded != null) return@async downloadedTracks(downloaded)
                    try {
                        api.tracks(disc.id)
                    } catch (e: JellyfinError) {
                        downloaded?.let(::downloadedTracks) ?: run {
                            firstError = firstError ?: e.message
                            null
                        }
                    }
                }
            }.awaitAll()
            if (this@AlbumModel.discs.map { it.id } != discs.map { it.id }) return@launch // superseded
            tracksByDisc = discs.zip(results).mapNotNull { (disc, tracks) -> tracks?.let { disc to it } }
            tracksByDiscNumber = if (discs.size == 1) DiscGrouping.byDiscNumber(tracks) else null
            errorMessage = if (tracks.isEmpty()) firstError else null
            isLoadingTracks = false
        }
    }

    companion object {
        /** A download's tracks as the server would list them (disc 2 track 1 is saved as number 201). */
        fun downloadedTracks(album: DownloadedAlbum): List<JellyfinTrack> {
            val hasDiscs = album.tracks.any { it.number >= 200 }
            return album.tracks.map { t ->
                JellyfinTrack(
                    id = t.trackId,
                    name = t.title,
                    indexNumber = if (hasDiscs) t.number % 100 else t.number,
                    runTimeTicks = (t.durationSeconds * 10_000_000).roundToLong(),
                    album = album.title,
                    albumId = album.albumId,
                    normalizationGain = t.normalizationGain,
                    discNumber = if (hasDiscs) maxOf(1, t.number / 100) else null,
                    hasOwnArt = t.hasOwnArt,
                )
            }
        }
    }

    /** The server's MusicBrainz ID for the artist when it has one, else a search by name. */
    private suspend fun loadArtistInfo() {
        val name = album.artistName.takeIf { it.isNotEmpty() } ?: return
        val mbid = album.artistId?.let { runCatching { api.musicBrainzArtistId(it) }.getOrNull() }
        artistInfo = artistInfoClient.info(name, mbid)
    }

    /** Flips the heart at once; puts it back if the server says no. */
    fun toggleFavorite() {
        val newValue = !isFavorite
        isFavorite = newValue
        scope.launch {
            try {
                api.setFavorite(album.id, newValue)
                favorites.record(album.id, newValue)
            } catch (e: JellyfinError) {
                isFavorite = !newValue
            }
        }
    }
}
