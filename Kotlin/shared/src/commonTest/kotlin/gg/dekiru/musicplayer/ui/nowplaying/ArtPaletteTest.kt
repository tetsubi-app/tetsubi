package gg.dekiru.musicplayer.ui.nowplaying

import gg.dekiru.musicplayer.ui.nowplaying.ArtPalette.Rgb
import gg.dekiru.musicplayer.ui.nowplaying.ArtPalette.Swatch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // Synthetic covers, SAMPLE_SIZE square, as the app shrinks the real art to.

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A square cover: [base] fills the image except the first [share] of pixels (a band), which are [band]. */
    private fun cover(base: Int, band: Int, share: Double, side: Int = ArtPalette.SAMPLE_SIZE): IntArray =
        IntArray(side * side) { if (it < (side * side * share).toInt()) band else base }

    private fun hueNear(accent: ArtPalette.Rgb?, hue: Double, tolerance: Double = 0.06): Boolean =
        accent != null && minOf(abs(accent.hue - hue), 1 - abs(accent.hue - hue)) <= tolerance

    @Test
    fun redOnBlackIsRedAtAnyShare() {
        for (share in listOf(0.03, 0.10, 0.20, 0.35)) {
            val accent = ArtPalette.accentColor(cover(argb(8, 8, 10), argb(210, 25, 30), share))
            assertTrue(hueNear(accent, 0.0), "red band $share of the cover: $accent")
            assertTrue(accent!!.brightness >= ArtPalette.DISPLAY_BRIGHTNESS - 1e-9)
        }
    }

    @Test
    fun darkRedOnBlackIsALiftedRed() {
        // A moody cover: near-black with deep reds that never get bright (the Punisher case).
        for (share in listOf(0.10, 0.20, 0.35)) {
            val accent = ArtPalette.accentColor(cover(argb(12, 10, 10), argb(70, 12, 14), share))
            assertTrue(hueNear(accent, 0.0), "dark red band $share of the cover: $accent")
            assertTrue(accent!!.brightness >= ArtPalette.DISPLAY_BRIGHTNESS - 1e-9)
        }
    }

    @Test
    fun darkRedWithBlackShadowsAndDarkRedMainStillGivesRed() {
        // Mostly deep red, black shadows, a smaller brighter red: no other hue, so the red still wins.
        val pixels = IntArray(1600) { if (it < 800) argb(90, 10, 12) else if (it < 1300) argb(10, 8, 8) else argb(180, 30, 30) }
        assertTrue(hueNear(ArtPalette.accentColor(pixels), 0.0))
    }

    @Test
    fun darkBlueCoverIsALiftedBlue() {
        val accent = ArtPalette.accentColor(cover(argb(5, 8, 20), argb(25, 60, 150), 0.3))
        assertTrue(hueNear(accent, 0.62, 0.05), "$accent")
        assertTrue(accent!!.brightness >= ArtPalette.DISPLAY_BRIGHTNESS - 1e-9)
    }

    @Test
    fun darkBlueOnlyCoverIsLiftedBlue() {
        // All dark blue, nothing brighter: a lifted blue beats the brand fallback being used by accident.
        val accent = ArtPalette.accentColor(cover(argb(4, 6, 16), argb(10, 24, 70), 0.4))
        assertTrue(hueNear(accent, 0.62, 0.06), "$accent")
    }

    @Test
    fun pastelCoverGivesAPastelAccent() {
        val accent = ArtPalette.accentColor(cover(argb(240, 235, 225), argb(240, 150, 180), 0.3))
        assertNotNull(accent)
        assertTrue(accent.saturation >= ArtPalette.MINIMUM_SATURATION)
    }

    @Test
    fun greyscaleCoverHasNoAccent() {
        assertNull(ArtPalette.accentColor(cover(argb(20, 20, 20), argb(200, 200, 200), 0.4)))
    }

    @Test
    fun greenOnBlackIsGreen() {
        assertTrue(hueNear(ArtPalette.accentColor(cover(argb(5, 8, 5), argb(30, 190, 60), 0.25)), 0.36, 0.06))
    }

    @Test
    fun accentFollowsAChangingSequenceOfCovers() {
        val covers = listOf(
            cover(argb(8, 8, 10), argb(210, 25, 30), 0.2) to 0.0, // red
            cover(argb(5, 8, 20), argb(25, 60, 150), 0.3) to 0.62, // blue
            cover(argb(5, 8, 5), argb(30, 190, 60), 0.25) to 0.36, // green
            cover(argb(12, 10, 10), argb(70, 12, 14), 0.2) to 0.0, // dark red
        )
        val accents = covers.map { (pixels, hue) -> ArtPalette.accentColor(pixels).also { assertTrue(hueNear(it, hue), "$hue: $it") } }
        assertTrue(accents.zipWithNext().all { (a, b) -> a != b })
    }

    @Test
    fun resolutionReportsWhyThereIsNoAccent() {
        assertEquals("noBitmap", resolveArtAccent(null).reason)
        val grey = resolveArtAccent(cover(argb(20, 20, 20), argb(200, 200, 200), 0.4))
        assertEquals("noSuitableColour", grey.reason)
        assertNull(grey.color)
        assertEquals(1600, grey.sampled)
        assertEquals("ok", resolveArtAccent(cover(argb(8, 8, 10), argb(210, 25, 30), 0.2)).reason)
    }

    @Test
    fun logLineHasIdentifiersAndNumbersOnly() {
        val red = resolveArtAccent(cover(argb(8, 8, 10), argb(255, 0, 0), 0.5))
        val line = artAccentLine("abc123", fromFile = false, red)
        assertTrue(line.startsWith("art accent: item=abc123 source=server result=#"), line)
        assertTrue(line.endsWith("reason=ok sampled=1600"), line)
        assertEquals(
            "art accent: item=x source=file result=none reason=noBitmap sampled=0",
            artAccentLine("x", fromFile = true, resolveArtAccent(null)),
        )
    }
}
