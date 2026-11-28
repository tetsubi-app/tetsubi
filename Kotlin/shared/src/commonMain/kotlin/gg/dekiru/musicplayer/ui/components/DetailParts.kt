package gg.dekiru.musicplayer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.data.artist.ArtistInfo
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.layout.LocalWindowClass
import gg.dekiru.musicplayer.ui.layout.WindowClass
import gg.dekiru.musicplayer.ui.layout.rememberBottomInset
import gg.dekiru.musicplayer.ui.tetsubiType

// The detail-page pattern shared by Album, Playlist and Artist (docs/redesign.md section 5).

/** The info pane's width on a two-pane page. */
val InfoPaneWidth = 320.dp

/** A Medium window gets two panes once the page itself is this wide. */
private val TwoPaneFromWidth = 900.dp

/** What a detail page's info block needs to know: stacked or two-pane, and how big the art is. */
class DetailMetrics(val twoPane: Boolean, val artSize: Dp) {
    val horizontal: Alignment.Horizontal get() = if (twoPane) Alignment.Start else Alignment.CenterHorizontally
    val textAlign: TextAlign get() = if (twoPane) TextAlign.Start else TextAlign.Center
}

/**
 * A detail page. Stacked (Compact, and Medium below 900): [info] (art, title, actions), then the list
 * ([list], the page's own rows and shelves), then [after] (About), all in one scrolling column. Two
 * panes (Expanded, and Medium from 900): the info pane (320, scrolls on its own, [after] under it) on
 * the left and the list on the right. The list's rows draw their own page margin.
 */
@Composable
fun DetailPage(
    info: @Composable ColumnScope.(DetailMetrics) -> Unit,
    modifier: Modifier = Modifier,
    listModifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    after: (@Composable ColumnScope.() -> Unit)? = null,
    list: LazyListScope.(DetailMetrics) -> Unit,
) {
    val windowClass = LocalWindowClass.current
    val margin = windowClass.pageMargin
    val bottom = rememberBottomInset()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val twoPane = windowClass == WindowClass.Expanded || (windowClass == WindowClass.Medium && maxWidth >= TwoPaneFromWidth)
        if (twoPane) {
            val metrics = DetailMetrics(true, InfoPaneWidth)
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(InfoPaneWidth + margin).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(start = margin, top = 8.dp, bottom = bottom + 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    info(metrics)
                    after?.invoke(this)
                }
                LazyColumn(
                    state = state,
                    contentPadding = PaddingValues(top = 8.dp, bottom = bottom + 32.dp),
                    modifier = listModifier.weight(1f).fillMaxHeight(),
                ) { list(metrics) }
            }
        } else {
            val art = minOf(maxWidth - margin * 2, if (windowClass == WindowClass.Medium) 320.dp else 260.dp)
            val metrics = DetailMetrics(false, art)
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(bottom = bottom + 32.dp),
                modifier = listModifier.fillMaxSize(),
            ) {
                item(key = "detail-info") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = margin), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = metrics.horizontal) {
                        info(metrics)
                    }
                }
                list(metrics)
                if (after != null) {
                    item(key = "detail-after") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = margin, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { after() }
                    }
                }
            }
        }
    }
}

/** The title block under the art: [title] (28 bold), an optional [artist] line, and [meta] (secondary). */
@Composable
fun DetailTitle(
    metrics: DetailMetrics,
    title: String,
    meta: String? = null,
    artist: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = metrics.horizontal, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title, style = tetsubiType.pageTitle, color = TetsubiColors.textPrimary, textAlign = metrics.textAlign,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
        )
        artist?.invoke()
        if (meta != null) Text(meta, style = tetsubiType.body, color = TetsubiColors.textSecondary, textAlign = metrics.textAlign, modifier = Modifier.fillMaxWidth())
    }
}

