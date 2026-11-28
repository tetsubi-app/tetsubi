package gg.dekiru.musicplayer.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max

// The one grid (docs/redesign.md section 3). Pure rules first, so they can be tested.

/** Columns for a content width (dp, inside the page margins): `max(2, floor((w + g) / (min + g)))`. */
fun gridColumns(contentWidth: Float, density: Density, gutter: Float): Int =
    max(2, floor((contentWidth + gutter) / (density.minCard + gutter)).toInt())

/**
 * The width the columns share: the pane less the start margin and the end margin, which is [endInset]
 * (the A-Z strip) where that is wider than the margin, else the margin itself.
 */
fun gridContentWidth(paneWidth: Float, margin: Float, endInset: Float = 0f): Float =
    paneWidth - margin - max(margin, endInset)

/** Width of one card: the columns and their gutters fill the content width exactly. */
fun cardWidth(contentWidth: Float, columns: Int, gutter: Float): Float =
    (contentWidth - (columns - 1) * gutter) / columns

/** Share of a card left showing at the shelf's right edge on Compact. */
const val SHELF_PEEK = 0.35f

/**
 * A shelf's card width. Wider than Compact: exactly the grid's card. Compact: the cards are narrowed
 * a little so [columns] whole cards and [SHELF_PEEK] of the next fit between the left margin and the
 * window's right edge (at the grid's own width the next card would show only a few dp).
 */
fun shelfCardWidth(contentWidth: Float, columns: Int, gutter: Float, pageMargin: Float, peek: Boolean): Float {
    if (!peek) return cardWidth(contentWidth, columns, gutter)
    return (contentWidth + pageMargin - columns * gutter) / (columns + SHELF_PEEK)
}

/** A list view flows into two columns from this pane width (dp, margins included). */
const val LIST_TWO_COLUMNS_FROM = 700f

/** Columns of a list view for a pane [paneWidth] dp wide: 2 from [LIST_TWO_COLUMNS_FROM], else 1. */
fun listColumns(paneWidth: Float): Int = if (paneWidth >= LIST_TWO_COLUMNS_FROM) 2 else 1

/**
 * What a grid or shelf cell needs to draw itself. [listColumns] is how many columns a list of rows
 * placed in the same pane would use ([listColumns] the function), for rows shown inside a grid.
 */
class CardMetrics(val columns: Int, val cardWidth: Dp, val gutter: Dp, val listColumns: Int = 1)

/** The bottom system-bar room a scrolling page needs (zero where the shell already keeps clear of it). */
@Composable
fun rememberBottomInset(): Dp = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()

/** The page's own system-bar room: the top and the sides. The shell has already used what it uses (padding consumes insets). */
@Composable
fun Modifier.screenInsets(): Modifier = windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))

/**
 * The grid for albums, artists, playlists and everything else with cards: fixed columns from
 * [density] and the window class, gutters from the class, row gap = gutter + 8, margin all round.
 * [bottomPadding] is extra room at the end (the navigation bar when nothing else keeps clear of it).
 * [endInset] is room kept at the right for something over the margin (the A-Z strip): it narrows the
 * content, so columns are counted without it. The cells fill their column.
 */
@Composable
fun CardGrid(
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    windowClass: WindowClass = LocalWindowClass.current,
    density: Density = LocalDensityState.current.density,
    bottomPadding: Dp = rememberBottomInset(),
    endInset: Dp = 0.dp,
    content: LazyGridScope.(CardMetrics) -> Unit,
) {
    val margin = windowClass.pageMargin
    val gutter = windowClass.gutter
    BoxWithConstraints(modifier.fillMaxSize()) {
        // The strip (endInset) sits over the right margin where that is wide enough, else it takes a little more.
        val end = maxOf(margin, endInset)
        val contentWidth = gridContentWidth(maxWidth.value, margin.value, endInset.value)
        val columns = gridColumns(contentWidth, density, gutter.value)
        val metrics = CardMetrics(columns, cardWidth(contentWidth, columns, gutter.value).dp, gutter, listColumns(maxWidth.value - endInset.value))
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = PaddingValues(start = margin, top = margin, end = end, bottom = margin + bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(gutter),
            verticalArrangement = Arrangement.spacedBy(gutter + 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) { content(metrics) }
    }
}

/**
 * Rows (a list view) in the same pane: [ListRow][gg.dekiru.musicplayer.ui.components.ListRow]s fill the width,
 * in two columns from [LIST_TWO_COLUMNS_FROM]. Rows are laid out in row-major order, so the list reads
 * left to right, then down. Scrolls with [state]; [endInset] keeps clear of the A-Z strip.
 */
@Composable
fun ListGrid(
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    bottomPadding: Dp = rememberBottomInset(),
    endInset: Dp = 0.dp,
    content: LazyGridScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().padding(end = endInset)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(listColumns(maxWidth.value)),
            state = state,
            contentPadding = PaddingValues(bottom = 16.dp + bottomPadding),
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }
}

/**
 * Lets a child of a grid's full-width item run out into the grid's side margins (a [CardShelf], which
 * draws its own margin, inside a [CardGrid] that already has one).
 */
fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = (horizontal * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** A full-width row in a [CardGrid] (a section header, a banner, a footer). */
fun LazyGridScope.fullWidthItem(key: Any? = null, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/**
 * A section header in a [CardGrid]: [fullWidthItem], pulled down towards what follows, since the grid's
 * row gap (gutter + 8) is made for rows of cards and left the header floating a card-gap above them.
 */
fun LazyGridScope.headerItem(key: Any? = null, content: @Composable () -> Unit) {
    fullWidthItem(key) {
        val pull = LocalWindowClass.current.gutter + 4.dp
        Box(
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, (placeable.height - pull.roundToPx()).coerceAtLeast(0)) { placeable.place(0, 0) }
            },
        ) { content() }
    }
}

/**
 * [items] as rows inside a [CardGrid] (Search's playlists): [CardMetrics.listColumns] to a line, a
 * gutter apart, each [content] filling its share. The grid already has the page margin, so give
 * rows `horizontalPadding = 0.dp`.
 */
fun <T> LazyGridScope.rowItems(items: List<T>, metrics: CardMetrics, key: (T) -> Any, content: @Composable (T) -> Unit) {
    val perLine = maxOf(1, metrics.listColumns)
    for (line in items.chunked(perLine)) {
        fullWidthItem(key = "row-${key(line.first())}") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.gutter)) {
                for (item in line) Box(Modifier.weight(1f)) { content(item) }
                // A short last line keeps its rows the same width as the others.
                repeat(perLine - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A horizontal row of the same cards (Home's shelves). On Compact the next card peeks in at the
 * right edge; wider windows show whole cards. [card] gets the card's width.
 */
@Composable
fun <T> CardShelf(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    windowClass: WindowClass = LocalWindowClass.current,
    density: Density = LocalDensityState.current.density,
    card: @Composable (item: T, cardWidth: Dp) -> Unit,
) {
    val margin = windowClass.pageMargin
    val gutter = windowClass.gutter
    BoxWithConstraints(modifier) {
        val contentWidth = maxWidth.value - 2 * margin.value
        val columns = gridColumns(contentWidth, density, gutter.value)
        val width = shelfCardWidth(contentWidth, columns, gutter.value, margin.value, peek = windowClass == WindowClass.Compact).dp
        LazyRow(
            contentPadding = PaddingValues(horizontal = margin),
            horizontalArrangement = Arrangement.spacedBy(gutter),
        ) {
            items(items, key = key) { item -> card(item, width) }
        }
    }
}
