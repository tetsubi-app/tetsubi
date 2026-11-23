package gg.dekiru.musicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.Dp
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.library.CardGrid
import gg.dekiru.musicplayer.ui.library.LibraryModel

private val cardWidth = LibraryModel.HOME_CARD.dp

/**
 * An album card (as `AlbumCardView` on iOS): 140 dp art, title (2 lines),
 * artist, year; an optional badge ("30 years ago") over the art's corner,
 * and a green tick at the bottom right when the album is downloaded.
 * A long press or right-click offers Play Next and Add to Queue.
 * With [size] (Home on wide layouts, [LibraryModel.homeRow]) the art is
 * that wide with no side margin (cards exactly the gap apart, as iOS) and
 * the text, gaps, badge, tick and corners scale with it.
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
    size: CardGrid? = null,
) {
    val width = size?.cardWidth?.dp ?: cardWidth
    val t = size?.textScale ?: 1f
    // Long press or right-click: Play Next, Add to Queue.
    WithContextMenu(albumQueueActions(album)) { openMenu ->
    Column(
        Modifier
            .clip(RoundedCornerShape((8 * t).dp))
            .menuClickable(onClick, openMenu)
            .then(homeCardPadding(size))
            .width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((4 * t).dp),
    ) {
        Box {
            AlbumArt(api, album.id, size = width, cornerRadius = (8 * t).dp, maxWidth = size?.imageSize ?: 300, localPath = localArtPath)
            if (!badge.isNullOrEmpty()) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = (11 * t).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding((4 * t).dp)
                        .background(TetsubiColors.blue.copy(alpha = 0.7f), RoundedCornerShape(50))
                        .padding(horizontal = (6 * t).dp, vertical = (3 * t).dp),
                )
            }
            if (isDownloaded) {
                CheckCircleIcon(
                    (20 * t).dp, TetsubiColors.green,
                    Modifier.align(Alignment.BottomEnd).padding((6 * t).dp).semantics { contentDescription = "Downloaded" },
                )
            }
        }
        CardText(album.displayName, Color.White, (12 * t).sp, width, bold = true, lines = titleLines)
        CardText(album.artistName, TetsubiColors.albumArtistText, (11 * t).sp, width)
        album.productionYear?.let { CardText(it.toString(), TetsubiColors.albumYearText, (11 * t).sp, width) }
    }
    }
}

/** Same size as a card ([size] as [AlbumCard]'s), shown while a row loads so the screen doesn't jump. */
@Composable
fun AlbumCardPlaceholder(size: CardGrid? = null) {
    val width = size?.cardWidth?.dp ?: cardWidth
    val t = size?.textScale ?: 1f
    Column(
        Modifier.then(homeCardPadding(size)).width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((4 * t).dp),
    ) {
        Box(Modifier.size(width)) { LoadingOutline((8 * t).dp) }
        CardText("Loading…", Color.White, (12 * t).sp, width, bold = true)
        CardText("Sit tight…", TetsubiColors.albumArtistText, (11 * t).sp, width)
        CardText("Soon…", TetsubiColors.albumYearText, (11 * t).sp, width)
    }
}

/** A Home card's margin: all round on phones; wide ([size]), top and bottom only, scaled with the text. */
internal fun homeCardPadding(size: CardGrid?): Modifier =
    if (size == null) Modifier.padding(LibraryModel.HOME_CARD_PADDING.dp)
    else Modifier.padding(vertical = (LibraryModel.HOME_CARD_PADDING * size.textScale).dp)

/** Home rows' padding: phones 10 dp each side; wide ([size]) 16 dp each side, which [LibraryModel.homeRow] counts on. */
fun homeRowPadding(size: CardGrid?): PaddingValues =
    PaddingValues(horizontal = (if (size == null) LibraryModel.HOME_EDGE else LibraryModel.HOME_WIDE_LEADING).dp)

@Composable
private fun CardText(text: String, color: Color, size: androidx.compose.ui.unit.TextUnit, width: Dp, bold: Boolean = false, lines: Int = 1) {
    Text(
        text,
        color = color,
        fontSize = size,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(width),
    )
}

/** A horizontally scrolling row of album cards; [size] as [AlbumCard]'s (and its gap). */
@Composable
fun AlbumRow(
    api: JellyfinApi,
    albums: List<JellyfinAlbum>,
    onOpen: (JellyfinAlbum) -> Unit,
    badge: (JellyfinAlbum) -> String? = { null },
    /** Default: Home's ([homeRowPadding]). */
    contentPadding: PaddingValues? = null,
    localArt: (JellyfinAlbum) -> String? = { null },
    isDownloaded: (JellyfinAlbum) -> Boolean = { false },
    size: CardGrid? = null,
) {
    LazyRow(contentPadding = contentPadding ?: homeRowPadding(size), horizontalArrangement = Arrangement.spacedBy((size?.spacing ?: 12f).dp)) {
        // Each album once: a repeated ID would crash the row (keys must be unique).
        items(albums.distinctBy { it.id }, key = { it.id }) { album ->
            AlbumCard(
                api, album, onClick = { onOpen(album) }, badge = badge(album), localArtPath = localArt(album),
                isDownloaded = isDownloaded(album), size = size,
            )
        }
    }
}

/** Four placeholder cards (wide: the whole cards and the peeking one, as iOS), laid out like a loaded row. */
@Composable
fun AlbumRowPlaceholder(size: CardGrid? = null) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState(), enabled = false) // lets the peeking one run off the edge
            .padding(homeRowPadding(size))
            .clearAndSetSemantics { contentDescription = "Loading" },
        horizontalArrangement = Arrangement.spacedBy((size?.spacing ?: 12f).dp),
    ) {
        repeat(size?.let { it.columns + 1 } ?: 4) { AlbumCardPlaceholder(size) }
    }
}
