package gg.dekiru.musicplayer.android.auto

import gg.dekiru.musicplayer.data.models.DiscGrouping
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.PlaylistEntry
import gg.dekiru.musicplayer.data.models.allDiscs
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.ui.library.AlphaIndex
import gg.dekiru.musicplayer.ui.playlist.PlaylistsModel

/** How a page's rows are laid out in the car: albums as a grid of covers, songs and names as a list. */
enum class AutoStyle { LIST, GRID }

/**
 * One row of Android Auto's browse tree, before it becomes a Media3
 * `MediaItem` (see `AutoMediaItems`). Plain data, so the tree can be tested
 * without Android.
 */
data class AutoRow(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    /** The item whose picture the row shows (an album, a playlist or a track). */
    val artItemId: String? = null,
    /** Opens a page of its own. */
    val browsable: Boolean = false,
    /** Starts music when tapped. */
    val playable: Boolean = false,
    /** The heading the row sits under ("Recently Played", "Disc 2"). */
    val group: String? = null,
    /** How the page it opens lays out its rows (browsable rows only). */
    val childStyle: AutoStyle? = null,
)

/**
 * Android Auto's browse tree, as CarPlay's (`CarPlaySceneDelegate`): Home,
 * Artists, Playlists and Downloads tabs, album and playlist pages with Play
 * and Shuffle, and the same wording. Maniac Mode takes Shuffle away (a
 * tapped track still plays the album from track 1: the player sees to that).
 */
object AutoTree {

    // Wording, as CarPlay's ("on your iPhone" becomes "on your phone").
    const val NOT_SIGNED_IN = "Not signed in"
    const val SIGN_IN_HINT = "Open Tetsubi on your phone to sign in."
    const val CANT_REACH = "Can't reach your Jellyfin server"
    const val DOWNLOADS_HINT = "Downloaded albums are in Downloads."
    const val NOTHING_YET = "Nothing here yet"
    const val NO_ALBUMS = "No albums"
    const val NO_PLAYLISTS = "No playlists yet"
    const val NO_DOWNLOADED_PLAYLISTS = "No downloaded playlists"
    const val DOWNLOAD_PLAYLISTS_HINT = "Download playlists on your phone to play them offline."
    const val NO_DOWNLOADED_ALBUMS = "No downloaded albums"
    const val DOWNLOAD_ALBUMS_HINT = "Download albums on your phone to play them offline."
    const val CANT_LOAD_ALBUM = "Can't load this album"
    const val CANT_LOAD_PLAYLIST = "Can't load this playlist"
    const val PLAYLIST_EMPTY = "This playlist is empty."
    const val PLAY = "Play"
    const val SHUFFLE = "Shuffle"
    const val RECENTLY_PLAYED = "Recently Played"
    const val FAVOURITES = "Favourites"
    const val RECENTLY_ADDED = "Recently Added"

    /** Albums per Home row, as CarPlay. */
    const val HOME_ROW_LIMIT = 12

    /** The four tabs: Home and Downloads as covers, Artists and Playlists as lists. */
    fun tabs(): List<AutoRow> = AutoTab.entries.map { tab ->
        AutoRow(
            id = AutoId.Tab(tab).format(),
            title = tab.title,
            browsable = true,
            childStyle = if (tab == AutoTab.HOME || tab == AutoTab.DOWNLOADS) AutoStyle.GRID else AutoStyle.LIST,
        )
    }

    /** A page with nothing in it shows why, as CarPlay's empty text. */
    fun note(title: String, subtitle: String? = null): AutoRow =
        AutoRow(id = AutoId.Note(title.filter { it.isLetterOrDigit() }).format(), title = title, subtitle = subtitle)

    fun signedOut(): List<AutoRow> = listOf(note(NOT_SIGNED_IN, SIGN_IN_HINT))

    /** An album that opens its page; `tag` keeps the same album in two rows apart. */
    fun album(album: JellyfinAlbum, group: String? = null, tag: String? = null): AutoRow = AutoRow(
        id = AutoId.Album(album.allDiscs.map { it.id }).format(tag),
        title = album.displayName,
        subtitle = album.artistName.ifEmpty { null },
        artItemId = album.id,
        browsable = true,
        group = group,
        childStyle = AutoStyle.LIST,
    )

