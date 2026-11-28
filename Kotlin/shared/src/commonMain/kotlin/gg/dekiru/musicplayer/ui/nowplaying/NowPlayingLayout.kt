package gg.dekiru.musicplayer.ui.nowplaying

import gg.dekiru.musicplayer.ui.layout.WindowClass

/**
 * Now Playing's size rules (docs/redesign.md section 5), in dp, with no scaling. The window class
 * picks the layout: Compact is one column (a full-screen sheet with the queue below), Medium and
 * Expanded are two (the player on the left, the queue panel on the right).
 */
object NowPlayingLayout {
    /** The cover on one column (a phone): at most 300. */
    const val COMPACT_MAX_ART = 300f

    /** The cover in the two-column layout: at most 420. */
    const val WIDE_MAX_ART = 420f
    const val MIN_ART = 140f

    /** Side room of the single column. */
    const val COMPACT_MARGIN = 16f

    fun usesTwoColumns(windowClass: WindowClass): Boolean = windowClass.isWide

    /** One column: as wide as fits between the margins, up to 300, and no more than 40% of the height. */
    fun compactArtSize(width: Float, height: Float): Float =
        maxOf(MIN_ART, minOf(width - 2 * COMPACT_MARGIN, COMPACT_MAX_ART, height * 0.4f))

    /** The left column's width in two columns: 46% of the width, from 360 to 560. */
    fun leftColumnWidth(width: Float): Float = minOf(maxOf(width * 0.46f, 360f), 560f)

    /**
     * The cover in two columns: as big as fits, so the title, progress and buttons below it are all
     * on screen without scrolling. The rest of that column is about 430 (510 with the visualiser).
     */
    fun wideArtSize(columnWidth: Float, height: Float, visualiser: Boolean): Float {
        val others = if (visualiser) 510f else 430f
        val byWidth = minOf(columnWidth - 64f, WIDE_MAX_ART)
        return maxOf(MIN_ART, minOf(byWidth, height - others))
    }
}
