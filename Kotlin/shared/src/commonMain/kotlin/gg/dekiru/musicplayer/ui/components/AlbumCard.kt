package gg.dekiru.musicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.layout.CardShelf
import gg.dekiru.musicplayer.ui.library.ArtistModel
import gg.dekiru.musicplayer.ui.library.LibraryModel
import gg.dekiru.musicplayer.ui.tetsubiType

/** Cover pixels to ask the server for, for a card [width] wide on this screen (a few steps, so the image cache keeps hitting). */
@Composable
fun artPixels(width: Dp): Int = LibraryModel.imageSize(width.value * LocalDensity.current.density)

/**
 * The one card (docs/redesign.md section 3): a square [art] (radius 10, or a circle for artists) as
 * wide as the column, then [title] (14 semibold) and [subtitle] (12 secondary). Left aligned, so a
 * grid reads as columns. [overlay] draws over the art (a badge, the downloaded tick). [modifier]
 * goes after the card's own clip, so a click or long press gets the rounded highlight.
 */
@Composable
fun MediaCard(
    width: Dp,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    circle: Boolean = false,
    titleLines: Int = 1,
    /** A third, dimmer line (Downloads: size and length). */
    detail: String? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    art: @Composable (cornerRadius: Dp) -> Unit,
) {
    Column(
        Modifier.width(width).clip(RoundedCornerShape(Radii.card)).then(modifier),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(width)) {
            art(if (circle) width / 2 else Radii.card)
            overlay?.invoke(this)
        }
        Text(title, style = tetsubiType.cardTitle, color = TetsubiColors.textPrimary, maxLines = titleLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        if (!subtitle.isNullOrEmpty()) {
            Text(subtitle, style = tetsubiType.caption, color = TetsubiColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        }
        if (detail != null) {
            Text(detail, style = tetsubiType.caption, color = TetsubiColors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The green tick over a downloaded cover's corner. */
@Composable
fun BoxScope.DownloadedBadge() {
    CheckCircleIcon(
        20.dp, TetsubiColors.green,
        Modifier.align(Alignment.BottomEnd).padding(6.dp).semantics { contentDescription = "Downloaded" },
    )
}

/**
 * An album card [width] wide (the grid's or shelf's column): cover, title, artist (or, with
 * [showsYear], the year, on an artist's page); an optional [badge] ("30 years ago") over the cover's
 * corner and a green tick when it is downloaded. A long press or right-click offers Play Next and
 * Add to Queue.
 */
@Composable
fun AlbumCard(
    api: JellyfinApi,
    album: JellyfinAlbum,
    width: Dp,
    onClick: () -> Unit,
    badge: String? = null,
    /** A downloaded cover, shown instead of the server's. */
    localArtPath: String? = null,
    isDownloaded: Boolean = false,
    showsYear: Boolean = false,
    titleLines: Int = 1,
) {
    val pixels = artPixels(width)
    // Long press or right-click: Play Next, Add to Queue.
    WithContextMenu(albumQueueActions(album)) { openMenu ->
        MediaCard(
            width, album.displayName, if (showsYear) ArtistModel.yearText(album) else album.artistName,
            modifier = Modifier.menuClickable(onClick, openMenu), titleLines = titleLines,
            overlay = {
                if (!badge.isNullOrEmpty()) {
                    Text(
                        badge, color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .background(TetsubiColors.blue.copy(alpha = 0.7f), RoundedCornerShape(Radii.FULL_PERCENT))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                if (isDownloaded) DownloadedBadge()
            },
        ) { corner -> AlbumArt(api, album.id, size = width, cornerRadius = corner, maxWidth = pixels, localPath = localArtPath) }
    }
}

/** An artist card: a circular picture, then the name. */
@Composable
fun ArtistCard(api: JellyfinApi, artist: JellyfinArtist, width: Dp, onClick: () -> Unit) {
    val pixels = artPixels(width)
    MediaCard(width, artist.name, null, modifier = Modifier.menuClickable(onClick, null), circle = true) { corner ->
        AlbumArt(api, artist.id, size = width, cornerRadius = corner, maxWidth = pixels)
    }
}

/** The shape of a card while its row loads: the cover as a pulsing outline, bars for the text, so the screen doesn't jump. */
@Composable
fun CardPlaceholder(width: Dp) {
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(width)) { LoadingOutline(Radii.card) }
        Box(Modifier.padding(top = 4.dp).width(width * 0.7f).height(12.dp).clip(RoundedCornerShape(Radii.thumb)).background(TetsubiColors.surface))
        Box(Modifier.padding(top = 2.dp).width(width * 0.45f).height(10.dp).clip(RoundedCornerShape(Radii.thumb)).background(TetsubiColors.surface))
    }
}

/** Home's shelf of album cards (the grid's own cards in a row, [CardShelf]); [albums] repeated by ID are shown once. */
@Composable
fun AlbumShelf(
    api: JellyfinApi,
    albums: List<JellyfinAlbum>,
    onOpen: (JellyfinAlbum) -> Unit,
    modifier: Modifier = Modifier,
    badge: (JellyfinAlbum) -> String? = { null },
    localArt: (JellyfinAlbum) -> String? = { null },
    isDownloaded: (JellyfinAlbum) -> Boolean = { false },
) {
    // Each album once: a repeated ID would crash the row (keys must be unique).
    CardShelf(albums.distinctBy { it.id }, key = { it.id }, modifier = modifier) { album, width ->
        AlbumCard(
            api, album, width, onClick = { onOpen(album) }, badge = badge(album), localArtPath = localArt(album),
            isDownloaded = isDownloaded(album),
        )
    }
}

/** A loading shelf: a screenful of [CardPlaceholder]s laid out like the loaded row. */
@Composable
fun AlbumShelfPlaceholder(modifier: Modifier = Modifier) {
    CardShelf(List(8) { it }, key = { it }, modifier = modifier.clearAndSetSemantics { contentDescription = "Loading" }) { _, width ->
        CardPlaceholder(width)
    }
}
