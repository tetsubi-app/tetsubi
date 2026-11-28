package gg.dekiru.musicplayer.ui.library

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.AlbumCard
import gg.dekiru.musicplayer.ui.components.ArtistCard
import gg.dekiru.musicplayer.ui.components.CheckCircleIcon
import gg.dekiru.musicplayer.ui.components.ListRow
import gg.dekiru.musicplayer.ui.components.WithContextMenu
import gg.dekiru.musicplayer.ui.components.albumQueueActions
import gg.dekiru.musicplayer.ui.components.menuClickable
import gg.dekiru.musicplayer.ui.layout.CardGrid
import gg.dekiru.musicplayer.ui.layout.ListGrid
import gg.dekiru.musicplayer.ui.layout.fullWidthItem

// The album and artist grids and lists shared by the Library's tabs, artist pages and the full lists.

/**
 * Album cards in the app's one grid ([CardGrid]: columns from the card size setting and the window).
 * [onAppear] runs as each album comes into view. [footer]: full width after the last row, scrolling
 * with the grid. [showsYear]: each album's year in place of the artist (an artist's page).
 * [endInset]: room for the A-Z strip.
 */
@Composable
fun LibraryAlbumGrid(
    api: JellyfinApi,
    albums: List<JellyfinAlbum>,
    state: LazyGridState,
    onOpen: (JellyfinAlbum) -> Unit,
    isDownloaded: (JellyfinAlbum) -> Boolean,
    localArt: (JellyfinAlbum) -> String? = { null },
    endInset: Dp = 0.dp,
    onAppear: (JellyfinAlbum) -> Unit = {},
    footer: (@Composable () -> Unit)? = null,
    showsYear: Boolean = false,
    titleLines: Int = 1,
) {
    CardGrid(state = state, endInset = endInset) { metrics ->
        items(albums, key = { it.id }) { album ->
            LaunchedEffect(album.id, albums.size) { onAppear(album) }
            AlbumCard(
                api, album, metrics.cardWidth, onClick = { onOpen(album) }, localArtPath = localArt(album),
                isDownloaded = isDownloaded(album), showsYear = showsYear, titleLines = titleLines,
            )
        }
        if (footer != null) fullWidthItem("grid-footer", footer)
    }
}

/** Round artist pictures with the name underneath, in the same grid. */
@Composable
internal fun LibraryArtistGrid(
    api: JellyfinApi,
    artists: List<JellyfinArtist>,
    state: LazyGridState,
    onOpen: (JellyfinArtist) -> Unit,
    endInset: Dp = 0.dp,
) {
    CardGrid(state = state, endInset = endInset) { metrics ->
        items(artists, key = { it.id }) { artist -> ArtistCard(api, artist, metrics.cardWidth) { onOpen(artist) } }
    }
}

/** Album rows ([ListRow]: small cover, title, artist, year, the tick), in two columns on a wide pane. */
@Composable
fun LibraryAlbumList(
    api: JellyfinApi,
    albums: List<JellyfinAlbum>,
    state: LazyGridState,
    onOpen: (JellyfinAlbum) -> Unit,
    isDownloaded: (JellyfinAlbum) -> Boolean,
    localArt: (JellyfinAlbum) -> String? = { null },
    endInset: Dp = 0.dp,
    onAppear: (JellyfinAlbum) -> Unit = {},
) {
    ListGrid(state = state, endInset = endInset) {
        items(albums, key = { it.id }) { album ->
            LaunchedEffect(album.id, albums.size) { onAppear(album) }
            // Long press or right-click: Play Next, Add to Queue.
            WithContextMenu(albumQueueActions(album)) { openMenu ->
                ListRow(
                    title = album.displayName, subtitle = album.artistName,
                    art = { AlbumArt(api, album.id, size = 40.dp, cornerRadius = Radii.thumb, maxWidth = 100, localPath = localArt(album)) },
                    trailingMeta = album.productionYear?.toString(),
                    trailing = if (isDownloaded(album)) ({ DownloadedTick(20.dp) }) else null,
                    modifier = Modifier.menuClickable({ onOpen(album) }, openMenu),
                )
            }
        }
    }
}

/** Artist rows: a round picture and the name. */
@Composable
internal fun LibraryArtistList(
    api: JellyfinApi,
    artists: List<JellyfinArtist>,
    state: LazyGridState,
    onOpen: (JellyfinArtist) -> Unit,
    endInset: Dp = 0.dp,
) {
    ListGrid(state = state, endInset = endInset) {
        items(artists, key = { it.id }) { artist ->
            ListRow(
                title = artist.name, artCircle = true,
                art = { AlbumArt(api, artist.id, size = 40.dp, cornerRadius = 20.dp, maxWidth = 100) },
                onClick = { onOpen(artist) },
            )
        }
    }
}

@Composable
internal fun DownloadedTick(size: Dp, modifier: Modifier = Modifier) {
    CheckCircleIcon(size, TetsubiColors.green, modifier.semantics { contentDescription = "Downloaded" })
}
