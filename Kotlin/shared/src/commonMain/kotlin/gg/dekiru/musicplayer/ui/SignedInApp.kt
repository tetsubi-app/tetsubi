package gg.dekiru.musicplayer.ui

import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.ui.components.GearIcon
import gg.dekiru.musicplayer.ui.components.GridTabIcon
import gg.dekiru.musicplayer.ui.library.ArtistModel
import gg.dekiru.musicplayer.ui.library.ArtistScreen
import gg.dekiru.musicplayer.ui.library.LibraryModel
import gg.dekiru.musicplayer.ui.library.LibraryScreen
import gg.dekiru.musicplayer.ui.library.LibraryTab
import gg.dekiru.musicplayer.ui.search.MagnifyingGlassIcon
import gg.dekiru.musicplayer.ui.search.SearchHistory
import gg.dekiru.musicplayer.ui.search.SearchModel
import gg.dekiru.musicplayer.ui.search.SearchScreen
import gg.dekiru.musicplayer.ui.settings.SettingsScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.ui.components.DownloadCircleIcon
import gg.dekiru.musicplayer.ui.components.HouseIcon
import gg.dekiru.musicplayer.ui.downloads.DownloadsScreen
import gg.dekiru.musicplayer.ui.components.AlbumBackdrop
import gg.dekiru.musicplayer.ui.nowplaying.NowPlayingBar
import gg.dekiru.musicplayer.ui.nowplaying.NowPlayingScreen
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import gg.dekiru.musicplayer.AppSession
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.ui.playlist.AddToPlaylistModel
import gg.dekiru.musicplayer.ui.playlist.AddToPlaylistSheet
import gg.dekiru.musicplayer.ui.playlist.PlaylistModel
import gg.dekiru.musicplayer.ui.playlist.PlaylistNoticeBanner
import gg.dekiru.musicplayer.ui.playlist.PlaylistScreen
import gg.dekiru.musicplayer.ui.playlist.PlaylistsModel
import gg.dekiru.musicplayer.ui.album.AlbumModel
import gg.dekiru.musicplayer.ui.album.AlbumScreen
import gg.dekiru.musicplayer.ui.home.AlbumList
import gg.dekiru.musicplayer.ui.home.AlbumListModel
import gg.dekiru.musicplayer.ui.home.AlbumListScreen
import gg.dekiru.musicplayer.ui.home.HomeModel
import gg.dekiru.musicplayer.ui.home.HomeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** A page on a tab's back stack, with its own model (kept while you go deeper and come back). */
private sealed class Entry(val key: String, val scope: CoroutineScope)
private class AlbumEntry(key: String, val model: AlbumModel, scope: CoroutineScope) : Entry(key, scope)
private class ListEntry(key: String, val model: AlbumListModel, scope: CoroutineScope) : Entry(key, scope)
private class ArtistEntry(key: String, val model: ArtistModel, scope: CoroutineScope) : Entry(key, scope)
private class PlaylistPageEntry(key: String, val model: PlaylistModel, scope: CoroutineScope) : Entry(key, scope)

/** The tabs along the bottom, in the order of iOS `AppRootView`. */
private enum class Tab(val title: String, val icon: @Composable (Color) -> Unit) {
    HOME("Home", { HouseIcon(24.dp, it) }),
    LIBRARY("Library", { GridTabIcon(24.dp, it) }),
    SEARCH("Search", { MagnifyingGlassIcon(24.dp, it) }),
    DOWNLOADS("Downloads", { DownloadCircleIcon(24.dp, it) }),
    SETTINGS("Settings", { GearIcon(24.dp, it) }),
}

