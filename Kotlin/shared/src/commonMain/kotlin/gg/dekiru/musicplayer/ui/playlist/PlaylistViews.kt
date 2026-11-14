package gg.dekiru.musicplayer.ui.playlist

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.library.LibraryModel

// Playlists as the Library's grid and list and Home's row show them.
// A playlist without its own picture gets its first song's album art
// (the placeholder until that's known, offline, or when it has no songs).

/** The playlist's picture; asks the model for the first song's art when the playlist has none of its own. */
@Composable
private fun PlaylistArt(api: JellyfinApi, model: PlaylistsModel, playlist: JellyfinPlaylist, size: Dp, cornerRadius: Dp, maxWidth: Int) {
    LaunchedEffect(playlist.id, playlist.hasArt, playlist.songCount, model.isOffline) { model.lookUpArt(playlist) }
    AlbumArt(api, model.artItemId(playlist), size = size, cornerRadius = cornerRadius, maxWidth = maxWidth, localPath = model.artPath(playlist))
}

/** Square covers in columns, laid out as the Library's album grid. */
@Composable
fun PlaylistGrid(api: JellyfinApi, model: PlaylistsModel, playlists: List<JellyfinPlaylist>, state: LazyGridState, onOpen: (JellyfinPlaylist) -> Unit) {
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = LibraryModel.gridLayout(maxWidth.value - LibraryModel.GRID_EDGE * 2)
        LazyVerticalGrid(
            columns = GridCells.Fixed(layout.columns),
            state = state,
            contentPadding = PaddingValues(start = LibraryModel.GRID_EDGE.dp, end = LibraryModel.GRID_EDGE.dp, top = 16.dp, bottom = 16.dp + bottom),
            horizontalArrangement = Arrangement.spacedBy(layout.spacing.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(playlists, key = { it.id }) { playlist ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onOpen(playlist) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PlaylistArt(api, model, playlist, size = LibraryModel.GRID_ART.dp, cornerRadius = 8.dp, maxWidth = 300)
                    Text(
                        playlist.displayName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        PlaylistsModel.summary(playlist), color = TetsubiColors.albumArtistText, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Rows: small cover, name, "N songs · 1 h 12 min" (as the Library's album list). */
@Composable
fun PlaylistList(api: JellyfinApi, model: PlaylistsModel, playlists: List<JellyfinPlaylist>, state: LazyListState, onOpen: (JellyfinPlaylist) -> Unit) {
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 16.dp + bottom), modifier = Modifier.fillMaxSize()) {
        items(playlists, key = { it.id }) { playlist ->
            PlaylistListRow(api, model, playlist, onOpen = { onOpen(playlist) })
            Box(Modifier.padding(start = 88.dp).fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
        }
    }
}

/** One row of the list. Search shows its playlists with it too, at the size of its album rows (`compact`). */
@Composable
fun PlaylistListRow(api: JellyfinApi, model: PlaylistsModel, playlist: JellyfinPlaylist, compact: Boolean = false, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlaylistArt(api, model, playlist, size = if (compact) 56.dp else 60.dp, cornerRadius = 4.dp, maxWidth = 100)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(playlist.displayName, color = Color.White, fontSize = if (compact) 16.sp else 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(PlaylistsModel.summary(playlist), color = TetsubiColors.albumArtistText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Home's row: cards the size of the album cards. */
@Composable
fun PlaylistRow(api: JellyfinApi, model: PlaylistsModel, playlists: List<JellyfinPlaylist>, onOpen: (JellyfinPlaylist) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(playlists, key = { it.id }) { playlist ->
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onOpen(playlist) }.padding(6.dp).width(140.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PlaylistArt(api, model, playlist, size = 140.dp, cornerRadius = 8.dp, maxWidth = 300)
                Text(
                    playlist.displayName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    PlaylistsModel.summary(playlist), color = TetsubiColors.albumArtistText, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
