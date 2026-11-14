package gg.dekiru.musicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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

private val cardWidth = 140.dp

/**
 * An album card (as `AlbumCardView` on iOS): 140 dp art, title (2 lines),
 * artist, year; an optional badge ("30 years ago") over the art's corner,
 * and a green tick at the bottom right when the album is downloaded.
 */
@Composable
fun AlbumCard(
    api: JellyfinApi,
    album: JellyfinAlbum,
    onClick: () -> Unit,
    badge: String? = null,
    titleLines: Int = 2,
    /** A downloaded cover, shown instead of the server's. */
    localArtPath: String? = null,
    isDownloaded: Boolean = false,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(6.dp)
            .width(cardWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            AlbumArt(api, album.id, size = cardWidth, cornerRadius = 8.dp, maxWidth = 300, localPath = localArtPath)
            if (!badge.isNullOrEmpty()) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(TetsubiColors.blue.copy(alpha = 0.7f), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            if (isDownloaded) {
                CheckCircleIcon(
                    20.dp, TetsubiColors.green,
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).semantics { contentDescription = "Downloaded" },
                )
            }
        }
        CardText(album.displayName, Color.White, 12.sp, bold = true, lines = titleLines)
        CardText(album.artistName, TetsubiColors.albumArtistText, 11.sp)
        album.productionYear?.let { CardText(it.toString(), TetsubiColors.albumYearText, 11.sp) }
    }
}

/** Same size as a card, shown while a row loads so the screen doesn't jump. */
@Composable
fun AlbumCardPlaceholder() {
    Column(
        Modifier.padding(6.dp).width(cardWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(cardWidth)) { LoadingOutline(8.dp) }
        CardText("Loading…", Color.White, 12.sp, bold = true)
        CardText("Sit tight…", TetsubiColors.albumArtistText, 11.sp)
        CardText("Soon…", TetsubiColors.albumYearText, 11.sp)
    }
}

@Composable
private fun CardText(text: String, color: Color, size: androidx.compose.ui.unit.TextUnit, bold: Boolean = false, lines: Int = 1) {
    Text(
        text,
        color = color,
        fontSize = size,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(cardWidth),
    )
}

/** A horizontally scrolling row of album cards. */
@Composable
fun AlbumRow(
    api: JellyfinApi,
    albums: List<JellyfinAlbum>,
    onOpen: (JellyfinAlbum) -> Unit,
    badge: (JellyfinAlbum) -> String? = { null },
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp),
    localArt: (JellyfinAlbum) -> String? = { null },
    isDownloaded: (JellyfinAlbum) -> Boolean = { false },
) {
    LazyRow(contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Each album once: a repeated ID would crash the row (keys must be unique).
        items(albums.distinctBy { it.id }, key = { it.id }) { album ->
            AlbumCard(
                api, album, onClick = { onOpen(album) }, badge = badge(album), localArtPath = localArt(album),
                isDownloaded = isDownloaded(album),
            )
        }
    }
}

/** Four placeholder cards, laid out like a loaded row. */
@Composable
fun AlbumRowPlaceholder() {
    Row(
        Modifier.padding(horizontal = 10.dp).clearAndSetSemantics { contentDescription = "Loading" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(4) { AlbumCardPlaceholder() }
    }
}
