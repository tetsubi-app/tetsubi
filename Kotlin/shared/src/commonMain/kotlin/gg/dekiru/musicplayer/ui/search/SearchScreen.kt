package gg.dekiru.musicplayer.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.network.NetworkState
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.LocalAlbum
import gg.dekiru.musicplayer.downloads.LocalFiles
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.CheckCircleIcon
import gg.dekiru.musicplayer.ui.components.PlayIcon
import gg.dekiru.musicplayer.ui.components.XCircleIcon
import gg.dekiru.musicplayer.ui.downloads.coverPath
import gg.dekiru.musicplayer.ui.downloads.rememberIsDownloaded
import gg.dekiru.musicplayer.ui.playlist.PlaylistListRow
import gg.dekiru.musicplayer.ui.playlist.PlaylistsModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val readableWidth = 720.dp
private val secondary = Color.White.copy(alpha = 0.6f)
private val tertiary = Color.White.copy(alpha = 0.4f)

/**
 * The Search tab (as iOS `SearchView`): the field under the title, then
 * the results (a "Playlists" section, then album rows; tap to open),
 * the recent searches while the box is empty, or a hint. Offline:
 * matching downloads (playlists, tap to open; albums, tap to play).
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
) {
    LaunchedEffect(model, network) { network.collect { model.networkChanged(it.online) } }
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) focus.clearFocus() }
    val isDownloaded = rememberIsDownloaded(downloads)
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()

    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = readableWidth).fillMaxSize()) {
            Text(
                "Search",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp).semantics { heading() },
            )
            SearchField(model.query, model::onQueryChange, onDone = { focus.clearFocus() })
            LazyColumn(state = listState, contentPadding = insets, modifier = Modifier.weight(1f).fillMaxWidth()) {
                content(api, model, downloads, isDownloaded, playlists, onOpen = { album ->
                    focus.clearFocus()
                    onOpen(album)
                }, onOpenPlaylist = { playlist ->
                    focus.clearFocus()
                    onOpenPlaylist(playlist)
                }, onPlay = { local ->
                    focus.clearFocus()
                    play(local, player, onOpen)
                })
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private fun LazyListScope.content(
    api: JellyfinApi,
    model: SearchModel,
    downloads: DownloadManager?,
    isDownloaded: (JellyfinAlbum) -> Boolean,
    playlists: PlaylistsModel?,
    onOpen: (JellyfinAlbum) -> Unit,
    onOpenPlaylist: (JellyfinPlaylist) -> Unit,
    onPlay: (LocalAlbum) -> Unit,
) {
    when {
        model.isLoading -> item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), color = secondary, strokeWidth = 2.dp)
                Text("Searching…", color = secondary, fontSize = 15.sp)
            }
        }
        model.isOfflineResults -> {
            val found = model.localPlaylistResults.takeIf { playlists != null }.orEmpty()
            offlineResults(api, model, downloads, playlists, found, onOpenPlaylist, onPlay)
        }
        (playlists == null || model.playlistResults.isEmpty()) && model.results.isEmpty() && model.hasSearched -> item {
            Unavailable("No results", SearchModel.noResultsMessage(model.query))
        }
        !model.hasSearched && model.recent.isNotEmpty() -> history(model)
        !model.hasSearched -> item { Unavailable("Search your library", SearchModel.EMPTY_HINT) }
        else -> {
            val found = model.playlistResults.takeIf { playlists != null }.orEmpty()
            playlistSection(api, playlists, found, "playlist", onOpenPlaylist)
            if (model.results.isNotEmpty()) {
                item { Header(SearchModel.albumsHeader(model.results.size, found.size)) }
                items(model.results, key = { "result-${it.id}" }) { album ->
                    ResultRow(api, album, downloads.coverPath(album), isDownloaded(album), onClick = { onOpen(album) })
                }
            }
        }
    }
}

/** "Playlists (2)": rows as the Library's playlist list, each opening its page. Nothing when there are none. */
private fun LazyListScope.playlistSection(
    api: JellyfinApi,
    model: PlaylistsModel?,
    found: List<JellyfinPlaylist>,
    keyPrefix: String,
    onOpen: (JellyfinPlaylist) -> Unit,
) {
    if (model == null || found.isEmpty()) return
    item { Header(SearchModel.playlistsHeader(found.size)) }
    items(found, key = { "$keyPrefix-${it.id}" }) { playlist ->
        PlaylistListRow(api, model, playlist, compact = true, onOpen = { onOpen(playlist) })
    }
}

