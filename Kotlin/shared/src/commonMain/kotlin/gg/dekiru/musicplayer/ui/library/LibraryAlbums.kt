package gg.dekiru.musicplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.CheckCircleIcon

// The album grid and list shared by the Library's tabs and artist pages.

/**
 * Square covers in columns (as iOS `AlbumGridView`): fixed 120 dp covers,
 * three across on a phone, more on the Pad, a green tick on downloaded
 * albums. [onAppear] runs as each album comes into view.
 */
@Composable
fun LibraryAlbumGrid(
    api: JellyfinApi,
    albums: List<JellyfinAlbum>,
    state: LazyGridState,
    onOpen: (JellyfinAlbum) -> Unit,
    isDownloaded: (JellyfinAlbum) -> Boolean,
    localArt: (JellyfinAlbum) -> String? = { null },
    trailingEdge: Boolean = true,
    onAppear: (JellyfinAlbum) -> Unit = {},
) {
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The A–Z strip already leaves room on the right: no trailing padding then (so three fit on a phone).
        val trailing = if (trailingEdge) LibraryModel.GRID_EDGE else 0f
        val layout = LibraryModel.gridLayout(maxWidth.value - LibraryModel.GRID_EDGE - trailing)
        LazyVerticalGrid(
            columns = GridCells.Fixed(layout.columns),
            state = state,
            contentPadding = PaddingValues(start = LibraryModel.GRID_EDGE.dp, end = trailing.dp, top = 16.dp, bottom = 16.dp + bottom),
            horizontalArrangement = Arrangement.spacedBy(layout.spacing.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(albums, key = { it.id }) { album ->
                LaunchedEffect(album.id, albums.size) { onAppear(album) }
                GridCell(api, album, isDownloaded(album), localArt(album)) { onOpen(album) }
            }
        }
    }
}

@Composable
private fun GridCell(api: JellyfinApi, album: JellyfinAlbum, downloaded: Boolean, localArt: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            AlbumArt(api, album.id, size = LibraryModel.GRID_ART.dp, cornerRadius = 8.dp, maxWidth = 300, localPath = localArt)
            if (downloaded) DownloadedTick(16.dp, Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
        Text(
            album.displayName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            album.artistName, color = TetsubiColors.albumArtistText, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Rows: small cover, title, artist, year, the tick at the right (as the iOS Library's list). */
@Composable
fun LibraryAlbumList(
    api: JellyfinApi,
    albums: List<JellyfinAlbum>,
    state: LazyListState,
    onOpen: (JellyfinAlbum) -> Unit,
    isDownloaded: (JellyfinAlbum) -> Boolean,
    localArt: (JellyfinAlbum) -> String? = { null },
    onAppear: (JellyfinAlbum) -> Unit = {},
) {
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 16.dp + bottom), modifier = Modifier.fillMaxSize()) {
        items(albums, key = { it.id }) { album ->
            LaunchedEffect(album.id, albums.size) { onAppear(album) }
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(album) }.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AlbumArt(api, album.id, size = 60.dp, cornerRadius = 4.dp, maxWidth = 100, localPath = localArt(album))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(album.displayName, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(album.artistName, color = TetsubiColors.albumArtistText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    album.productionYear?.let { Text(it.toString(), color = TetsubiColors.albumYearText, fontSize = 11.sp) }
                }
                if (isDownloaded(album)) DownloadedTick(20.dp)
            }
            Box(Modifier.padding(start = 88.dp).fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
        }
    }
}

@Composable
internal fun DownloadedTick(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    CheckCircleIcon(size, TetsubiColors.green, modifier.semantics { contentDescription = "Downloaded" })
}

/** The small spinner shown while something loads. */
@Composable
internal fun LibrarySpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        CircularProgressIndicator(Modifier.padding(32.dp).size(24.dp), color = TetsubiColors.albumArtistText, strokeWidth = 2.dp)
    }
}

/** A short grey note (empty lists). */
@Composable
internal fun LibraryNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(text, color = TetsubiColors.albumYearText, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
    }
}
