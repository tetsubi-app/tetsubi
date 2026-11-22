package gg.dekiru.musicplayer.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.network.NetworkState
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.downloads.collectDownloaded
import gg.dekiru.musicplayer.ui.downloads.coverPath
import gg.dekiru.musicplayer.ui.downloads.rememberIsDownloaded
import gg.dekiru.musicplayer.ui.home.HomeModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One artist's albums (as `ArtistAlbumsView` on iOS), titled with their
 * name, with Back. Offline: their downloaded albums only. Wide layouts:
 * the zoom buttons at the right of the top bar ([zoom], the Library's).
 */
@Composable
fun ArtistScreen(
    api: JellyfinApi,
    model: ArtistModel,
    onOpen: (JellyfinAlbum) -> Unit,
    onBack: () -> Unit,
    network: StateFlow<NetworkState> = MutableStateFlow(NetworkState()),
    downloads: DownloadManager? = null,
    zoom: GridZoom? = null,
) {
    val online = network.collectAsState().value.online
    LaunchedEffect(model, online) { if (online) model.loadIfNeeded() }
    val isDownloaded = rememberIsDownloaded(downloads)
    val downloaded by downloads.collectDownloaded()
    val downloadedAlbums = remember(downloaded) { HomeModel.downloadedRow(downloaded) }
    val albums = ArtistModel.ordered(model.shown(!online, downloadedAlbums, isDownloaded))
    val grid = rememberLazyGridState()

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                    Text("‹ Back", color = TetsubiColors.blue, fontSize = 16.sp)
                }
                Text(
                    model.artist.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (zoom != null && isWideLayout()) GridZoomButtons(zoom, Modifier.padding(end = 8.dp))
            }
            when {
                albums.isNotEmpty() -> LibraryAlbumGrid(
                    api, albums, grid, onOpen, isDownloaded,
                    localArt = { album -> if (isDownloaded(album)) downloads.coverPath(album) else null },
                    zoom = zoom,
                    showsYear = true,
                )
                online && !model.loaded -> LibrarySpinner()
                else -> LibraryNote("No albums found.")
            }
        }
    }
}
