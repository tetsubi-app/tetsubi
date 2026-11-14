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
}
