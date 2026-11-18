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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.CheckCircleIcon

// The album grid and list shared by the Library's tabs and artist pages.

/**
 * Square covers in columns (as iOS `AlbumGridView`): fixed 120 dp covers,
 * three across on a phone, a green tick on downloaded albums. Wide (with
 * [zoom]): the shared cards-per-row, each cover filling its column, the
 * text growing with it. [onAppear] runs as each album comes into view.
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
    zoom: GridZoom? = null,
) {
    CardGridView(albums, { it.id }, state, trailingEdge, zoom) { album, grid ->
        LaunchedEffect(album.id, albums.size) { onAppear(album) }
        GridCell(api, album, grid, isDownloaded(album), localArt(album)) { onOpen(album) }
    }
}

/** Round artist pictures with the name underneath, the same size and columns as album cards (as the iOS Artists grid). */
@Composable
internal fun LibraryArtistGrid(
    api: JellyfinApi,
    artists: List<JellyfinArtist>,
    state: LazyGridState,
    onOpen: (JellyfinArtist) -> Unit,
    zoom: GridZoom? = null,
) {
    CardGridView(artists, { it.id }, state, trailingEdge = false, zoom = zoom) { artist, grid ->
        val t = grid.textScale
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onOpen(artist) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((4 * t).dp),
        ) {
            AlbumArt(api, artist.id, size = grid.cardWidth.dp, cornerRadius = (grid.cardWidth / 2).dp, maxWidth = grid.imageSize)
            Text(
                artist.name, color = Color.White, fontSize = (12 * t).sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The columns shared by album and artist grids ([LibraryModel.cardGrid]).
 * Wide with [zoom]: tells it how many columns show, for the zoom buttons.
 */
@Composable
private fun <T> CardGridView(
    items: List<T>,
    key: (T) -> Any,
    state: LazyGridState,
    trailingEdge: Boolean,
    zoom: GridZoom?,
    cell: @Composable (T, CardGrid) -> Unit,
) {
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    val wide = zoom != null && isWideLayout()
    val density = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The A–Z strip already leaves room on the right: no trailing padding then (so three fit on a phone).
        val trailing = if (trailingEdge) LibraryModel.GRID_EDGE else 0f
        val grid = LibraryModel.cardGrid(maxWidth.value - LibraryModel.GRID_EDGE - trailing, wide, zoom?.columns, density)
        if (wide) SideEffect { zoom.shown = grid.columns }
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns),
            state = state,
            contentPadding = PaddingValues(start = LibraryModel.GRID_EDGE.dp, end = trailing.dp, top = 16.dp, bottom = 16.dp + bottom),
            horizontalArrangement = Arrangement.spacedBy(grid.spacing.dp),
            verticalArrangement = Arrangement.spacedBy((16 * grid.textScale).dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = key) { item -> cell(item, grid) }
        }
    }
}

/** One album card; on wide layouts the cover fills the column and the text, gaps, tick and corners scale with it. */
@Composable
private fun GridCell(api: JellyfinApi, album: JellyfinAlbum, grid: CardGrid, downloaded: Boolean, localArt: String?, onClick: () -> Unit) {
    val t = grid.textScale
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape((8 * t).dp)).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((4 * t).dp),
    ) {
        Box {
            AlbumArt(api, album.id, size = grid.cardWidth.dp, cornerRadius = (8 * t).dp, maxWidth = grid.imageSize, localPath = localArt)
            if (downloaded) DownloadedTick((16 * t).dp, Modifier.align(Alignment.BottomEnd).padding((4 * t).dp))
        }
        Text(
            album.displayName, color = Color.White, fontSize = (12 * t).sp, fontWeight = FontWeight.Bold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            album.artistName, color = TetsubiColors.albumArtistText, fontSize = (11 * t).sp, maxLines = 1,
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
