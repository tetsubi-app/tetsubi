package gg.dekiru.musicplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.AlbumCard
import gg.dekiru.musicplayer.ui.components.ArtistAbout
import gg.dekiru.musicplayer.ui.components.InfoPaneWidth
import gg.dekiru.musicplayer.ui.components.DetailMetrics
import gg.dekiru.musicplayer.ui.components.DetailTitle
import gg.dekiru.musicplayer.ui.layout.CardGrid
import gg.dekiru.musicplayer.ui.layout.LocalWindowClass
import gg.dekiru.musicplayer.ui.layout.WindowClass
import gg.dekiru.musicplayer.ui.layout.fullWidthItem
import gg.dekiru.musicplayer.ui.layout.rememberBottomInset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.network.NetworkState
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.ui.components.EmptyState
import gg.dekiru.musicplayer.ui.components.LoadingSpinner
import gg.dekiru.musicplayer.ui.components.PageControls
import gg.dekiru.musicplayer.ui.components.PageHeader
import gg.dekiru.musicplayer.ui.downloads.collectDownloaded
import gg.dekiru.musicplayer.ui.downloads.coverPath
import gg.dekiru.musicplayer.ui.downloads.rememberIsDownloaded
import gg.dekiru.musicplayer.ui.home.HomeModel
import gg.dekiru.musicplayer.ui.layout.screenInsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One artist (as `ArtistAlbumsView` on iOS), laid out as the other detail pages: a round picture, the
 * name and About (MusicBrainz facts, genres, the Wikipedia introduction with its attribution), and the
 * albums as the app's grid with the year under each title. Two panes from Expanded (info 320 on the
 * left, the grid on the right); stacked on Compact. The card size control sits in the header.
 * Offline: their downloaded albums only, and no About.
 * There is no Play all or Shuffle here: the player has no "play an artist" yet.
 */
@Composable
fun ArtistScreen(
    api: JellyfinApi,
    model: ArtistModel,
    onOpen: (JellyfinAlbum) -> Unit,
    onBack: () -> Unit,
    network: StateFlow<NetworkState> = MutableStateFlow(NetworkState()),
    downloads: DownloadManager? = null,
) {
    val online = network.collectAsState().value.online
    LaunchedEffect(model, online) {
        if (online) {
            model.loadIfNeeded()
            model.loadInfo()
        }
    }
    val isDownloaded = rememberIsDownloaded(downloads)
    val downloaded by downloads.collectDownloaded()
    val downloadedAlbums = remember(downloaded) { HomeModel.downloadedRow(downloaded) }
    val albums = ArtistModel.ordered(model.shown(!online, downloadedAlbums, isDownloaded))
    val grid = rememberLazyGridState()
    val info = model.info?.takeIf { online }
    val windowClass = LocalWindowClass.current
    val margin = windowClass.pageMargin

    Column(Modifier.fillMaxSize().screenInsets()) {
        PageHeader(null, onBack = onBack) { PageControls(density = true) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val twoPane = windowClass == WindowClass.Expanded || (windowClass == WindowClass.Medium && maxWidth >= 900.dp)
            val albumGrid: @Composable () -> Unit = {
                when {
                    albums.isNotEmpty() -> LibraryAlbumGrid(
                        api, albums, grid, onOpen, isDownloaded,
                        localArt = { album -> if (isDownloaded(album)) downloads.coverPath(album) else null },
                        showsYear = true,
                    )
                    online && !model.loaded -> LoadingSpinner()
                    else -> EmptyState("No albums found")
                }
            }
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(InfoPaneWidth + margin).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(start = margin, top = 8.dp, bottom = rememberBottomInset() + 32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val metrics = DetailMetrics(true, 240.dp)
                        ArtistInfoBlock(api, model, metrics)
                        info?.let { ArtistAbout(model.artist.name, it) }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { albumGrid() }
                }
            } else if (albums.isNotEmpty()) {
                // Stacked: the info block is the grid's first row, About its last.
                CardGrid(state = grid) { metrics ->
                    fullWidthItem("artist-info") {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ArtistInfoBlock(api, model, DetailMetrics(false, 160.dp))
                        }
                    }
                    items(albums, key = { it.id }) { album ->
                        AlbumCard(
                            api, album, metrics.cardWidth, onClick = { onOpen(album) },
                            localArtPath = if (isDownloaded(album)) downloads.coverPath(album) else null,
                            isDownloaded = isDownloaded(album), showsYear = true,
                        )
                    }
                    info?.let { about -> fullWidthItem("artist-about") { ArtistAbout(model.artist.name, about, Modifier.padding(top = 16.dp)) } }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = margin), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ArtistInfoBlock(api, model, DetailMetrics(false, 160.dp))
                    albumGrid()
                    info?.let { ArtistAbout(model.artist.name, it) }
                }
            }
        }
    }
}

/** The round picture, name and album count. */
@Composable
private fun ArtistInfoBlock(api: JellyfinApi, model: ArtistModel, m: DetailMetrics) {
    AlbumArt(api, model.artist.id, size = m.artSize, cornerRadius = m.artSize / 2, maxWidth = 600)
    DetailTitle(m, model.artist.name, meta = model.albums.size.takeIf { it > 0 }?.let { if (it == 1) "1 album" else "$it albums" })
}
