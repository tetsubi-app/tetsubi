package gg.dekiru.musicplayer.ui.nowplaying

import androidx.compose.ui.graphics.Color
import gg.dekiru.musicplayer.ui.TetsubiColors
import kotlin.test.Test
import kotlin.test.assertEquals

/** Now Playing's accent: the art's colour, or brand blue while there is none. */
class ArtAccentTest {
    @Test
    fun fallsBackToBrandBlueWithoutArt() = assertEquals(TetsubiColors.blue, resolveAccent(null))

    @Test
    fun usesTheArtAccentWhenThereIsOne() {
        val art = Color(0.9f, 0.8f, 0.1f)
        assertEquals(art, resolveAccent(art))
    }

    @Test
    fun paletteLiftsADarkAccentToDisplayBrightness() {
        // Mostly grey art with a dark but vivid patch: the picked colour is brightened to stay legible.
        val image = IntArray(100 * 100) { if (it < 3000) (0xFF shl 24) or (90 shl 16) or (20 shl 8) or 20 else (0xFF shl 24) or (70 shl 16) or (70 shl 8) or 70 }
        val accent = ArtPalette.accentColor(image)
        assertEquals(true, accent != null && accent.brightness >= ArtPalette.DISPLAY_BRIGHTNESS - 1e-9)
    }
}