    /**
     * Home: Recently Played, Favourites and Recently Added, each under its
     * heading, empty rows left out. When the server couldn't be reached,
     * one row that says so and opens Downloads.
     */
    fun home(recentlyPlayed: List<JellyfinAlbum>, favourites: List<JellyfinAlbum>, recentlyAdded: List<JellyfinAlbum>, failed: Boolean): List<AutoRow> {
        val sections = listOf(
            Triple(RECENTLY_PLAYED, "played", recentlyPlayed),
            Triple(FAVOURITES, "favourite", favourites),
            Triple(RECENTLY_ADDED, "added", recentlyAdded),
        )
        val rows = sections.flatMap { (heading, tag, albums) -> albums.take(HOME_ROW_LIMIT).map { album(it, heading, tag) } }
        if (rows.isNotEmpty()) return rows
        return if (failed) listOf(offline()) else listOf(note(NOTHING_YET))
    }

    /** "Can't reach your Jellyfin server", opening Downloads. */
    fun offline(): AutoRow = AutoRow(
        id = AutoId.Tab(AutoTab.DOWNLOADS).format("offline"),
        title = CANT_REACH,
        subtitle = DOWNLOADS_HINT,
        browsable = true,
        childStyle = AutoStyle.GRID,
    )

    /** Artists A–Z, each opening their albums as covers. */
    fun artists(artists: List<JellyfinArtist>?): List<AutoRow> {
        if (artists == null) return listOf(note(CANT_REACH))
        return artists.sortedWith(AlphaIndex.comparator { it.name }).map {
            AutoRow(id = AutoId.Artist(it.id).format(), title = it.name, browsable = true, childStyle = AutoStyle.GRID)
        }
    }

    /** An artist's albums (discs merged), or why there are none. */
    fun artistAlbums(albums: List<JellyfinAlbum>?): List<AutoRow> {
        if (albums == null) return listOf(note(CANT_REACH))
        if (albums.isEmpty()) return listOf(note(NO_ALBUMS))
        return DiscGrouping.merge(albums).map { album(it) }
    }

    /** The server's playlists, or the downloaded ones without it (`fromServer` false). By name. */
    fun playlists(playlists: List<JellyfinPlaylist>, fromServer: Boolean): List<AutoRow> {
        if (playlists.isEmpty()) {
            return listOf(if (fromServer) note(NO_PLAYLISTS) else note(NO_DOWNLOADED_PLAYLISTS, DOWNLOAD_PLAYLISTS_HINT))
        }
        return playlists.sortedWith(AlphaIndex.comparator { it.displayName }).map {
            AutoRow(
                id = AutoId.Playlist(it.id).format(),
                title = it.displayName,
                subtitle = PlaylistsModel.summary(it),
                artItemId = if (it.hasArt || !fromServer) it.id else null,
                browsable = true,
                childStyle = AutoStyle.LIST,
            )
        }
    }

    /** Downloaded albums by title; tapping one plays it from this device. */
    fun downloads(albums: List<DownloadedAlbum>): List<AutoRow> {
        if (albums.isEmpty()) return listOf(note(NO_DOWNLOADED_ALBUMS, DOWNLOAD_ALBUMS_HINT))
        return albums.sortedWith(AlphaIndex.comparator { it.title }).map {
            AutoRow(
                id = AutoId.Downloaded(it.albumId).format(),
                title = it.title,
                subtitle = it.artist.ifEmpty { null },
                artItemId = it.albumId,
                playable = true,
            )
        }
    }

