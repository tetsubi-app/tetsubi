package gg.dekiru.musicplayer.ui.nowplaying

import gg.dekiru.musicplayer.ui.nowplaying.ArtPalette.Rgb
import gg.dekiru.musicplayer.ui.nowplaying.ArtPalette.Swatch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same cases as iOS `ArtPaletteTests`, so both apps pick the same colour. */
class ArtPaletteTest {

    /** Pixels in horizontal bands, 100 wide: (r, g, b, rows). */
    private fun image(vararg bands: IntArray): IntArray =
        bands.flatMap { (r, g, b, rows) -> List(rows * 100) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b } }.toIntArray()

    @Test
    fun picksTheContrastingHighlightNotTheSecondCommonest() {
        // Like the SCREW UP cover: mostly red, dark shadows, a yellow highlight.
        val accent = ArtPalette.accentColor(image(intArrayOf(200, 45, 25, 55), intArrayOf(40, 15, 10, 30), intArrayOf(220, 210, 40, 15)))
        assertNotNull(accent)
        assertTrue(accent.green > 0.7, "should be the yellow")
        assertTrue(accent.blue < 0.3)
    }

    @Test
    fun smallSpecksAreIgnored() {
        assertNull(ArtPalette.accentColor(listOf(Swatch(Rgb(0.1, 0.15, 0.8), 0.99), Swatch(Rgb(0.95, 0.5, 0.1), 0.01))))
    }

    @Test
    fun greyOnlyArtHasNoAccent() {
        assertNull(ArtPalette.accentColor(image(intArrayOf(30, 30, 30, 60), intArrayOf(200, 200, 200, 40))))
    }

    @Test
    fun darkAccentIsBrightened() {
        val accent = ArtPalette.accentColor(listOf(Swatch(Rgb(0.9, 0.9, 0.9), 0.7), Swatch(Rgb(0.4, 0.05, 0.05), 0.3)))
        assertNotNull(accent)
        assertTrue(abs(accent.brightness - ArtPalette.DISPLAY_BRIGHTNESS) < 0.001)
        assertTrue(accent.red > accent.green)
    }
}
