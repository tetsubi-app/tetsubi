package gg.dekiru.musicplayer

import androidx.compose.ui.graphics.toArgb
import gg.dekiru.musicplayer.ui.TetsubiColors
import kotlin.test.Test
import kotlin.test.assertEquals

/** The colours must match the iOS/Mac app exactly. */
class TetsubiColorsTest {
    @Test
    fun coloursMatchTheAppleApps() {
        assertEquals(0xFF000B25.toInt(), TetsubiColors.background.toArgb())
        assertEquals(0xFFAA5CC3.toInt(), TetsubiColors.pink.toArgb())
        assertEquals(0xFF039FD6.toInt(), TetsubiColors.blue.toArgb())
    }

    @Test
    fun redesignTokens() {
        assertEquals(TetsubiColors.background, TetsubiColors.navy)
        assertEquals(0xFF30D158.toInt(), TetsubiColors.green.toArgb())
        assertEquals(0xFFFF453A.toInt(), TetsubiColors.red.toArgb())
        assertEquals(0.08f, TetsubiColors.surface.alpha, 0.005f)
        assertEquals(0.16f, TetsubiColors.art.alpha, 0.005f)
        assertEquals(0.20f, TetsubiColors.blueTint.alpha, 0.005f)
        assertEquals(0.25f, TetsubiColors.pinkTint.alpha, 0.005f)
        assertEquals(0.12f, TetsubiColors.hairline.alpha, 0.005f)
        assertEquals(1f, TetsubiColors.textPrimary.alpha)
        assertEquals(0.60f, TetsubiColors.textSecondary.alpha, 0.005f)
        assertEquals(0.40f, TetsubiColors.textTertiary.alpha, 0.005f)
    }
}
