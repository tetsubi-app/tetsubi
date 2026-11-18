package gg.dekiru.musicplayer.ui.nowplaying

import kotlin.math.floor

/**
 * Now Playing's size rules, as iOS `NowPlayingView` (in dp, with no window
 * scaling). Wide (a tablet on its side, a Linux window): two columns, the
 * cover, titles and controls on the left and Up next on the right, all grown
 * by [scale]. Otherwise (phones, a tablet upright below the threshold) one
 * column at the normal size.
 */
object NowPlayingLayout {
    /**
     * Size from which the two-column layout is used. The width takes in an
     * unfolded phone, a tablet and a desktop window; the height keeps a phone
     * on its side on one column, where two would leave the controls off screen.
     */
    const val WIDE_MINIMUM_WIDTH = 640f
    const val WIDE_MINIMUM_HEIGHT = 520f

    /** The size [scale] is measured against, and its limit. */
    const val SCALE_BASE_WIDTH = 800f
    const val SCALE_BASE_HEIGHT = 600f
    const val MAX_SCALE = 1.6f

    fun isWide(width: Float, height: Float): Boolean =
        width >= WIDE_MINIMUM_WIDTH && height >= WIDE_MINIMUM_HEIGHT

    /**
     * How much bigger everything in Now Playing is drawn, from the size of its
     * area: `min(width / 800, height / 600)`, between 1 and 1.6, rounded down
     * to 0.05 so a small resize doesn't lay it out again. Always 1 when the
     * layout isn't wide.
     */
    fun scale(width: Float, height: Float): Float {
        if (!isWide(width, height)) return 1f
        val raw = minOf(width / SCALE_BASE_WIDTH, height / SCALE_BASE_HEIGHT).toDouble().coerceIn(1.0, MAX_SCALE.toDouble())
        // The small nudge keeps an exact step (1.15) from falling to the one below.
        return (floor(raw * 20 + 1e-6) / 20).toFloat()
    }

    /**
     * The left column's width in the wide layout, at normal scale: 46% of the
     * width, from 320 to 520. Inside the scaled area (width already divided
     * by the scale) this equals iOS's `min(max(w * 0.46, 320s), 520s)`.
     */
    fun leftColumnWidth(width: Float): Float = minOf(maxOf(width * 0.46f, 320f), 520f)

    /**
     * The cover in the wide layout, at normal scale: as big as fits, so the
     * title, progress and buttons below it are all on screen without
     * scrolling. The rest of that column is about 430 (510 with the
     * visualiser).
     */
    fun wideArtSize(columnWidth: Float, height: Float, visualiser: Boolean): Float {
        val others = if (visualiser) 510f else 430f
        val byWidth = minOf(columnWidth - 56f, 360f)
        return maxOf(140f, minOf(byWidth, height - others))
    }
}
