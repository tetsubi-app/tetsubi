package gg.dekiru.musicplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.ui.components.LocalPlayPrompt
import gg.dekiru.musicplayer.ui.components.PillButton
import gg.dekiru.musicplayer.ui.components.PlayIcon
import androidx.compose.ui.graphics.Color
import gg.dekiru.musicplayer.ui.components.PlayChoice
import gg.dekiru.musicplayer.ui.components.PlayPrompt
import gg.dekiru.musicplayer.ui.components.PrimaryButton
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
import gg.dekiru.musicplayer.ui.TetsubiColors
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
 * Play all (oldest release first, in order) and Shuffle (hidden in Maniac Mode) queue up to 500 tracks of
 * the albums shown; offline that is the downloaded albums.
 */
@Composable
fun ArtistScreen(
    api: JellyfinApi,
    model: ArtistModel,
    onOpen: (JellyfinAlbum) -> Unit,
    onBack: () -> Unit,
    network: StateFlow<NetworkState> = MutableStateFlow(NetworkState()),
    downloads: DownloadManager? = null,
    player: PlayerController? = null,
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
    val prompt = LocalPlayPrompt.current
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
                        ArtistInfoBlock(api, model, metrics, player, albums, prompt)
                        info?.let { ArtistAbout(model.artist.name, it) }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { albumGrid() }
                }
            } else if (albums.isNotEmpty()) {
                // Stacked: the info block is the grid's first row, About its last.
                CardGrid(state = grid) { metrics ->
                    fullWidthItem("artist-info") {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ArtistInfoBlock(api, model, DetailMetrics(false, 160.dp), player, albums, prompt)
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
                    ArtistInfoBlock(api, model, DetailMetrics(false, 160.dp), player, albums, prompt)
                    albumGrid()
                    info?.let { ArtistAbout(model.artist.name, it) }
                }
            }
        }
    }
}

/** The round picture, name and album count. */
@Composable
private fun ArtistInfoBlock(
    api: JellyfinApi, model: ArtistModel, m: DetailMetrics, player: PlayerController?, albums: List<JellyfinAlbum>, prompt: PlayPrompt,
) {
    // Smaller than an album's cover (240 two-pane, 200 stacked) with the lighter placeholder, as on iOS and Mac.
    val art = minOf(m.artSize, if (m.twoPane) 240.dp else 200.dp)
    AlbumArt(api, model.artist.id, size = art, cornerRadius = art / 2, maxWidth = 600, placeholderColor = TetsubiColors.surface)
    DetailTitle(m, model.artist.name, meta = model.albums.size.takeIf { it > 0 }?.let { if (it == 1) "1 album" else "$it albums" })
    ArtistActions(model, m, player, albums, prompt)
}

/** Play all and Shuffle (not in Maniac Mode), as on an album; off until the albums are loaded. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistActions(model: ArtistModel, m: DetailMetrics, player: PlayerController?, albums: List<JellyfinAlbum>, prompt: PlayPrompt) {
    val canPlay = player?.isSupported == true && albums.isNotEmpty()
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (m.twoPane) Alignment.Start else Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PrimaryButton("Play all", onClick = { player?.let { prompt.play(it, artistChoice(model, it, albums)) } }, enabled = canPlay, icon = { PlayIcon(16.dp, Color.White) })
        if (player?.maniacMode != true) {
            PillButton(
                "Shuffle", onClick = { player?.let { prompt.play(it, artistChoice(model, it, albums, shuffled = true)) } }, enabled = canPlay,
                icon = { ShuffleIcon(18.dp, if (canPlay) TetsubiColors.textPrimary else TetsubiColors.textTertiary) },
            )
        }
    }
}

/** Play the artist's albums (or shuffled), or, from the prompt, queue them instead. */
private fun artistChoice(model: ArtistModel, player: PlayerController, albums: List<JellyfinAlbum>, shuffled: Boolean = false) = PlayChoice(
    title = model.artist.name,
    replace = { player.play(model.artist, albums, shuffled = shuffled) },
    playNext = { player.playNext(model.artist, albums) },
    addToQueue = { player.addToQueue(model.artist, albums) },
)
