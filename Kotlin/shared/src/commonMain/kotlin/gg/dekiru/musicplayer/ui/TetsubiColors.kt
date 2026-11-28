package gg.dekiru.musicplayer.ui

import androidx.compose.ui.graphics.Color

/** App colours, the same as the iOS/Mac app's `AppColors.swift`. */
object TetsubiColors {
    /** Page background: #000B25. */
    val background = Color(0xFF000B25)
    /** The redesign's name for [background]. */
    val navy = background
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

    // Redesign tokens (docs/redesign.md section 6). Dark only.
    /** Cards, bars, groups, the mini player and the sidebar: white 8%. */
    val surface = Color.White.copy(alpha = 0.08f)
    /** Art placeholder: white 16%. */
    val art = Color.White.copy(alpha = 0.16f)
    /** Selected row, segment or nav item: blue 20%. */
    val blueTint = blue.copy(alpha = 0.20f)
    /** The current track: pink 25%. */
    val pinkTint = pink.copy(alpha = 0.25f)
    /** The rows being dragged in the queue: white 12% over the panel. */
    val dragWash = Color.White.copy(alpha = 0.12f)
    /** Dividers and outlines: white 12%. */
    val hairline = Color.White.copy(alpha = 0.12f)
    val textPrimary = Color.White
    val textSecondary = Color.White.copy(alpha = 0.60f)
    val textTertiary = Color.White.copy(alpha = 0.40f)
}
