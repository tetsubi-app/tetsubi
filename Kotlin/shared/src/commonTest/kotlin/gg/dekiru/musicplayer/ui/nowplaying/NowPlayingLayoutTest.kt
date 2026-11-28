package gg.dekiru.musicplayer.ui.nowplaying

import gg.dekiru.musicplayer.ui.layout.WindowClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Now Playing's size rules: the window class picks one or two columns, with no density scaling. */
class NowPlayingLayoutTest {

    @Test
    fun twoColumnsFromMedium() {
        assertFalse(NowPlayingLayout.usesTwoColumns(WindowClass.Compact))
        assertTrue(NowPlayingLayout.usesTwoColumns(WindowClass.Medium))
        assertTrue(NowPlayingLayout.usesTwoColumns(WindowClass.Expanded))
        // A phone on its side is still Compact by width: one column.
        assertEquals(WindowClass.Compact, WindowClass.fromWidth(393f))
        assertEquals(WindowClass.Medium, WindowClass.fromWidth(600f))
    }

    @Test
    fun compactArtFitsBetweenTheMarginsUpTo300() {
        assertEquals(300f, NowPlayingLayout.compactArtSize(393f, 852f), 1e-3f)
        assertEquals(300f, NowPlayingLayout.compactArtSize(360f, 800f), 1e-3f)
        // A narrow window: width less 2 x 16.
        assertEquals(268f, NowPlayingLayout.compactArtSize(300f, 900f), 1e-3f)
    }

    @Test
    fun compactArtShrinksWithTheHeightButNotUnder140() {
        assertEquals(240f, NowPlayingLayout.compactArtSize(393f, 600f), 1e-3f)
        assertEquals(140f, NowPlayingLayout.compactArtSize(393f, 300f), 1e-3f)
    }

    @Test
    fun leftColumnIs46PercentFrom360To560() {
        assertEquals(360f, NowPlayingLayout.leftColumnWidth(640f), 1e-3f)
        assertEquals(460f, NowPlayingLayout.leftColumnWidth(1000f), 1e-3f)
        assertEquals(560f, NowPlayingLayout.leftColumnWidth(1600f), 1e-3f)
    }

    @Test
    fun wideArtIsLimitedByColumnHeightAndTheFloor() {
        // By the column (less 64), by 420, by the height left, never under 140.
        assertEquals(296f, NowPlayingLayout.wideArtSize(360f, 900f, visualiser = false), 1e-3f)
        assertEquals(420f, NowPlayingLayout.wideArtSize(560f, 1000f, visualiser = false), 1e-3f)
        assertEquals(170f, NowPlayingLayout.wideArtSize(560f, 600f, visualiser = false), 1e-3f)
        assertEquals(140f, NowPlayingLayout.wideArtSize(560f, 600f, visualiser = true), 1e-3f)
    }
}
