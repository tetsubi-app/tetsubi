package gg.dekiru.musicplayer.ui

import androidx.compose.ui.graphics.Color

/** App colours, the same as the iOS/Mac app's `AppColors.swift`. */
object TetsubiColors {
    /** Page background: #000B25. */
    val background = Color(0xFF000B25)
    /** Accent pink: #AA5CC3. */
    val pink = Color(0xFFAA5CC3)
    /** Accent blue: #039FD6. */
    val blue = Color(0xFF039FD6)
    /** Downloaded tick: iOS system green (dark). */
    val green = Color(0xFF30D158)
    /** Download failures: iOS system red (dark). */
    val red = Color(0xFFFF453A)
    /** Boxes over the background picture (Settings, Downloads): 50% black. */
    val groupBackground = Color.Black.copy(alpha = 0.5f)
    /** Artist name under album titles. */
    val albumArtistText = Color.White.copy(alpha = 0.85f)
    /** Release year under the artist, one step dimmer. */
    val albumYearText = Color.White.copy(alpha = 0.65f)
}
