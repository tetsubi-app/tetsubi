package gg.dekiru.musicplayer.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.network.NetworkState
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.LocalAlbum
import gg.dekiru.musicplayer.downloads.LocalFiles
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.ActionChip
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.AlbumCard
import gg.dekiru.musicplayer.ui.components.ArtistCard
import gg.dekiru.musicplayer.ui.components.Banner
import gg.dekiru.musicplayer.ui.components.BannerKind
import gg.dekiru.musicplayer.ui.components.EmptyState
import gg.dekiru.musicplayer.ui.components.FilterChips
import gg.dekiru.musicplayer.ui.components.ListRow
import gg.dekiru.musicplayer.ui.components.LoadingSpinner
import gg.dekiru.musicplayer.ui.components.LocalPlayPrompt
import gg.dekiru.musicplayer.ui.components.PageHeader
import gg.dekiru.musicplayer.ui.components.PlayChoice
import gg.dekiru.musicplayer.ui.components.PlayIcon
import gg.dekiru.musicplayer.ui.components.PlayPrompt
import gg.dekiru.musicplayer.ui.components.SectionHeader
import gg.dekiru.musicplayer.ui.components.WithContextMenu
import gg.dekiru.musicplayer.ui.components.XCircleIcon
import gg.dekiru.musicplayer.ui.components.albumQueueActions
import gg.dekiru.musicplayer.ui.components.menuClickable
import gg.dekiru.musicplayer.ui.downloads.coverPath
import gg.dekiru.musicplayer.ui.downloads.rememberIsDownloaded
import gg.dekiru.musicplayer.ui.layout.CardGrid
import gg.dekiru.musicplayer.ui.layout.CardMetrics
import gg.dekiru.musicplayer.ui.layout.LocalWindowClass
import gg.dekiru.musicplayer.ui.layout.fullWidthItem
import gg.dekiru.musicplayer.ui.layout.headerItem
import gg.dekiru.musicplayer.ui.layout.rowItems
import gg.dekiru.musicplayer.ui.layout.screenInsets
import gg.dekiru.musicplayer.ui.playlist.PlaylistListRow
import gg.dekiru.musicplayer.ui.playlist.PlaylistsModel
import gg.dekiru.musicplayer.ui.tetsubiType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Search tab (as iOS `SearchView`): a "Search" page header, the pill field, filter chips
 * (All, Albums, Artists, Playlists), then the results in the app's grid (album and artist cards;
 * playlists as rows, in two columns when wide), the recent searches while the box is empty, or a
 * hint. Offline: matching downloads (playlists, tap to open; albums, tap to play).
 * The keyboard goes away on Search, on scrolling and on opening a result.
 */
