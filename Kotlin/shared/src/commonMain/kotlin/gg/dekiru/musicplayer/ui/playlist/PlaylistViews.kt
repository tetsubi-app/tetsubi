package gg.dekiru.musicplayer.ui.playlist

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.ListRow
import gg.dekiru.musicplayer.ui.components.MediaCard
import gg.dekiru.musicplayer.ui.components.WithContextMenu
import gg.dekiru.musicplayer.ui.components.artPixels
import gg.dekiru.musicplayer.ui.components.menuClickable
import gg.dekiru.musicplayer.ui.components.playlistQueueActions
import gg.dekiru.musicplayer.ui.layout.CardGrid
import gg.dekiru.musicplayer.ui.layout.CardShelf
import gg.dekiru.musicplayer.ui.layout.ListGrid

// Playlists as the Library's grid and list and Home's shelf show them.
// A playlist without its own picture gets its first song's album art
// (the placeholder until that's known, offline, or when it has no songs).

/** The playlist's picture; asks the model for the first song's art when the playlist has none of its own. */
@Composable
private fun PlaylistArt(api: JellyfinApi, model: PlaylistsModel, playlist: JellyfinPlaylist, size: Dp, cornerRadius: Dp, maxWidth: Int) {
    LaunchedEffect(playlist.id, playlist.hasArt, playlist.songCount, model.isOffline) { model.lookUpArt(playlist) }
    AlbumArt(api, model.artItemId(playlist), size = size, cornerRadius = cornerRadius, maxWidth = maxWidth, localPath = model.artPath(playlist))
}

/** A playlist card [width] wide, as an album's: cover, name, "N songs · 1 h 12 min". Long press or right-click: Play Next, Add to Queue. */
@Composable
private fun PlaylistCard(api: JellyfinApi, model: PlaylistsModel, playlist: JellyfinPlaylist, width: Dp, onOpen: () -> Unit) {
    val pixels = artPixels(width)
    WithContextMenu(playlistQueueActions(playlist)) { openMenu ->
        MediaCard(width, playlist.displayName, PlaylistsModel.summary(playlist), modifier = Modifier.menuClickable(onOpen, openMenu)) { corner ->
            PlaylistArt(api, model, playlist, size = width, cornerRadius = corner, maxWidth = pixels)
        }
    }
}

/** Playlist cards in the app's one grid. */
@Composable
fun PlaylistGrid(api: JellyfinApi, model: PlaylistsModel, playlists: List<JellyfinPlaylist>, state: LazyGridState, onOpen: (JellyfinPlaylist) -> Unit) {
    CardGrid(state = state) { metrics ->
        items(playlists, key = { it.id }) { playlist -> PlaylistCard(api, model, playlist, metrics.cardWidth) { onOpen(playlist) } }
    }
}

/** Rows ([ListRow]: small cover, name, "N songs · 1 h 12 min"), in two columns on a wide pane. */
@Composable
fun PlaylistList(api: JellyfinApi, model: PlaylistsModel, playlists: List<JellyfinPlaylist>, state: LazyGridState, onOpen: (JellyfinPlaylist) -> Unit) {
    ListGrid(state = state) {
        items(playlists, key = { it.id }) { playlist -> PlaylistListRow(api, model, playlist, onOpen = { onOpen(playlist) }) }
    }
}

/**
 * One playlist row. Search shows its playlists with it too; [horizontalPadding] is the side room
 * (the page margin by default; 0 inside a grid that already has it).
 */
@Composable
fun PlaylistListRow(
    api: JellyfinApi, model: PlaylistsModel, playlist: JellyfinPlaylist, modifier: Modifier = Modifier,
    horizontalPadding: Dp = gg.dekiru.musicplayer.ui.layout.LocalWindowClass.current.pageMargin, onOpen: () -> Unit,
) {
    // Long press or right-click: Play Next, Add to Queue.
    WithContextMenu(playlistQueueActions(playlist)) { openMenu ->
        ListRow(
            title = playlist.displayName, subtitle = PlaylistsModel.summary(playlist),
            art = { PlaylistArt(api, model, playlist, size = 40.dp, cornerRadius = Radii.thumb, maxWidth = 100) },
            horizontalPadding = horizontalPadding,
            modifier = modifier.menuClickable(onOpen, openMenu),
        )
    }
}

/** Home's shelf: the playlists as cards, the albums' size. */
@Composable
fun PlaylistShelf(api: JellyfinApi, model: PlaylistsModel, playlists: List<JellyfinPlaylist>, onOpen: (JellyfinPlaylist) -> Unit, modifier: Modifier = Modifier) {
    CardShelf(playlists, key = { it.id }, modifier = modifier) { playlist, width -> PlaylistCard(api, model, playlist, width) { onOpen(playlist) } }
}