    /**
     * An album page: Play and Shuffle (no Shuffle in Maniac Mode), then the
     * tracks, under "Disc N" for a multi-disc album. `discs` pairs each disc
     * with its tracks (null: they couldn't be loaded).
     */
    fun albumPage(album: JellyfinAlbum, discs: List<Pair<JellyfinAlbum, List<JellyfinTrack>?>>, maniac: Boolean): List<AutoRow> {
        val groups = trackGroups(discs)
        if (groups.isEmpty()) return listOf(note(CANT_LOAD_ALBUM))
        val discIds = album.allDiscs.map { it.id }
        val rows = mutableListOf(
            AutoRow(id = AutoId.PlayAlbum(discIds, shuffled = false).format(), title = PLAY, artItemId = album.id, playable = true),
        )
        if (!maniac) {
            rows += AutoRow(id = AutoId.PlayAlbum(discIds, shuffled = true).format(), title = SHUFFLE, artItemId = album.id, playable = true)
        }
        for ((heading, tracks) in groups) {
            rows += tracks.map { track ->
                AutoRow(
                    id = AutoId.AlbumTrack(discIds, track.id).format(),
                    title = track.displayName,
                    subtitle = duration(track.durationSeconds),
                    playable = true,
                    group = heading,
                )
            }
        }
        return rows
    }

    /**
     * Tracks under their headings, as CarPlay: one group per disc album
     * ("Disc N" from its name, else its place), or per disc tag inside one
     * album, or one group with no heading. Discs that didn't load are left out.
     */
    fun trackGroups(discs: List<Pair<JellyfinAlbum, List<JellyfinTrack>?>>): List<Pair<String?, List<JellyfinTrack>>> {
        val groups = mutableListOf<Pair<String?, List<JellyfinTrack>>>()
        discs.forEachIndexed { i, (disc, tracks) ->
            if (tracks.isNullOrEmpty()) return@forEachIndexed
            if (discs.size > 1) {
                groups += "Disc ${DiscGrouping.discNumber(disc) ?: (i + 1)}" to tracks
            } else {
                val byTag = DiscGrouping.byDiscNumber(tracks)
                if (byTag != null) groups += byTag.map { (n, t) -> "Disc $n" to t } else groups += null to tracks
            }
        }
        return groups
    }

    /**
     * A playlist page: Play and Shuffle (no Shuffle in Maniac Mode), then the
     * songs in playlist order. `entries` null: it couldn't be loaded.
     */
    fun playlistPage(playlistId: String, entries: List<PlaylistEntry>?, maniac: Boolean): List<AutoRow> {
        if (entries == null) return listOf(note(CANT_LOAD_PLAYLIST))
        if (entries.isEmpty()) return listOf(note(PLAYLIST_EMPTY))
        val rows = mutableListOf(
            AutoRow(id = AutoId.PlayPlaylist(playlistId, shuffled = false).format(), title = PLAY, playable = true),
        )
        if (!maniac) rows += AutoRow(id = AutoId.PlayPlaylist(playlistId, shuffled = true).format(), title = SHUFFLE, playable = true)
        rows += entries.mapIndexed { place, entry ->
            AutoRow(
                // By its place in the playlist: a song can be there twice.
                id = AutoId.PlaylistSong(playlistId, place).format(),
                title = entry.track.displayName,
                subtitle = detailLine(entry).ifEmpty { duration(entry.track.durationSeconds) },
                artItemId = entry.artItemId,
                playable = true,
            )
        }
        return rows
    }

    /** Search results: albums, then playlists. */
    fun searchResults(albums: List<JellyfinAlbum>, playlists: List<JellyfinPlaylist>): List<AutoRow> =
        albums.map { album(it) } + playlists.map {
            AutoRow(
                id = AutoId.Playlist(it.id).format(),
                title = it.displayName,
                subtitle = PlaylistsModel.summary(it),
                artItemId = if (it.hasArt) it.id else null,
                browsable = true,
                childStyle = AutoStyle.LIST,
            )
        }

    /** "Artist · Album", as a playlist row on iOS. */
    fun detailLine(entry: PlaylistEntry): String =
        listOfNotNull(entry.albumArtist, entry.albumName).filter { it.isNotEmpty() }.joinToString(" · ")

    /** One page of `rows` (Android Auto usually asks for them all at once). */
    fun <T> page(rows: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0 || pageSize == Int.MAX_VALUE) return rows
        val from = page.toLong() * pageSize
        if (from >= rows.size) return emptyList()
        return rows.drop(from.toInt()).take(pageSize)
    }

    /** "3:07"; null for an unknown length. */
    fun duration(seconds: Long): String? {
        if (seconds <= 0) return null
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}
