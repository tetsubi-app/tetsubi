package gg.dekiru.musicplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import gg.dekiru.musicplayer.resources.Res
import gg.dekiru.musicplayer.resources.dekirusans_bold
import gg.dekiru.musicplayer.resources.dekirusans_medium
import gg.dekiru.musicplayer.resources.dekirusans_regular
import gg.dekiru.musicplayer.resources.dekirusans_semibold
import org.jetbrains.compose.resources.Font

/**
 * DEKIRU Sans (IBM Plex Sans with the iOS line height, 1.193 em, built into
 * the files), the same files as iOS. Characters it lacks (Japanese, for
 * one) fall back to the system font.
 */
@Composable
fun dekiruSans(): FontFamily = FontFamily(
    Font(Res.font.dekirusans_regular, FontWeight.Normal),
    Font(Res.font.dekirusans_medium, FontWeight.Medium),
    Font(Res.font.dekirusans_semibold, FontWeight.SemiBold),
    Font(Res.font.dekirusans_bold, FontWeight.Bold),
)

/**
 * Material's type scale in DEKIRU Sans. Line heights are left to the font:
 * Material's own (24 sp for body text, whatever the size) spread small
 * text far apart, which iOS doesn't do.
 */
private fun Typography.inFamily(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family, lineHeight = TextUnit.Unspecified)
    return Typography(
        displayLarge.f(), displayMedium.f(), displaySmall.f(),
        headlineLarge.f(), headlineMedium.f(), headlineSmall.f(),
        titleLarge.f(), titleMedium.f(), titleSmall.f(),
        bodyLarge.f(), bodyMedium.f(), bodySmall.f(),
        labelLarge.f(), labelMedium.f(), labelSmall.f(),
    )
}

/** Tetsubi is always dark: navy background, white text, blue and pink accents. */
@Composable
fun TetsubiTheme(content: @Composable () -> Unit) {
    val family = dekiruSans()
    val typography = remember(family) { Typography().inFamily(family) }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TetsubiColors.blue,
            onPrimary = Color.White,
            secondary = TetsubiColors.pink,
            onSecondary = Color.White,
            background = TetsubiColors.background,
            onBackground = Color.White,
            surface = TetsubiColors.background,
            onSurface = Color.White,
        ),
        typography = typography,
        content = content,
    )
}