/** An artist's name under a title: blue and tappable when it opens the artist, else plain secondary text. */
@Composable
fun ArtistLine(name: String, metrics: DetailMetrics, onClick: (() -> Unit)?) {
    Text(
        name, style = tetsubiType.nowPlayingArtist, color = if (onClick != null) TetsubiColors.blue else TetsubiColors.textSecondary,
        textAlign = metrics.textAlign, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().artistLink(name, onClick),
    )
}

/** An outlined pill, 40 high: a leading [icon] and a [title] (Download, Shuffle). */
@Composable
fun PillButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = TetsubiColors.textPrimary,
    description: String = title,
    icon: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Radii.FULL_PERCENT)
    val tint = if (enabled) color else color.copy(alpha = 0.5f)
    Row(
        modifier.height(40.dp).clip(shape).border(BorderStroke(1.dp, tint.copy(alpha = if (enabled) 0.4f else 0.25f)), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.invoke()
        Text(title, style = tetsubiType.rowTitle, color = tint, maxLines = 1, softWrap = false)
    }
}

/**
 * A round icon button: surface fill, or a tint of [accent] (blue by default) with an [accent] icon when [on]. [icon] gets the colour
 * to draw with. 44 for Now Playing's toggles; 40 beside the Play button.
 */
@Composable
fun RoundIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    on: Boolean = false,
    enabled: Boolean = true,
    /** The on-state colour (Now Playing passes the art's accent). */
    accent: Color = TetsubiColors.blue,
    icon: @Composable (Color) -> Unit,
) {
    val shape = RoundedCornerShape(Radii.FULL_PERCENT)
    val tint = if (on) accent else TetsubiColors.textPrimary
    Box(
        modifier.size(size).clip(shape).background(if (on) accent.copy(alpha = 0.2f) else TetsubiColors.surface, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { icon(if (enabled) tint else tint.copy(alpha = 0.4f)) }
}

/**
 * "About the artist": MusicBrainz facts and genres, the Wikipedia introduction, and links to both
 * (the attribution is required). Side room comes from the container.
 */
@Composable
fun ArtistAbout(artistName: String, info: ArtistInfo, modifier: Modifier = Modifier, title: String = "About $artistName") {
    var showFullBio by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title, horizontalPadding = 0.dp)
        info.facts?.let { Text(it, style = tetsubiType.body, color = TetsubiColors.textSecondary) }
        if (info.genres.isNotEmpty()) Text(info.genres.joinToString(" · "), style = tetsubiType.caption, color = TetsubiColors.blue)
        info.summary?.let { summary ->
            Text(summary, style = tetsubiType.body, color = TetsubiColors.textPrimary, maxLines = if (showFullBio) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
            if (summary.length > 300) {
                Text(
                    if (showFullBio) "Less" else "More", style = tetsubiType.control, color = TetsubiColors.blue,
                    modifier = Modifier.clickable(role = Role.Button) { showFullBio = !showFullBio },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AboutLink("MusicBrainz") { uri.openUri(info.musicBrainzUrl) }
            info.wikipediaUrl?.let { wiki ->
                Text("·", style = tetsubiType.caption, color = TetsubiColors.textTertiary)
                AboutLink("Wikipedia") { uri.openUri(wiki) }
                Text("(CC BY-SA)", style = tetsubiType.caption, color = TetsubiColors.textTertiary)
            }
        }
    }
}

@Composable
private fun AboutLink(text: String, onClick: () -> Unit) {
    Text(text, style = tetsubiType.caption, color = TetsubiColors.textSecondary, modifier = Modifier.clickable(role = Role.Button, onClick = onClick))
}

/** A page's loading, empty or error line inside a list. */
@Composable
fun ListMessage(text: String, modifier: Modifier = Modifier, color: Color = TetsubiColors.textSecondary) {
    Text(
        text, style = tetsubiType.body, color = color,
        modifier = modifier.fillMaxWidth().padding(horizontal = LocalWindowClass.current.pageMargin, vertical = 8.dp),
    )
}
