package gg.dekiru.musicplayer.ui.nowplaying

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Now Playing's size rules: the same thresholds and scale as iOS `NowPlayingView`. */
class NowPlayingLayoutTest {

    private fun assertScale(expected: Float, width: Float, height: Float) =
        assertEquals(expected, NowPlayingLayout.scale(width, height), 1e-4f, "scale for $width × $height")

    @Test
    fun wideFrom640By520AsIos() {
        assertTrue(NowPlayingLayout.isWide(640f, 520f))
        assertFalse(NowPlayingLayout.isWide(639.9f, 800f))
        assertFalse(NowPlayingLayout.isWide(1000f, 519.9f))
        // A phone upright and on its side, a small tablet upright: one column.
        assertFalse(NowPlayingLayout.isWide(393f, 852f))
        assertFalse(NowPlayingLayout.isWide(852f, 393f))
        assertFalse(NowPlayingLayout.isWide(600f, 960f))
        // An unfolded phone, a tablet on its side, the Linux window: two.
        assertTrue(NowPlayingLayout.isWide(673f, 841f))
        assertTrue(NowPlayingLayout.isWide(1069f, 668f))
        assertTrue(NowPlayingLayout.isWide(1100f, 760f))
    }

    @Test
    fun scaleExamples() {
        assertScale(1.25f, 1100f, 760f) // desktop default window
        assertScale(1.10f, 1069f, 668f) // small tablet on its side
        assertScale(1.6f, 1600f, 1000f)
        assertScale(1.6f, 2560f, 1400f)
    }

    @Test
    fun scaleIsOneWhenNotWide() {
        assertScale(1f, 393f, 852f)
        assertScale(1f, 852f, 393f)
        assertScale(1f, 639f, 2000f)
        assertScale(1f, 2000f, 519f)
    }

    @Test
    fun scaleIsClampedBetweenOneAndOnePointSix() {
        assertScale(1f, 640f, 520f)
        assertScale(1f, 800f, 600f)
        assertScale(1f, 2000f, 590f)
        assertScale(1.6f, 4000f, 3000f)
        assertScale(1.6f, 1280f, 960f)
    }

    @Test
    fun scaleRoundsDownToFiveHundredths() {
        assertScale(1.15f, 920f, 690f) // exactly 1.15
        assertScale(1.15f, 935f, 900f) // 1.16875
        assertScale(1.05f, 879f, 1000f) // 1.09875
        assertScale(1.5f, 1200f, 950f)
        // Every result is a whole number of steps.
        for (w in 640..2600 step 37) for (h in 520..1600 step 29) {
            val steps = NowPlayingLayout.scale(w.toFloat(), h.toFloat()) * 20
            assertEquals(steps, kotlin.math.round(steps), 1e-3f, "$w × $h")
        }
    }

    @Test
    fun leftColumnAndArtAsIos() {
        assertEquals(320f, NowPlayingLayout.leftColumnWidth(640f), 1e-3f)
        assertEquals(460f, NowPlayingLayout.leftColumnWidth(1000f), 1e-3f)
        assertEquals(520f, NowPlayingLayout.leftColumnWidth(1400f), 1e-3f)
        // Limited by the column, by 360, by the height left, and never under 140.
        assertEquals(264f, NowPlayingLayout.wideArtSize(320f, 900f, visualiser = false), 1e-3f)
        assertEquals(360f, NowPlayingLayout.wideArtSize(520f, 900f, visualiser = false), 1e-3f)
        assertEquals(170f, NowPlayingLayout.wideArtSize(520f, 600f, visualiser = false), 1e-3f)
        assertEquals(140f, NowPlayingLayout.wideArtSize(520f, 600f, visualiser = true), 1e-3f)
    }
}
