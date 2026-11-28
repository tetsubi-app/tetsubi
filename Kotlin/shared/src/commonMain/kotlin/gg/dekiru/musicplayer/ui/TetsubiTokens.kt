package gg.dekiru.musicplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Spacing steps (docs/redesign.md section 6): 4, 8, 12, 16, 24, 32 only. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Corner radii: 6 thumbs and track highlights, 10 card art, 16 panels, groups and banners; pills use [full]. */
object Radii {
    val thumb = 6.dp
    val card = 10.dp
    val panel = 16.dp
    /** Pills and round buttons (a percentage, so any size is fully round). */
    const val FULL_PERCENT = 50
}

/** The type scale in DEKIRU Sans. Read it with [tetsubiType]; the theme provides the family. */
@Immutable
class TetsubiType(family: FontFamily) {
    val pageTitle = TextStyle(fontFamily = family, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    val sectionTitle = TextStyle(fontFamily = family, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val rowTitle = TextStyle(fontFamily = family, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontFamily = family, fontSize = 14.sp, fontWeight = FontWeight.Normal)
    /** A card's title: 14 semibold. Its subtitle is [caption] in secondary. */
    val cardTitle = TextStyle(fontFamily = family, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    /** Trailing meta in rows and small controls: 13 medium. */
    val control = TextStyle(fontFamily = family, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontFamily = family, fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val navLabel = TextStyle(fontFamily = family, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    /** Now Playing: title 22 bold, artist 17 medium. */
    val nowPlayingTitle = TextStyle(fontFamily = family, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    val nowPlayingArtist = TextStyle(fontFamily = family, fontSize = 17.sp, fontWeight = FontWeight.Medium)
}

val LocalTetsubiType = staticCompositionLocalOf { TetsubiType(FontFamily.Default) }

val tetsubiType: TetsubiType @Composable get() = LocalTetsubiType.current
