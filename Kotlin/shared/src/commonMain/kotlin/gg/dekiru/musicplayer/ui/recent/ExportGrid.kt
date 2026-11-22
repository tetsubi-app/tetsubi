package gg.dekiru.musicplayer.ui.recent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.settings.Preferences
import kotlinx.datetime.LocalDateTime

/**
 * The Recently Played page's "Export grid" (as iOS): a 1200 × 1200 PNG of
 * the n × n most recently played albums, square tiles with no gaps, left
 * to right, top to bottom; optionally each album's title and artist in the
 * tile's top-left corner. Everything here is plain maths, so it's tested;
 * the drawing itself is per platform ([GridExporter]).
 */
object ExportGrid {
    /** The picture's width and height, in pixels. */
    const val IMAGE_SIZE = 1200

    /** The grid sizes offered: 3 × 3, 4 × 4, 5 × 5. */
    val SIZES = listOf(3, 4, 5)
    const val DEFAULT_SIZE = 5

    /** Fewer albums than this: no export (a 3 × 3 needs nine). */
    const val MIN_ALBUMS = 9

    /** Covers are asked of the server at this width, enough for the biggest tile (400 px). */
    const val COVER_SIZE = 600

    /** Inset of the labels from the tile's top and left (and right) edges, as a share of the tile. */
    const val INSET = 0.02f
    const val TITLE_SIZE = 0.055f
    const val ARTIST_SIZE = 0.045f

    /** The labels' shadow: sharp (no blur), black at 80%, down and right by this share of the tile (at least 1 px). */
    const val SHADOW_OFFSET = 0.005f
    const val SHADOW_ALPHA = 0.8f

    /** A missing cover (as the app's placeholder): a dark grey tile, a white note at 55%, 28% of the tile, centred. */
    const val PLACEHOLDER_RGB = 0x2D2D2D
    const val NOTE = "♪"
    const val NOTE_SIZE = 0.28f
    const val NOTE_ALPHA = 0.55f

    /** Tile width for an n × n grid: 400, 300, 240. */
    fun tile(n: Int): Int = IMAGE_SIZE / n

    /** Albums one per entry (by ID), in order. */
    fun distinct(albums: List<JellyfinAlbum>): List<JellyfinAlbum> = albums.distinctBy { it.id }

    /**
     * The page grid's columns: the size used (as many across as the export,
     * on every device); null below nine albums (the Library's usual columns).
     */
    fun gridColumns(albums: List<JellyfinAlbum>, chosen: Int): Int? = resolvedSize(chosen, distinct(albums).size)

    /** Whether there are enough albums for an n × n grid. */
    fun isAvailable(n: Int, count: Int): Boolean = count >= n * n

    /** Whether there's anything to export at all (at least nine albums). */
    fun canExport(count: Int): Boolean = count >= MIN_ALBUMS

    /** The sizes [count] albums can fill. */
    fun availableSizes(count: Int): List<Int> = SIZES.filter { isAvailable(it, count) }

    /**
     * The size to use: the chosen one if there are enough albums, else the
     * biggest that can be filled; null when not even 3 × 3 can.
     */
    fun resolvedSize(chosen: Int, count: Int): Int? {
        if (isAvailable(chosen, count)) return chosen
        return availableSizes(count).lastOrNull()
    }

    /** The n² most recent albums (one per ID) for an n × n grid; null when there are too few. */
    fun albumsFor(albums: List<JellyfinAlbum>, n: Int): List<JellyfinAlbum>? =
        distinct(albums).takeIf { isAvailable(n, it.size) }?.take(n * n)

    /**
     * The albums the page's grid shows: exactly the ones that go into the
     * export, the first n² (one per ID) for the size used ([resolvedSize]:
     * the chosen one, else the biggest that fits). Fewer than nine albums
     * (no export): all there are.
     */
    fun shownAlbums(albums: List<JellyfinAlbum>, chosen: Int): List<JellyfinAlbum> {
        val distinct = distinct(albums)
        val n = resolvedSize(chosen, distinct.size) ?: return distinct
        return distinct.take(n * n)
    }

    /** A tile's place in the picture, in pixels. */
    data class TileRect(val x: Int, val y: Int, val size: Int)