private fun LazyListScope.history(model: SearchModel) {
    item {
        Header("Recent searches") {
            TextButton(onClick = model::clearHistory) { Text("Clear", color = TetsubiColors.blue, fontSize = 13.sp) }
        }
    }
    items(model.recent, key = { "recent-$it" }) { term ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { model.useHistory(term) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ClockArrowIcon(20.dp, TetsubiColors.blue)
            Text(term, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Offline / server unreachable: matching downloads. A playlist opens its page; an album plays. */
private fun LazyListScope.offlineResults(
    api: JellyfinApi,
    model: SearchModel,
    downloads: DownloadManager?,
    playlistsModel: PlaylistsModel?,
    playlists: List<JellyfinPlaylist>,
    onOpenPlaylist: (JellyfinPlaylist) -> Unit,
    onPlay: (LocalAlbum) -> Unit,
) {
    item {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WifiSlashIcon(16.dp, secondary)
            Text(
                "Can't reach the server — searching downloads",
                color = secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
    if (model.localResults.isEmpty() && playlists.isEmpty()) {
        item {
            Text(
                SearchModel.noDownloadsMessage(model.query),
                color = secondary, fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
    if (playlists.isNotEmpty()) {
        playlistSection(api, playlistsModel, playlists, "local-playlist", onOpenPlaylist)
        if (model.localResults.isNotEmpty()) item { Header(SearchModel.albumsHeader(model.localResults.size, playlists.size)) }
    }
    items(model.localResults, key = { "local-${it.id}" }) { album ->
        val cover = downloads?.downloadedAlbum(album.id)?.coverPath?.takeIf(LocalFiles::exists)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Play album") { onPlay(album) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AlbumArt(api, album.id, size = 56.dp, cornerRadius = 4.dp, maxWidth = 100, localPath = cover)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(album.title.ifEmpty { "Album" }, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(album.artist, color = secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayIcon(20.dp, TetsubiColors.blue)
        }
    }
}

/** Plays a download, opening Now Playing (as iOS). No player here (the desktop): its album page instead. */
private fun play(album: LocalAlbum, player: PlayerController?, onOpen: (JellyfinAlbum) -> Unit) {
    val jellyfinAlbum = JellyfinAlbum(
        id = album.id, name = album.title.ifEmpty { null },
        albumArtists = listOf(JellyfinPerson(name = album.artist)),
    )
    if (player?.isSupported == true) player.play(jellyfinAlbum) else onOpen(jellyfinAlbum)
}

@Composable
private fun ResultRow(api: JellyfinApi, album: JellyfinAlbum, localArt: String?, isDownloaded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AlbumArt(api, album.id, size = 56.dp, cornerRadius = 4.dp, maxWidth = 100, localPath = localArt)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(album.displayName, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.artistName, color = secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            album.productionYear?.let { Text(it.toString(), color = tertiary, fontSize = 11.sp) }
        }
        if (isDownloaded) {
            CheckCircleIcon(16.dp, TetsubiColors.green, Modifier.semantics { contentDescription = "Downloaded" })
        }
    }
}

/** The search box: a magnifying glass, "Albums, artists or playlists", × to empty it. */
@Composable
private fun SearchField(text: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MagnifyingGlassIcon(18.dp, secondary)
        Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(SearchModel.FIELD_HINT, color = tertiary, fontSize = 16.sp, maxLines = 1)
            BasicTextField(
                value = text,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
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
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button) { onChange("") }
                    .semantics { contentDescription = "Clear text" },
                contentAlignment = Alignment.Center,
            ) { XCircleIcon(18.dp, secondary) }
        } else {
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun Header(title: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }
}

/** An icon, a title and a line, centred (as iOS `ContentUnavailableView`). */
@Composable
private fun Unavailable(title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MagnifyingGlassIcon(44.dp, secondary)
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(description, color = secondary, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}
