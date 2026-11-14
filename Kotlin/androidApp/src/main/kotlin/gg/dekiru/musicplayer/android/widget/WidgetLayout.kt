package gg.dekiru.musicplayer.android.widget

/**
 * The widget's measurements for its real size (`width` × `height` in dp,
 * as the home screen gives it), in the iOS widget's proportions, so a big
 * cell (the Pad's ≈256 dp squares) gets big art and text, not a phone's
 * layout in the corner.
 *
 * Small: the art about half the width at the top left, the round
 * Play/Pause at the top right, the title and the finish time at the
 * bottom. Medium (wide enough, as 4×2): the art the full height on the
 * left; title, artist, album and the times beside it, the buttons under
 * them. Text grows with the widget within limits; on a short widget the
 * album line, then the times, then the artist are left out rather than
 * cut off. Sizes are dp, text sp (`fontScale` is the device's text size
 * setting, so lines are measured as they'll be drawn).
 */
data class WidgetLayout(
    /** The medium layout (else small). */
    val medium: Boolean,
    val padding: Float,
    val art: Float,
    val artCorner: Float,
    /** The gap between the art and what's beside or under it. */
    val gap: Float,
    val play: Float,
    val skip: Float,
    /** Between the buttons. */
    val buttonGap: Float,
    val titleSp: Float,
    val secondarySp: Float,
    val timesSp: Float,
    val showArtist: Boolean,
    val showAlbum: Boolean,
    val showTimes: Boolean,
    /** Small only: "Duration: …" on its own line above the finish time (medium has both on one line). */
    val showDuration: Boolean = false,
) {
    companion object {
        /** The art's corners, as a share of its side. */
        const val ART_CORNER = 0.1f

        /** A line of text's height for its size, with the font's padding. */
        const val LINE = 1.35f

        /** Medium from this wide (dp), when also [WIDE] times as wide as tall. */
        const val MEDIUM_MIN_WIDTH = 200f
        const val WIDE = 1.5f

        fun of(width: Float, height: Float, fontScale: Float = 1f, skips: Boolean = true): WidgetLayout =
            if (width >= MEDIUM_MIN_WIDTH && width >= height * WIDE) medium(width, height, fontScale, skips) else small(width, height, fontScale)

        /**
         * Small: the art fills the widget (as iOS), play/pause at the top right,
         * and over a dark fade at the bottom the title, then as room allows the
         * artist, album and finish time (no duration); the album goes first,
         * then the artist and the finish time.
         */
        private fun small(width: Float, height: Float, fontScale: Float): WidgetLayout {
            val side = minOf(width, height)
            val padding = (side * 0.08f).coerceIn(12f, 22f)
            val gap = (side * 0.04f).coerceIn(4f, 12f)
            val title = (side * 0.085f).coerceIn(13f, 24f)
            val secondary = (title * 0.78f).coerceIn(11f, 19f)
            val times = (title * 0.7f).coerceIn(10f, 17f)
            val play = (side * 0.24f).coerceIn(36f, 72f)
            val room = height - 2 * padding - play - gap
            val titleH = title * fontScale * LINE
            val secondaryH = secondary * fontScale * LINE
            val timesH = times * fontScale * LINE
            var showArtist = true
            var showAlbum = true
            // No duration on the small size (Jake, 2026-11-13): just the finish time.
            val showDuration = false
            var showTimes = true
            fun used() = titleH + (if (showArtist) secondaryH else 0f) + (if (showAlbum) secondaryH else 0f) +
                (if (showTimes) timesH else 0f)
            if (used() > room) showAlbum = false
            if (used() > room) showArtist = false
            if (used() > room) showTimes = false
            // Only drawn when there's no art: the placeholder at the top left, the button's height.
            val art = play
            return WidgetLayout(
                medium = false, padding = padding, art = art, artCorner = art * ART_CORNER, gap = gap,
                play = play, skip = 0f, buttonGap = 0f,
                titleSp = title, secondarySp = secondary, timesSp = times,
                showArtist = showArtist, showAlbum = showAlbum, showTimes = showTimes, showDuration = showDuration,
            )
        }

        private fun medium(width: Float, height: Float, fontScale: Float, skips: Boolean): WidgetLayout {
            val padding = (height * 0.1f).coerceIn(12f, 26f)
            val innerH = height - 2 * padding
            val gap = (height * 0.09f).coerceIn(12f, 24f)
            val art = minOf(innerH, width * 0.42f).coerceAtLeast(24f)
            val column = width - 2 * padding - art - gap
            val title = (height * 0.1f).coerceIn(14f, 26f)
            val secondary = (title * 0.84f).coerceIn(12f, 22f)
            val times = (title * 0.7f).coerceIn(11f, 18f)
            var play = (height * 0.25f).coerceIn(36f, 76f)
            var skip = play * 0.9f
            var buttonGap = play * 0.35f
            // The buttons fit the column's width.
            val row = if (skips) play + 2 * skip + 2 * buttonGap else play
            if (row > column) {
                val scale = (column / row).coerceAtLeast(0f)
                play = (play * scale).coerceAtLeast(32f)
                skip *= scale
                buttonGap *= scale
            }
            // The lines that fit above the buttons, leaving out the album, then the times, then the artist.
            val room = innerH - play - 4f
            val titleH = title * fontScale * LINE
            val secondaryH = secondary * fontScale * LINE
            val timesH = times * fontScale * LINE
            var showArtist = true
            var showAlbum = true
            var showTimes = true
            fun used() = titleH + (if (showArtist) secondaryH else 0f) + (if (showAlbum) secondaryH else 0f) + (if (showTimes) timesH else 0f)
            if (used() > room) showAlbum = false
            if (used() > room) showTimes = false
            if (used() > room) showArtist = false
            return WidgetLayout(
                medium = true, padding = padding, art = art, artCorner = art * ART_CORNER, gap = gap,
                play = play, skip = skip, buttonGap = buttonGap,
                titleSp = title, secondarySp = secondary, timesSp = times,
                showArtist = showArtist, showAlbum = showAlbum, showTimes = showTimes,
            )
        }
    }
}