/**
 * The signed-in app: a tab bar along the bottom (Home, Library, Search, Downloads, Settings), each
 * tab with its own stack of pages (album and playlist pages, full lists). System back
 * (Android) or Escape (desktop) and the page's Back button go back; back
 * on a tab's first page goes to Home. Tapping the open tab goes to its
 * first page. Each page keeps its scroll position. While something is
 * loaded: the blurred album art behind, the Now Playing bar above the tab
 * bar, and Now Playing sliding up over everything.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SignedInApp(session: AppSession, api: JellyfinApi) {
    val scope = rememberCoroutineScope()
    val home = remember(api) { HomeModel(api, session.favorites, scope, ::today) }
    val library = remember(api) { LibraryModel(api, session.favorites, session.environment.preferences, scope) }
    // One list of playlists for Home's row and the Library's tab.
    val playlists = remember(api) { PlaylistsModel(api, session.environment.preferences, scope, downloads = { session.downloads.value }) }
    val search = remember(api) {
        SearchModel(
            api, SearchHistory(session.environment.preferences), scope, session.environment.isOnline,
            downloadedAlbums = { session.downloads.value?.localAlbums().orEmpty() },
            downloadedPlaylists = { session.downloads.value?.playlists?.availableOffline.orEmpty().map { it.manifest.toPlaylist() } },
        )
    }
    val stacks = remember(api) { Tab.entries.associateWith { mutableStateListOf<Entry>() } }
    var tab by rememberSaveable(api) { mutableStateOf(Tab.HOME) }
    val saved = rememberSaveableStateHolder()
    val counter = remember { intArrayOf(0) }
    val downloads by session.downloads.collectAsState()

    // Is there a newer release? Once per run, at most once a day (see UpdateChecker).
    LaunchedEffect(Unit) { session.updates.checkAtLaunch() }

    // Offline, the playlist lists are the downloaded ones.
    LaunchedEffect(downloads, playlists) { downloads?.playlists?.all?.collect(playlists::downloadsChanged) }

    fun childScope() = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))

    fun open(album: JellyfinAlbum) {
        val entryScope = childScope()
        val key = "album-${counter[0]++}-${album.id}"
        val model = AlbumModel(
            album, api, session.favorites, session.artistInfo, entryScope,
            downloads = { session.downloads.value }, isOnline = session.environment.isOnline,
        )
        stacks.getValue(tab) += AlbumEntry(key, model, entryScope)
    }

    fun openList(list: AlbumList) {
        val entryScope = childScope()
        stacks.getValue(tab) += ListEntry("list-${counter[0]++}-${list.name}", AlbumListModel(list, api, session.favorites, entryScope), entryScope)
    }

    fun openArtist(artist: JellyfinArtist) {
        val entryScope = childScope()
        stacks.getValue(tab) += ArtistEntry("artist-${counter[0]++}-${artist.id}", ArtistModel(artist, api, entryScope), entryScope)
    }

    fun openPlaylist(playlist: JellyfinPlaylist) {
        val entryScope = childScope()
        stacks.getValue(tab) += PlaylistPageEntry("playlist-${counter[0]++}-${playlist.id}", PlaylistModel(playlist, api, entryScope, playlists, downloads = { session.downloads.value }), entryScope)
    }

    // "Add to Playlist…": one sheet for the album pages, the playlist pages and Now Playing.
    val addToPlaylist = remember(api) {
        AddToPlaylistModel(api, scope) { changedId ->
            playlists.changed(changedId)
            // A page of that playlist that's open shows the new songs.
            for (stack in stacks.values) {
                for (entry in stack) if (entry is PlaylistPageEntry && entry.model.playlist.id == changedId) entry.model.load()
            }
        }
    }

    fun pop(stack: MutableList<Entry>) {
        val top = stack.removeLastOrNull() ?: return
        top.scope.cancel()
        saved.removeState(top.key)
    }

    /** A playlist was deleted: its page (wherever it is) goes, with any page opened from it. */
    fun leave(page: Entry) {
        for (stack in stacks.values) {
            if (page in stack) {
                while (stack.isNotEmpty() && stack.last() !== page) pop(stack)
                pop(stack)
            }
        }
    }

    fun back() {
        val stack = stacks.getValue(tab)
        if (stack.isNotEmpty()) pop(stack) else tab = Tab.HOME
    }

    fun select(selected: Tab) {
        // The open tab again: back to its first page.
        if (selected == tab) stacks.getValue(tab).let { while (it.isNotEmpty()) pop(it) }
        tab = selected
    }

    DisposableEffect(api) { onDispose { stacks.values.forEach { stack -> stack.forEach { it.scope.cancel() } } } }
    BackHandler(enabled = stacks.getValue(tab).isNotEmpty() || tab != Tab.HOME) { back() }

    val player by session.player.collectAsState()
    val network by session.environment.network.state.collectAsState()
    val playing = player
    // While the keyboard is up (Search), the bars stay behind it, as on iOS.
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showBar = playing?.currentTrack != null && !keyboardUp

    Box(Modifier.fillMaxSize()) {
        // The blurred album art while something is loaded.
        AlbumBackdrop(api, playing?.currentTrack?.artItemId, localPath = playing?.currentTrack?.localArtPath)
        Column(Modifier.fillMaxSize()) {
            // The tab bar keeps clear of the navigation bar, so the screens don't.
            Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                val top = stacks.getValue(tab).lastOrNull()
                saved.SaveableStateProvider(top?.key ?: "root-${tab.name}") {
                    when (top) {
                        null -> when (tab) {
                            Tab.HOME -> HomeScreen(
                                api, home, onOpen = ::open, onMore = ::openList, onSignOut = session::signOut,
                                network = session.environment.network.state, downloads = downloads,
                                playlists = playlists, onOpenPlaylist = ::openPlaylist,
                                onMorePlaylists = {
                                    // The Library's first page, on its Playlists tab.
                                    stacks.getValue(Tab.LIBRARY).let { while (it.isNotEmpty()) pop(it) }
                                    library.tab = LibraryTab.PLAYLISTS
                                    tab = Tab.LIBRARY
                                },
                                zoom = library.gridZoom,
                                updates = session.updates,
                            )
                            Tab.LIBRARY -> LibraryScreen(
                                api, library, onOpen = ::open, onOpenArtist = ::openArtist,
                                network = session.environment.network.state, downloads = downloads,
                                playlists = playlists, onOpenPlaylist = ::openPlaylist,
                            )
                            Tab.SEARCH -> SearchScreen(
                                api, search, onOpen = ::open, player = playing, downloads = downloads,
                                network = session.environment.network.state,
                                playlists = playlists, onOpenPlaylist = ::openPlaylist,
                            )
                            Tab.DOWNLOADS -> DownloadsScreen(api, downloads, playing, onOpenPlaylist = ::openPlaylist)
                            Tab.SETTINGS -> SettingsScreen(session, api)
                        }
                        is AlbumEntry -> AlbumScreen(api, top.model, playing, onOpen = ::open, onBack = ::back, onAddToPlaylist = addToPlaylist::start)
                        is ArtistEntry -> ArtistScreen(
                            api, top.model, onOpen = ::open, onBack = ::back,
                            network = session.environment.network.state, downloads = downloads, zoom = library.gridZoom,
                        )
                        is PlaylistPageEntry -> PlaylistScreen(
                            api, top.model, playing, onOpenAlbum = ::open, onBack = ::back,
                            isOnline = network.online, onAddToPlaylist = addToPlaylist::start, onDeleted = { leave(top) },
                            manager = downloads,
                        )
                        is ListEntry -> AlbumListScreen(api, top.model, onOpen = ::open, onBack = ::back, downloads = downloads, zoom = library.gridZoom)
                    }
                }
            }
            if (showBar && playing != null) {
                NowPlayingBar(api, playing, Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)))
            }
            if (!keyboardUp) TabBar(tab, onSelect = ::select)
        }
        AnimatedVisibility(
            visible = playing?.isNowPlayingOpen == true,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            if (playing != null) {
                NowPlayingScreen(
                    api, playing, onAddToPlaylist = { addToPlaylist.start(listOf(it)) },
                    effects = session.environment.playerEngine.audioEffects, effectsSettings = session.effectsSettings,
                    cast = session.environment.playerEngine.cast, isOnline = network.online,
                    preferences = session.environment.preferences,
                )
            }
        }
        // Over everything, Now Playing too: the sheet, and what adding did.
        AddToPlaylistSheet(addToPlaylist)
        PlaylistNoticeBanner(
            addToPlaylist,
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).widthIn(max = 720.dp),
        )
    }
}

/** Icons over labels, the open tab in blue (as the iOS tab bar), clear of the navigation bar. */
@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(TetsubiColors.background.copy(alpha = 0.85f))) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.15f)))
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .selectableGroup(),
            horizontalArrangement = Arrangement.Center,
        ) {
            for (t in Tab.entries) {
                val isSelected = t == selected
                val color = if (isSelected) TetsubiColors.blue else Color.White.copy(alpha = 0.55f)
                Column(
                    Modifier
                        .weight(1f)
                        .widthIn(max = 160.dp)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(t) })
                        .padding(top = 6.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    t.icon(color)
                    Text(t.title, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
private fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