    /** The n² tiles, row by row. */
    fun tileRects(n: Int): List<TileRect> {
        val t = tile(n)
        return (0 until n * n).map { i -> TileRect(x = (i % n) * t, y = (i / n) * t, size = t) }
    }

    /** Where and how big the labels are in a tile [tile] pixels wide. */
    data class LabelLayout(
        val inset: Float,
        val titleSize: Float,
        val artistSize: Float,
        /** The shadow's offset, down and right, in whole pixels. */
        val shadowOffset: Int,
        /** Room for each line: the tile less both insets. */
        val maxWidth: Float,
    )

    fun labelLayout(tile: Int): LabelLayout = LabelLayout(
        inset = tile * INSET,
        titleSize = tile * TITLE_SIZE,
        artistSize = tile * ARTIST_SIZE,
        shadowOffset = maxOf(1, kotlin.math.round(tile * SHADOW_OFFSET).toInt()),
        maxWidth = tile - 2 * tile * INSET,
    )

    /**
     * Baselines of the two lines, from the tile's top: the title's top at the
     * inset (inset + ascent), the artist under it (+ the title's descent and
     * leading + the artist's ascent). Ascents and descents are positive.
     */
    fun baselines(layout: LabelLayout, titleAscent: Float, titleDescent: Float, titleLeading: Float, artistAscent: Float): Pair<Float, Float> {
        val title = layout.inset + titleAscent
        return title to title + titleDescent + titleLeading + artistAscent
    }

    /**
     * [text] cut to fit [maxWidth] with an ellipsis ([measure] gives a
     * string's width). Fits: unchanged. Not even "…" fits: empty.
     */
    fun ellipsize(text: String, maxWidth: Float, measure: (String) -> Float): String {
        if (measure(text) <= maxWidth) return text
        val ellipsis = "…"
        if (measure(ellipsis) > maxWidth) return ""
        // The longest start that fits with the ellipsis.
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (measure(text.take(mid).trimEnd() + ellipsis) <= maxWidth) low = mid else high = mid - 1
        }
        return text.take(low).trimEnd() + ellipsis
    }

    /**
     * The part of a [width] × [height] picture drawn into a square tile,
     * aspect-fill (the middle square). Returns left, top, right, bottom.
     */
    fun aspectFillSource(width: Int, height: Int): List<Int> {
        val side = minOf(width, height)
        val left = (width - side) / 2
        val top = (height - side) / 2
        return listOf(left, top, left + side, top + side)
    }

    /**
     * "Tetsubi Recently Played 2026-11-22 14.05.32.png": 24-hour time (the
     * device's, as [time] is given), dots rather than colons so it's a valid
     * name on every file system.
     */
    fun fileName(time: LocalDateTime): String {
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "Tetsubi Recently Played ${time.date} ${two(time.hour)}.${two(time.minute)}.${two(time.second)}.png"
    }
}

/**
 * The size and labels choice, kept across launches (the same keys as iOS).
 * Compose state, so the page's grid (which shows the albums of the chosen
 * size) and the Export section read the one value and both follow a change.
 */
class ExportSettings(private val preferences: Preferences) {
    private var _size by mutableStateOf(
        preferences.getString(SIZE_KEY)?.toIntOrNull()?.takeIf { it in ExportGrid.SIZES } ?: ExportGrid.DEFAULT_SIZE,
    )
    private var _showLabels by mutableStateOf(preferences.getString(LABELS_KEY) == "true")

    /** 3, 4 or 5 (5 until changed); a size not offered is ignored. */
    var size: Int
        get() = _size
        set(value) {
            if (value !in ExportGrid.SIZES) return
            _size = value
            preferences.putString(SIZE_KEY, value.toString())
        }

    var showLabels: Boolean
        get() = _showLabels
        set(value) {
            _showLabels = value
            preferences.putString(LABELS_KEY, value.toString())
        }

    companion object {
        const val SIZE_KEY = "recentlyPlayed.exportSize"
        const val LABELS_KEY = "recentlyPlayed.exportLabels"
    }
}

/** One tile to draw: the cover's file bytes (null: the placeholder) and its two labels. */
class ExportTile(val cover: ByteArray?, val title: String, val artist: String)