@Composable
fun SearchScreen(
    api: JellyfinApi,
    model: SearchModel,
    onOpen: (JellyfinAlbum) -> Unit,
    player: PlayerController? = null,
    downloads: DownloadManager? = null,
    network: StateFlow<NetworkState> = MutableStateFlow(NetworkState()),
    /** The app's playlists, for the rows' pictures; without it no playlists are shown. */
    playlists: PlaylistsModel? = null,
    onOpenPlaylist: (JellyfinPlaylist) -> Unit = {},
    onOpenArtist: (JellyfinArtist) -> Unit = {},
) {
    LaunchedEffect(model, network) { network.collect { model.networkChanged(it.online) } }
    val focus = LocalFocusManager.current
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState.isScrollInProgress) { if (gridState.isScrollInProgress) focus.clearFocus() }
    val isDownloaded = rememberIsDownloaded(downloads)
    val prompt = LocalPlayPrompt.current
    var filter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    val margin = LocalWindowClass.current.pageMargin

    Column(Modifier.fillMaxSize().screenInsets()) {
        PageHeader("Search")
        SearchField(model.query, model::onQueryChange, onDone = { focus.clearFocus() }, Modifier.padding(horizontal = margin))
        if (model.query.isNotBlank()) {
            FilterChips(
                SearchFilter.entries, filter, { it.title }, { filter = it },
                Modifier.padding(horizontal = margin).padding(top = 12.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CardGrid(state = gridState) { metrics ->
                content(
                    api, model, filter, metrics, downloads, isDownloaded, playlists,
                    onOpen = { album ->
                        focus.clearFocus()
                        onOpen(album)
                    }, onOpenArtist = { artist ->
                        focus.clearFocus()
                        onOpenArtist(artist)
                    }, onOpenPlaylist = { playlist ->
                        focus.clearFocus()
                        onOpenPlaylist(playlist)
                    }, onPlay = { local ->
                        focus.clearFocus()
                        play(local, player, prompt, onOpen)
                    },
                )
            }
        }
    }
}

private fun LazyGridScope.content(
    api: JellyfinApi,
    model: SearchModel,
    filter: SearchFilter,
    metrics: CardMetrics,
    downloads: DownloadManager?,
    isDownloaded: (JellyfinAlbum) -> Boolean,
    playlists: PlaylistsModel?,
    onOpen: (JellyfinAlbum) -> Unit,
    onOpenArtist: (JellyfinArtist) -> Unit,
    onOpenPlaylist: (JellyfinPlaylist) -> Unit,
    onPlay: (LocalAlbum) -> Unit,
) {
    when {
        model.isLoading -> fullWidthItem("loading") { LoadingSpinner() }
        model.isOfflineResults -> {
            val found = model.localPlaylistResults.takeIf { playlists != null && filter.showsPlaylists }.orEmpty()
            offlineResults(api, model, filter, metrics, downloads, playlists, found, onOpenPlaylist, onPlay)
        }
        !model.hasSearched && model.recent.isNotEmpty() -> history(model)
        !model.hasSearched -> fullWidthItem("hint") {
            EmptyState("Search your library", message = SearchModel.EMPTY_HINT, icon = { MagnifyingGlassIcon(44.dp, TetsubiColors.textTertiary) })
        }
        else -> {
            val albums = model.results.takeIf { filter.showsAlbums }.orEmpty()
            val artists = model.artistResults.takeIf { filter.showsArtists }.orEmpty()
            val found = model.playlistResults.takeIf { playlists != null && filter.showsPlaylists }.orEmpty()
            if (albums.isEmpty() && artists.isEmpty() && found.isEmpty()) {
                fullWidthItem("none") {
                    EmptyState(
                        "No results", message = SearchModel.noResultsMessage(model.query, filter),
                        icon = { MagnifyingGlassIcon(44.dp, TetsubiColors.textTertiary) },
                    )
                }
                return
            }
            val sections = listOf(albums.size, artists.size, found.size).count { it > 0 }
            if (albums.isNotEmpty()) {
                headerItem("albums-header") { SectionHeader(SearchModel.albumsHeader(albums.size, sections - 1), horizontalPadding = 0.dp) }
                items(albums, key = { "result-${it.id}" }) { album ->
                    AlbumCard(api, album, metrics.cardWidth, onClick = { onOpen(album) }, localArtPath = downloads.coverPath(album), isDownloaded = isDownloaded(album))
                }
            }
            if (artists.isNotEmpty()) {
                headerItem("artists-header") { SectionHeader(SearchModel.artistsHeader(artists.size), horizontalPadding = 0.dp) }
                items(artists, key = { "artist-${it.id}" }) { artist -> ArtistCard(api, artist, metrics.cardWidth) { onOpenArtist(artist) } }
            }
            playlistSection(api, playlists, found, metrics, "playlist", onOpenPlaylist)
        }
    }
}

/** "Playlists (2)": rows as the Library's playlist list, each opening its page. Nothing when there are none. */
private fun LazyGridScope.playlistSection(
    api: JellyfinApi,
    model: PlaylistsModel?,
    found: List<JellyfinPlaylist>,
    metrics: CardMetrics,
    keyPrefix: String,
    onOpen: (JellyfinPlaylist) -> Unit,
) {
    if (model == null || found.isEmpty()) return
    headerItem("$keyPrefix-header") { SectionHeader(SearchModel.playlistsHeader(found.size), horizontalPadding = 0.dp) }
    rowItems(found, metrics, key = { "$keyPrefix-${it.id}" }) { playlist ->
        PlaylistListRow(api, model, playlist, horizontalPadding = 0.dp, onOpen = { onOpen(playlist) })
    }
}

private fun LazyGridScope.history(model: SearchModel) {
    fullWidthItem("recent-header") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Recent searches", Modifier.weight(1f), horizontalPadding = 0.dp)
            ActionChip("Clear", model::clearHistory)
        }
    }
    for (term in model.recent) {
        fullWidthItem("recent-$term") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { model.useHistory(term) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ClockArrowIcon(20.dp, TetsubiColors.blue)
                Text(term, style = tetsubiType.rowTitle, color = TetsubiColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Offline / server unreachable: matching downloads. A playlist opens its page; an album plays. */
private fun LazyGridScope.offlineResults(
    api: JellyfinApi,
    model: SearchModel,
    filter: SearchFilter,
    metrics: CardMetrics,
    downloads: DownloadManager?,
    playlistsModel: PlaylistsModel?,
    playlists: List<JellyfinPlaylist>,
    onOpenPlaylist: (JellyfinPlaylist) -> Unit,
    onPlay: (LocalAlbum) -> Unit,
) {
    val local = model.localResults.takeIf { filter.showsAlbums }.orEmpty()
    fullWidthItem("offline") {
        Banner("Can't reach the server. Searching downloads.", kind = BannerKind.Offline, horizontalPadding = 0.dp)
    }
    if (local.isEmpty() && playlists.isEmpty()) {
        fullWidthItem("none") {
            Text(
                if (filter == SearchFilter.ALL) SearchModel.noDownloadsMessage(model.query) else SearchModel.noResultsMessage(model.query, filter),
                style = tetsubiType.body, color = TetsubiColors.textSecondary, modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
    if (playlists.isNotEmpty()) {
        playlistSection(api, playlistsModel, playlists, metrics, "local-playlist", onOpenPlaylist)
        if (local.isNotEmpty()) headerItem("local-header") { SectionHeader(SearchModel.albumsHeader(local.size, 1), horizontalPadding = 0.dp) }
    }
    rowItems(local, metrics, key = { "local-${it.id}" }) { album ->
        val cover = downloads?.downloadedAlbum(album.id)?.coverPath?.takeIf(LocalFiles::exists)
        // Long press or right-click: Play Next, Add to Queue.
        WithContextMenu(albumQueueActions(album.toJellyfinAlbum())) { openMenu ->
            ListRow(
                title = album.title.ifEmpty { "Album" }, subtitle = album.artist,
                art = { AlbumArt(api, album.id, size = 40.dp, cornerRadius = Radii.thumb, maxWidth = 100, localPath = cover) },
                trailing = { PlayIcon(20.dp, TetsubiColors.blue) },
                horizontalPadding = 0.dp,
                modifier = Modifier.menuClickable({ onPlay(album) }, openMenu, onClickLabel = "Play album"),
            )
        }
    }
}

/** A download as an album to play or queue. */
private fun LocalAlbum.toJellyfinAlbum() = JellyfinAlbum(
    id = id, name = title.ifEmpty { null },
    albumArtists = listOf(JellyfinPerson(name = artist)),
)

/**
 * Plays a download, opening Now Playing (as iOS); with more than the
 * playing album queued, asks first. No player here (the desktop): its
 * album page instead.
 */
private fun play(album: LocalAlbum, player: PlayerController?, prompt: PlayPrompt, onOpen: (JellyfinAlbum) -> Unit) {
    val jellyfinAlbum = album.toJellyfinAlbum()
    if (player?.isSupported != true) return onOpen(jellyfinAlbum)
    prompt.play(
        player,
        PlayChoice(
            jellyfinAlbum.displayName,
            replace = { player.play(jellyfinAlbum) },
            playNext = { player.playNext(jellyfinAlbum) },
            addToQueue = { player.addToQueue(jellyfinAlbum) },
        ),
    )
}

/** The search box: a pill with a magnifying glass, "Albums, artists or playlists", and an x to empty it. */
@Composable
private fun SearchField(text: String, onChange: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radii.FULL_PERCENT)
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(shape).background(TetsubiColors.surface, shape).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MagnifyingGlassIcon(18.dp, TetsubiColors.textSecondary)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(SearchModel.FIELD_HINT, style = tetsubiType.body.copy(fontSize = 16.sp), color = TetsubiColors.textTertiary, maxLines = 1)
            BasicTextField(
                value = text,
                onValueChange = onChange,
                singleLine = true,
                textStyle = tetsubiType.body.copy(fontSize = 16.sp, color = TetsubiColors.textPrimary),
                cursorBrush = SolidColor(TetsubiColors.blue),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { onDone() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (text.isNotEmpty()) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(Radii.FULL_PERCENT))
                    .clickable(role = Role.Button) { onChange("") }
                    .semantics { contentDescription = "Clear text" },
                contentAlignment = Alignment.Center,
            ) { XCircleIcon(18.dp, TetsubiColors.textSecondary) }
        } else {
            Box(Modifier.size(36.dp))
        }
    }
}
